package com.obdlogger.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import com.obdlogger.core.DataLogger
import com.obdlogger.core.DtcSnapshot
import com.obdlogger.core.ElmConnection
import com.obdlogger.core.ObdSession
import com.obdlogger.core.Pids
import com.obdlogger.core.SessionReport
import java.io.IOException
import java.io.Writer

/**
 * Foreground service that owns the Bluetooth link and the recording, so logging
 * continues with the screen off. Reconnects automatically if the adapter drops.
 */
class LoggerService : Service() {
    @Volatile private var stopRequested = false
    @Volatile private var recording = false
    @Volatile private var socket: BluetoothSocket? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> requestStop()
            ACTION_MARK -> if (recording) LoggerState.requestMarker()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        startForeground(NOTIFICATION_ID, notification("Подключение…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (worker?.isAlive == true) return
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return stopSelf()
        val vehicle = intent.getStringExtra(EXTRA_VEHICLE).orEmpty()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obdlogger:session")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        stopRequested = false
        LoggerState.resetMarkers()
        LoggerState.update { LoggerState.Snapshot(running = true, status = "Подключение к адаптеру…") }
        worker = Thread({ runSession(address, vehicle) }, "obd-session").also { it.start() }
    }

    private fun requestStop() {
        if (worker?.isAlive != true) return stopSelf()
        stopRequested = true
        LoggerState.update { it.copy(status = "Остановка, чтение кодов ошибок…") }
        // While connecting there is nothing to finish gracefully; unblock connect().
        if (!recording) closeSocket()
    }

    @SuppressLint("MissingPermission")
    private fun runSession(address: String, vehicle: String) {
        val device = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        val files = SessionFiles.create(this)
        val trace = ElmTraceLog(files.elmLog)
        var csv: Writer? = null
        var logger: DataLogger? = null
        var report: SessionReport? = null
        var liveSession: ObdSession? = null
        var finalDtcs: DtcSnapshot? = null

        try {
            while (!stopRequested) {
                try {
                    status("Подключение к ${device.name ?: address}…")
                    val s = BluetoothElm.connect(device) { socket = it }
                    if (stopRequested) break
                    val elm = ElmConnection(s.inputStream, s.outputStream, trace::write)
                    val session = ObdSession(elm)
                    status("Инициализация ELM327…")
                    val adapterInfo = session.initAdapter()
                    trace.write("adapter: $adapterInfo")
                    status("Поиск протокола (на K-line до 20 с)…")
                    while (!stopRequested && !session.connectEcu()) {
                        status("ЭБУ не отвечает (${session.lastError}). Включите зажигание или заведите двигатель.")
                        Thread.sleep(3000)
                    }
                    if (stopRequested) break
                    trace.write("protocol: ${session.protocolName} (#${session.protocolNumber}), single response: ${session.singleResponse}")

                    // After a reconnect the same CSV continues.
                    val activeLogger = logger ?: run {
                        status("Чтение VIN, поддерживаемых датчиков и ошибок…")
                        val info = session.readVehicleInfo()
                        val items = Pids.pollItems(info.supportedPids, session.singleResponse)
                        val writer = files.csv.bufferedWriter().also { csv = it }
                        val created = DataLogger(items, writer).also { it.writeHeader() }
                        report = SessionReport(vehicle, adapterInfo, info).also {
                            files.info.writeText(it.render(created, null, null))
                        }
                        LoggerState.update { it.copy(dtcInfo = dtcSummary(info.dtcs) + "\nПротокол: ${info.protocol}") }
                        created
                    }
                    logger = activeLogger

                    liveSession = session
                    recording = true
                    status("Запись")
                    updateNotification("Идёт запись")
                    while (!stopRequested) {
                        val marker = LoggerState.peekMarker()
                        val r = activeLogger.cycle(elm, marker)
                        if (r.wroteRow) LoggerState.consumeMarker(marker)
                        LoggerState.update {
                            it.copy(
                                status = if (r.wroteRow) "Запись" else "ЭБУ не отвечает (${r.error}) — двигатель заглушен?",
                                rows = activeLogger.rows,
                                elapsedSec = (System.currentTimeMillis() - activeLogger.startMs) / 1000,
                                cycleMs = r.durationMs,
                                values = activeLogger.latest.entries.map { e -> e.key to e.value },
                            )
                        }
                        if (!r.wroteRow) Thread.sleep(1000)
                    }
                } catch (e: IOException) {
                    recording = false
                    liveSession = null
                    closeSocket()
                    if (stopRequested) break
                    trace.write("link error: $e")
                    status("Связь с адаптером потеряна: ${e.message}. Повтор через 3 с…")
                    updateNotification("Переподключение…")
                    Thread.sleep(3000)
                }
            }
            finalDtcs = liveSession?.let {
                try {
                    it.readDtcs()
                } catch (e: IOException) {
                    null
                }
            }
        } catch (e: InterruptedException) {
            // stop
        } catch (e: Exception) {
            trace.write("fatal: $e")
            status("Ошибка: ${e.message}")
        } finally {
            recording = false
            closeSocket()
            try {
                csv?.close()
            } catch (_: IOException) {
            }
            val currentLogger = logger
            if (currentLogger != null) {
                report?.let { files.info.writeText(it.render(currentLogger, finalDtcs, System.currentTimeMillis())) }
            }
            trace.close()
            val exported = files.existing().mapNotNull { SessionFiles.exportToDownloads(this, it) }
            val rows = currentLogger?.rows ?: 0
            LoggerState.update {
                it.copy(
                    running = false,
                    status = if (rows > 0) {
                        "Сохранено строк: $rows → Загрузки/${SessionFiles.DOWNLOAD_FOLDER}/${files.csv.name}"
                    } else {
                        "Данные не записаны. Журнал обмена с адаптером: Загрузки/${SessionFiles.DOWNLOAD_FOLDER}/${files.elmLog.name}"
                    },
                    dtcInfo = finalDtcs?.let { d -> "В конце поездки:\n" + dtcSummary(d) } ?: it.dtcInfo,
                    exported = exported,
                )
            }
            wakeLock?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun dtcSummary(d: DtcSnapshot): String {
        fun codes(list: List<String>?) = when {
            list == null -> "—"
            list.isEmpty() -> "нет"
            else -> list.joinToString(", ")
        }
        val mil = when (d.milOn) {
            true -> "горит"
            false -> "не горит"
            null -> "?"
        }
        return "Check Engine: $mil\nОшибки: ${codes(d.stored)}\nОжидающие: ${codes(d.pending)}"
    }

    private fun status(text: String) = LoggerState.update { it.copy(status = text) }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Запись OBD", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(title: String, action: String, code: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_launcher), title,
            PendingIntent.getService(this, code, Intent(this, LoggerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action("Метка", ACTION_MARK, 1))
            .addAction(action("Стоп", ACTION_STOP, 2))
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    companion object {
        const val ACTION_START = "com.obdlogger.START"
        const val ACTION_STOP = "com.obdlogger.STOP"
        const val ACTION_MARK = "com.obdlogger.MARK"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_VEHICLE = "vehicle"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        fun intent(ctx: Context, action: String) = Intent(ctx, LoggerService::class.java).setAction(action)
    }
}
