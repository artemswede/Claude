package com.obdlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Logic behind the record, trip, comparison, version and check-log screens. */
class ScreensLogicTest {
    private fun store(profile: CarProfile, minutes: Double): SeriesStore {
        val trip = SimDrive.drive(profile, minutes)
        return SeriesStore().apply {
            reset(trip.columns)
            trip.rows.forEach { (t, v) -> add(t, v) }
        }
    }

    @Test
    fun storeDerivesTotalTrim() {
        val s = store(CarProfile.LEAN_IDLE, 15.0)
        assertTrue("trim_b1" in s.columns)
        val st = s.last("stft_b1_pct")
        val lt = s.last("ltft_b1_pct")
        assertEquals(st + lt, s.last("trim_b1"), 1e-9)
    }

    @Test
    fun attentionPutsLeanTrimFirstOnIdle() {
        // The scenario parks with a warm idle at 700–820 s.
        val s = store(CarProfile.LEAN_IDLE, 13.3)
        val ranked = Attention.rank(s)
        assertEquals("trim_b1", ranked.first().code, ranked.joinToString { "${it.code}=${it.score}" })
        assertTrue((ranked.first().score ?: 0.0) > 50)
    }

    @Test
    fun panelShowsEverySensorWithTheProblemFirst() {
        val s = store(CarProfile.LEAN_IDLE, 13.3)
        val all = Panel.order(s, PanelSort.PROBLEM, emptyList(), null)
        assertEquals("trim_b1", all.first())
        // All decoded sensors, more than one page; raw bytes are off by default.
        assertTrue(all.size > Panel.PER_PAGE, all.toString())
        assertTrue(all.none { Panel.isRaw(it) || it == "t_s" || it == "time" }, all.toString())
        assertTrue("speed_kmh" in all && "intake_air_c" in all, all.toString())
        assertEquals(all.toSet(), Panel.order(s, PanelSort.JUMPS, emptyList(), null).toSet())
    }

    @Test
    fun panelKeepsTheOwnersOrderAndHidesSwitchedOff() {
        val s = store(CarProfile.LEAN_IDLE, 13.3)
        val hidden = setOf("trim_b2", "speed_kmh")
        val custom = Panel.order(s, PanelSort.CUSTOM, listOf("coolant_c", "rpm", "trim_b2"), hidden)
        assertEquals(listOf("coolant_c", "rpm"), custom.take(2))
        assertTrue(hidden.none { it in custom })
        assertTrue(hidden.none { it in Panel.order(s, PanelSort.PROBLEM, emptyList(), hidden) })
        assertEquals(1, Panel.pages(0))
        assertEquals(2, Panel.pages(9))
    }

    @Test
    fun healthyCarHasNothingOutOfNormOnIdle() {
        val s = store(CarProfile.HEALTHY, 13.3)
        val ranked = Attention.rank(s)
        assertTrue(ranked.none { (it.score ?: 0.0) > 30 }, ranked.joinToString { "${it.code}=${it.score} ${it.note}" })
    }

    @Test
    fun tripDetailRatesIdleTrimOutOfNorm() {
        val trip = SimDrive.drive(CarProfile.LEAN_IDLE, 20.0)
        val table = TripAnalyzer.table(trip.csv)!!
        val d = TripDetail(table, TripAnalyzer.analyze("t", table, trip.info))
        val trim = d.sensors.first { it.code == "trim_b1" }
        assertEquals(Verdict.OUT, trim.byMode.first { it.mode == DriveMode.WARM_IDLE }.verdict)
        assertEquals(Verdict.OK, trim.byMode.first { it.mode == DriveMode.CRUISE }.verdict)
        assertEquals(Verdict.NOT_RATED, trim.byMode.first { it.mode == DriveMode.COLD }.verdict)
        assertTrue("trim_b1" in d.rating(AttentionSort.DEVIATION).take(2).map { it.code })
        assertTrue(d.modeMinutes.getValue(DriveMode.WARM_IDLE) > 1.5)
    }

    @Test
    fun comparisonOfRealTrips() {
        val dir = java.io.File(javaClass.getResource("/trips")!!.toURI())
        val trips = dir.listFiles()!!.filter { it.name.endsWith(".csv") }.sortedBy { it.name }.mapNotNull { f ->
            val info = java.io.File(dir, f.nameWithoutExtension + "_info.txt")
            TripAnalyzer.analyze(f.nameWithoutExtension, f.readText(), if (info.exists()) info.readText() else null)
        }
        val t = TripComparison.table(trips)
        assertEquals(3, t.trips.size)
        val b1 = t.rows.first { it.metric == Metric.IDLE_TRIM_B1 }
        assertEquals(3, b1.values.size)
        assertTrue(b1.verdict.isNotBlank())
    }

