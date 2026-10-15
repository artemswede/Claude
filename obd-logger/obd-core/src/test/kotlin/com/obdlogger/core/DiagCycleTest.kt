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
    fun `first step is rpm`() {
        val s = DiagCycle.step(0, cols)
        assertEquals("rpm", s.focus)
        assertTrue(s.label.contains("Обороты"))
        assertNull(s.crossRef)
    }

    @Test
    fun `cycles through available sensors`() {
        val s1 = DiagCycle.step(0, cols)
        val s2 = DiagCycle.step(DiagCycle.STEP_SEC, cols)
        assertNotEquals(s1.focus, s2.focus)
    }

    @Test
    fun `skips unavailable sensors`() {
        val small = listOf("rpm", "battery_v")
        val s = DiagCycle.step(0, small)
        assertEquals("rpm", s.focus)
        val s2 = DiagCycle.step(DiagCycle.STEP_SEC, small)
        assertEquals("battery_v", s2.focus)
    }

    @Test
    fun `cross-reference step after all sensors`() {
        val available = listOf("rpm", "trim_b1")
        val sensorCount = 2
        val s = DiagCycle.step(sensorCount * DiagCycle.STEP_SEC, available)
        assertNotNull(s.crossRef)
        assertTrue(s.label.contains("и"))
    }

    @Test
    fun `wraps around after all steps`() {
        val small = listOf("rpm", "battery_v")
        val s1 = DiagCycle.step(0, small)
        // Find total steps by cycling until we hit s1.focus again
        var total = 1
        while (DiagCycle.step(total * DiagCycle.STEP_SEC, small).focus != s1.focus || total < 2) total++
        val sWrap = DiagCycle.step(total * DiagCycle.STEP_SEC, small)
        assertEquals(s1.focus, sWrap.focus)
    }

    @Test
    fun `excludes speed and marker`() {
        val steps = (0 until 20).map { DiagCycle.step(it * DiagCycle.STEP_SEC, cols) }
        assertTrue(steps.none { it.focus == "speed_kmh" })
        assertTrue(steps.none { it.focus == "marker" })
    }

    @Test
    fun `empty columns returns fallback`() {
        val s = DiagCycle.step(0, emptyList())
        assertEquals("rpm", s.focus)
    }
}
