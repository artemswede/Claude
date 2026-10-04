package com.obdlogger.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.obdlogger.app.ui.HomeModel
import com.obdlogger.app.ui.HomeView
import com.obdlogger.app.ui.Bt
import com.obdlogger.app.ui.CheckActions
import com.obdlogger.app.ui.CheckView
import com.obdlogger.app.ui.PlanView
import com.obdlogger.app.ui.SetupView
import com.obdlogger.core.CheckResult
import com.obdlogger.core.CheckTest
import com.obdlogger.app.ui.RecordView
import com.obdlogger.app.ui.TripActions
import com.obdlogger.app.ui.TripDetailView
import com.obdlogger.app.ui.TripItem
import com.obdlogger.app.ui.TripsModel
import com.obdlogger.app.ui.TripsView
import com.obdlogger.app.ui.VersionActions
import com.obdlogger.app.ui.VersionView
import com.obdlogger.core.Hypotheses
import com.obdlogger.core.TripDetail
import com.obdlogger.core.SeriesStore
import com.obdlogger.app.ui.SettingsView
import com.obdlogger.app.ui.Shell
import com.obdlogger.core.CarProfile
import com.obdlogger.core.SimDrive
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.TripSummary
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every screen with real and simulated trips into PNGs (build/screens/<size>/),
 * so layout, sizes and fonts can be checked without a device. Any crash while
 * building a screen fails the build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreensTest {
    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tablet() = all("1280x800")

    @Test
    @Config(qualifiers = "w1024dp-h600dp-land-mdpi")
    fun small() = all("1024x600")

    @Test
    @Config(qualifiers = "w400dp-h860dp-port-mdpi")
    fun phone() = all("phone")

    private fun all(size: String) {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scenes = Scenes(a)
        for ((name, build) in scenes.list()) {
            val root = build()
            shot(a, size, name, root)
        }
    }

    private fun shot(a: Activity, size: String, name: String, view: View) {
        val dm = a.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File(System.getProperty("screens.dir") ?: "build/screens", size).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Sample data: the real Avensis trips from the fixtures plus simulated cars. */
object Samples {
    private val dir = File("../obd-core/src/test/resources/trips")

    val real: List<TripSummary> by lazy {
        dir.listFiles().orEmpty().filter { it.name.endsWith(".csv") }.sortedBy { it.name }.mapNotNull { f ->
            val info = File(dir, f.nameWithoutExtension + "_info.txt")
            TripAnalyzer.analyze(f.nameWithoutExtension, f.readText(), if (info.exists()) info.readText() else null)
        }
    }

    val realItems: List<TripItem> by lazy {
        dir.listFiles().orEmpty().filter { it.name.endsWith(".csv") }.sortedBy { it.name }.mapNotNull { f ->
            real.firstOrNull { it.name == f.nameWithoutExtension }?.let { TripItem(f, it, null) }
        }
    }

    fun detail(item: TripItem) = TripDetail(TripAnalyzer.table(item.csv.readText())!!, item.summary)

    fun sim(profile: CarProfile, minutes: Double = 20.0) = SimDrive.drive(profile, minutes)

    fun simSummary(profile: CarProfile, minutes: Double = 20.0): TripSummary {
        val t = sim(profile, minutes)
        return TripAnalyzer.analyze(t.name, t.csv, t.info)!!
    }
}

/** Every screen state as (name, builder); each builder returns the full window content. */
class Scenes(private val a: Activity) {
    private val recording = LoggerState.Snapshot(
        running = true, auto = true, recording = true, link = Lamp.OK, linkText = "ЭБУ на связи",
        engine = Lamp.OK, engineText = "работает, 690 об/мин", rows = 412, elapsedSec = 1260, cycleMs = 3300,
        lastDataMs = System.currentTimeMillis() - 3000, protocol = "ISO 9141-2",
        dtcInfo = "Check Engine: не горит\nОшибки: нет\nОжидающие: нет",
    )
    private val waiting = LoggerState.Snapshot(
        running = true, auto = true, link = Lamp.OK, linkText = "ЭБУ на связи", engine = Lamp.OFF, engineText = "заглушен",
        lastDataMs = System.currentTimeMillis() - 3_600_000,
    )
    private val off = LoggerState.Snapshot(lastDataMs = System.currentTimeMillis() - 86_400_000)

    private fun shell(page: Shell.Page, s: LoggerState.Snapshot, content: (Shell) -> View): View {
        val shell = Shell(a)
        shell.containers.getValue(page).addView(content(shell))
        shell.show(page)
        shell.render(s)
        return shell.root
    }

    private fun home(s: LoggerState.Snapshot, saved: List<TripSummary>, current: TripSummary?) = shell(Shell.Page.OVERVIEW, s) { sh ->
        HomeView(a, sh.sc, {}, {}).apply { bind(HomeModel.from(saved, current, s), s) }
    }

    private val liveStore by lazy {
        val t = Samples.sim(CarProfile.LEAN_IDLE, 13.3)
        SeriesStore().apply {
            reset(t.columns)
            t.rows.forEach { (ms, v) -> add(ms, v) }
        }
    }

    private fun check(s: LoggerState.Snapshot, results: Pair<CheckResult?, CheckResult?>?, st: CheckTest.State? = null) = shell(Shell.Page.OVERVIEW, s) { sh ->
        CheckView(a, sh.sc, NoActions).apply { bind(s.copy(check = st), results) }
    }

    private fun run(t: CheckTest, until: Int, rpm: (Int) -> Double): CheckTest.State {
        var st: CheckTest.State? = null
        for (sec in 1..until) st = t.update(sec * 1000L, rpm(sec), 0.0)
        return st!!
    }

    private fun step2() = run(CheckTest(0), 162) { if (it <= 121) 760.0 else 2480.0 }
    private fun aborted() = CheckTest(0).let { t -> run(t, 30) { 760.0 }; t.update(31_000, 760.0, 14.0) }
    private fun done() = run(CheckTest(0), 245) { if (it in 122..181) 2500.0 else 760.0 }

    private fun checkResults(): Pair<CheckResult?, CheckResult?> {
        fun res(trim: Double, rear: Double) = CheckResult("c", null, trim, trim - 14, trim - 7, 690.0, rear, 0.7, 1.7)
        return res(4.2, 0.62) to res(21.4, 0.06)
    }

    private fun setup(step: Int): View = SetupView(a, Bt.scaleFor(a), FakeSetup).apply { show(step) }

    private fun problem(pr: HomeView.Problem) = shell(Shell.Page.OVERVIEW, waiting) { sh ->
        HomeView(a, sh.sc, {}, {}).apply { bind(HomeModel.from(Samples.real, null, waiting), waiting); problem(pr) }
    }

    private fun versionTrip() = Samples.realItems.last { it.summary.top != null }

    private fun hypothesis() = Hypotheses.of(versionTrip().summary.top!!, Samples.real)

    private fun trip(tab: Int) = shell(Shell.Page.TRIPS, waiting) { sh ->
        val item = versionTrip()
        TripDetailView(a, sh.sc, item, NoActions).apply { bind(Samples.detail(item)); showTab(tab) }
    }

    private fun record(tab: Int, s: LoggerState.Snapshot) = shell(Shell.Page.RECORD, s) { sh ->
        RecordView(a, sh.sc).apply { bind(liveStore, s); showTab(tab) }
    }

    fun list(): List<Pair<String, () -> View>> {
        val real = Samples.real
        return listOf(
            "D1_version" to { home(recording, real.dropLast(1), real.last()) },
            "D2_calm" to { home(recording, real, Samples.simSummary(CarProfile.HEALTHY)) },
            "D3_dtc" to { home(recording, real, Samples.simSummary(CarProfile.CAN_DTC)) },
            "D4_collecting" to { home(recording, real, Samples.simSummary(CarProfile.LEAN_IDLE, 3.0)) },
            "D5_wait" to { home(waiting, real, null) },
            "D6_noconn" to { home(waiting.copy(link = Lamp.FAIL, linkText = "нет связи с адаптером"), real, null) },
            "D_off" to { home(off, real, null) },
            "D_notrips" to { home(LoggerState.Snapshot(), emptyList(), null) },
            "G1_panel" to { record(0, recording) },
            "G3_attention" to { record(1, recording) },
            "G2_charts" to { record(2, recording) },
            "N2_stale" to { record(0, off) },
            "V1_journal" to { shell(Shell.Page.TRIPS, waiting) { sh -> TripsView(a, sh.sc) {}.apply { bind(TripsModel.from(Samples.realItems)) } } },
            "V1a_empty" to { shell(Shell.Page.TRIPS, waiting) { sh -> TripsView(a, sh.sc) {}.apply { bind(TripsModel.from(emptyList())) } } },
            "V3_compare" to { shell(Shell.Page.TRIPS, waiting) { sh -> TripsView(a, sh.sc) {}.apply { bind(TripsModel.from(Samples.realItems)); showTab(1) } } },
            "V3a_compare_few" to { shell(Shell.Page.TRIPS, waiting) { sh -> TripsView(a, sh.sc) {}.apply { bind(TripsModel.from(Samples.realItems.take(1))); showTab(1) } } },
            "V2_trip" to { trip(0) },
            "V2_stats" to { trip(1) },
            "V2_rating" to { trip(2) },
            "V2_charts" to { trip(3) },
            "K2_version" to { shell(Shell.Page.OVERVIEW, waiting) { sh -> VersionView(a, sh.sc, hypothesis(), "Обзор", NoActions) } },
            "K3_plan" to { shell(Shell.Page.OVERVIEW, waiting) { sh -> PlanView(a, sh.sc, hypothesis(), "Toyota Avensis 2005 · 2.0 D-4 (1AZ-FSE)", NoActions) } },
            "P1_prep" to { check(recording.copy(values = listOf("rpm" to "760", "speed_kmh" to "0", "coolant_c" to "88")), null) },
            "P2_step" to { check(recording, null, step2()) },
            "P3_abort" to { check(recording, null, aborted()) },
            "P4_result" to { check(recording, checkResults(), done()) },
            "R1_setup_adapter" to { setup(0) },
            "R3_setup_battery" to { setup(2) },
            "R6_setup_car" to { setup(5) },
            "E1_bt_off" to { problem(HomeView.Problem("Bluetooth", "Bluetooth выключен", "Адаптер подключается по Bluetooth. Включите его — запись начнётся сама, когда заведёте мотор.", "Включить Bluetooth", {})) },
            "E3_killed" to { problem(HomeView.Problem("Запись", "Система остановила запись", "В 03.10 11:40 Android закрыл Бортач в фоне. Записанное до этого момента сохранено в «Поездках». Чтобы это не повторялось, снимите ограничение батареи.", "Не ограничивать в фоне", {}, dismiss = {})) },
            "E4_silent" to { problem(HomeView.Problem("Адаптер", "Адаптер сопряжён, но молчит", "Bluetooth соединяется, а ELM327 не отвечает на команды. Чаще всего адаптер обесточен: вынут или не держит контакт в разъёме.", "Переподключить", {}, hint = "Проверьте, горит ли индикатор на адаптере.")) },
            "N1_settings" to { shell(Shell.Page.SETTINGS, off) { sh -> SettingsView(a, sh.sc).apply { bind(FakeHost, "") } } },
        )
    }
}

object FakeSetup : SetupView.Host {
    override fun devices() = listOf(Triple("OBDII", "00:1D:A5:68:98:8A", true), Triple("JBL Flip 5", "F8:DF:15:22:01:9C", false)) to null
    override fun selectedDevice() = "00:1D:A5:68:98:8A"
    override fun selectDevice(address: String) = Unit
    override fun openBluetoothSettings() = Unit
    override fun permissionsGranted() = true
    override fun requestPermissions() = Unit
    override fun batteryFree() = false
    override fun askBattery() = Unit
    override fun overlayAllowed() = false
    override fun askOverlay() = Unit
    override fun autoOn() = true
    override fun setAuto(on: Boolean) = Unit
    override fun bootOn() = true
    override fun setBoot(on: Boolean) = Unit
    override fun carText() = "Toyota Avensis 2005"
    override fun setCar(text: String) = Unit
    override fun finishSetup() = Unit
}

object NoActions : TripActions, VersionActions, CheckActions {
    override fun startTest() = Unit
    override fun stopTest() = Unit
    override fun closeTest() = Unit
    override fun back() = Unit
    override fun share(files: List<java.io.File>, title: String) = Unit
    override fun printReport(item: TripItem) = Unit
    override fun openVersion(f: com.obdlogger.core.Finding, item: TripItem?) = Unit
    override fun openPlan(h: com.obdlogger.core.Hypothesis) = Unit
    override fun startCheck() = Unit
    override fun openCompare() = Unit
    override fun printPlan(h: com.obdlogger.core.Hypothesis) = Unit
    override fun sharePlan(h: com.obdlogger.core.Hypothesis) = Unit
}

object FakeHost : SettingsView.Host {
    override fun autoOn() = true
    override fun setAuto(on: Boolean) = Unit
    override fun bootOn() = true
    override fun setBoot(on: Boolean) = Unit
    override fun extendedOn() = true
    override fun setExtended(on: Boolean) = Unit
    override fun marksOn() = false
    override fun setMarks(on: Boolean) = Unit
    override fun traceOn() = true
    override fun setTrace(on: Boolean) = Unit
    override fun keepDays() = 90
    override fun setKeepDays(days: Int) = Unit
    override fun adapterText() = "OBDII · ISO 9141-2"
    override fun pickAdapter() = Unit
    override fun carText() = "Toyota Avensis 2005"
    override fun editCar() = Unit
    override fun batteryText() = "снято — запись не остановится при выключенном экране"
    override fun askBattery() = Unit
    override fun overlayText() = "разрешено — Бортач откроется сам, когда начнётся поездка"
    override fun askOverlay() = Unit
    override fun storageText() = "6 поездок · 14.2 МБ · свободно 9.4 ГБ"
    override fun exportAll() = Unit
    override fun canShareLast() = true
    override fun shareLast() = Unit
    override fun manualText() = "начать запись сейчас, не дожидаясь автозаписи"
    override fun toggleManual() = Unit
    override fun demo() = Unit
    override fun version() = "20261003_2259"
}
