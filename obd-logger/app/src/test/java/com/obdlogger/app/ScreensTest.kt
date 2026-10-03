package com.obdlogger.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.obdlogger.app.ui.HomeModel
import com.obdlogger.app.ui.HomeView
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
            "N1_settings" to { shell(Shell.Page.SETTINGS, off) { sh -> SettingsView(a, sh.sc).apply { bind(FakeHost, "") } } },
        )
    }
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
