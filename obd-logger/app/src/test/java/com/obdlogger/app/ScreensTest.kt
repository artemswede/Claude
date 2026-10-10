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
 * Renders every screen with real recorded trips into PNGs (build/screens/<size>/),
 * so layout, sizes and fonts can be checked without a device, and runs the UX
 * audit ([UxAudit]) on each: small touch targets, clipped text, too small type,
 * low contrast, views off screen. Any crash while building a screen fails the build.
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

    /** Car head unit 1024×600 at hdpi: wide and short. */
    @Test
    @Config(qualifiers = "w683dp-h400dp-land-mdpi")
    fun headUnit() = all("headunit")

    /** The owner's head unit: 1024×600 at 1.4 after the Android bars and [com.obdlogger.app.UiScale] ≈ 731×400 dp. */
    @Test
    @Config(qualifiers = "w731dp-h400dp-land-mdpi")
    fun headUnitWide() = all("headunit731")

    /** Regression: a gauge with no room used to hang the app on «Внимание» (head unit). */
    @Test(timeout = 20_000)
    @Config(qualifiers = "w683dp-h400dp-land-mdpi")
    fun tinyGaugeDoesNotHang() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val store = SeriesStore().also { SessionFiles.loadInto(Samples.realItems.last().csv, it) }
        val item = com.obdlogger.core.Attention.rank(store).first()
        for ((w, h) in listOf(120 to 40, 60 to 20, 300 to 1)) {
            val g = com.obdlogger.app.ui.GaugeView(a, com.obdlogger.app.ui.Bt.DARK)
            g.set(item, 1, store.series(item.code, 0).second, null)
            g.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            g.layout(0, 0, w, h)
            g.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
        }
    }

    private fun all(size: String) {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scenes = Scenes(a)
        val report = StringBuilder()
        val failed = mutableListOf<String>()
        for ((name, build) in scenes.list()) {
            // One broken scene must not hide the others: its stack goes to the report, the test fails at the end.
            val root = try {
                build().also { shot(a, size, name, it) }
            } catch (e: Throwable) {
                failed += name
                report.append("== $name: ПАДЕНИЕ\n").append(e.stackTraceToString().lines().take(25).joinToString("\n")).append('\n')
                continue
            }
            val issues = UxAudit.check(root)
            report.append("== $name: ${if (issues.isEmpty()) "ok" else "${issues.size} замечаний"}\n")
            issues.forEach { report.append("   - ").append(it).append('\n') }
        }
        val dir = File(System.getProperty("screens.dir") ?: "build/screens", size).apply { mkdirs() }
        File(dir, "ux-report.txt").writeText(report.toString())
        if (failed.isNotEmpty()) throw AssertionError("$size: сцены упали: $failed\n$report")
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

/**
 * Sample data: only real recorded trips (test fixtures of one car). Trips without
 * an info file borrow the previous trip's, so all belong to the same car.
 */
object NoChat : com.obdlogger.app.ui.ChatActions {
    override fun sendQuestion(text: String) {}
    override fun voiceQuestion() {}
    override fun editAiKey() {}
    override fun resetChat() {}
    override fun runResearch() {}
    override fun chartView(req: com.obdlogger.core.ChartRequest): android.view.View? = null
}

object NoDtc : com.obdlogger.app.ui.DtcActions {
    override fun closeCodes() {}
    override fun readCodes() {}
    override fun clearCodes() {}
    override fun shareCodes(path: String) {}
}

object NoBaseline : com.obdlogger.app.ui.BaselineActions {
    override fun closeBaseline() {}
    override fun resetProfile(note: String?) {}
    override fun askAbout(question: String) {}
}

object Samples {
    private val dir = File("../obd-core/src/test/resources/trips")
    private val csvs by lazy { dir.listFiles().orEmpty().filter { it.name.endsWith(".csv") }.sortedBy { it.name } }

    private fun infoFor(f: File): String? {
        val own = File(dir, f.nameWithoutExtension + "_info.txt")
        if (own.exists()) return own.readText()
        return csvs.takeWhile { it != f }.reversed().map { File(dir, it.nameWithoutExtension + "_info.txt") }.firstOrNull { it.exists() }?.readText()
    }

    val real: List<TripSummary> by lazy {
        csvs.mapNotNull { f -> TripAnalyzer.analyze(f.nameWithoutExtension, f.readText(), infoFor(f)) }
    }

    val realItems: List<TripItem> by lazy {
        csvs.mapNotNull { f -> real.firstOrNull { it.name == f.nameWithoutExtension }?.let { TripItem(f, it, null) } }
    }

    fun detail(item: TripItem) = TripDetail(TripAnalyzer.table(item.csv.readText())!!, item.summary)

    private val realProfiles by lazy { realItems.map { com.obdlogger.core.TripProfile.of(detail(it)) } }

    /**
     * A learnt car from the real trips, repeated over [n] days; with [shift] the last
     * three trips run the coolant 3 °C hotter in every mode — «обычно 97–98, стало 100–101».
     */
    fun profile(n: Int, shift: Boolean): CarProfile.State {
        val start = java.time.LocalDateTime.of(2026, 9, 20, 8, 0)
        val list = (0 until n).map { i ->
            val base = realProfiles[i % realProfiles.size]
            val hot = shift && i >= n - 3
            val stats = base.stats.mapValues { (code, byMode) ->
                if (!hot || code != "coolant_c") byMode
                else byMode.mapValues { (_, st) -> com.obdlogger.core.TripProfile.Stat(st.median + 3, st.p10 + 3, st.p90 + 3, st.n) }
            }
            val at = start.plusDays(i.toLong())
            com.obdlogger.core.TripProfile("t$i", at.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")), at, stats, base.intakeAir)
        }
        return CarProfile.State(real.last().car?.key, list, null, null)
    }

    /** The same real trip seen differently: no version (calm) or with a stored code. */
    fun variant(t: TripSummary, findings: List<com.obdlogger.core.Finding>, dtcs: List<String>?) = TripSummary(
        t.name, t.start, t.durationMin, t.rows, t.modeRows, t.metrics, dtcs, findings, t.trace, t.warmIdleSec, t.car,
    )
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

    /** The last real trip, as if it were being recorded now. */
    private val liveStore by lazy {
        SeriesStore().also { SessionFiles.loadInto(Samples.realItems.last().csv, it) }
    }

    private fun check(s: LoggerState.Snapshot, results: Pair<CheckResult?, CheckResult?>?, st: CheckTest.State? = null,
        kind: com.obdlogger.core.CheckKind? = com.obdlogger.core.CheckKind.MIXTURE) = shell(Shell.Page.OVERVIEW, s) { sh ->
        CheckView(a, sh.sc, NoActions, kind).apply { bind(s.copy(check = st), results) }
    }

    private fun warming(): CheckTest.State {
        val t = CheckTest(0, com.obdlogger.core.CheckKind.WARMUP)
        var st: CheckTest.State? = null
        for (sec in 1..300) st = t.update(sec * 1000L, 900.0, 30.0, 30.0 + sec * 0.12)
        return st!!
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
        RecordView(a, sh.sc).apply {
            if (!sh.sc.phone) sh.setPageBar(Shell.Page.RECORD, header)
            onMode = { sh.setPageInfo(Shell.Page.RECORD, it) }
            bind(liveStore, s); showTab(tab)
        }
    }

    fun list(): List<Pair<String, () -> View>> {
        val real = Samples.real
        return listOf(
            "D1_version" to { home(recording, real.dropLast(1), real.last()) },
            // A trip whose version is not about the mixture: the chart and trend follow rpm.
            "D1b_version_rpm" to { home(recording, real.take(1), real[1]) },
            "D2_calm" to { home(recording, real.dropLast(1), Samples.variant(real.last(), emptyList(), emptyList())) },
            "D3_dtc" to { home(recording, real.dropLast(1), Samples.variant(real.last(), real.last().findings, listOf("P0171"))) },
            "D4_collecting" to { home(recording, real.drop(1), real.first()) },
            "D7_night" to {
                val shell = Shell(a)
                shell.nightHome = true
                shell.containers.getValue(Shell.Page.OVERVIEW).addView(HomeView(a, shell.sc, {}, {}, p = Bt.DARK).apply {
                    bind(HomeModel.from(real.dropLast(1), real.last(), recording), recording)
                })
                shell.show(Shell.Page.OVERVIEW)
                shell.render(recording)
                shell.root
            },
            "D8_usual_changed" to { shell(Shell.Page.OVERVIEW, recording) { sh ->
                HomeView(a, sh.sc, {}, {}).apply { bind(HomeModel.from(real.dropLast(1), real.last(), recording), recording); bindUsual(Samples.profile(10, true)) } } },
            "D9_usual_learning" to { shell(Shell.Page.OVERVIEW, recording) { sh ->
                HomeView(a, sh.sc, {}, {}).apply { bind(HomeModel.from(real.dropLast(1), real.last(), recording), recording); bindUsual(Samples.profile(3, false)) } } },
            "B1_usual" to { shell(Shell.Page.OVERVIEW, waiting) { sh -> com.obdlogger.app.ui.BaselineView(a, sh.sc, NoBaseline).apply { bind(Samples.profile(10, true)) } } },
            "B2_usual_calm" to { shell(Shell.Page.OVERVIEW, waiting) { sh -> com.obdlogger.app.ui.BaselineView(a, sh.sc, NoBaseline).apply { bind(Samples.profile(10, false)) } } },
            "B3_usual_learning" to { shell(Shell.Page.OVERVIEW, waiting) { sh -> com.obdlogger.app.ui.BaselineView(a, sh.sc, NoBaseline).apply { bind(Samples.profile(3, false)) } } },
            "V3b_compare_usual" to { shell(Shell.Page.TRIPS, waiting) { sh ->
                CarProfile.use(Samples.profile(10, true))
                TripsView(a, sh.sc, {}).apply { bind(TripsModel.from(Samples.realItems)); showTab(1) }.also { CarProfile.use(null) } } },
            "D5_wait" to { home(waiting, real, null) },
            "D6_noconn" to { home(waiting.copy(link = Lamp.FAIL, linkText = "нет связи с адаптером"), real, null) },
            "D_off" to { home(off, real, null) },
            "D_notrips" to { home(LoggerState.Snapshot(), emptyList(), null) },
            "G1_panel" to { record(0, recording) },
            "G3_attention" to { record(1, recording) },
            "G1b_panel_page2" to { shell(Shell.Page.RECORD, recording) { sh -> RecordView(a, sh.sc).apply { if (!sh.sc.phone) sh.setPageBar(Shell.Page.RECORD, header); bind(liveStore, recording); showTab(0); showPanelPage(1) } } },
            "G2_charts" to { record(2, recording) },
            "G2b_charts_overlay" to { shell(Shell.Page.RECORD, recording) { sh -> RecordView(a, sh.sc).apply {
                if (!sh.sc.phone) sh.setPageBar(Shell.Page.RECORD, header); bind(liveStore, recording); showOverlay(listOf("trim_b1", "maf_gs", "rpm")) } } },
            "H1_chat_nokey" to { shell(Shell.Page.CHAT, recording) { sh -> com.obdlogger.app.ui.ChatView(a, sh.sc, NoChat).apply {
                bind(com.obdlogger.core.ChatState(), null, false, "Ответы — по данным этой машины: Avensis 2.0 D-4 · поездок 4 · проверочных логов 1", null) } } },
            "H2_chat" to { shell(Shell.Page.CHAT, recording) { sh -> com.obdlogger.app.ui.ChatView(a, sh.sc, object : com.obdlogger.app.ui.ChatActions by NoChat {
                override fun chartView(req: com.obdlogger.core.ChartRequest): android.view.View? = when (req) {
                    is com.obdlogger.core.ChartRequest.Overlay -> com.obdlogger.app.ui.TripCache.detail(Samples.realItems.last())?.let {
                        com.obdlogger.app.ui.OverlayChartView(a, com.obdlogger.app.ui.Bt.LIGHT, it, req.sensors)
                    }
                    else -> com.obdlogger.app.ui.TrendChartView(a, com.obdlogger.app.ui.Bt.LIGHT,
                        "Коррекция Б1 по поездкам · холостой", "%", listOf("03.10 11:46" to 14.8, "03.10 16:06" to 13.3, "03.10 19:51" to 21.9, "08.10 17:20" to 19.6), -10.0, 10.0)
                }
            }).apply {
                bind(com.obdlogger.core.ChatState(mutableListOf(
                    com.obdlogger.core.ChatMessage("user", "Почему коррекция Б1 на холостом выше, чем в движении?"),
                    com.obdlogger.core.ChatMessage("assistant", "**Коротко:** похоже на подсос воздуха.\n\n- На холостом коррекция Б1 +21.9 %, в движении −3.1 %: лишний воздух заметен, когда его мало.\n- Задняя лямбда на ХХ 0.06 В — «бедно».\n\nПроверьте шланги вентиляции картера и прокладку впуска, затем запишите проверочный лог.\n[график: тренд trim_b1 WARM_IDLE]"),
                    com.obdlogger.core.ChatMessage("user", "А может быть забит топливный фильтр?"),
                    com.obdlogger.core.ChatMessage("assistant", "Вряд ли: под нагрузкой коррекция падает до −3 %, а забитый фильтр дал бы рост именно там. Смотрите на наложение — коррекция растёт, когда ДМРВ на холостом минимален:\n[график: наложение trim_b1 maf_gs rpm последняя]"),
                ), "конспект"), "Думаю… (глубокий режим — до минуты)", true, "Ответы — по данным этой машины: Avensis 2.0 D-4 · поездок 4 · проверочных логов 1 · коды: P0136, P0156", null) } } },
            "H3_chat_actions" to { shell(Shell.Page.CHAT, recording) { sh -> com.obdlogger.app.ui.ChatView(a, sh.sc, object : com.obdlogger.app.ui.ChatActions by NoChat {
                override fun chartView(req: com.obdlogger.core.ChartRequest): android.view.View? =
                    com.obdlogger.app.ui.LiveOverlayView(a, com.obdlogger.app.ui.Bt.LIGHT, liveStore, req.let { (it as? com.obdlogger.core.ChartRequest.Live)?.sensors ?: listOf(it.sensor) }, { true })
            }).apply {
                bind(com.obdlogger.core.ChatState(mutableListOf(
                    com.obdlogger.core.ChatMessage("user", "Что сейчас происходит со смесью?"),
                    com.obdlogger.core.ChatMessage("assistant", "Сейчас коррекция Б1 держится около +18 %, а ДМРВ на холостом 1.6 г/с — ниже обычного для вашей машины. " +
                        "Это похоже на подсос после ДМРВ. Посмотрите живой график и проверьте под нагрузкой.\n[график: наложение trim_b1 maf_gs rpm сейчас]\n[проверка: смесь]\n[наблюдать: trim_b1 > 15 когда WARM_IDLE]"),
                    com.obdlogger.core.ChatMessage("user", "📋 Наблюдение «Коррекция Б1 > 15 % — холостой ход», поездка 09.10 08:10 (31 мин): выполнялось 6 раз, всего 4 мин 12 с из 7 мин 40 с в режиме (54 %)."),
                ), ""), null, true, "Ответы — по данным этой машины · поездок 4 · проверок 1", null,
                    listOf(com.obdlogger.core.WatchRule("trim_b1", ">", 15.0, com.obdlogger.core.LiveMode.IDLE, 2)), false) } } },
            // Codes screen with what the owner's car reported: both rear O2 sensors, flat at 0.02 V.
            "C1_codes" to {
                val ff = com.obdlogger.core.FreezeFrame("P0136", listOf(
                    com.obdlogger.core.FreezeFrame.Value("rpm", "об/мин", "", 1650.0),
                    com.obdlogger.core.FreezeFrame.Value("speed_kmh", "км/ч", "", 42.0),
                    com.obdlogger.core.FreezeFrame.Value("coolant_c", "°C", "", 88.0),
                    com.obdlogger.core.FreezeFrame.Value("engine_load_pct", "%", "", 31.0),
                    com.obdlogger.core.FreezeFrame.Value("stft_b1_pct", "%", "", 6.3),
                    com.obdlogger.core.FreezeFrame.Value("ltft_b1_pct", "%", "", 13.3),
                    com.obdlogger.core.FreezeFrame.Value("o2_b1s2_v", "В", "", 0.02),
                ))
                val s = recording.copy(
                    dtcSnap = com.obdlogger.core.DtcSnapshot(true, 2, listOf("P0136", "P0156"), emptyList(), null),
                    freeze = ff, dtcReportFile = "/x/dtc_20261007_1700.txt",
                    dtcInfo = "Check Engine: горит\nОшибки: P0136, P0156\nОжидающие: нет",
                )
                shell(Shell.Page.OVERVIEW, s) { sh -> com.obdlogger.app.ui.DtcView(a, sh.sc, NoDtc, { liveStore }).apply { bind(s) } }
            },
            "N2_stale" to { record(0, off) },
            "V1_journal" to { shell(Shell.Page.TRIPS, waiting) { sh -> TripsView(a, sh.sc, {}).apply { bind(TripsModel.from(Samples.realItems)) } } },
            "V1a_empty" to { shell(Shell.Page.TRIPS, waiting) { sh -> TripsView(a, sh.sc, {}).apply { bind(TripsModel.from(emptyList())) } } },
            "V3_compare" to { shell(Shell.Page.TRIPS, waiting) { sh -> TripsView(a, sh.sc, {}).apply { bind(TripsModel.from(Samples.realItems)); showTab(1) } } },
            "V3a_compare_few" to { shell(Shell.Page.TRIPS, waiting) { sh -> TripsView(a, sh.sc, {}).apply { bind(TripsModel.from(Samples.realItems.take(1))); showTab(1) } } },
            "V2_trip" to { trip(0) },
            "V2_stats" to { trip(1) },
            "V2_rating" to { trip(2) },
            "V2_charts" to { trip(3) },
            "K2_version" to { shell(Shell.Page.OVERVIEW, waiting) { sh -> VersionView(a, sh.sc, hypothesis(), "Обзор", NoActions) } },
            "K3_plan" to { shell(Shell.Page.OVERVIEW, waiting) { sh -> PlanView(a, sh.sc, hypothesis(), "Toyota Avensis 2005 · 2.0 D-4 (1AZ-FSE)", NoActions) } },
            "P0_checks" to { check(recording.copy(values = listOf("rpm" to "760", "speed_kmh" to "0", "coolant_c" to "88")), null, kind = null) },
            "P1b_prep_charge" to { check(recording.copy(values = listOf("rpm" to "760", "speed_kmh" to "0", "coolant_c" to "88")), null, kind = com.obdlogger.core.CheckKind.CHARGE) },
            "P2b_warmup" to { check(recording, null, warming()) },
            "P4b_result_charge" to { check(recording, CheckResult("c", null, null, null, null, 760.0, null, null, null, com.obdlogger.core.CheckKind.CHARGE, voltIdle = 14.1, voltLoad = 13.3) to
                CheckResult("c0", java.time.LocalDateTime.of(2026, 10, 1, 9, 0), null, null, null, 760.0, null, null, null, com.obdlogger.core.CheckKind.CHARGE, voltIdle = 14.2, voltLoad = 13.8), done()) },
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
    override fun startTest(kind: com.obdlogger.core.CheckKind) = Unit
    override fun stopTest() = Unit
    override fun closeTest() = Unit
    override fun back() = Unit
    override fun share(files: List<java.io.File>, title: String) = Unit
    override fun printReport(item: TripItem) = Unit
    override fun shareTrip(item: TripItem) = Unit
    override fun openVersion(f: com.obdlogger.core.Finding, item: TripItem?) = Unit
    override fun openPlan(h: com.obdlogger.core.Hypothesis) = Unit
    override fun startCheck(kind: com.obdlogger.core.CheckKind) = Unit
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
    override fun version() = "20261003_2259"
    override fun crashText(): String? = null
    override fun shareCrashes() = Unit
    override fun readiness() = listOf("Адаптер выбран" to true, "Автозапись поездок включена" to true, "Запуск при включении планшета" to true,
        "Разрешения Bluetooth и уведомлений" to true, "Система не ограничивает Бортач в фоне" to false, "Открываться при запуске мотора (необязательно)" to false)
}
