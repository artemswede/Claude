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
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.obdlogger.app.ui.Bt
import com.obdlogger.app.ui.HomeModel
import com.obdlogger.app.ui.HomeView
import com.obdlogger.app.ui.SettingsView
import com.obdlogger.app.ui.Shell
import com.obdlogger.app.ui.dp
import com.obdlogger.app.ui.text

class MainActivity : Activity(), SettingsView.Host {
    private lateinit var shell: Shell
    private lateinit var home: HomeView
    private lateinit var settings: SettingsView
    private lateinit var tripsText: TextView
    private lateinit var monitor: MonitorPanel
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0
    @Volatile private var homeLoading = false
    private var tripsShownFor = -1
    private var settingsShownFor: LoggerState.Snapshot? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (shell.page == Shell.Page.RECORD) monitor.refresh()
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
        shell = Shell(this)
        setContentView(shell.root)

        home = HomeView(this, shell.sc, onDetails = { shell.show(Shell.Page.TRIPS) }, onSettings = { shell.show(Shell.Page.SETTINGS) })
        shell.containers.getValue(Shell.Page.OVERVIEW).addView(home)

        monitor = MonitorPanel(this, shell.containers.getValue(Shell.Page.RECORD))

        tripsText = text("Загрузка поездок…", if (shell.sc.phone) 11f else 14f, Bt.LIGHT.t1, 400, mono = true).apply {
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setTextIsSelectable(true)
        }
        shell.containers.getValue(Shell.Page.TRIPS).addView(ScrollView(this).apply {
            addView(HorizontalScrollView(this@MainActivity).apply { addView(tripsText) })
        })

        settings = SettingsView(this, shell.sc)
        shell.containers.getValue(Shell.Page.SETTINGS).addView(settings)

        shell.onPage = { pg ->
            when (pg) {
                Shell.Page.OVERVIEW -> refreshHome()
                Shell.Page.TRIPS -> refreshTrips(force = true)
                Shell.Page.SETTINGS -> refreshSettings(force = true)
                else -> Unit
            }
        }

        requestNeededPermissions()
        loadDevices()
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
            // Android 7–9 save to the public Downloads folder directly.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
    }

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Paired devices, OBD-looking ones first; null with a reason when Bluetooth is not usable. */
    @SuppressLint("MissingPermission")
    private fun loadDevices(): String? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
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
                prefs.edit().putString(Prefs.VEHICLE, input.text.toString().trim()).apply()
                refreshSettings(force = true)
                shell.render(LoggerState.snapshot)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun batteryText(): String {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val huawei = Build.MANUFACTURER.equals("HUAWEI", true) || Build.MANUFACTURER.equals("HONOR", true)
        return when {
            !pm.isIgnoringBatteryOptimizations(packageName) -> "ограничено — система может остановить запись в фоне. Нажмите, чтобы снять"
            huawei -> "снято. На Huawei ещё: Батарея → Запуск приложений → Бортач → «Управлять вручную»"
            else -> "снято — запись не остановится при выключенном экране"
        }
    }

    @SuppressLint("BatteryLife")
    override fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
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
        if (Settings.canDrawOverlays(this)) "разрешено — Бортач откроется сам, когда начнётся поездка"
        else "не разрешено — запись идёт в фоне, приложение открывается вручную"

    override fun askOverlay() {
        if (Settings.canDrawOverlays(this)) {
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

    override fun demo() {
        if (LoggerState.snapshot.running) {
            Toast.makeText(this, "Сначала остановите запись", Toast.LENGTH_SHORT).show()
            return
        }
        Compat.startForegroundService(
            this,
            LoggerService.intent(this, LoggerService.ACTION_START)
                .putExtra(LoggerService.EXTRA_DEMO, true)
                .putExtra(LoggerService.EXTRA_VEHICLE, "ДЕМО: симуляция, не реальный автомобиль"),
        )
        shell.show(Shell.Page.OVERVIEW)
    }

    override fun version(): String = BuildConfig.VERSION_NAME

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
            }
        }.start()
    }

    private fun refreshTrips(force: Boolean = false) {
        val saved = LoggerState.snapshot.savedTrips
        if (!force && saved == tripsShownFor) return
        tripsShownFor = saved
        Thread {
            val text = try {
                SessionFiles.compareRecent(this)
            } catch (e: Exception) {
                "Не удалось разобрать поездки: ${e.message}"
            }
            runOnUiThread { tripsText.text = text }
        }.start()
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
