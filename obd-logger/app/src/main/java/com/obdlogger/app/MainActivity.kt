package com.obdlogger.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.obdlogger.app.ui.Bt
import com.obdlogger.app.ui.CheckActions
import com.obdlogger.app.ui.CheckView
import com.obdlogger.app.ui.HomeModel
import com.obdlogger.core.CheckResult
import com.obdlogger.core.CheckTest
import com.obdlogger.app.ui.HomeView
import com.obdlogger.app.ui.PlanView
import com.obdlogger.app.ui.RecordView
import com.obdlogger.app.ui.ReportData
import com.obdlogger.app.ui.Reports
import com.obdlogger.app.ui.SettingsView
import com.obdlogger.app.ui.SetupView
import com.obdlogger.app.ui.Shell
import com.obdlogger.app.ui.TripActions
import com.obdlogger.app.ui.TripCache
import com.obdlogger.app.ui.TripDetailView
import com.obdlogger.app.ui.TripItem
import com.obdlogger.app.ui.TripsModel
import com.obdlogger.app.ui.TripsView
import com.obdlogger.app.ui.VersionActions
import com.obdlogger.app.ui.VersionView
import com.obdlogger.core.Finding
import com.obdlogger.core.Hypotheses
import com.obdlogger.core.Hypothesis
import java.io.File

