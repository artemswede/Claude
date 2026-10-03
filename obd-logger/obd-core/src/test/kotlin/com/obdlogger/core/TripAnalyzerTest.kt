package com.obdlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real drives of a Toyota Avensis 2.0 D-4 (1AZ-FSE), ISO 9141-2, 03.10.2026. */
class TripAnalyzerTest {
    private fun trip(name: String): TripSummary {
        val csv = javaClass.getResource("/trips/$name.csv")!!.readText()
        val info = javaClass.getResource("/trips/${name}_info.txt")?.readText()
        return assertNotNull(TripAnalyzer.analyze(name, csv, info))
    }

    @Test
    fun morningTripShowsLeanIdleAndDips() {
        val t = trip("obd_20261003_114606")
        val m = t.metrics
        assertEquals(612.5, m.getValue(Metric.IDLE_RPM), 15.0)
        assertTrue(m.getValue(Metric.RPM_DIPS) >= 5, "dips ${m[Metric.RPM_DIPS]}")
        assertTrue(m.getValue(Metric.IDLE_TRIM_B1) > 10, "idle trim ${m[Metric.IDLE_TRIM_B1]}")
        assertEquals(emptyList(), t.dtcs)
        val titles = t.findings.map { it.title }
        assertTrue("Провалы холостого хода" in titles, titles.toString())
        assertTrue(titles.any { it.startsWith("Бедная смесь") }, titles.toString())
    }

    @Test
    fun eveningTripLeanOnlyAtIdle() {
        val t = trip("obd_20261003_195129")
        val m = t.metrics
        assertTrue(m.getValue(Metric.IDLE_TRIM_B1) > 18, "idle trim ${m[Metric.IDLE_TRIM_B1]}")
        assertTrue(kotlin.math.abs(m.getValue(Metric.CRUISE_TRIM_B1)) < 8, "cruise trim ${m[Metric.CRUISE_TRIM_B1]}")
        assertEquals(0.0, m.getValue(Metric.RPM_DIPS))
        assertTrue(m.getValue(Metric.CRUISE_REAR_O2) > 0.5)
        assertEquals("Бедная смесь только на холостом", t.findings.first().title)
    }

    @Test
    fun comparisonAcrossTrips() {
        val trips = listOf("obd_20261003_111936", "obd_20261003_114606", "obd_20261003_160542", "obd_20261003_195129").map(::trip)
        val report = TripComparison.render(trips)
        println(report)
        assertTrue("ПОСЛЕДНЯЯ ПОЕЗДКА 03.10 19:51" in report)
        assertTrue("СРАВНЕНИЕ ПОЕЗДОК" in report && "ПРОГНОЗ" in report)
        assertEquals(-1.0, TripComparison.slope(listOf(3.0, 2.0, 1.0)), 1e-9)
    }
}

class SensorNamesTest {
    @Test
    fun russianLabels() {
        assertEquals("Обороты", SensorNames.label("rpm"))
        assertEquals("Долг. коррекция Б1", SensorNames.label("ltft_b1_pct"))
        assertEquals("Лямбда Б2 после кат.", SensorNames.label("o2_b2s2_v"))
        assertEquals("Наличие лямбда-зондов · байт 0", SensorNames.label("pid01_13_b00"))
        assertEquals("Toyota 21 01 · байт 2", SensorNames.label("m21_fn_01_b02"))
        assertEquals("об/мин", SensorNames.unit("rpm"))
        assertEquals("Обороты (rpm)", SensorNames.full("rpm"))
    }
}
