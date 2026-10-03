package com.obdlogger.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.obdlogger.app.ui.Bt
import com.obdlogger.app.ui.HomeModel
import com.obdlogger.app.ui.HomeView
import com.obdlogger.app.ui.Shell
import com.obdlogger.app.ui.dp
import com.obdlogger.app.ui.text

class MainActivity : Activity() {
    private lateinit var devicesView: Spinner
    private lateinit var vehicleView: EditText
    private lateinit var startStop: Button
    private lateinit var demo: Button
    private lateinit var deviceHint: TextView
    private lateinit var mark: Button
    private lateinit var share: Button
    private lateinit var statusView: TextView
    private lateinit var progressView: TextView
    private lateinit var dtcView: TextView
    private lateinit var extended: CheckBox
    private lateinit var auto: CheckBox
    private lateinit var autoSetup: View
    private lateinit var shell: Shell
    private lateinit var home: HomeView
    private lateinit var tripsText: TextView
    private lateinit var monitor: MonitorPanel
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0
    @Volatile private var homeLoading = false
    private var tripsShownFor = -1
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
        // Настройки: прежние элементы управления, светлая тема.
        val settings = layoutInflater.inflate(R.layout.controls, null)
        shell.containers.getValue(Shell.Page.SETTINGS).addView(ScrollView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(settings)
        })
        home = HomeView(this, shell.sc) { shell.show(Shell.Page.TRIPS) }
        shell.containers.getValue(Shell.Page.OVERVIEW).addView(home)
        tripsText = text("Загрузка поездок…", if (shell.sc.phone) 11f else 14f, Bt.LIGHT.t1, 400, mono = true).apply {
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setTextIsSelectable(true)
        }
        shell.containers.getValue(Shell.Page.TRIPS).addView(ScrollView(this).apply {
            addView(HorizontalScrollView(this@MainActivity).apply { addView(tripsText) })
        })
        shell.onPage = { pg ->
            when (pg) {
                Shell.Page.OVERVIEW -> refreshHome()
                Shell.Page.TRIPS -> refreshTrips(force = true)
                else -> Unit
            }
        }
        devicesView = findViewById(R.id.devices)
        vehicleView = findViewById(R.id.vehicle)
        startStop = findViewById(R.id.startStop)
        demo = findViewById(R.id.demo)
        deviceHint = findViewById(R.id.deviceHint)
        mark = findViewById(R.id.mark)
        share = findViewById(R.id.share)
        statusView = findViewById(R.id.status)
        progressView = findViewById(R.id.progress)
        dtcView = findViewById(R.id.dtc)
        extended = findViewById(R.id.extended)
        auto = findViewById(R.id.auto)
        autoSetup = findViewById(R.id.autoSetup)
        extended.isChecked = Prefs.extended(this)
        auto.isChecked = Prefs.auto(this)
        monitor = MonitorPanel(this, shell.containers.getValue(Shell.Page.RECORD))

        vehicleView.setText(Prefs.vehicle(this))
        findViewById<Button>(R.id.refresh).setOnClickListener { loadDevices() }
        startStop.setOnClickListener { if (LoggerState.snapshot.running) stopRecording() else startRecording() }
        demo.setOnClickListener { startDemo() }
        auto.setOnCheckedChangeListener { _, checked -> setAuto(checked) }
        findViewById<Button>(R.id.battery).setOnClickListener { askBatteryExemption() }
        findViewById<Button>(R.id.overlay).setOnClickListener { askOverlay() }
        devicesView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateDeviceHint()
            override fun onNothingSelected(parent: AdapterView<*>?) = updateDeviceHint()
        }
        mark.setOnClickListener {
            val name = LoggerState.requestMarker()
            Toast.makeText(this, "Метка $name — запомните, что происходило", Toast.LENGTH_SHORT).show()
        }
        share.setOnClickListener { shareLast() }
        findViewById<Button>(R.id.exportAll).setOnClickListener { exportAll() }

        requestNeededPermissions()
        loadDevices()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
    }

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

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> return showStatus("На устройстве нет Bluetooth")
            !hasBluetoothPermission() -> return showStatus("Нужно разрешение «Устройства поблизости» для Bluetooth")
            !adapter.isEnabled -> {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return showStatus("Включите Bluetooth и нажмите «Обновить»")
            }
        }
        devices = adapter!!.bondedDevices.sortedBy { d -> if (looksLikeObd(d)) 0 else 1 }
        devicesView.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            devices.map { "${it.name ?: "?"}  (${it.address})" },
        )
        val last = Prefs.device(this)
        devices.indexOfFirst { it.address == last }.takeIf { it >= 0 }?.let { devicesView.setSelection(it) }
        if (devices.isEmpty()) showStatus("Нет сопряжённых устройств. Сопрягите адаптер в настройках Bluetooth (PIN 1234 или 0000).")
        updateDeviceHint()
    }

    // Adapters usually call themselves OBDII / OBD2 / ELM327 / V-LINK.
    @SuppressLint("MissingPermission")
    private fun looksLikeObd(d: BluetoothDevice): Boolean {
        val name = d.name.orEmpty().uppercase()
        return listOf("OBD", "ELM", "LINK", "VGATE", "ICAR", "KONNWEI", "SCAN").any { it in name }
    }

    private fun updateDeviceHint() {
        val d = devices.getOrNull(devicesView.selectedItemPosition)
        deviceHint.visibility = if (d != null && !looksLikeObd(d)) View.VISIBLE else View.GONE
        deviceHint.text = "Это устройство не похоже на OBD-адаптер (обычно они называются OBDII, OBD2, ELM327 или V-LINK). " +
            "Если в списке нет адаптера: вставьте его в разъём, включите зажигание и сопрягите в настройках Bluetooth."
    }

    /** Saves the chosen adapter and car; false if no adapter is selected. */
    private fun saveChoice(): Boolean {
        val device = devices.getOrNull(devicesView.selectedItemPosition)
        if (device == null) {
            showStatus("Выберите адаптер")
            return false
        }
        prefs.edit()
            .putString(Prefs.DEVICE, device.address)
            .putString(Prefs.VEHICLE, vehicleView.text.toString().trim())
            .putBoolean(Prefs.EXTENDED, extended.isChecked)
            .apply()
        return true
    }

    private fun startRecording() {
        if (!hasBluetoothPermission()) return requestNeededPermissions()
        if (!saveChoice()) return
        if (auto.isChecked) return startAuto()
        Compat.startForegroundService(
            this,
            LoggerService.intent(this, LoggerService.ACTION_START)
                .putExtra(LoggerService.EXTRA_ADDRESS, Prefs.device(this))
                .putExtra(LoggerService.EXTRA_VEHICLE, Prefs.vehicle(this))
                .putExtra(LoggerService.EXTRA_EXTENDED, extended.isChecked),
        )
    }

    private fun startAuto() {
        Compat.startForegroundService(this, LoggerService.intent(this, LoggerService.ACTION_AUTO))
    }

    private fun setAuto(on: Boolean) {
        if (on) {
            if (!hasBluetoothPermission()) {
                auto.isChecked = false
                return requestNeededPermissions()
            }
            if (!saveChoice()) {
                auto.isChecked = false
                return
            }
            prefs.edit().putBoolean(Prefs.AUTO, true).apply()
            if (!LoggerState.snapshot.running) startAuto()
            Toast.makeText(
                this,
                "Автозапись включена. Чтобы она не выключалась в фоне, нажмите «Не ограничивать в фоне» и «Открывать при старте».",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            prefs.edit().putBoolean(Prefs.AUTO, false).apply()
            if (LoggerState.snapshot.auto) stopRecording()
        }
    }

    private fun startDemo() {
        Compat.startForegroundService(
            this,
            LoggerService.intent(this, LoggerService.ACTION_START)
                .putExtra(LoggerService.EXTRA_DEMO, true)
                .putExtra(LoggerService.EXTRA_VEHICLE, "ДЕМО: симуляция, не реальный автомобиль"),
        )
    }

    private fun stopRecording() {
        // «Стоп» turns everything off, including auto mode.
        if (auto.isChecked) auto.isChecked = false
        startService(LoggerService.intent(this, LoggerService.ACTION_STOP))
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        if (Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) || Build.MANUFACTURER.equals("HONOR", ignoreCase = true)) {
            Toast.makeText(
                this,
                "Huawei: Настройки → Батарея → Запуск приложений → OBD Логгер → «Управлять вручную», включить все три переключателя.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun askOverlay() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Уже разрешено: приложение откроется само, когда начнётся поездка", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun shareLast() {
        val uris = ArrayList<Uri>(LoggerState.snapshot.exported)
        if (uris.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Отправить логи"))
    }

    /** Re-exports every session kept in app storage, e.g. after the app was killed mid-recording. */
    private fun exportAll() {
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

    private fun showStatus(text: String) {
        statusView.text = text
    }

    private fun render(s: LoggerState.Snapshot) {
        shell.render(s)
        if (shell.page == Shell.Page.TRIPS) refreshTrips()
        statusView.text = s.status
        startStop.text = when {
            s.auto -> "Остановить автозапись"
            s.running -> "Остановить запись"
            else -> "Начать запись"
        }
        demo.isEnabled = !s.running
        mark.isEnabled = s.recording
        mark.text = if (s.markers > 0) "Метка (поставлено: ${s.markers})" else "Метка (что-то почувствовал)"
        share.isEnabled = !s.recording && s.exported.isNotEmpty()
        devicesView.isEnabled = !s.running
        vehicleView.isEnabled = !s.running
        extended.isEnabled = !s.running
        autoSetup.visibility = if (auto.isChecked) View.VISIBLE else View.GONE
        progressView.text = if (s.recording || s.rows > 0) {
            "Строк: ${s.rows}   Время: %d:%02d   Цикл: %.1f с".format(s.elapsedSec / 60, s.elapsedSec % 60, s.cycleMs / 1000.0)
        } else ""
        dtcView.text = s.dtcInfo
        if (s.recording) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
