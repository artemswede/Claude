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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.obdlogger.core.DataLogger
import com.obdlogger.core.DtcSnapshot
import com.obdlogger.core.ElmConnection
import com.obdlogger.core.ElmIo
import com.obdlogger.core.ObdSession
import com.obdlogger.core.Pids
import com.obdlogger.core.SessionReport
import com.obdlogger.core.SimulatedElm
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
        val demo = intent.getBooleanExtra(EXTRA_DEMO, false)
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        if (!demo && address == null) return stopSelf()
        val vehicle = intent.getStringExtra(EXTRA_VEHICLE).orEmpty()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obdlogger:session")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        stopRequested = false
        LoggerState.resetMarkers()
        LoggerState.update { LoggerState.Snapshot(running = true, status = "Подключение к адаптеру…") }
        val extendedScan = intent.getBooleanExtra(EXTRA_EXTENDED, true)
        worker = Thread({ runSession(if (demo) null else address, vehicle, extendedScan) }, "obd-session").also { it.start() }
    }

    private fun requestStop() {
        if (worker?.isAlive != true) return stopSelf()
        stopRequested = true
        LoggerState.update { it.copy(status = "Остановка…") }
        // While connecting there is nothing to finish gracefully; unblock connect().
        if (!recording) closeSocket()
    }

    /** Bluetooth adapter, or the simulated car when [address] is null (demo mode). */
    @SuppressLint("MissingPermission")
    private fun openLink(address: String?, trace: ElmTraceLog): ElmIo {
        if (address == null) {
            trace.write("demo mode: simulated car, no Bluetooth")
            return SimulatedElm(timeScale = DEMO_TIME_SCALE, latencyMs = 60)
        }
        val device = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        status("Подключение к ${device.name ?: address}…")
        trace.write("device: ${device.name} ($address), bond state ${device.bondState}")
        val s = BluetoothElm.connect(device, trace::write, { stopRequested }) { socket = it }
        return ElmConnection(s.inputStream, s.outputStream, trace::write)
    }

    private fun runSession(address: String?, vehicle: String, extendedScan: Boolean) {
        val files = SessionFiles.create(this)
        val trace = ElmTraceLog(files.elmLog)
        trace.write("session start; app ${BuildConfig.VERSION_NAME}; Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})")
        var csv: Writer? = null
        var logger: DataLogger? = null
        var report: SessionReport? = null
        var liveSession: ObdSession? = null
        var finalDtcs: DtcSnapshot? = null
        var failure: String? = null

        try {
            while (!stopRequested) {
                try {
                    val elm = openLink(address, trace)
                    if (stopRequested) break
                    val session = ObdSession(elm)
                    status("Инициализация ELM327…")
                    val adapterInfo = session.initAdapter()
                    trace.write("adapter: $adapterInfo")
                    status("Поиск протокола (на K-line до 20 с)…")
                    while (!stopRequested && !session.connectEcu()) {
                        failure = "ЭБУ не отвечает (${session.lastError})"
                        status("$failure. Включите зажигание или заведите двигатель.")
                        Thread.sleep(3000)
                    }
                    if (stopRequested) break
                    failure = null
                    trace.write("protocol: ${session.protocolName} (#${session.protocolNumber}), single response: ${session.singleResponse}")

                    // After a reconnect the same CSV continues.
                    val activeLogger = logger ?: run {
                        status("Чтение VIN, поддерживаемых датчиков и ошибок…")
                        val info = session.readVehicleInfo()
                        trace.write("vehicle: vin=${info.vin}, pids=${info.supportedPids.joinToString(" ") { "%02X".format(it) }}")
                        status("Поиск датчиков без формулы…")
                        val rawPids = session.discoverRawPids(info.supportedPids)
                        trace.write("raw mode 01 pids: ${rawPids.map { (p, n) -> "%02X:%d".format(p, n) }}")
                        val extended = if (extendedScan) session.discoverExtended { status(it) } else emptyList()
                        trace.write("mode 21 blocks: ${extended.map { "${it.ecu}:%02X:%d".format(it.id, it.length) }}")
                        val items = Pids.pollItems(info.supportedPids, session.singleResponse, rawPids, extended)
                        val writer = files.csv.bufferedWriter().also { csv = it }
                        val created = DataLogger(items, writer, defaultHeader = session.defaultHeader).also { it.writeHeader() }
                        LiveData.store.reset(created.columns.map { it.name })
                        report = SessionReport(vehicle, adapterInfo, info).also {
                            files.info.writeText(it.render(created, null, null))
                        }
                        LoggerState.update { it.copy(dtcInfo = dtcSummary(info.dtcs) + "\nПротокол: ${info.protocol}") }
                        created
                    }
                    logger = activeLogger
                    activeLogger.onReconnect()

                    liveSession = session
                    recording = true
                    status("Запись")
                    updateNotification(if (address == null) "Демо-запись" else "Идёт запись")
                    var silentCycles = 0
                    var autoMarker: String? = null
                    while (!stopRequested) {
                        val marker = LoggerState.peekMarker()
                        val r = activeLogger.cycle(elm, listOfNotNull(autoMarker, marker).joinToString(" ").ifEmpty { null })
                        if (r.wroteRow) {
                            LoggerState.consumeMarker(marker)
                            autoMarker = null
                            LiveData.store.add(activeLogger.lastRowMs, activeLogger.lastRow)
                        }
                        LoggerState.update {
                            it.copy(
                                status = when {
                                    stopRequested -> "Остановка…"
                                    r.wroteRow -> if (address == null) "Демо-запись (симуляция, без машины)" else "Запись"
                                    else -> "ЭБУ не отвечает (${r.error}) — двигатель заглушен?"
                                },
                                rows = activeLogger.rows,
                                elapsedSec = (System.currentTimeMillis() - activeLogger.startMs) / 1000,
                                cycleMs = r.durationMs,
                                values = activeLogger.latest.entries.map { e -> e.key to e.value },
                            )
                        }
                        if (r.wroteRow) {
                            silentCycles = 0
                        } else if (r.adapterReset || ++silentCycles >= SILENT_CYCLES_BEFORE_REINIT) {
                            // Engine switched off / restarted: the K-line session is dead and the adapter may
                            // have rebooted from the cranking voltage dip (losing echo/header settings).
                            // Re-initialise everything and keep writing the same CSV.
                            trace.write("ecu silent (${r.error}), adapter reset=${r.adapterReset}: re-initialising")
                            status("Двигатель перезапускается? Жду ЭБУ, запись продолжится автоматически…")
                            updateNotification("Ожидание ЭБУ…")
                            session.initAdapter()
                            while (!stopRequested && !session.connectEcu()) {
                                status("ЭБУ не отвечает (${session.lastError}). Жду запуска двигателя или зажигания…")
                                Thread.sleep(2000)
                            }
                            if (stopRequested) break
                            trace.write("ecu back: ${session.protocolName}")
                            activeLogger.onReconnect()
                            autoMarker = "RECONNECT"
                            silentCycles = 0
                            updateNotification(if (address == null) "Демо-запись" else "Идёт запись")
                        } else {
                            Thread.sleep(1000)
                        }
                    }
                } catch (e: IOException) {
                    recording = false
                    liveSession = null
                    closeSocket()
                    trace.write("link error: $e")
                    failure = e.message
                    if (stopRequested) break
                    status("Нет связи с адаптером: ${e.message}. Повтор через 3 с…")
                    updateNotification("Переподключение…")
                    Thread.sleep(3000)
                }
            }
            liveSession?.let {
                status("Остановка, чтение кодов ошибок…")
                finalDtcs = try {
                    it.readDtcs()
                } catch (e: IOException) {
                    null
                }
            }
        } catch (e: InterruptedException) {
            // stop
        } catch (e: Exception) {
            trace.write("fatal: $e")
            failure = e.toString()
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
            trace.write("session end; rows=${currentLogger?.rows ?: 0}")
            trace.close()
            val toExport = files.existing()
            val exported = toExport.mapNotNull { SessionFiles.exportToDownloads(this, it) }
            val rows = currentLogger?.rows ?: 0
            val folder = "Загрузки/${SessionFiles.DOWNLOAD_FOLDER}"
            LoggerState.update {
                it.copy(
                    running = false,
                    status = when {
                        exported.size < toExport.size ->
                            "Не удалось сохранить ${toExport.size - exported.size} из ${toExport.size} файлов в $folder"
                        rows > 0 -> "Сохранено строк: $rows → $folder/${files.csv.name}"
                        else -> "Данные не записаны" + (failure?.let { f -> ": $f" } ?: "") +
                            ".\nЖурнал подключения: $folder/${files.elmLog.name}"
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
        const val EXTRA_DEMO = "demo"
        const val EXTRA_EXTENDED = "extended"
        private const val SILENT_CYCLES_BEFORE_REINIT = 2
        /** Demo drive runs 5× faster: the 12-minute scenario (warm-up, city, highway) in ~2.5 minutes. */
        private const val DEMO_TIME_SCALE = 5.0
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        fun intent(ctx: Context, action: String) = Intent(ctx, LoggerService::class.java).setAction(action)
    }
}
