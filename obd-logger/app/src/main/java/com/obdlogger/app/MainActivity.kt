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
import android.view.WindowManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

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
    private lateinit var valuesView: TextView
    private var devices: List<BluetoothDevice> = emptyList()

    private val listener: (LoggerState.Snapshot) -> Unit = { render(it) }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
        valuesView = findViewById(R.id.values)

        vehicleView.setText(prefs.getString(PREF_VEHICLE, "Toyota Avensis 2005"))
        findViewById<Button>(R.id.refresh).setOnClickListener { loadDevices() }
        startStop.setOnClickListener { if (LoggerState.snapshot.running) stopRecording() else startRecording() }
        demo.setOnClickListener { startDemo() }
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
    }

    override fun onStart() {
        super.onStart()
        LoggerState.addListener(listener)
        render(LoggerState.snapshot)
    }

    override fun onStop() {
        LoggerState.removeListener(listener)
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
            adapter == null -> return showStatus("На телефоне нет Bluetooth")
            !hasBluetoothPermission() -> return showStatus("Нужно разрешение «Устройства поблизости» для Bluetooth")
            !adapter.isEnabled -> {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return showStatus("Включите Bluetooth и нажмите «Обновить»")
            }
        }
        // Adapters usually call themselves OBDII / OBD2 / ELM327 / V-LINK; show them first.
        devices = adapter!!.bondedDevices.sortedBy { d -> if (looksLikeObd(d)) 0 else 1 }
        devicesView.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            devices.map { "${it.name ?: "?"}  (${it.address})" },
        )
        val last = prefs.getString(PREF_DEVICE, null)
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

    private fun startRecording() {
        if (!hasBluetoothPermission()) return requestNeededPermissions()
        val device = devices.getOrNull(devicesView.selectedItemPosition)
            ?: return showStatus("Выберите адаптер")
        val vehicle = vehicleView.text.toString().trim()
        prefs.edit().putString(PREF_DEVICE, device.address).putString(PREF_VEHICLE, vehicle).apply()
        startForegroundService(
            LoggerService.intent(this, LoggerService.ACTION_START)
                .putExtra(LoggerService.EXTRA_ADDRESS, device.address)
                .putExtra(LoggerService.EXTRA_VEHICLE, vehicle),
        )
    }

    private fun startDemo() {
        startForegroundService(
            LoggerService.intent(this, LoggerService.ACTION_START)
                .putExtra(LoggerService.EXTRA_DEMO, true)
                .putExtra(LoggerService.EXTRA_VEHICLE, "ДЕМО: симуляция, не реальный автомобиль"),
        )
    }

    private fun stopRecording() {
        startService(LoggerService.intent(this, LoggerService.ACTION_STOP))
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
        if (LoggerState.snapshot.running) {
            Toast.makeText(this, "Сначала остановите запись", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val files = SessionFiles.dir(this).listFiles().orEmpty().filter { it.length() > 0 }.sortedBy { it.name }
            val ok = files.count { SessionFiles.exportToDownloads(this, it) != null }
            val text = when {
                files.isEmpty() -> "Сохранённых записей пока нет"
                ok < files.size -> "Скопировано $ok из ${files.size} файлов в Загрузки/${SessionFiles.DOWNLOAD_FOLDER}"
                else -> "Скопировано файлов: $ok в Загрузки/${SessionFiles.DOWNLOAD_FOLDER}"
            }
            runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun showStatus(text: String) {
        statusView.text = text
    }

    private fun render(s: LoggerState.Snapshot) {
        statusView.text = s.status
        startStop.text = if (s.running) "Остановить запись" else "Начать запись"
        demo.isEnabled = !s.running
        mark.isEnabled = s.running
        mark.text = if (s.markers > 0) "Метка (поставлено: ${s.markers})" else "Метка (что-то почувствовал)"
        share.isEnabled = !s.running && s.exported.isNotEmpty()
        devicesView.isEnabled = !s.running
        vehicleView.isEnabled = !s.running
        progressView.text = if (s.running || s.rows > 0) {
            "Строк: ${s.rows}   Время: %d:%02d   Цикл: %.1f с".format(s.elapsedSec / 60, s.elapsedSec % 60, s.cycleMs / 1000.0)
        } else ""
        dtcView.text = s.dtcInfo
        valuesView.text = s.values.joinToString("\n") { (k, v) -> "%-24s %s".format(k, v) }
        if (s.running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    companion object {
        private const val PREF_DEVICE = "device"
        private const val PREF_VEHICLE = "vehicle"
    }
}