class MainActivity : Activity(), SettingsView.Host, SetupView.Host, TripActions, VersionActions, CheckActions {
    private lateinit var shell: Shell
    private lateinit var home: HomeView
    private lateinit var settings: SettingsView
    private lateinit var trips: TripsView
    @Volatile private var tripsModel: TripsModel? = null
    /** Car chosen in the journal; null = the car of the newest trip. */
    private var tripsCar: String? = null
    /** Pages opened on top of a section (trip, version, plan); «назад» closes the top one. */
    private val stack = HashMap<Shell.Page, ArrayList<View>>()
    private lateinit var record: RecordView
    @Volatile private var storeLoading = false
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0
    @Volatile private var homeLoading = false
    private var tripsShownFor = -1
    private var settingsShownFor: LoggerState.Snapshot? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (shell.page == Shell.Page.RECORD) refreshRecord()
            checkView?.let { if (it.isShown) refreshCheck() }
            // The main screen re-analyses the trip every 5 s while visible.
            if (shell.page == Shell.Page.OVERVIEW && ticks % 5 == 0) refreshHome()
            ticks++
            handler.postDelayed(this, 1000)
        }
    }
    private var devices: List<BluetoothDevice> = emptyList()

    private val listener: (LoggerState.Snapshot) -> Unit = { render(it) }

    private val prefs by lazy { Prefs.of(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        shell = Shell(this)
        setContentView(shell.root)

        home = makeHome(false)
        shell.containers.getValue(Shell.Page.OVERVIEW).addView(home)

        record = RecordView(this, shell.sc)
        shell.containers.getValue(Shell.Page.RECORD).addView(record)

        trips = TripsView(this, shell.sc, { openTrip(it) }, { key -> tripsCar = key; refreshTrips(force = true) })
        shell.containers.getValue(Shell.Page.TRIPS).addView(trips)

        settings = SettingsView(this, shell.sc)
        shell.containers.getValue(Shell.Page.SETTINGS).addView(settings)

        shell.onCar = { editCar() }
        shell.onPage = { pg ->
            when (pg) {
                Shell.Page.OVERVIEW -> refreshHome()
                Shell.Page.TRIPS -> refreshTrips(force = true)
                Shell.Page.SETTINGS -> refreshSettings(force = true)
                else -> Unit
            }
        }

        if (!LoggerState.snapshot.running) Thread { Recovery.run(this); runOnUiThread { if (shell.page == Shell.Page.OVERVIEW) refreshHome() } }.start()
        loadDevices()
        if (!Prefs.setupDone(this) && Prefs.device(this) == null) {
            setup = SetupView(this, shell.sc, this).also { setContentView(it) }
        } else {
            requestNeededPermissions()
        }
        Thread { SessionFiles.cleanup(this, Prefs.keepDays(this)) }.start()
        // Auto mode is on but the service is not running (app was updated, killed or the tablet rebooted).
        if (Prefs.auto(this) && !LoggerState.snapshot.running && Prefs.device(this) != null && hasBluetoothPermission()) {
            startAuto()
        }
    }

    override fun onStart() {
        super.onStart()
        LoggerState.addListener(listener)
        render(LoggerState.snapshot)
        handler.post(ticker)
    }

    override fun onStop() {
        LoggerState.removeListener(listener)
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the battery / overlay system screens.
        refreshSettings(force = true)
        loadDevices()
        setup?.refresh()
        if (shell.page == Shell.Page.OVERVIEW) refreshHome()
    }

    // ---- SetupView.Host (first run) ----

    private var setup: SetupView? = null

    @SuppressLint("MissingPermission")
    override fun devices(): Pair<List<Triple<String, String, Boolean>>, String?> {
        val problem = loadDevices()
        return devices.map { Triple(it.name ?: "?", it.address, looksLikeObd(it)) } to problem
    }

    override fun selectedDevice(): String? = Prefs.device(this)
    override fun selectDevice(address: String) = prefs.edit().putString(Prefs.DEVICE, address).apply()
    override fun openBluetoothSettings() = startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    override fun permissionsGranted(): Boolean = hasBluetoothPermission() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Compat.granted(this, Manifest.permission.POST_NOTIFICATIONS))

    override fun requestPermissions() = requestNeededPermissions()

    override fun batteryFree(): Boolean = Compat.batteryFree(this)
    override fun overlayAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Compat.canOverlay(this)
    override fun setCar(text: String) = Prefs.setVehicle(this, text)

    override fun finishSetup() {
        prefs.edit().putBoolean(Prefs.SETUP_DONE, true).apply()
        setup = null
        setContentView(shell.root)
        shell.render(LoggerState.snapshot)
        shell.show(Shell.Page.OVERVIEW)
        if (Prefs.auto(this) && !LoggerState.snapshot.running && Prefs.device(this) != null && hasBluetoothPermission()) startAuto()
    }

    /** Е1–Е4: what stops recording right now, if anything. */
    private fun currentProblem(s: LoggerState.Snapshot): HomeView.Problem? {
        val bt = Compat.bluetooth(this)
        val killed = Prefs.killedAt(this)
        val wantsCar = Prefs.auto(this) || s.running
        return when {
            // Not while a trip is being recorded: then it already works, the notice waits for the stop.
            killed > 0 && !s.recording -> {
                // A crash left its report: that is our fault, not a background limit.
                val crashed = CrashLog.files(this).firstOrNull()?.let { it.lastModified() > killed - 12 * 60 * 60 * 1000L } == true
                if (crashed) HomeView.Problem(
                    "Запись", "Бортач закрылся с ошибкой",
                    "Прошлая запись оборвалась из-за ошибки в приложении. Записанное до этого момента сохранено в «Поездках». " +
                        "Отправьте отчёт об ошибке — по нему её исправят.",
                    "Отправить отчёт", { shareCrashes(); clearKilled() },
                    dismiss = { clearKilled() },
                ) else HomeView.Problem(
                    "Запись", "Система остановила запись",
                    "Прошлая запись оборвалась, хотя устройство оставалось включённым: Android закрыл Бортач в фоне. " +
                        "Записанное до этого момента сохранено в «Поездках». Чтобы это не повторялось, снимите ограничение батареи.",
                    "Снять ограничение", { askBattery(); clearKilled() },
                    dismiss = { clearKilled() },
                )
            }
            wantsCar && Prefs.device(this) != null && !hasBluetoothPermission() -> HomeView.Problem(
                "Разрешения", "Нет разрешения на Bluetooth",
                "Без разрешения «Устройства поблизости» Бортач не может подключиться к адаптеру.",
                "Разрешить", { requestNeededPermissions() },
            )
            wantsCar && bt != null && !bt.isEnabled -> HomeView.Problem(
                "Bluetooth", "Bluetooth выключен",
                "Адаптер подключается по Bluetooth. Включите его — запись начнётся сама, когда заведёте мотор.",
                "Включить Bluetooth", { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            )
            s.running && s.link == Lamp.FAIL -> HomeView.Problem(
                "Адаптер", "Адаптер сопряжён, но молчит",
                "Bluetooth соединяется, а ELM327 не отвечает на команды. Чаще всего адаптер обесточен: вынут или не держит контакт в разъёме.",
                "Переподключить", { startService(LoggerService.intent(this, LoggerService.ACTION_POKE)) },
                hint = "Проверьте, горит ли индикатор на адаптере.",
            )
            else -> null
        }
    }

    private fun clearKilled() {
        prefs.edit().remove(Prefs.KILLED_AT).apply()
        refreshHome()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
        refreshSettings(force = true)
    }

    // ---- permissions and adapter ----

    private fun requestNeededPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            // Android 5–9 save to the public Downloads folder directly.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { !Compat.granted(this, it) }
        if (needed.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) requestPermissions(needed.toTypedArray(), 1)
    }

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            Compat.granted(this, Manifest.permission.BLUETOOTH_CONNECT)

    /** Paired devices, OBD-looking ones first; null with a reason when Bluetooth is not usable. */
    @SuppressLint("MissingPermission")
    private fun loadDevices(): String? {
        val adapter = Compat.bluetooth(this)
        val problem = when {
            adapter == null -> "На устройстве нет Bluetooth"
            !hasBluetoothPermission() -> "Нужно разрешение «Устройства поблизости» для Bluetooth"
            !adapter.isEnabled -> "Bluetooth выключен"
            else -> null
        }
        if (problem != null) {
            devices = emptyList()
            return problem
        }
        devices = adapter!!.bondedDevices.sortedBy { d -> if (looksLikeObd(d)) 0 else 1 }
        return null
    }

    // Adapters usually call themselves OBDII / OBD2 / ELM327 / V-LINK.
    @SuppressLint("MissingPermission")
    private fun looksLikeObd(d: BluetoothDevice): Boolean {
        val name = d.name.orEmpty().uppercase()
        return listOf("OBD", "ELM", "LINK", "VGATE", "ICAR", "KONNWEI", "SCAN").any { it in name }
    }

    @SuppressLint("MissingPermission")
    override fun pickAdapter() {
        val problem = loadDevices()
        if (problem != null) {
            if (problem == "Bluetooth выключен") startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else if (!hasBluetoothPermission()) requestNeededPermissions()
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        val names = devices.map { d -> "${d.name ?: "?"}  (${d.address})" + if (looksLikeObd(d)) "" else " — не похоже на OBD-адаптер" }
        AlertDialog.Builder(this)
            .setTitle(if (devices.isEmpty()) "Нет сопряжённых устройств" else "Адаптер ELM327")
            .apply {
                if (devices.isEmpty()) {
                    setMessage("Вставьте адаптер в разъём OBD, включите зажигание и сопрягите его в настройках Bluetooth (код 1234 или 0000).")
                } else {
                    setItems(names.toTypedArray()) { _, i ->
                        prefs.edit().putString(Prefs.DEVICE, devices[i].address).apply()
                        refreshSettings(force = true)
                    }
                }
            }
            .setNeutralButton("Настройки Bluetooth") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---- SettingsView.Host ----

    override fun autoOn() = Prefs.auto(this)

    override fun setAuto(on: Boolean) {
        if (on) {
            if (!hasBluetoothPermission()) {
                requestNeededPermissions()
                refreshSettings(force = true)
                return
            }
            if (Prefs.device(this) == null) {
                Toast.makeText(this, "Сначала выберите адаптер", Toast.LENGTH_LONG).show()
                refreshSettings(force = true)
                pickAdapter()
                return
            }
            prefs.edit().putBoolean(Prefs.AUTO, true).apply()
            if (!LoggerState.snapshot.running) startAuto()
        } else {
            prefs.edit().putBoolean(Prefs.AUTO, false).apply()
            if (LoggerState.snapshot.auto) stopRecording()
        }
        refreshSettings(force = true)
    }

    override fun bootOn() = Prefs.boot(this)
    override fun setBoot(on: Boolean) = prefs.edit().putBoolean(Prefs.BOOT, on).apply()
    override fun extendedOn() = Prefs.extended(this)
    override fun setExtended(on: Boolean) = prefs.edit().putBoolean(Prefs.EXTENDED, on).apply()
    override fun marksOn() = Prefs.marks(this)
    override fun setMarks(on: Boolean) = prefs.edit().putBoolean(Prefs.MARKS, on).apply()
    override fun traceOn() = Prefs.trace(this)
    override fun setTrace(on: Boolean) = prefs.edit().putBoolean(Prefs.TRACE, on).apply()
    override fun keepDays() = Prefs.keepDays(this)

    override fun setKeepDays(days: Int) {
        prefs.edit().putInt(Prefs.KEEP_DAYS, days).apply()
        Thread { SessionFiles.cleanup(this, days) }.start()
    }

    @SuppressLint("MissingPermission")
    override fun adapterText(): String {
        val addr = Prefs.device(this) ?: return "не выбран — нажмите, чтобы выбрать"
        val d = devices.firstOrNull { it.address == addr }
        val proto = LoggerState.snapshot.protocol.takeIf { it.isNotBlank() }
        return listOfNotNull(d?.name ?: addr, proto).joinToString(" · ")
    }

    override fun carText() = Prefs.vehicle(this)

    override fun editCar() {
        val input = EditText(this).apply {
            setText(Prefs.vehicle(this@MainActivity))
            hint = "Марка, модель, год, двигатель, пробег"
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Профиль машины")
            .setMessage("Попадёт в разбор и в отчёт для специалиста.")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                Prefs.setVehicle(this, input.text.toString().trim())
                refreshSettings(force = true)
                shell.render(LoggerState.snapshot)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun batteryText(): String {
        val huawei = Build.MANUFACTURER.equals("HUAWEI", true) || Build.MANUFACTURER.equals("HONOR", true)
        return when {
            !Compat.batteryFree(this) -> "ограничено — система может остановить запись в фоне. Нажмите, чтобы снять"
            huawei -> "снято. На Huawei ещё: Батарея → Запуск приложений → Бортач → «Управлять вручную»"
            else -> "снято — запись не остановится при выключенном экране"
        }
    }

    @SuppressLint("BatteryLife")
    override fun askBattery() {
        if (!Compat.batteryFree(this)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } else {
            Toast.makeText(this, batteryText(), Toast.LENGTH_LONG).show()
        }
    }

    override fun overlayText() =
        if (Compat.canOverlay(this)) "разрешено — Бортач откроется сам, когда начнётся поездка"
        else "не разрешено — запись идёт в фоне, приложение открывается вручную"

    override fun askOverlay() {
        if (Compat.canOverlay(this)) {
            Toast.makeText(this, overlayText(), Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun storageText() = SessionFiles.storageSummary(this)

    override fun canShareLast() = !LoggerState.snapshot.recording && LoggerState.snapshot.exported.isNotEmpty()

    override fun shareLast() {
        val uris = ArrayList<Uri>(LoggerState.snapshot.exported)
        if (uris.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Отправить записи"))
    }

    /** Re-exports every session kept in app storage, e.g. after the app was killed mid-recording. */
    override fun exportAll() {
        if (LoggerState.snapshot.recording) {
            Toast.makeText(this, "Сначала дождитесь конца записи", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val files = SessionFiles.dir(this).listFiles().orEmpty().filter { it.length() > 0 }.sortedBy { it.name }
            val ok = files.count { SessionFiles.exportToDownloads(this, it).ok }
            val text = when {
                files.isEmpty() -> "Сохранённых записей пока нет"
                ok < files.size -> "Скопировано $ok из ${files.size} файлов в Загрузки/${SessionFiles.DOWNLOAD_FOLDER}"
                else -> "Скопировано файлов: $ok в Загрузки/${SessionFiles.DOWNLOAD_FOLDER}"
            }
            runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun manualText(): String {
        val s = LoggerState.snapshot
        return when {
            s.auto -> "идёт автозапись — остановить всё"
            s.running -> "идёт запись — нажмите, чтобы остановить"
            else -> "начать запись сейчас, не дожидаясь автозаписи"
        }
    }

    override fun toggleManual() {
        if (LoggerState.snapshot.running) stopRecording() else startRecording()
    }

    override fun version(): String = BuildConfig.VERSION_NAME

    override fun crashText(): String? = CrashLog.files(this).takeIf { it.isNotEmpty() }?.let { f ->
        "приложение закрывалось с ошибкой ${f.size} раз(а), последний — ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT).format(java.util.Date(f.first().lastModified()))}. Пришлите разработчику"
    }

    override fun shareCrashes() = share(CrashLog.files(this).take(5), "Бортач: отчёт об ошибке")

    override fun readiness(): List<Pair<String, Boolean>> = listOf(
        "Адаптер выбран" to (Prefs.device(this) != null),
        "Автозапись поездок включена" to Prefs.auto(this),
        "Запуск при включении планшета" to Prefs.boot(this),
        "Разрешения Bluetooth и уведомлений" to permissionsGranted(),
        "Система не ограничивает Бортач в фоне" to batteryFree(),
        "Открываться при запуске мотора (необязательно)" to overlayAllowed(),
    )

    // ---- recording ----

    private fun startRecording() {
        if (!hasBluetoothPermission()) return requestNeededPermissions()
        val device = Prefs.device(this) ?: return pickAdapter()
        if (Prefs.auto(this)) return startAuto()
        Compat.startForegroundService(
            this,
            LoggerService.intent(this, LoggerService.ACTION_START)
                .putExtra(LoggerService.EXTRA_ADDRESS, device)
                .putExtra(LoggerService.EXTRA_VEHICLE, Prefs.vehicle(this))
                .putExtra(LoggerService.EXTRA_EXTENDED, Prefs.extended(this)),
        )
    }

    private fun startAuto() {
        Compat.startForegroundService(this, LoggerService.intent(this, LoggerService.ACTION_AUTO))
    }

    private fun stopRecording() {
        // Stopping by hand also turns auto mode off, otherwise it would start again at once.
        if (Prefs.auto(this)) prefs.edit().putBoolean(Prefs.AUTO, false).apply()
        startService(LoggerService.intent(this, LoggerService.ACTION_STOP))
    }

    // ---- screens ----

    /** Analyses trips off the main thread and updates the main screen. */
    private fun refreshHome() {
        updateNight()
        if (homeLoading) return
        homeLoading = true
        val snap = LoggerState.snapshot
        Thread {
            val model = try {
                HomeModel.build(this, snap)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                homeLoading = false
                if (model != null) home.bind(model, LoggerState.snapshot)
                home.problem(currentProblem(LoggerState.snapshot))
            }
        }.start()
    }

    /** Live data of the record page; after a restart of the app the last trip is loaded instead. */
    private fun refreshRecord() {
        val s = LoggerState.snapshot
        if (!s.recording && LiveData.store.size() == 0 && !storeLoading) {
            storeLoading = true
            Thread {
                SessionFiles.tripCsvs(this).lastOrNull()?.let { SessionFiles.loadInto(it, LiveData.store) }
                runOnUiThread { record.bind(LiveData.store, LoggerState.snapshot) }
            }.start()
        }
        record.bind(LiveData.store, s)
    }

    private fun refreshTrips(force: Boolean = false) {
        val saved = LoggerState.snapshot.savedTrips
        if (!force && saved == tripsShownFor) return
        tripsShownFor = saved
        Thread {
            val m = try {
                TripsModel.build(this, tripsCar)
            } catch (e: Exception) {
                null
            }
            runOnUiThread { if (m != null) { tripsModel = m; trips.bind(m) } }
        }.start()
    }

    // ---- pages on top of a section ----

    private fun push(page: Shell.Page, v: View) {
        val box = shell.containers.getValue(page)
        val list = stack.getOrPut(page) { ArrayList() }
        (list.lastOrNull() ?: box.getChildAt(0))?.visibility = View.GONE
        list += v
        box.addView(v)
        if (shell.page != page) shell.show(page)
    }

    private fun pop(): Boolean {
        val list = stack[shell.page] ?: return false
        val top = list.removeLastOrNull() ?: return false
        if (top === checkView) checkView = null
        val box = shell.containers.getValue(shell.page)
        box.removeView(top)
        (list.lastOrNull() ?: box.getChildAt(0))?.visibility = View.VISIBLE
        return true
    }

    @Deprecated("Activity back handling for API 24+")
    override fun onBackPressed() {
        if (!pop()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun makeHome(night: Boolean) = HomeView(this, shell.sc, onDetails = { openHomeVersion() }, onSettings = { shell.show(Shell.Page.SETTINGS) },
        onCheck = { openCheck() }, onOpenLast = { openLastTrip() }, p = if (night) Bt.DARK else Bt.LIGHT)

    /** Д7: dark main screen from 21:00 to 7:00 while driving, so it does not glare. */
    private fun updateNight() {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val night = LoggerState.snapshot.recording && (h >= 21 || h < 7)
        if (night == shell.nightHome) return
        val box = shell.containers.getValue(Shell.Page.OVERVIEW)
        val idx = box.indexOfChild(home)
        val fresh = makeHome(night)
        fresh.visibility = home.visibility
        box.removeView(home)
        box.addView(fresh, idx.coerceAtLeast(0))
        home = fresh
        shell.nightHome = night
        refreshHome()
    }

    private fun openLastTrip() {
        Thread {
            val m = TripsModel.build(this).also { tripsModel = it }
            runOnUiThread {
                shell.show(Shell.Page.TRIPS)
                trips.bind(m)
                m.trips.firstOrNull()?.let {
                    Prefs.setSeenTrip(this, it.summary.name)
                    openTrip(it)
                }
            }
        }.start()
    }

    private fun openTrip(item: TripItem) {
        if (item === tripsModel?.trips?.firstOrNull()) Prefs.setSeenTrip(this, item.summary.name)
        val v = TripDetailView(this, shell.sc, item, this)
        push(Shell.Page.TRIPS, v)
        Thread {
            val d = TripCache.detail(item)
            runOnUiThread { v.bind(d) }
        }.start()
    }

    /** «Подробнее» on the main screen: the version card of the trip shown there. */
    private fun openHomeVersion() {
        Thread {
            val model = HomeModel.build(this, LoggerState.snapshot)
            val f = model.trip?.top ?: model.past?.top
            // Only this car's trips go into the version.
            val m = TripsModel.build(this, (model.trip ?: model.past)?.car?.key)
            runOnUiThread {
                if (f == null) shell.show(Shell.Page.TRIPS)
                else push(Shell.Page.OVERVIEW, VersionView(this, shell.sc, Hypotheses.of(f, m.trips.map { it.summary } + listOfNotNull(model.trip.takeIf { model.live })), "Обзор", this))
            }
        }.start()
    }

    // ---- TripActions / VersionActions ----

    override fun back() {
        pop()
    }

    override fun share(files: List<File>, title: String) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { ShareProvider.uri(this, it) })
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
            .setType("text/*")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) send.putExtra(Intent.EXTRA_STREAM, uris[0]) else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        startActivity(Intent.createChooser(send, title))
    }

    /** Report data for a trip: detail, this car's comparison up to the trip, check logs, version. Heavy. */
    private fun reportData(item: TripItem): ReportData {
        val m = TripsModel.build(this, item.summary.car?.key)
        val upTo = m.trips.filter { (it.summary.start ?: java.time.LocalDateTime.MIN) <= (item.summary.start ?: java.time.LocalDateTime.MAX) }
        val sums = upTo.map { it.summary }
        val h = item.summary.top?.takeIf { item.summary.durationMin >= com.obdlogger.core.HomeLogic.NEED_TRIP_MIN }?.let { Hypotheses.of(it, sums) }
        val interrupted = SessionFiles.infoOf(item.csv).takeIf { it.exists() }?.readText()?.contains("=== ЗАПИСЬ ПРЕРВАНА ===") == true
        return ReportData(item, TripCache.detail(item), com.obdlogger.core.TripComparison.table(sums, 5), m.checks, h, interrupted)
    }

    override fun printReport(item: TripItem) {
        Toast.makeText(this, "Готовлю отчёт с графиками…", Toast.LENGTH_SHORT).show()
        Thread {
            val html = try {
                Reports.tripHtml(reportData(item), Prefs.vehicle(this), BuildConfig.VERSION_NAME)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (html == null) Toast.makeText(this, "Не удалось собрать отчёт", Toast.LENGTH_LONG).show()
                else print("Бортач — поездка ${HomeModel.tripRange(item.summary)}", html)
            }
        }.start()
    }

    /** «Поделиться» a trip: its files plus the same report as HTML (opens in any browser, with charts). */
    override fun shareTrip(item: TripItem) {
        Thread {
            val report = try {
                File(SessionFiles.dir(this), "report_${item.csv.nameWithoutExtension}.html").apply {
                    writeText(Reports.tripHtml(reportData(item), Prefs.vehicle(this@MainActivity), BuildConfig.VERSION_NAME))
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread { share(item.files() + listOfNotNull(report), "Поездка ${HomeModel.tripRange(item.summary)}") }
        }.start()
    }

    override fun openVersion(f: Finding, item: TripItem?) {
        val all = tripsModel?.trips?.map { it.summary }.orEmpty().filter { item == null || it.car?.key == item.summary.car?.key }
        val upTo = item?.summary?.start?.let { st -> all.filter { (it.start ?: st) <= st } } ?: all
        push(shell.page, VersionView(this, shell.sc, Hypotheses.of(f, upTo.ifEmpty { listOfNotNull(item?.summary) }), if (shell.page == Shell.Page.TRIPS) "Поездка" else "Обзор", this))
    }

    override fun openPlan(h: Hypothesis) = push(shell.page, PlanView(this, shell.sc, h, Prefs.vehicle(this), this))

    override fun startCheck() {
        if (shell.page != Shell.Page.OVERVIEW) shell.show(Shell.Page.OVERVIEW)
        while (pop()) Unit
        openCheck()
    }

    // ---- CheckActions ----

    private var checkView: CheckView? = null
    private var checkResults: Pair<CheckResult?, CheckResult?>? = null
    @Volatile private var checkLoading = false

    private fun openCheck() {
        val v = CheckView(this, shell.sc, this)
        checkView = v
        push(Shell.Page.OVERVIEW, v)
        refreshCheck()
    }

    private fun refreshCheck() {
        val v = checkView ?: return
        val s = LoggerState.snapshot
        val done = s.check?.phase == CheckTest.Phase.DONE
        if (done && checkResults == null && !checkLoading && s.checkCsv != null) {
            checkLoading = true
            Thread {
                val f = File(s.checkCsv)
                val now = try { CheckResult.of(f.nameWithoutExtension, f.readText()) } catch (e: Exception) { null }
                val prevFile = SessionFiles.checkCsvs(this).lastOrNull { it.name < f.name }
                val prev = prevFile?.let { pf -> try { CheckResult.of(pf.nameWithoutExtension, pf.readText()) } catch (e: Exception) { null } }
                runOnUiThread {
                    checkResults = now to prev
                    checkLoading = false
                    checkView?.bind(LoggerState.snapshot, checkResults)
                }
            }.start()
        }
        v.bind(s, if (done) checkResults else null)
    }

    override fun startTest() {
        checkResults = null
        startService(LoggerService.intent(this, LoggerService.ACTION_CHECK))
    }

    override fun stopTest() {
        startService(LoggerService.intent(this, LoggerService.ACTION_CHECK_STOP))
    }

    override fun closeTest() {
        checkResults = null
        LoggerState.update { it.copy(check = null) }
    }

    override fun openCompare() {
        shell.show(Shell.Page.TRIPS)
        while (pop()) Unit
        trips.showTab(1)
    }

    /** This car's comparison for the plan (the version's trips decide the car). */
    private fun planComparison(h: Hypothesis) = h.seenIn.lastOrNull()?.first?.car?.key?.let { key ->
        com.obdlogger.core.TripComparison.table(TripsModel.build(this, key).trips.map { it.summary }, 5)
    }

    override fun printPlan(h: Hypothesis) {
        Thread {
            val html = Reports.planHtml(h, Prefs.vehicle(this), BuildConfig.VERSION_NAME, planComparison(h))
            runOnUiThread { print("Бортач — план проверки", html) }
        }.start()
    }

    override fun sharePlan(h: Hypothesis) {
        Thread {
            val f = File(SessionFiles.dir(this), "plan_${h.finding.kind.ifEmpty { "version" }}.html")
            f.writeText(Reports.planHtml(h, Prefs.vehicle(this), BuildConfig.VERSION_NAME, planComparison(h)))
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/html")
                    .putExtra(Intent.EXTRA_TEXT, Reports.planText(h, Prefs.vehicle(this)))
                    .putExtra(Intent.EXTRA_STREAM, ShareProvider.uri(this, f))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "План для мастера"))
            }
        }.start()
    }

    /** Print / save as PDF through the system print dialog. */
    private var printView: WebView? = null

    private fun print(title: String, html: String) {
        val wv = WebView(this)
        printView = wv
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                pm.print(title, view.createPrintDocumentAdapter(title), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
                printView = null
            }
        }
        // Base URL = assets, so the report's @font-face finds the IBM Plex fonts.
        wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
    }

    private fun refreshSettings(force: Boolean = false) {
        if (!::settings.isInitialized) return
        val s = LoggerState.snapshot
        val prev = settingsShownFor
        // Rebuild only when something shown there changed (it holds switches the user may be touching).
        if (!force && prev != null && prev.running == s.running && prev.auto == s.auto && prev.protocol == s.protocol &&
            prev.exported == s.exported && prev.status == s.status
        ) return
        settingsShownFor = s
        if (shell.page == Shell.Page.SETTINGS || force) settings.bind(this, s.status.takeIf { s.running }.orEmpty())
    }

    private fun render(s: LoggerState.Snapshot) {
        shell.render(s)
        if (shell.page == Shell.Page.TRIPS) refreshTrips()
        if (shell.page == Shell.Page.SETTINGS) refreshSettings()
        if (s.recording) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
