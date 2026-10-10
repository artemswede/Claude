package com.obdlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Проверки»: steps per kind, start conditions, results from the CSV and the verdict. */
class ChecksTest {
    private fun csv(rows: List<Triple<String, Map<String, Double>, Int>>): String {
        val cols = listOf("rpm", "battery_v", "coolant_c", "ltft_b1_pct", "stft_b1_pct")
        val t0 = java.time.LocalDateTime.of(2026, 10, 9, 10, 0)
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        return "time," + cols.joinToString(",") + ",marker\n" + rows.joinToString("\n") { (mk, v, sec) ->
            t0.plusSeconds(sec.toLong()).format(f) + "," + cols.joinToString(",") { c -> v[c]?.toString().orEmpty() } + "," + mk
        }
    }

    @Test
    fun chargeCheckRunsTwoStepsAndReadsVoltages() {
        val t = CheckTest(0, CheckKind.CHARGE)
        var st = t.update(0, 760.0, 0.0)
        for (sec in 1..125) st = t.update(sec * 1000L, 760.0, 0.0)
        assertEquals(CheckTest.Phase.DONE, st.phase)
        assertTrue(st.progress.all { it >= 1.0 })
        val rows = (0 until 60).map { Triple("TEST1 зарядка", mapOf("rpm" to 760.0, "battery_v" to 14.1), it) } +
            (60 until 120).map { Triple("TEST2 зарядка", mapOf("rpm" to 760.0, "battery_v" to 13.0), it) }
        val r = assertNotNull(CheckResult.of("c", csv(rows)))
        assertEquals(CheckKind.CHARGE, r.kind)
        assertEquals(14.1, r.voltIdle!!, 1e-9)
        assertEquals(13.0, r.voltLoad!!, 1e-9)
        val (text, good, _) = CheckVerdict.of(r, null)
        assertTrue(!good && text.contains("не держит нагрузку"), text)
    }

    @Test
    fun warmupEndsAtTargetCoolantAndMayBeDriven() {
        assertNotNull(CheckTest.blocker(800.0, 0.0, 85.0, CheckKind.WARMUP))
        assertNull(CheckTest.blocker(800.0, 40.0, 30.0, CheckKind.WARMUP))
        val t = CheckTest(0, CheckKind.WARMUP)
        var st = t.update(0, 900.0, 0.0, 30.0)
        for (sec in 1..400) {
            st = t.update(sec * 1000L, 900.0, 50.0, 30.0 + sec * 0.15)
            if (st.phase != CheckTest.Phase.RUNNING) break
        }
        assertEquals(CheckTest.Phase.DONE, st.phase)
        val rows = (0..600 step 5).map { Triple("TEST1 прогрев", mapOf("rpm" to 900.0, "coolant_c" to 30.0 + it * 0.1), it) }
        val r = assertNotNull(CheckResult.of("w", csv(rows)))
        assertEquals(CheckKind.WARMUP, r.kind)
        assertEquals(500.0 / 60, r.warmMin!!, 0.1)
        assertTrue(CheckVerdict.of(r, null).second)
    }

    @Test
    fun oldCheckLogsAreTheMixtureCheck() {
        val rows = (0 until 30).map { Triple(if (it < 20) "TEST1" else "TEST2", mapOf("rpm" to if (it < 20) 760.0 else 2500.0, "ltft_b1_pct" to 15.0, "stft_b1_pct" to 3.0), it) }
        val r = assertNotNull(CheckResult.of("m", csv(rows)))
        assertEquals(CheckKind.MIXTURE, r.kind)
        assertEquals(18.0, r.idleTrim!!, 1e-9)
        assertEquals(CheckKind.CHARGE, CheckKind.of("зарядка"))
        assertEquals(CheckKind.MIXTURE, CheckKind.forFinding("rich_idle"))
        assertNull(CheckKind.forFinding("dtc"))
    }
}
