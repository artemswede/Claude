package com.obdlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesStoreTest {
    @Test
    fun statsAndInstabilityRanking() {
        val store = SeriesStore(capacity = 1000)
        store.reset(listOf("coolant_c", "stft_b1_pct", "fuel_system", "battery_v"))
        for (i in 0 until 200) {
            val coolant = 20.0 + i * 0.3 // smooth warm-up
            val stft = if (i % 2 == 0) 8.0 else -8.0 // erratic, mean 0
            store.add(i * 1000L, listOf(coolant.toString(), stft.toString(), "CL", if (i % 5 == 0) "14.1" else null))
        }
        val stats = store.stats().associateBy { it.name }
        assertEquals(setOf("coolant_c", "stft_b1_pct", "battery_v"), stats.keys) // text column skipped
        val coolant = stats.getValue("coolant_c")
        assertEquals(20.0, coolant.min)
        assertEquals(79.7, coolant.max, 1e-9)
        assertEquals(49.85, coolant.median, 1e-9)
        assertTrue(coolant.jitter!! < 1)
        val stft = stats.getValue("stft_b1_pct")
        assertNull(stft.cv) // mean ~0: CV meaningless
        assertTrue(stft.jitter!! > 90)
        assertEquals(40, stats.getValue("battery_v").count) // gaps skipped
        assertEquals(0.0, stats.getValue("battery_v").jitter)

        val (t, v) = store.series("coolant_c", fromMs = 190_000)
        assertEquals(10, t.size)
        assertEquals(20.0 + 199 * 0.3, v.last(), 1e-9)
        assertEquals(200, store.histogram("coolant_c", 10).sum())
    }

    @Test
    fun keepsOnlyCapacityRows() {
        val store = SeriesStore(capacity = 10)
        store.reset(listOf("rpm"))
        repeat(25) { store.add(it.toLong(), listOf("$it")) }
        assertEquals(10, store.size())
        assertEquals(15.0, store.values("rpm").first())
    }
}

class EngineRestartTest {
    @Test
    fun silentEcuEndsCycleEarly() {
        val items = Pids.pollItems((0x04..0x11).toSet(), singleResponse = false)
        val elm = object : ElmIo {
            var ecuRequests = 0
            override fun command(cmd: String, timeoutMs: Long): String {
                if (cmd.startsWith("01")) ecuRequests++
                return if (cmd == "ATRV") "11.7V" else "NO DATA"
            }
        }
        val r = DataLogger(items, java.io.StringWriter()).cycle(elm, null)
        assertTrue(!r.wroteRow && !r.adapterReset)
        assertEquals(DataLogger.SILENT_REQUESTS_TO_GIVE_UP, elm.ecuRequests)
    }

    @Test
    fun adapterRebootIsDetected() {
        val items = Pids.pollItems(setOf(0x0C, 0x0D), singleResponse = false)
        val elm = object : ElmIo {
            override fun command(cmd: String, timeoutMs: Long) =
                if (cmd == "010C") "\r\rELM327 v1.5\r\r" else "41 0D 10"
        }
        val r = DataLogger(items, java.io.StringWriter()).cycle(elm, null)
        assertTrue(r.adapterReset && !r.wroteRow)
    }
}
