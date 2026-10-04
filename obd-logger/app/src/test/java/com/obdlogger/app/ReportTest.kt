package com.obdlogger.app

import com.obdlogger.app.ui.ReportData
import com.obdlogger.app.ui.Reports
import com.obdlogger.core.Hypotheses
import com.obdlogger.core.TripComparison
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds the printable trip report and the mechanic's plan from the real recorded
 * trips (build/screens/report/, HTML); CI prints them to PDF for review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportTest {
    @Test
    fun tripReportAndPlan() {
        val items = Samples.realItems
        val item = items.last { it.summary.top != null }
        val sums = items.map { it.summary }.filter { (it.start ?: item.summary.start!!) <= item.summary.start!! }
        val h = Hypotheses.of(item.summary.top!!, sums)
        val data = ReportData(item, Samples.detail(item), TripComparison.table(sums, 5), emptyList(), h)
        val trip = Reports.tripHtml(data, "Toyota Avensis 2005 · 2.0 D-4", "test")
        val plan = Reports.planHtml(h, "Toyota Avensis 2005 · 2.0 D-4", "test", TripComparison.table(sums, 5))
        val dir = File(System.getProperty("screens.dir") ?: "build/screens", "report").apply { mkdirs() }
        File(dir, "trip_report.html").writeText(trip)
        File(dir, "plan.html").writeText(plan)
        assertTrue("charts", trip.contains("<svg"))
        assertTrue("comparison", trip.contains("Сравнение с прошлыми поездками"))
        assertTrue("plan comparison", plan.contains("<svg"))
    }
}
