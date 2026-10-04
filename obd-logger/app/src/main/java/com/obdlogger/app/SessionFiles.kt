package com.obdlogger.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.TripComparison
import com.obdlogger.core.TripSummary
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Files of one recording. They are written to app storage while recording
 * (fast, survives crashes) and copied to Downloads/OBD-Logger when the session ends.
 */
class SessionFiles(dir: File, baseName: String) {
    val csv = File(dir, "$baseName.csv")
    val info = File(dir, "${baseName}_info.txt")
    val elmLog = File(dir, "${baseName}_elm.log")

    fun existing() = listOf(csv, info, elmLog).filter { it.exists() && it.length() > 0 }

    /** Result of copying one file to Downloads; [uri] is shareable, null when sharing is not possible. */
    class Exported(val ok: Boolean, val uri: Uri?)

    companion object {
        const val DOWNLOAD_FOLDER = "OBD-Logger"
        const val CHECK_PREFIX = "check_"

        fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "sessions").apply { mkdirs() }

        fun create(ctx: Context) = SessionFiles(dir(ctx), "obd_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))

        /** Copies [file] to Downloads/OBD-Logger: MediaStore on Android 10+, the public folder before. */
        fun exportToDownloads(ctx: Context, file: File): Exported {
            val mime = when (file.extension) {
                "csv" -> "text/csv"
                else -> "text/plain"
            }
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportMediaStore(ctx, file, mime) else exportLegacy(ctx, file, mime)
        }

        private fun exportMediaStore(ctx: Context, file: File, mime: String): Exported {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_FOLDER")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return Exported(false, null)
            return try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: return Exported(false, null)
                Exported(true, uri)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                Exported(false, null)
            }
        }

        /** Android 5–9: needs WRITE_EXTERNAL_STORAGE; the media scanner gives a content:// URI for sharing. */
        @Suppress("DEPRECATION")
        private fun exportLegacy(ctx: Context, file: File, mime: String): Exported {
            return try {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_FOLDER)
                dir.mkdirs()
                val target = File(dir, file.name)
                file.copyTo(target, overwrite = true)
                val latch = CountDownLatch(1)
                var uri: Uri? = null
                MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mime)) { _, u ->
                    uri = u
                    latch.countDown()
                }
                latch.await(5, TimeUnit.SECONDS)
                Exported(true, uri)
            } catch (e: Exception) {
                Exported(false, null)
            }
        }

        /** Recorded trips, oldest first. */
        fun tripCsvs(ctx: Context): List<File> =
            dir(ctx).listFiles().orEmpty()
                .filter { it.name.startsWith("obd_") && it.name.endsWith(".csv") && it.length() > 0 }
                .sortedBy { it.name }

        /** Check logs (проверочный лог), oldest first. */
        fun checkCsvs(ctx: Context): List<File> =
            dir(ctx).listFiles().orEmpty()
                .filter { it.name.startsWith(CHECK_PREFIX) && it.name.endsWith(".csv") && it.length() > 0 }
                .sortedBy { it.name }

        fun infoOf(csv: File) = File(csv.parentFile, csv.name.removeSuffix(".csv") + "_info.txt")
        fun elmOf(csv: File) = File(csv.parentFile, csv.name.removeSuffix(".csv") + "_elm.log")

        /** New check-log files next to the trips. */
        fun createCheck(ctx: Context) = SessionFiles(dir(ctx), CHECK_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))

        fun analyze(csv: File): TripSummary? {
            val info = infoOf(csv)
            return try {
                TripAnalyzer.analyze(csv.nameWithoutExtension, csv.readText(), if (info.exists()) info.readText() else null)
            } catch (e: Exception) {
                null
            }
        }

        /** Fills [store] from a saved CSV (the record page after the app was restarted). */
        fun loadInto(csv: File, store: com.obdlogger.core.SeriesStore) {
            val t = try {
                TripAnalyzer.table(csv.readText())
            } catch (e: Exception) {
                null
            } ?: return
            val cols = t.header.filter { it != "time" && it != "t_s" && it != "marker" }
            val idx = cols.map { t.header.indexOf(it) }
            val first = t.start ?: return
            val base = first.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            store.reset(cols)
            t.rows.forEachIndexed { i, r -> store.add(base + t.ms[i], idx.map { r.getOrNull(it)?.ifEmpty { null } }) }
        }

        /** Deletes recordings older than [days] (0 = keep everything). Returns how many files were removed. */
        fun cleanup(ctx: Context, days: Int): Int {
            if (days <= 0) return 0
            val cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000
            return dir(ctx).listFiles().orEmpty().count { it.lastModified() < cutoff && it.delete() }
        }

        /** «46 поездок · 112 МБ · свободно 9.4 ГБ» */
        fun storageSummary(ctx: Context): String {
            val d = dir(ctx)
            val files = d.listFiles().orEmpty()
            val trips = files.count { it.name.startsWith("obd_") && it.name.endsWith(".csv") }
            val mb = files.sumOf { it.length() } / 1_000_000.0
            val free = d.usableSpace / 1_000_000_000.0
            return "$trips поездок · ${"%.1f".format(mb)} МБ · свободно ${"%.1f".format(free)} ГБ"
        }

        /** Analysis of the latest trip compared with up to [count] - 1 previous ones. */
        fun compareRecent(ctx: Context, count: Int = 6): String {
            val trips = tripCsvs(ctx).takeLast(count).mapNotNull(::analyze).filter { it.rows >= 10 }
            return TripComparison.render(trips)
        }
    }
}

/** Raw ELM327 traffic for troubleshooting clone adapters; capped in size. Off: nothing is written. */
class ElmTraceLog(file: File, enabled: Boolean = true) {
    private val writer: BufferedWriter? = if (enabled) file.bufferedWriter() else null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var written = 0L
    private var closed = false

    @Synchronized
    fun write(line: String) {
        val w = writer ?: return
        if (closed || written > MAX_BYTES) return
        val text = "${time.format(Date())} $line\n"
        written += text.length
        w.write(text)
        if (written > MAX_BYTES) w.write("... журнал обрезан (лимит ${MAX_BYTES / 1_000_000} МБ)\n")
        w.flush()
    }

    @Synchronized
    fun close() {
        if (!closed) writer?.close()
        closed = true
    }

    companion object {
        private const val MAX_BYTES = 5_000_000L
    }
}
