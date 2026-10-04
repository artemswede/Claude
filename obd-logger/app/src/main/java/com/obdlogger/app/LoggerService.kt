package com.obdlogger.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import com.obdlogger.core.AdapterInfo
import com.obdlogger.core.CarId
import com.obdlogger.core.DataLogger
import com.obdlogger.core.DtcSnapshot
import com.obdlogger.core.ElmConnection
import com.obdlogger.core.ElmIo
import com.obdlogger.core.ElmResponse
import com.obdlogger.core.ElmTimeoutException
import com.obdlogger.core.ObdSession
import com.obdlogger.core.Pids
import com.obdlogger.core.SessionReport
import java.io.File
import java.io.IOException
import java.io.Writer

/**
 * Foreground service that owns the Bluetooth link and the recording, so logging
 * continues with the screen off.
 *
 * Manual mode records one trip until the user stops it, riding through engine
 * restarts. Auto mode runs indefinitely: it waits for the engine, records the
 * trip, closes it after the engine has been off for [TRIP_GAP_MS] and waits again.
 */
class LoggerService : Service() {
    /** One recording: its files and the logger writing them. */
    private class Trip(val files: SessionFiles, val trace: ElmTraceLog) {
        var csv: Writer? = null
        /** The CSV's file stream: synced to storage regularly, so a power cut loses seconds, not the trip. */
        var csvStream: java.io.FileOutputStream? = null
        var lastSyncMs = 0L
        var logger: DataLogger? = null
        var report: SessionReport? = null
        var lastDataMs = System.currentTimeMillis()
        var engineOffSinceMs: Long? = null
    }

    @Volatile private var stopRequested = false
    @Volatile private var checkRequested = false
    /** Info file of the trip being recorded (its header is copied into check logs). */
    @Volatile private var tripInfoFile: File? = null
    @Volatile private var checkStopRequested = false
    @Volatile private var recording = false
    @Volatile private var autoMode = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var traceTarget: ElmTraceLog? = null
    private var worker: Thread? = null
    private var tripLock: PowerManager.WakeLock? = null
    private var probeLock: PowerManager.WakeLock? = null
    private val pauseLock = Object()
    private var receiverRegistered = false

