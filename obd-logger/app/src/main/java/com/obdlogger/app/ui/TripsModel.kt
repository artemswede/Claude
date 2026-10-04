package com.obdlogger.app.ui

import android.content.Context
import com.obdlogger.app.SessionFiles
import com.obdlogger.core.CheckResult
import com.obdlogger.core.DriveMode
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.TripComparison
import com.obdlogger.core.TripDetail
import com.obdlogger.core.TripSummary
import java.io.File

/** A saved recording in the journal: an ordinary trip or a check log. */
class TripItem(val csv: File, val summary: TripSummary, val check: CheckResult?) {
    val isCheck get() = check != null

    /** Minutes per mode, estimated from row counts (journal bars). */
    fun minutes(mode: DriveMode): Double {
        val total = summary.modeRows.values.sum().coerceAtLeast(1)
        return summary.durationMin * (summary.modeRows[mode] ?: 0) / total
    }

    fun files(): List<File> = listOf(csv, SessionFiles.infoOf(csv), SessionFiles.elmOf(csv)).filter { it.exists() && it.length() > 0 }
}

/** Journal + comparison, built off the main thread from the files on disk. */
class TripsModel(val items: List<TripItem>, val comparison: TripComparison.Table, val checks: List<TripItem>) {
    val trips get() = items.filter { !it.isCheck }

    companion object {
        /** Heavy: reads CSV files (cached by name and size). */
        fun build(ctx: Context): TripsModel {
            val files = SessionFiles.tripCsvs(ctx) + SessionFiles.checkCsvs(ctx)
            return from(files.mapNotNull { TripCache.item(it) })
        }

        fun from(items: List<TripItem>): TripsModel {
            val sorted = items.filter { it.summary.rows >= 10 }.sortedByDescending { it.summary.start }
            val trips = sorted.filter { !it.isCheck }.map { it.summary }
            return TripsModel(sorted, TripComparison.table(trips), sorted.filter { it.isCheck })
        }
    }
}

/** Trip analyses are cached in memory: the journal is opened often, files rarely change. */
object TripCache {
    private val cache = HashMap<String, TripItem>()

    @Synchronized
    fun item(csv: File): TripItem? {
        val key = "${csv.absolutePath}:${csv.length()}"
        cache[key]?.let { return it }
        val text = try {
            csv.readText()
        } catch (e: Exception) {
            return null
        }
        val info = SessionFiles.infoOf(csv).takeIf { it.exists() }?.readText()
        val s = TripAnalyzer.analyze(csv.nameWithoutExtension, text, info) ?: return null
        val check = if (csv.name.startsWith(SessionFiles.CHECK_PREFIX)) CheckResult.of(csv.nameWithoutExtension, text) else null
        return TripItem(csv, s, check).also { cache[key] = it }
    }

    /** Full detail for the trip screen. */
    fun detail(item: TripItem): TripDetail? {
        val t = try {
            TripAnalyzer.table(item.csv.readText())
        } catch (e: Exception) {
            null
        } ?: return null
        return TripDetail(t, item.summary)
    }
}
