package com.obdlogger.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exact relations for the AI, on the owner's real 46-minute trip. */
class RelationsTest {
    private fun detail(name: String): TripDetail {
        val dir = File(javaClass.getResource("/trips")!!.toURI())
        val csv = File(dir, "$name.csv").readText()
        val info = File(dir, "${name}_info.txt").takeIf { it.exists() }?.readText()
        val s = TripAnalyzer.analyze(name, csv, info)!!
        // As the app builds it (TripCache.detail).
        return TripDetail(TripAnalyzer.table(csv)!!, s)
    }

    @Test
    fun pearsonIsExact() {
        val x = (0 until 50).map { it.toDouble() }
        val (r, n) = Relations.pearson(x, x.map { 3 * it + 1 }, x.indices.toList())!!
        assertEquals(1.0, r, 1e-9); assertEquals(50, n)
        val (r2, _) = Relations.pearson(x, x.map { -it }, x.indices.toList())!!
        assertEquals(-1.0, r2, 1e-9)
    }

    @Test
    fun realTripGivesRelationsEventsAndRawRows() {
        val d = detail("obd_20261003_195129")
        val corr = Relations.correlations(d.table)
        assertTrue(corr.isNotEmpty())
        // A healthy coupling is there and marked as expected.
        assertTrue(corr.any { it.expected && setOf(it.a, it.b) == setOf("rpm", "maf_gs") }, corr.take(10).joinToString { "${it.a}-${it.b} ${it.r}" })
        val text = Relations.render(d)
        assertTrue(text.contains("РАСЧЁТЫ БОРТАЧА"))
        assertTrue(text.contains("коррекция Б1 выше +15 %") || text.contains("провал оборотов"), text)
        val raw = Relations.rawTable(d)
        val lines = raw.lines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 839, "${lines.size}")
        assertTrue(lines[1].startsWith("t_s,режим,trim_b1"), lines[1])
        // ≈ 45–60K tokens for 46 minutes: fits many times into 1M.
        assertTrue(ChatMemory.tokens(raw) in 20_000..70_000, "${ChatMemory.tokens(raw)}")
    }

    @Test
    fun overlayTagsAreParsed() {
        val r = ChatCharts.parse("[график: наложение trim_b1 maf_gs rpm последняя]\n[график: наложение o2_b1s2_v trim_b1 03.10 19:51]")
        assertEquals(listOf(
            ChartRequest.Overlay(listOf("trim_b1", "maf_gs", "rpm"), "последняя"),
            ChartRequest.Overlay(listOf("o2_b1s2_v", "trim_b1"), "03.10 19:51"),
        ), r)
        assertEquals(listOf(ChartRequest.Trend("trim_b1", DriveMode.WARM_IDLE)), ChatCharts.parse("[график: тренд trim_b1 WARM_IDLE]"))
    }
}