    /** Car started (tablet starts charging), screen on or adapter appeared: check right away. */
    private val pokeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Tablet shutting down (often together with the car): get the trip onto storage now.
            if (intent.action == Intent.ACTION_SHUTDOWN || intent.action == "android.intent.action.QUICKBOOT_POWEROFF") {
                trace("shutdown broadcast: syncing the trip")
                currentTrip?.let { syncTrip(it, force = true) }
                return
            }
            poke()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent, auto = false)
            ACTION_AUTO -> start(intent, auto = true)
            ACTION_STOP -> requestStop()
            ACTION_MARK -> if (recording) LoggerState.requestMarker()
            ACTION_POKE -> poke()
            ACTION_CHECK -> if (recording) checkRequested = true
            ACTION_CHECK_STOP -> checkStopRequested = true
            // Restarted by the system after being killed: note it and resume auto mode if it is on.
            null -> {
                if (Prefs.auto(this)) start(Intent(this, LoggerService::class.java), auto = true) else stopSelf()
            }
        }
        return if (autoMode) START_STICKY else START_NOT_STICKY
    }

    private fun start(intent: Intent, auto: Boolean) {
        Compat.startForeground(this, NOTIFICATION_ID, notification(if (auto) "Жду машину" else "Подключение…"))
        if (worker?.isAlive == true) return
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: Prefs.device(this)
        if (address == null) {
            LoggerState.update { it.copy(status = "Выберите адаптер, чтобы включить автозапись") }
            Compat.stopForeground(this)
            stopSelf()
            return
        }
        val vehicle = intent.getStringExtra(EXTRA_VEHICLE) ?: Prefs.vehicle(this)
        val extendedScan = intent.getBooleanExtra(EXTRA_EXTENDED, Prefs.extended(this))
        autoMode = auto
        stopRequested = false
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        tripLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obdlogger:trip").apply { setReferenceCounted(false) }
        probeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obdlogger:probe").apply { setReferenceCounted(false) }
        registerPokes()
        LoggerState.update {
            LoggerState.Snapshot(
                running = true, auto = auto,
                status = if (auto) "Автозапись: жду двигатель" else "Подключение к адаптеру…",
                link = Lamp.WAIT, linkText = "подключение", savedTrips = it.savedTrips,
            )
        }
        worker = Thread({ runWorker(address, vehicle, extendedScan, auto) }, "obd-session").also { it.start() }
    }

    private fun requestStop() {
        if (worker?.isAlive != true) {
            Compat.stopForeground(this)
            stopSelf()
            return
        }
        stopRequested = true
        LoggerState.update { it.copy(status = "Остановка…") }
        poke()
        // While connecting there is nothing to finish gracefully; unblock connect().
        if (!recording) closeSocket()
    }

    private fun registerPokes() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction("android.intent.action.QUICKBOOT_POWEROFF")
        }
        registerReceiver(pokeReceiver, filter)
        receiverRegistered = true
    }

    private fun poke() {
        // Keep the CPU awake long enough for the worker to try the adapter.
        probeLock?.acquire(90_000)
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    /** Sleep that [poke] (or a stop) can cut short. */
    private fun pause(ms: Long) {
        if (stopRequested) return
        synchronized(pauseLock) { pauseLock.wait(ms) }
    }

    private fun trace(line: String) {
        traceTarget?.write(line)
    }

    /** The Bluetooth adapter. */
    @SuppressLint("MissingPermission")
    private fun openLink(address: String): ElmIo {
        val device = (Compat.bluetooth(this) ?: throw IOException("Bluetooth недоступен")).getRemoteDevice(address)
        lamps(link = Lamp.WAIT, linkText = "Bluetooth: подключение")
        trace("device: ${device.name} ($address), bond state ${device.bondState}")
        val s = BluetoothElm.connect(device, ::trace, { stopRequested }) { socket = it }
        return ElmConnection(s.inputStream, s.outputStream, ::trace)
    }

    private fun runWorker(address: String, vehicle: String, extendedScan: Boolean, auto: Boolean) {
        // A trip cut off by a power loss or a kill: finish its files before recording anew.
        Recovery.run(this)
        // Link traffic before a trip exists (auto mode waiting) goes to a small separate log.
        val waitLog = ElmTraceLog(File(SessionFiles.dir(this), if (auto) "auto_wait.log" else "connect.log"))
        traceTarget = waitLog
        trace("service start; app ${BuildConfig.VERSION_NAME}; Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL}); auto=$auto")
        var trip: Trip? = null
        var session: ObdSession? = null
        var failure: String? = null
        try {
            while (!stopRequested) {
                try {
                    val elm = openLink(address)
                    if (stopRequested) break
                    val s = ObdSession(elm).also { session = it }
                    lamps(link = Lamp.WAIT, linkText = "адаптер: инициализация")
                    val adapterInfo = s.initAdapter()
                    trace("adapter: $adapterInfo")
                    if (!waitForEcu(s, auto)) break
                    if (auto && trip == null && !waitForEngine(elm, s, auto)) break

                    // In auto mode a long pause means the previous trip is over.
                    val previous = trip
                    if (auto && previous != null && System.currentTimeMillis() - previous.lastDataMs > TRIP_GAP_MS) {
                        finishTrip(previous, null)
                        trip = null
                        traceTarget = waitLog
                        if (!waitForEngine(elm, s, auto)) break
                    }
                    if (stopRequested) break
                    val t = trip ?: startTrip(s, adapterInfo, vehicle, extendedScan, auto).also { trip = it }
                    failure = null
                    if (recordTrip(t, elm, s, auto)) {
                        finishTrip(t, s)
                        trip = null
                        traceTarget = waitLog
                    }
                } catch (e: IOException) {
                    recording = false
                    session = null
                    closeSocket()
                    trace("link error: $e")
                    failure = e.message
                    if (stopRequested) break
                    lamps(link = if (auto) Lamp.WAIT else Lamp.FAIL, linkText = "нет связи с адаптером", engine = Lamp.OFF, engineText = "неизвестно")
                    val current = trip
                    if (auto && current != null && System.currentTimeMillis() - current.lastDataMs > TRIP_GAP_MS) {
                        finishTrip(current, null)
                        trip = null
                        traceTarget = waitLog
                    }
                    val waiting = auto && trip == null
                    status(
                        if (waiting) "Автозапись: жду машину (адаптер не отвечает — зажигание выключено?)"
                        else "Нет связи с адаптером: ${e.message}. Повтор…",
                    )
                    updateNotification(if (waiting) "Жду машину" else "Переподключение…")
                    pause(if (waiting) 30_000 else 3_000)
                }
            }
        } catch (e: InterruptedException) {
            // stop
        } catch (e: Exception) {
            trace("fatal: $e")
            failure = e.toString()
        } finally {
            recording = false
            trip?.let { finishTrip(it, session) }
            closeSocket()
            waitLog.close()
            if (receiverRegistered) {
                unregisterReceiver(pokeReceiver)
                receiverRegistered = false
            }
            probeLock?.let { if (it.isHeld) it.release() }
            val fail = failure
            LoggerState.update {
                it.copy(
                    running = false, recording = false, auto = false,
                    link = Lamp.OFF, linkText = "нет связи", engine = Lamp.OFF, engineText = "неизвестно",
                    status = if (it.rows == 0 && fail != null) "Данные не записаны: $fail" else it.status,
                )
            }
            Compat.stopForeground(this)
            stopSelf()
        }
    }

    /** Waits until the ECU answers. False if stopped meanwhile. */
    private fun waitForEcu(s: ObdSession, auto: Boolean): Boolean {
        lamps(link = Lamp.WAIT, linkText = "поиск протокола ЭБУ")
        status("Поиск протокола (на K-line до 20 с)…")
        while (!stopRequested && !s.connectEcu()) {
            lamps(link = Lamp.WAIT, linkText = "адаптер есть, ЭБУ молчит", engine = Lamp.OFF, engineText = "зажигание выключено?")
            status(
                if (auto) "Автозапись: адаптер на связи, жду зажигание"
                else "ЭБУ не отвечает (${s.lastError}). Включите зажигание или заведите двигатель.",
            )
            pause(if (auto) 15_000 else 3_000)
        }
        if (stopRequested) return false
        trace("protocol: ${s.protocolName} (#${s.protocolNumber}), single response: ${s.singleResponse}")
        lamps(link = Lamp.OK, linkText = "ЭБУ на связи")
        return true
    }

    /** Auto mode: waits until the engine actually runs. False if stopped meanwhile. */
    private fun waitForEngine(elm: ElmIo, s: ObdSession, auto: Boolean): Boolean {
        while (!stopRequested) {
            val rpm = try {
                ElmResponse.pidData(elm.command("010C"), 0x0C)
                    ?.takeIf { it.size >= 2 }
                    ?.let { ((it[0].toInt() and 0xFF) * 256 + (it[1].toInt() and 0xFF)) / 4 }
            } catch (_: ElmTimeoutException) {
                null
            }
            when {
                rpm == null -> if (!waitForEcu(s, auto)) return false
                rpm > ENGINE_RPM -> {
                    lamps(engine = Lamp.OK, engineText = "работает, $rpm об/мин")
                    return true
                }
                else -> {
                    lamps(link = Lamp.OK, linkText = "ЭБУ на связи", engine = Lamp.OFF, engineText = "заглушен")
                    status("Автозапись: зажигание включено, жду запуска двигателя")
                    pause(5_000)
                }
            }
        }
        return false
    }

    private fun startTrip(s: ObdSession, adapterInfo: AdapterInfo, vehicle: String, extendedScan: Boolean, auto: Boolean): Trip {
        val files = SessionFiles.create(this)
        val t = Trip(files, ElmTraceLog(files.elmLog, Prefs.trace(this)))
        tripInfoFile = files.info
        traceTarget = t.trace
        trace("trip start; app ${BuildConfig.VERSION_NAME}; Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL}); auto=$auto")
        trace("adapter: $adapterInfo; protocol: ${s.protocolName} (#${s.protocolNumber})")
        tripLock?.acquire(12 * 60 * 60 * 1000L)
        Prefs.of(this).edit().putLong(Prefs.RECORDING_SINCE, System.currentTimeMillis()).apply()
        LoggerState.resetMarkers()
        LoggerState.update { it.copy(recording = true, rows = 0, markers = 0, exported = emptyList(), status = "Чтение VIN, датчиков и ошибок…") }
        if (auto) openUi()

        val info = s.readVehicleInfo()
        // Which car is this? The tablet moves between cars; each keeps its own name and history.
        CarId.key(info.vin, CarId.protocolLine(info), CarId.pidsLine(info))?.let { key ->
            val newCar = Prefs.carName(this, key) == null
            Prefs.setCurrentCar(this, key)
            if (newCar) Prefs.setCarName(this, key, if (Prefs.of(this).getString(Prefs.CURRENT_CAR + "_seen", null) == null) Prefs.of(this).getString(Prefs.VEHICLE, null).orEmpty() else "")
            Prefs.of(this).edit().putString(Prefs.CURRENT_CAR + "_seen", "1").apply()
            trace("car: $key (${if (newCar) "new" else Prefs.carName(this, key)})")
        }
        val carName = Prefs.vehicle(this)
        trace("vehicle: vin=${info.vin}, pids=${info.supportedPids.joinToString(" ") { "%02X".format(it) }}")
        status("Поиск датчиков без формулы…")
        val rawPids = s.discoverRawPids(info.supportedPids)
        trace("raw mode 01 pids: ${rawPids.map { (p, n) -> "%02X:%d".format(p, n) }}")
        val extended = if (extendedScan) s.discoverExtended { status(it) } else emptyList()
        trace("mode 21 blocks: ${extended.map { "${it.ecu}:%02X:%d".format(it.id, it.length) }}")
        val items = Pids.pollItems(info.supportedPids, s.singleResponse, rawPids, extended)
        val stream = java.io.FileOutputStream(files.csv).also { t.csvStream = it }
        val writer = stream.bufferedWriter().also { t.csv = it }
        currentTrip = t
        Prefs.of(this).edit().putString(Prefs.RECORDING_FILE, files.csv.absolutePath).apply()
        val logger = DataLogger(items, writer, defaultHeader = s.defaultHeader).also { it.writeHeader() }
        t.logger = logger
        LiveData.store.reset(logger.columns.map { it.name })
        t.report = SessionReport(carName, adapterInfo, info).also { files.info.writeText(it.render(logger, null, null)) }
        LoggerState.update {
            it.copy(dtcInfo = dtcSummary(info.dtcs) + "\nПротокол: ${info.protocol}", currentCsv = files.csv.absolutePath, protocol = info.protocol)
        }
        return t
    }

    /**
     * Records until stopped (returns false) or, in auto mode, until the engine has been
     * off or the ECU silent for [TRIP_GAP_MS] (returns true: the trip is over).
     */
    private fun recordTrip(t: Trip, elm: ElmIo, s: ObdSession, auto: Boolean): Boolean {
        val logger = t.logger ?: return false
        logger.onReconnect()
        recording = true
        val recText = if (auto) "Автозапись: идёт поездка" else "Запись"
        status(recText)
        updateNotification("Идёт запись")
        val rpmIndex = logger.columns.indexOfFirst { it.name == "rpm" }
        var silentCycles = 0
        var autoMarker: String? = if (logger.rows > 0) "RECONNECT" else null
        var check: CheckRun? = null
        while (!stopRequested) {
            if (checkRequested && check == null) {
                checkRequested = false
                checkStopRequested = false
                check = startCheck(logger)
            }
            val marker = LoggerState.peekMarker()
            val checkMarker = check?.state?.marker
            val r = logger.cycle(elm, listOfNotNull(autoMarker, marker, checkMarker).joinToString(" ").ifEmpty { null })
            val now = System.currentTimeMillis()
            check?.let { c ->
                if (checkStopRequested) c.test.stop(now)
                val idx = logger.columns.indexOfFirst { it.name == "speed_kmh" }
                val speed = logger.lastRow.getOrNull(idx)?.toDoubleOrNull()
                val rpmNow = if (r.wroteRow) logger.lastRow.getOrNull(rpmIndex)?.toDoubleOrNull() else null
                val st = c.test.update(now, rpmNow, speed)
                c.state = st
                LoggerState.update { it.copy(check = st) }
                if (st.phase != com.obdlogger.core.CheckTest.Phase.RUNNING) {
                    finishCheck(logger, c)
                    check = null
                }
            }
            if (r.wroteRow) {
                syncTrip(t)
                LoggerState.consumeMarker(marker)
                autoMarker = null
                silentCycles = 0
                t.lastDataMs = now
                LiveData.store.add(logger.lastRowMs, logger.lastRow)
                val rpm = logger.lastRow.getOrNull(rpmIndex)?.toDoubleOrNull()
                when {
                    rpm == null -> lamps(link = Lamp.OK, linkText = "ЭБУ на связи")
                    rpm > ENGINE_RPM -> {
                        t.engineOffSinceMs = null
                        lamps(link = Lamp.OK, linkText = "ЭБУ на связи", engine = Lamp.OK, engineText = "работает, ${rpm.toInt()} об/мин")
                    }
                    else -> {
                        if (t.engineOffSinceMs == null) t.engineOffSinceMs = now
                        lamps(link = Lamp.OK, linkText = "ЭБУ на связи", engine = Lamp.OFF, engineText = "заглушен")
                    }
                }
            }
            LoggerState.update {
                it.copy(
                    status = when {
                        stopRequested -> "Остановка…"
                        r.wroteRow -> recText
                        else -> "ЭБУ не отвечает (${r.error}) — двигатель заглушен?"
                    },
                    rows = logger.rows,
                    elapsedSec = (now - logger.startMs) / 1000,
                    cycleMs = r.durationMs,
                    lastDataMs = t.lastDataMs,
                    values = logger.latest.entries.map { e -> e.key to e.value },
                )
            }
            updateNotification("Идёт запись", force = false)
            // Ignition on, engine off for long enough: the trip is over.
            val offSince = t.engineOffSinceMs
            if (auto && offSince != null && now - offSince > TRIP_GAP_MS) {
                trace("engine off for ${TRIP_GAP_MS / 1000} s: trip finished")
                return true
            }
            if (r.wroteRow) continue
            if (r.adapterReset || ++silentCycles >= SILENT_CYCLES_BEFORE_REINIT) {
                // Engine switched off / restarted: the K-line session is dead and the adapter may
                // have rebooted from the cranking voltage dip (losing echo/header settings).
                trace("ecu silent (${r.error}), adapter reset=${r.adapterReset}: re-initialising")
                lamps(link = Lamp.WAIT, linkText = "ЭБУ молчит, переподключение", engine = Lamp.OFF, engineText = "заглушен?")
                status("Двигатель перезапускается? Жду ЭБУ, запись продолжится автоматически…")
                updateNotification("Ожидание ЭБУ…")
                s.initAdapter()
                while (!stopRequested && !s.connectEcu()) {
                    if (auto && System.currentTimeMillis() - t.lastDataMs > TRIP_GAP_MS) {
                        trace("ecu silent for ${TRIP_GAP_MS / 1000} s: trip finished")
                        return true
                    }
                    status("ЭБУ не отвечает (${s.lastError}). Жду запуска двигателя или зажигания…")
                    pause(if (auto) 10_000 else 2_000)
                }
                if (stopRequested) break
                trace("ecu back: ${s.protocolName}")
                lamps(link = Lamp.OK, linkText = "ЭБУ на связи")
                logger.onReconnect()
                autoMarker = "RECONNECT"
                silentCycles = 0
                updateNotification("Идёт запись")
            } else {
                pause(1000)
            }
        }
        check?.let { c ->
            c.test.stop(System.currentTimeMillis())
            c.state = c.test.update(System.currentTimeMillis(), null, null)
            LoggerState.update { it.copy(check = c.state) }
            finishCheck(logger, c)
        }
        return false
    }

    /** A running check log: the test state machine and its own CSV (rows are teed from the trip). */
    private class CheckRun(val test: com.obdlogger.core.CheckTest, val files: SessionFiles, val writer: Writer) {
        var state: com.obdlogger.core.CheckTest.State? = null
    }

    private fun startCheck(logger: DataLogger): CheckRun {
        val files = SessionFiles.createCheck(this)
        val w = files.csv.bufferedWriter()
        logger.tee = w
        trace("check log start: ${files.csv.name}")
        val run = CheckRun(com.obdlogger.core.CheckTest(System.currentTimeMillis()), files, w)
        LoggerState.update { it.copy(check = run.test.update(System.currentTimeMillis(), null, null)) }
        return run
    }

    private fun finishCheck(logger: DataLogger, c: CheckRun) {
        logger.tee = null
        try {
            c.writer.close()
        } catch (_: IOException) {
        }
        val ok = c.state?.phase == com.obdlogger.core.CheckTest.Phase.DONE
        trace("check log end: ${c.state?.phase}")
        if (ok) {
            // The trip's header (car, VIN, protocol, PIDs) ties the check log to its car.
            val tripInfo = tripInfoFile?.takeIf { it.exists() }?.readText()?.substringBefore("=== Коды неисправностей ===").orEmpty()
            c.files.info.writeText(tripInfo + "\n=== Проверочный лог Бортача ${BuildConfig.VERSION_NAME} ===\n" +
                "Шаги: прогретый холостой 2:00 → 2500 об/мин 1:00 → холостой 1:00 (столбец marker: TEST1…TEST3)\n")
            SessionFiles.exportToDownloads(this, c.files.csv)
            LoggerState.update { it.copy(checkCsv = c.files.csv.absolutePath, savedTrips = it.savedTrips + 1) }
        } else {
            // An aborted test is not a check log: nothing to compare with.
            c.files.csv.delete()
        }
    }

    @Volatile private var currentTrip: Trip? = null

    /**
     * Pushes the CSV from the OS cache to storage every 15 s (and on shutdown). The
     * writer already flushes every row; fsync makes it survive a power cut.
     */
    private fun syncTrip(t: Trip, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - t.lastSyncMs < 15_000) return
        t.lastSyncMs = now
        try {
            t.csv?.flush()
            t.csvStream?.fd?.sync()
        } catch (_: Exception) {
        }
    }

    /** Closes a trip: final trouble codes, report with analysis and comparison, export to Downloads. */
    private fun finishTrip(t: Trip, s: ObdSession?) {
        recording = false
        status("Сохранение поездки…")
        val finalDtcs: DtcSnapshot? = s?.let {
            try {
                it.readDtcs()
            } catch (e: IOException) {
                null
            }
        }
        try {
            t.csv?.close()
        } catch (_: IOException) {
        }
        val logger = t.logger
        val rows = logger?.rows ?: 0
        if (logger != null) {
            t.report?.let { t.files.info.writeText(it.render(logger, finalDtcs, System.currentTimeMillis())) }
            if (rows > 0) {
                val analysis = try {
                    SessionFiles.compareRecent(this)
                } catch (e: Exception) {
                    "Анализ не удался: $e"
                }
                t.files.info.appendText("\n\n=== АВТОАНАЛИЗ И СРАВНЕНИЕ С ПРОШЛЫМИ ПОЕЗДКАМИ ===\n$analysis\n")
            }
        }
        trace("trip end; rows=$rows")
        t.trace.close()
        if (traceTarget === t.trace) traceTarget = null
        tripLock?.let { if (it.isHeld) it.release() }
        if (currentTrip === t) currentTrip = null
        Prefs.of(this).edit().remove(Prefs.RECORDING_SINCE).remove(Prefs.RECORDING_FILE).apply()

        if (rows == 0 && autoMode) {
            // Auto mode probed the ECU but nothing was recorded: no empty trip files.
            listOf(t.files.csv, t.files.info, t.files.elmLog).forEach { it.delete() }
            return
        }
        val toExport = t.files.existing()
        val results = toExport.map { SessionFiles.exportToDownloads(this, it) }
        val saved = results.count { it.ok }
        val folder = "Загрузки/${SessionFiles.DOWNLOAD_FOLDER}"
        val waitingNext = autoMode && !stopRequested
        LoggerState.update {
            it.copy(
                recording = false,
                status = when {
                    saved < toExport.size -> "Не удалось сохранить ${toExport.size - saved} из ${toExport.size} файлов в $folder"
                    rows > 0 -> "Поездка сохранена: $rows строк → $folder/${t.files.csv.name}" +
                        if (waitingNext) "\nАвтозапись: жду следующего запуска двигателя" else ""
                    else -> "Данные не записаны. Журнал подключения: $folder/${t.files.elmLog.name}"
                },
                dtcInfo = finalDtcs?.let { d -> "В конце поездки:\n" + dtcSummary(d) } ?: it.dtcInfo,
                exported = results.mapNotNull { r -> r.uri },
                savedTrips = it.savedTrips + 1,
                currentCsv = null,
            )
        }
        updateNotification(if (waitingNext) "Жду машину" else "Запись выключена")
        if (rows > 0) tripSavedNotification(t.files.csv)
    }

    /** Auto mode brings the app to the front when a trip starts (needs «поверх других окон» on Android 10+). */
    private fun openUi() {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Compat.canOverlay(this)
        if (!allowed) return
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            trace("open ui failed: $e")
        }
    }

    private fun lamps(
        link: Lamp? = null, linkText: String? = null,
        engine: Lamp? = null, engineText: String? = null,
    ) = LoggerState.update {
        it.copy(
            link = link ?: it.link, linkText = linkText ?: it.linkText,
            engine = engine ?: it.engine, engineText = engineText ?: it.engineText,
        )
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

    /**
     * The ongoing notification (В5): «Жду машину» or «Идёт запись · N мин» with the
     * poll rate and the two lamps as words; Метка only when driver marks are on.
     */
    private fun notification(text: String): Notification {
        val snap = LoggerState.snapshot
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(title: String, action: String, code: Int) = Compat.action(this, R.drawable.ic_notify, title,
            PendingIntent.getService(this, code, Intent(this, LoggerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE))
        val openAction = Compat.action(this, R.drawable.ic_notify, "Открыть", open)
        fun lamp(l: Lamp) = when (l) {
            Lamp.OK -> "●"
            Lamp.WAIT -> "◐"
            else -> "○"
        }
        val lamps = "${lamp(snap.link)} ЭБУ  ${lamp(snap.engine)} мотор"
        val title = if (recording) "Идёт запись · ${snap.elapsedSec / 60} мин" else text
        val body = if (recording) {
            listOfNotNull(
                snap.cycleMs.takeIf { it > 0 }?.let { "Опрос 1 строка / %.1f с".format(it / 1000.0) },
                if (snap.dtcInfo.contains(Regex("Ошибки: [PCBU]"))) "есть коды" else "кодов нет",
                lamps,
            ).joinToString(" · ")
        } else lamps
        return Compat.notificationBuilder(this, CHANNEL_ID, "Автозапись")
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .apply {
                if (recording && Prefs.marks(this@LoggerService)) addAction(action("Метка", ACTION_MARK, 1))
                addAction(openAction)
                addAction(action(if (autoMode && !recording) "Остановить автозапись" else "Стоп", ACTION_STOP, 2))
            }
            .build()
    }

    private var lastNotifyMs = 0L
    private var lastNotifyText = ""

    /** Updates the ongoing notification; while recording at most once a minute, so the shade does not flicker. */
    private fun updateNotification(text: String, force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && text == lastNotifyText && now - lastNotifyMs < 60_000) return
        lastNotifyMs = now
        lastNotifyText = text
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text))
    }

    /** «Поездка сохранена» with the verdict — a normal notification on its own channel. */
    private fun tripSavedNotification(csv: File) {
        val s = try {
            SessionFiles.analyze(csv)
        } catch (e: Exception) {
            null
        } ?: return
        val top = s.top
        val text = when {
            !s.dtcs.isNullOrEmpty() -> "Записан код ${s.dtcs!!.joinToString(", ")} — откройте разбор"
            s.durationMin < com.obdlogger.core.HomeLogic.NEED_TRIP_MIN -> "Короткая поездка — для вывода мало данных"
            top != null -> "Есть версия — ${top.headline.replaceFirstChar { it.lowercase() }} (${top.confidence})"
            else -> "Отклонений не найдено"
        }
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Compat.notificationBuilder(this, RESULT_CHANNEL_ID, "Разбор")
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Поездка сохранена")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(RESULT_NOTIFICATION_ID, n)
    }

    companion object {
        const val ACTION_START = "com.obdlogger.START"
        const val ACTION_AUTO = "com.obdlogger.AUTO"
        const val ACTION_STOP = "com.obdlogger.STOP"
        const val ACTION_MARK = "com.obdlogger.MARK"
        const val ACTION_POKE = "com.obdlogger.POKE"
        const val ACTION_CHECK = "com.obdlogger.CHECK"
        const val ACTION_CHECK_STOP = "com.obdlogger.CHECK_STOP"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_VEHICLE = "vehicle"
        const val EXTRA_EXTENDED = "extended"
        private const val SILENT_CYCLES_BEFORE_REINIT = 2
        private const val ENGINE_RPM = 300
        /** Engine off longer than this ends an auto-mode trip; a quick restart (remote start) stays in it. */
        private const val TRIP_GAP_MS = 2 * 60_000L
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_CHANNEL_ID = "result"
        private const val RESULT_NOTIFICATION_ID = 2

        fun intent(ctx: Context, action: String) = Intent(ctx, LoggerService::class.java).setAction(action)
    }
}
