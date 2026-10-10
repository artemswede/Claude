package com.obdlogger.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The assistant's actions: live charts, check buttons, watched conditions — parsed safely and counted on a real trip. */
class ChatActionsTest {
    @Test
    fun tagsAreParsedAndStripped() {
        val answer = "Похоже на подсос.\n[график: наложение trim_b1 maf_gs сейчас]\n[проверка: смесь]\n[наблюдать: trim_b1 > 15 когда WARM_IDLE]\n[наблюдать: rm -rf / > 1]"
        assertEquals(listOf<ChartRequest>(ChartRequest.Live(listOf("trim_b1", "maf_gs"))), ChatCharts.parse(answer))
        assertEquals(listOf(CheckKind.MIXTURE), ChatTests.parse(answer))
        val w = WatchRule.parse(answer)
        assertEquals(listOf(WatchRule("trim_b1", ">", 15.0, LiveMode.IDLE)), w)
        assertEquals("Похоже на подсос.", ChatTests.strip(ChatCharts.strip(answer)))
        assertEquals(w.single(), WatchRule.decode(w.single().encode()))
    }

    @Test
    fun onlyTheAllowedShapeIsAccepted() {
        assertNotNull(WatchRule.of("coolant_c >= 100"))
        assertNotNull(WatchRule.of("battery_v < 13,2 на холостом"))
        assertNull(WatchRule.of("coolant_c > 100 когда луна"))
        assertNull(WatchRule.of("System.exit(0) > 1"))
        assertNull(WatchRule.of("coolant_c == 100"))
        assertNull(WatchRule.decode("x|!|1||3"))
    }

    @Test
    fun watchCountsOnARealTrip() {
        val dir = File(javaClass.getResource("/trips")!!.toURI())
        val csv = File(dir, "obd_20261003_195129.csv").readText()
        val t = TripAnalyzer.table(csv)!!
        val store = SeriesStore()
        val cols = t.header.filter { it != "time" && it != "t_s" && it != "marker" }
        val idx = cols.map { t.header.indexOf(it) }
        store.reset(cols)
        val tally = WatchTally(WatchRule("trim_b1", ">", 15.0, LiveMode.IDLE))
        t.rows.forEachIndexed { i, r ->
            store.add(t.ms[i], idx.map { r.getOrNull(it)?.ifEmpty { null } })
            val values = listOf("trim_b1", "rpm", "maf_gs", "coolant_c").associateWith { store.last(it) }
            tally.add(t.ms[i], values, LiveMode.of(store.last("rpm"), store.last("speed_kmh"), store.last("coolant_c")))
        }
        assertTrue(tally.secMode > 60, "${tally.secMode}")
        assertTrue(tally.events > 0)
        val text = tally.report("03.10 19:51", 46.0)
        assertTrue(text.contains("выполнялось") && text.contains("В эти моменты"), text)
        val live = LiveTable.render(store, 2)
        assertTrue(live.lines().size in 50..70 && live.contains("trim_b1"), live.take(300))
    }
}