    @Test
    fun forecastNeedsThreeTripsAndAMovingTrend() {
        fun trip(i: Int, trim: Double) = TripSummary("t$i", java.time.LocalDateTime.of(2026, 10, i, 10, 0), 30.0, 500,
            emptyMap(), mapOf(Metric.IDLE_TRIM_B1 to trim), emptyList(), emptyList())
        assertNull(TripComparison.table(listOf(trip(1, 10.0), trip(2, 14.0))).forecast)
        val f = TripComparison.table(listOf(trip(1, 10.0), trip(2, 14.0), trip(3, 18.0))).forecast
        assertNotNull(f)
        assertTrue("~2–3" in f.text, f.text)
    }

    @Test
    fun hypothesisForAirLeakHasPlan() {
        val s = SimDrive.drive(CarProfile.LEAN_IDLE, 20.0)
        val sum = TripAnalyzer.analyze("t", s.csv, s.info)!!
        val h = Hypotheses.of(sum.top!!, listOf(sum))
        assertEquals("air_leak", h.finding.kind)
        assertTrue(h.plan.size >= 4)
        assertTrue(h.proofs.isNotEmpty())
    }

    @Test
    fun checkTestRunsThroughStepsAndPausesOutsideCorridor() {
        val t = CheckTest(0)
        var now = 0L
        fun feed(sec: Int, rpm: Double): CheckTest.State {
            var st: CheckTest.State? = null
            repeat(sec) { now += 1000; st = t.update(now, rpm, 0.0) }
            return st!!
        }
        assertEquals(1, feed(119, 750.0).step.n)
        assertEquals(2, feed(2, 750.0).step.n)
        val low = feed(10, 1800.0)
        assertEquals("ниже", low.off)
        assertEquals(60, low.leftSec)
        assertEquals(3, feed(61, 2500.0).step.n)
        assertEquals(CheckTest.Phase.DONE, feed(61, 760.0).phase)
    }

    @Test
    fun checkTestAbortsWhenCarMoves() {
        val t = CheckTest(0)
        t.update(1000, 750.0, 0.0)
        val st = t.update(2000, 750.0, 12.0)
        assertEquals(CheckTest.Phase.ABORTED, st.phase)
        assertNotNull(CheckTest.blocker(750.0, 0.0, 40.0))
        assertNull(CheckTest.blocker(750.0, 0.0, 88.0))
    }

    @Test
    fun differentCarsGetDifferentKeys() {
        val a = SimDrive.drive(CarProfile.LEAN_IDLE, 6.0)
        val b = SimDrive.drive(CarProfile.CAN_HEALTHY, 6.0)
        val ka = CarId.of(a.info)!!.key
        val kb = CarId.of(b.info)!!.key
        assertTrue(ka != kb, "$ka vs $kb")
        // The same car twice gets the same key.
        assertEquals(ka, CarId.of(SimDrive.drive(CarProfile.LEAN_IDLE, 6.0).info)!!.key)
    }

    @Test
    fun comparisonPutsTheWorstProblemFirst() {
        fun trip(i: Int, trim: Double, dips: Double, volts: Double) = TripSummary("t$i", java.time.LocalDateTime.of(2026, 10, i, 10, 0), 30.0, 500,
            emptyMap(), mapOf(Metric.IDLE_TRIM_B1 to trim, Metric.RPM_DIPS to dips, Metric.CHARGE_V to volts), emptyList(), emptyList())
        // Mixture fixed, dips came up: dips go first.
        val t = TripComparison.table(listOf(trip(1, 20.0, 0.0, 14.1), trip(2, 4.0, 6.0, 14.1)))
        assertEquals(Metric.RPM_DIPS, t.rows.first().metric, t.rows.joinToString { "${it.metric}=${it.problem}" })
        assertEquals(Metric.CHARGE_V, t.rows.last().metric)
    }

    @Test
    fun findingsOfEqualSeverityAreSortedByHowFarOut() {
        val s = SimDrive.drive(CarProfile.LEAN_IDLE, 20.0)
        val sum = TripAnalyzer.analyze("t", s.csv, s.info)!!
        val scores = sum.findings.filter { it.severity == sum.findings.first().severity }.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }
}
