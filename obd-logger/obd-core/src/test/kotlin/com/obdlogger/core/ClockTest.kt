package com.obdlogger.core

import java.io.StringWriter
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A head unit without a clock battery: it starts in 2007 and later gets the real time. */
class ClockTest {
    private val y2007 = LocalDateTime.of(2007, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val y2026 = LocalDateTime.of(2026, 10, 11, 20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun loggerKeepsTimeMonotonicAndCountsTheStartBack() {
        var mono = 0L
        var wall = y2007
        val logger = DataLogger(Pids.pollItems(setOf(0x0C), singleResponse = false), StringWriter(),
            clock = { wall + mono }, zone = ZoneOffset.UTC, mono = { mono })
        val jumps = ArrayList<Long>()
        logger.onClockJump = { jumps += it }
        val elm = object : ElmIo { override fun command(cmd: String, timeoutMs: Long) = if (cmd.startsWith("010C")) "41 0C 1A F8" else "?" }
        repeat(10) { mono += 30_000; logger.cycle(elm, null) }
        // 5 minutes in, the network sets the clock.
        wall = y2026 - mono
        repeat(10) { mono += 30_000; logger.cycle(elm, null) }
        assertEquals(1, jumps.size)
        assertEquals(10 * 60_000L, logger.elapsedMs())
        // Started 10 minutes before the last (correct) reading.
        assertEquals(y2026 + 10 * 30_000L - 20 * 30_000L, logger.startMs)
    }

    @Test
    fun oldTripWithAClockJumpIsRepaired() {
        // Old format: t_s also jumped with the wall clock.
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        val t0 = LocalDateTime.of(2007, 1, 1, 0, 0)
        val t1 = LocalDateTime.of(2026, 10, 11, 20, 0)
        val rows = (0 until 10).map { t0.plusSeconds(it * 2L) } + (0 until 10).map { t1.plusSeconds(it * 2L) }
        val csv = "time,t_s,rpm\n" + rows.mapIndexed { i, t -> "${t.format(f)},${java.time.Duration.between(t0, t).seconds},${700 + i}" }.joinToString("\n")
        val table = TripAnalyzer.table(csv)!!
        // 20 rows 2 s apart: under a minute, not 19 years.
        assertTrue(table.ms.last() in 36_000L..40_000L, "${table.ms.last()}")
        assertEquals(t1.plusSeconds(18).minusNanos(table.ms.last() * 1_000_000L), table.start)
        assertEquals(t1.plusSeconds(18), table.times.last())
        val s = TripAnalyzer.analyze("obd_20070101_000000", csv)!!
        assertTrue(s.durationMin < 1.0, "${s.durationMin}")
        assertEquals(2026, s.start!!.year)
    }

    @Test
    fun newTripUsesMonotonicSeconds() {
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        val t0 = LocalDateTime.of(2007, 1, 1, 0, 0)
        val t1 = LocalDateTime.of(2026, 10, 11, 20, 0)
        val rows = (0 until 20).map { i -> (if (i < 10) t0.plusSeconds(i * 3L) else t1.plusSeconds((i - 10) * 3L)) to i * 3 }
        val csv = "time,t_s,rpm\n" + rows.joinToString("\n") { (t, s) -> "${t.format(f)},$s,700" }
        val table = TripAnalyzer.table(csv)!!
        assertEquals(57_000L, table.ms.last())
        assertEquals(t1.plusSeconds(27).minusSeconds(57), table.start)
    }
}
