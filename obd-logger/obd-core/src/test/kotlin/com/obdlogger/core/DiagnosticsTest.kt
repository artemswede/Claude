package com.obdlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Codes screen: freeze frame, reset, descriptions and the reasons behind a code. */
class DiagnosticsTest {
    private fun session(profile: CarProfile): ObdSession {
        var now = 1_000_000L
        val s = ObdSession(SimulatedElm({ now += 50; now }, profile = profile), resetDelayMs = 0)
        s.initAdapter()
        assertTrue(s.connectEcu())
        return s
    }

    @Test
    fun readsTheFreezeFrameAndClearsCodes() {
        for (profile in listOf(CarProfile.DEMO, CarProfile.CAN_DTC)) {
            val s = session(profile)
            assertEquals(profile.dtcs, s.readDtcs().stored)
            val ff = assertNotNull(s.readFreezeFrame())
            assertEquals(profile.dtcs.first(), ff.cause)
            assertNotNull(ff.of("rpm"), ff.values.joinToString { it.code })
            assertTrue(s.clearDtcs())
            assertEquals(emptyList(), s.readDtcs().stored)
            assertEquals(null, s.readFreezeFrame())
        }
    }

    @Test
    fun describesCodes() {
        assertEquals("Лямбда-зонд после катализатора (Б2): цепь", DtcCatalog.describe("P0156"))
        assertTrue(DtcCatalog.describe("P1349").contains("производителя"))
        assertTrue(DtcCatalog.describe("U0100").contains("связь"))
    }

    @Test
    fun deadRearSensorsOnBothBanksPointToACommonCause() {
        val store = SeriesStore().apply {
            reset(listOf("rpm", "o2_b1s2_v", "o2_b2s2_v"))
            for (i in 0 until 60) add(i * 1000L, listOf("750", "0.02", if (i % 2 == 0) "0.03" else "0.04"))
        }
        val codes = listOf("P0136", "P0156")
        val b1 = DtcExplain.explain("P0136", null, store, codes)
        assertTrue(b1.any { it.contains("сигнала нет") }, b1.toString())
        assertTrue(b1.any { it.contains("Оба задних") }, b1.toString())
        val report = DtcReport.render("Перед сбросом", "Avensis", DtcSnapshot(true, 2, codes, emptyList(), null), null, store)
        assertTrue(report.contains("P0156 — Лямбда-зонд после катализатора (Б2): цепь"), report)
    }

    @Test
    fun leanCodeTellsIdleFromLoad() {
        val store = SeriesStore().apply {
            reset(listOf("rpm", "stft_b1_pct", "ltft_b1_pct"))
            for (i in 0 until 40) add(i * 1000L, listOf("750", "8", "12"))
            for (i in 40 until 80) add(i * 1000L, listOf("2500", "0", "3"))
        }
        val facts = DtcExplain.explain("P0171", null, store, listOf("P0171"))
        assertTrue(facts.any { it.contains("подсоса") }, facts.toString())
    }
}
