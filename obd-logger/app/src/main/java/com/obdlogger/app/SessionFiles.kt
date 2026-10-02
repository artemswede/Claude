package com.obdlogger.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Files of one recording. They are written to app storage while recording
 * (fast, survives crashes) and copied to Downloads/OBD-Logger when the session ends.
 */
class SessionFiles(dir: File, baseName: String) {
    val csv = File(dir, "$baseName.csv")
    val info = File(dir, "${baseName}_info.txt")
    val elmLog = File(dir, "${baseName}_elm.log")

    fun existing() = listOf(csv, info, elmLog).filter { it.exists() && it.length() > 0 }

    companion object {
        const val DOWNLOAD_FOLDER = "OBD-Logger"

        fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "sessions").apply { mkdirs() }

        fun create(ctx: Context) =
            SessionFiles(dir(ctx), "obd_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))

        /** Copies [file] to the public Downloads/OBD-Logger folder (no storage permission needed on Android 10+). */
        fun exportToDownloads(ctx: Context, file: File): Uri? {
            val mime = when (file.extension) {
                "csv" -> "text/csv"
                else -> "text/plain"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_FOLDER")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return null
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        }
    }
}

/** Raw ELM327 traffic for troubleshooting clone adapters; capped in size. */
class ElmTraceLog(file: File) {
    private val writer: BufferedWriter = file.bufferedWriter()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var written = 0L

    @Synchronized
    fun write(line: String) {
        if (written > MAX_BYTES) return
        val text = "${time.format(Date())} $line\n"
        written += text.length
        writer.write(text)
        if (written > MAX_BYTES) writer.write("... журнал обрезан (лимит ${MAX_BYTES / 1_000_000} МБ)\n")
        writer.flush()
    }

    @Synchronized
    fun close() = writer.close()

    companion object {
        private const val MAX_BYTES = 5_000_000L
    }
}
