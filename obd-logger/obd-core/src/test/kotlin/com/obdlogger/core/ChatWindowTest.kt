package com.obdlogger.core

import java.io.File
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Charts in answers show the minutes the answer is about, with the moment marked. */
class ChatWindowTest {
    private fun detail(): TripDetail {
        val dir = File(javaClass.getResource("/trips")!!.toURI())
        val name = "obd_20261003_195129"
        val csv = File(dir, "$name.csv").readText()
        return TripDetail(TripAnalyzer.table(csv)!!, TripAnalyzer.analyze(name, csv)!!)
    }

    @Test
    fun windowsAndMarksAreParsed() {
        val r = ChatCharts.parse("[график: наложение rpm maf_gs trim_b1 последняя 20:02–20:12 отметка 20:05:12 провал отметка 20:07 коррекция]").single()
        r as ChartRequest.Overlay
        assertEquals(listOf("rpm", "maf_gs", "trim_b1"), r.sensors)
        assertEquals("последняя", r.trip)
        assertEquals(ChartRequest.Window(LocalTime.of(20, 2), LocalTime.of(20, 12)), r.window)
        assertEquals(listOf(ChartRequest.Mark(LocalTime.of(20, 5, 12), "провал"), ChartRequest.Mark(LocalTime.of(20, 7), "коррекция")), r.marks)

        val last = ChatCharts.parse("[график: поездка rpm 03.10 19:51 последние 10 мин]").single() as ChartRequest.Trip
        assertEquals("03.10 19:51", last.trip)
        assertEquals(10, last.window?.lastMin)

        val from = ChatCharts.parse("[график: наложение rpm trim_b1 последняя с 20:05 10 мин]").single() as ChartRequest.Overlay
        assertEquals(ChartRequest.Window(LocalTime.of(20, 5), LocalTime.of(20, 15)), from.window)

        val live = ChatCharts.parse("[график: наложение rpm trim_b1 сейчас 10 мин]").single() as ChartRequest.Live
        assertEquals(10, live.minutes)

        val plain = ChatCharts.parse("[график: наложение rpm trim_b1 последняя]").single() as ChartRequest.Overlay
        assertNull(plain.window)
        assertTrue(plain.marks.isEmpty())
    }

    @Test
    fun windowIsResolvedOnARealTrip() {
        val d = detail()
        val start = d.table.times.first()!!
        val r = assertNotNull(ChatCharts.range(d, ChartRequest.Window(start.toLocalTime().plusMinutes(5), start.toLocalTime().plusMinutes(15))))
        assertTrue(kotlin.math.abs(r.first - 300_000L) <= 2_000L, "${r.first}")
        assertEquals(600_000L, r.last - r.first)
        val tail = assertNotNull(ChatCharts.range(d, ChartRequest.Window(lastMin = 10)))
        assertEquals(d.table.ms.last(), tail.last)
        // A mark alone: five minutes around it.
        val m = assertNotNull(ChatCharts.range(d, null, listOf(ChartRequest.Mark(start.toLocalTime().plusMinutes(20), ""))))
        assertEquals(300_000L, m.last - m.first)
        // Outside the trip: the whole trip.
        assertNull(ChatCharts.range(d, ChartRequest.Window(LocalTime.of(3, 0), LocalTime.of(3, 1))))
    }

    @Test
    fun promptHasTheFormatAndKeyMoments() {
        val d = detail()
        val m = ChatPrompt.moments(d)
        assertTrue(m.startsWith("КЛЮЧЕВЫЕ МОМЕНТЫ"), m)
        assertTrue(Regex("\\d{2}:\\d{2}:\\d{2}").containsMatchIn(m), m)
        assertTrue(m.contains("максимум ОЖ"), m)
        val text = ChatPrompt.system("машина", listOf(d.summary), emptyList(), "", null, listOf(d), research = ChatPrompt.research(listOf(d), 1))
        assertTrue(text.startsWith("Ты — диагност-консультант"))
        assertTrue(text.contains("Коротко:") && text.contains("отметка"))
        assertTrue(text.contains("t_s=0 соответствует"), text.take(200))
        assertTrue(ChatMemory.tokens(ChatPrompt.ROLE) < 3000)
    }
}
