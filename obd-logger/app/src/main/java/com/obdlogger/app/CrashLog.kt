package com.obdlogger.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes every crash to sessions/crash_<time>.txt (device, Android version, stack)
 * before the app closes, so the owner can send it from Settings — head units
 * have no other way to show what went wrong.
 */
object CrashLog {
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                File(SessionFiles.dir(app), "crash_$stamp.txt").writeText(
                    "Бортач ${BuildConfig.VERSION_NAME}\n" +
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Экран: ${app.resources.configuration.screenWidthDp}×${app.resources.configuration.screenHeightDp} dp, " +
                        "плотность ${app.resources.displayMetrics.density}\n" +
                        "Поток: ${thread.name}\n\n$sw",
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Crash reports kept on the device, newest first. */
    fun files(ctx: Context): List<File> =
        SessionFiles.dir(ctx).listFiles().orEmpty().filter { it.name.startsWith("crash_") }.sortedByDescending { it.name }
}
