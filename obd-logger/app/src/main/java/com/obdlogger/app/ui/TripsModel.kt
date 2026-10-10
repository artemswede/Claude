package com.obdlogger.app.ui

import android.content.Context
import com.obdlogger.app.SessionFiles
import com.obdlogger.core.CarId
import com.obdlogger.core.CheckResult
import com.obdlogger.core.DriveMode
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.TripComparison
import com.obdlogger.core.TripDetail
import com.obdlogger.core.TripProfile
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
class TripsModel(
    val items: List<TripItem>,
    val comparison: TripComparison.Table,
    val checks: List<TripItem>,
    /** Cars seen in the recordings, the newest first; the journal shows one at a time. */
    val cars: List<CarId> = emptyList(),
    val car: CarId? = null,
) {
    val trips get() = items.filter { !it.isCheck }

    /** This car's trip fingerprints for «как обычно», oldest first, after the profile's reset ([since]). Heavy the first time. */
    fun profiles(since: java.time.LocalDateTime?): List<TripProfile> =
        trips.filter { since == null || (it.summary.start ?: return@filter false) >= since }
            .mapNotNull { TripCache.profile(it) }.sortedBy { it.start }

    companion object {
        /** Heavy: reads CSV files (cached by name and size). */
        fun build(ctx: Context, carKey: String? = null): TripsModel {
            val files = SessionFiles.tripCsvs(ctx) + SessionFiles.checkCsvs(ctx)
            return from(files.mapNotNull { TripCache.item(it) }, carKey)
        }

        /** [carKey] null = the car of the newest recording. */
        fun from(items: List<TripItem>, carKey: String? = null): TripsModel {
            val all = items.filter { it.summary.rows >= 10 }.sortedByDescending { it.summary.start }
            val cars = all.mapNotNull { it.summary.car }.distinctBy { it.key }
            val car = cars.firstOrNull { it.key == carKey } ?: all.firstOrNull()?.summary?.car
            val sorted = all.filter { it.summary.car?.key == car?.key }
            val trips = sorted.filter { !it.isCheck }.map { it.summary }
            return TripsModel(sorted, TripComparison.table(trips), sorted.filter { it.isCheck }, cars, car)
        }
    }
}

/** Trip analyses are cached in memory: the journal is opened often, files rarely change. */
object TripCache {
    private val cache = HashMap<String, TripItem>()

    @Synchronized
    fun item(csv: File): TripItem? {
        val key = "${csv.absolutePath}:${csv.length()}:${SessionFiles.infoOf(csv).length()}"
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

    private val profiles = HashMap<String, TripProfile>()

    /** The trip's fingerprint: from `_profile.txt` next to it, or computed once and written there. */
    fun profile(item: TripItem): TripProfile? {
        val key = "${item.csv.absolutePath}:${item.csv.length()}"
        synchronized(profiles) { profiles[key]?.let { return it } }
        val f = SessionFiles.profileOf(item.csv)
        val cached = if (f.exists() && f.lastModified() >= item.csv.lastModified()) runCatching { TripProfile.decode(f.readText()) }.getOrNull() else null
        val p = cached ?: detail(item)?.let { TripProfile.of(it) }?.also { runCatching { f.writeText(it.encode()) } } ?: return null
        synchronized(profiles) { profiles[key] = p }
        return p
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
