package com.obdlogger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Trips are kept by their own start, not by the file date (a head unit's clock may say 2007). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val y2007 = 1_167_609_600_000L

    private fun trip(daysAgo: Int, mtime: Long): File {
        val name = "obd_" + stamp.format(Date(System.currentTimeMillis() - daysAgo * 86_400_000L))
        val dir = SessionFiles.dir(ctx)
        val csv = File(dir, "$name.csv").apply { writeText("time,t_s,rpm\n") ; setLastModified(mtime) }
        File(dir, "${name}_info.txt").apply { writeText("info"); setLastModified(mtime) }
        return csv
    }

    @Test
    fun keepsRecentTripsWithA2007FileDateAndDropsOldOnes() {
        SessionFiles.dir(ctx).listFiles()?.forEach { it.delete() }
        val recent = trip(1, y2007)
        // 25 more recent trips so that the old one is not among the newest kept.
        (2..26).forEach { trip(it, System.currentTimeMillis()) }
        val old = trip(200, System.currentTimeMillis())
        SessionFiles.cleanup(ctx, 90)
        assertTrue(recent.exists())
        assertTrue(SessionFiles.infoOf(recent).exists())
        assertFalse(old.exists())
        assertFalse(SessionFiles.infoOf(old).exists())
    }

    @Test
    fun newestTripsAreNeverDeleted() {
        SessionFiles.dir(ctx).listFiles()?.forEach { it.delete() }
        val old = trip(300, y2007)
        SessionFiles.cleanup(ctx, 30)
        assertTrue(old.exists())
    }

    @Test
    fun renamesAfterTheRealStart() {
        SessionFiles.dir(ctx).listFiles()?.forEach { it.delete() }
        val dir = SessionFiles.dir(ctx)
        val csv = File(dir, "obd_20070101_000000.csv").apply { writeText("x") }
        File(dir, "obd_20070101_000000_info.txt").writeText("i")
        val start = stamp.parse("20261011_200000")!!.time
        val renamed = SessionFiles.renameTo(csv, start)
        assertEquals("obd_20261011_200000.csv", renamed.name)
        assertTrue(File(dir, "obd_20261011_200000_info.txt").exists())
        assertFalse(csv.exists())
    }
}
