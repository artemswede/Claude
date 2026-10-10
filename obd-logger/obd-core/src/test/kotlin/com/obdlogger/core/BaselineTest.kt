package com.obdlogger.core

import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** «Как обычно у вашей машины»: the car's own normal and the changes against it. */
class BaselineTest {
    private val t0 = LocalDateTime.of(2026, 9, 1, 8, 0)

    /** A synthetic trip: [values] per (code, mode) as the trip's median, a narrow p10…p90 around it. */
    private fun trip(i: Int, values: Map<Pair<String, DriveMode>, Double>, air: Double? = 20.0): TripProfile {
        val stats = LinkedHashMap<String, MutableMap<DriveMode, TripProfile.Stat>>()
        for ((k, v) in values) stats.getOrPut(k.first) { LinkedHashMap() }[k.second] = TripProfile.Stat(v, v - 0.5, v + 0.5, 200)
        return TripProfile("t$i", "%02d.09".format(i + 1), t0.plusDays(i.toLong()), stats, air)
    }

    private fun trips(coolant: List<Double>, air: (Int) -> Double = { 20.0 }) = coolant.mapIndexed { i, c ->
        // Battery steady with a tiny wobble: must never alarm.
        trip(i, mapOf(("coolant_c" to DriveMode.CRUISE) to c, ("battery_v" to DriveMode.CRUISE) to 14.0 + (i % 2) * 0.02), air(i))
    }

    @Test
    fun coolantShiftIsReportedAgainstThisCarsNormal() {
        val ps = trips(listOf(97.3, 97.6, 97.4, 97.8, 97.5, 97.2, 100.4, 100.5, 100.6))
        val d = Baseline.drifts(ps)
        assertEquals(1, d.size, d.joinToString { it.text })
        val c = d.single()
        assertEquals("coolant_c", c.code)
        assertEquals(Severity.WARN, c.level)
        assertTrue(c.text.contains("обычно у вас 97–98"), c.text)
        assertTrue(c.text.contains("100–101"), c.text)
        assertTrue(c.text.contains("Началось с 07.09"), c.text)
        assertTrue(c.causes.contains("термостат"))
        val r = Baseline.render(ps)
        assertTrue(r.contains("ЧТО ИЗМЕНИЛОСЬ") && r.contains("coolant_c [CRUISE]"), r)
    }

    @Test
    fun steadyCarIsAsUsual() {
        val ps = trips(listOf(97.3, 97.6, 97.4, 97.8, 97.5, 97.2, 97.6, 97.4, 97.7))
        assertTrue(Baseline.drifts(ps).isEmpty())
        assertTrue(Baseline.render(ps).contains("всё как обычно"))
    }

    @Test
    fun fewTripsMeansLearning() {
        val ps = trips(listOf(97.0, 97.5, 101.0))
        assertEquals(3 to 8, Baseline.learning(ps))
        assertTrue(Baseline.drifts(ps).isEmpty())
        assertTrue(Baseline.render(ps).contains("учится"))
    }

    @Test
    fun steadyRiseGetsAForecast() {
        val ps = trips(listOf(97.0, 97.5, 98.0, 98.5, 99.0, 99.5, 100.0, 100.5, 101.0))
        val c = Baseline.drifts(ps).first { it.code == "coolant_c" }
        assertTrue(c.slope > 0.4, "${c.slope}")
        assertNotNull(c.forecast, c.text)
        assertTrue(c.text.contains("105") && c.text.contains("через"), c.text)
    }

    @Test
    fun hotWeatherSoftensATemperatureRise() {
        val ps = trips(listOf(97.3, 97.6, 97.4, 97.8, 97.5, 97.2, 100.4, 100.5, 100.6)) { if (it >= 6) 36.0 else 18.0 }
        val c = Baseline.drifts(ps).single()
        assertTrue(c.hot)
        assertEquals(Severity.WATCH, c.level)
        assertTrue(c.text.contains("жара"), c.text)
    }

    @Test
    fun profileOfARealTripRoundTrips() {
        val dir = File(javaClass.getResource("/trips")!!.toURI())
        val name = "obd_20261003_195129"
        val csv = File(dir, "$name.csv").readText()
        val info = File(dir, "${name}_info.txt").takeIf { it.exists() }?.readText()
        val p = TripProfile.of(TripDetail(TripAnalyzer.table(csv)!!, TripAnalyzer.analyze(name, csv, info)!!))
        val rpm = assertNotNull(p.of("rpm", DriveMode.WARM_IDLE))
        assertTrue(rpm.median in 500.0..1000.0, "${rpm.median}")
        assertTrue(p.of("coolant_c", DriveMode.CRUISE) != null)
        val back = assertNotNull(TripProfile.decode(p.encode()))
        assertEquals(p.name, back.name)
        assertEquals(p.start, back.start)
        assertEquals(rpm.median, back.of("rpm", DriveMode.WARM_IDLE)!!.median, 1e-9)
        assertEquals(p.stats.size, back.stats.size)
    }
}
