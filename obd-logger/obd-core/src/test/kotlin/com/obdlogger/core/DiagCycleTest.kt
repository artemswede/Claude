package com.obdlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagCycleTest {
    private val cols = listOf("rpm", "coolant_c", "battery_v", "trim_b1", "maf_gs", "throttle_pct", "speed_kmh", "marker")

    @Test
    fun `first step at idle is rpm stability`() {
        val s = DiagCycle.step(0, cols, LiveMode.IDLE)
        assertEquals("rpm", s.focus)
        assertEquals("стабильность оборотов", s.label)
        assertNull(s.crossRef)
    }

    @Test
    fun `cycles through available sensors at idle`() {
        val s1 = DiagCycle.step(0, cols, LiveMode.IDLE)
        val s2 = DiagCycle.step(DiagCycle.STEP_SEC, cols, LiveMode.IDLE)
        assertNotEquals(s1.focus, s2.focus)
    }

    @Test
    fun `skips unavailable sensors`() {
        val small = listOf("rpm", "battery_v")
        val s = DiagCycle.step(0, small, LiveMode.IDLE)
        assertEquals("rpm", s.focus)
        val s2 = DiagCycle.step(DiagCycle.STEP_SEC, small, LiveMode.IDLE)
        assertEquals("battery_v", s2.focus)
    }

    @Test
    fun `cross-reference step after all sensors`() {
        val available = listOf("rpm", "trim_b1")
        val sensorCount = 2
        val s = DiagCycle.step(sensorCount * DiagCycle.STEP_SEC, available, LiveMode.IDLE)
        assertNotNull(s.crossRef)
        assertTrue(s.label.contains("и"))
    }

    @Test
    fun `wraps around after all steps`() {
        val small = listOf("rpm", "battery_v")
        val s1 = DiagCycle.step(0, small, LiveMode.IDLE)
        var total = 1
        while (DiagCycle.step(total * DiagCycle.STEP_SEC, small, LiveMode.IDLE).focus != s1.focus || total < 2) total++
        val sWrap = DiagCycle.step(total * DiagCycle.STEP_SEC, small, LiveMode.IDLE)
        assertEquals(s1.focus, sWrap.focus)
    }

    @Test
    fun `excludes speed and marker`() {
        val steps = (0 until 20).map { DiagCycle.step(it * DiagCycle.STEP_SEC, cols, LiveMode.IDLE) }
        assertTrue(steps.none { it.focus == "speed_kmh" })
        assertTrue(steps.none { it.focus == "marker" })
    }

    @Test
    fun `empty columns returns fallback`() {
        val s = DiagCycle.step(0, emptyList(), LiveMode.IDLE)
        assertEquals("rpm", s.focus)
    }

    // --- Mode-aware tests ---

    @Test
    fun `drive mode shows generic label`() {
        val s = DiagCycle.step(0, cols, LiveMode.DRIVE)
        assertEquals("Записываю данные движения", s.label)
        assertNull(s.crossRef)
    }

    @Test
    fun `drive mode focuses on relevant sensors`() {
        val steps = (0 until 10).map { DiagCycle.step(it * DiagCycle.STEP_SEC, cols, LiveMode.DRIVE) }
        val sensorSteps = steps.filter { it.crossRef == null }
        assertTrue(sensorSteps.all { it.focus != "rpm" && it.focus != "throttle_pct" })
        assertTrue(sensorSteps.any { it.focus == "trim_b1" })
    }

    @Test
    fun `drive mode cross-refs show label`() {
        val driveCols = listOf("rpm", "battery_v", "trim_b1", "engine_load_pct", "maf_gs", "o2_b1s1_v", "o2_b1s2_v")
        val driveFocusCount = listOf("trim_b1", "trim_b2", "battery_v", "o2_b1s1_v", "o2_b1s2_v", "engine_load_pct", "maf_gs")
            .count { it in driveCols }
        val s = DiagCycle.step(driveFocusCount * DiagCycle.STEP_SEC, driveCols, LiveMode.DRIVE)
        assertNotNull(s.crossRef)
    }

    @Test
    fun `cold mode shows warmup label`() {
        val s = DiagCycle.step(0, cols, LiveMode.COLD)
        assertEquals("Прогрев — записываю данные", s.label)
        assertEquals("coolant_c", s.focus)
    }

    @Test
    fun `cold mode focuses on coolant if available`() {
        val s = DiagCycle.step(100, listOf("rpm", "coolant_c"), LiveMode.COLD)
        assertEquals("coolant_c", s.focus)
    }

    @Test
    fun `off mode returns empty label`() {
        val s = DiagCycle.step(0, cols, LiveMode.OFF)
        assertEquals("", s.label)
    }

    @Test
    fun `idle labels are descriptive not just sensor names`() {
        val s = DiagCycle.step(0, cols, LiveMode.IDLE)
        assertNotEquals("Обороты", s.label)
        assertTrue(s.label.contains("стабильность") || s.label.contains("оборот"))
    }

    @Test
    fun `default mode parameter is IDLE for backward compat`() {
        val s = DiagCycle.step(0, cols)
        assertEquals("rpm", s.focus)
        assertEquals("стабильность оборотов", s.label)
    }
}
