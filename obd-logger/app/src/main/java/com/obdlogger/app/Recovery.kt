package com.obdlogger.app

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Finishes a trip that was cut off: the tablet lost power together with the car,
 * or Android killed the app. The CSV on storage is complete up to the last synced
 * row; here its info file gets an end note and the analysis, and the files are
 * copied to Downloads — as if the trip had ended normally. The main screen then
 * shows its result.
 *
 * A power loss is normal and not reported; a kill while the tablet stayed on is
 * («Система остановила запись»), because it means background limits are on.
 */
object Recovery {
    /** Changes on every boot of the device; null where the kernel does not show it. */
    fun bootId(): String? = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /** Total time the device has spent in deep sleep since boot. */
    fun slept(): Long = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    @Synchronized
    fun run(ctx: Context) {
        if (LoggerState.snapshot.recording) return
        val prefs = Prefs.of(ctx)
        val since = prefs.getLong(Prefs.RECORDING_SINCE, 0)
        val path = prefs.getString(Prefs.RECORDING_FILE, null)
        if (since == 0L && path == null) return
        val boot = prefs.getString(Prefs.RECORDING_BOOT, null)
        val sleptThen = prefs.getLong(Prefs.RECORDING_SLEEP, -1)
        val shutdown = prefs.getBoolean(Prefs.RECORDING_SHUTDOWN, false)
        prefs.edit().remove(Prefs.RECORDING_SINCE).remove(Prefs.RECORDING_FILE)
            .remove(Prefs.RECORDING_BOOT).remove(Prefs.RECORDING_SLEEP).remove(Prefs.RECORDING_SHUTDOWN).apply()

        // The car switched the device off, and that is normal, if it rebooted, announced a shutdown, or went to deep sleep
        // (head units sleep on ignition off and close apps). Wall clocks are not used: head units without a clock
        // battery wake up in a wrong year (21.02) and only later get the time from GPS or the network.
        val bootNow = bootId()
        val rebooted = if (boot != null && bootNow != null) boot != bootNow else since > 0 && System.currentTimeMillis() - SystemClock.elapsedRealtime() > since
        val sleptSince = sleptThen >= 0 && slept() - sleptThen > 30_000
        val powerLoss = shutdown || rebooted || sleptSince
        if (since > 0 && !powerLoss) prefs.edit().putLong(Prefs.KILLED_AT, System.currentTimeMillis()).apply()

        val csv = path?.let(::File)?.takeIf { it.exists() && it.length() > 0 } ?: return
        val lines = try {
            csv.readLines().count { it.isNotBlank() } - 1
        } catch (e: Exception) {
            0
        }
        val info = SessionFiles.infoOf(csv)
        if (lines <= 0) {
            // Connected but nothing recorded: no empty trip in the journal.
            listOf(csv, info, SessionFiles.elmOf(csv), SessionFiles.profileOf(csv)).forEach { it.delete() }
            return
        }
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(Date(csv.lastModified()))
        val why = if (powerLoss) "устройство выключилось или уснуло (скорее всего, вместе с машиной)" else "Android остановил приложение в фоне"
        try {
            info.appendText("\n\n=== ЗАПИСЬ ПРЕРВАНА ===\nПоследняя строка записана около $stamp: $why. " +
                "Данные до этого момента сохранены; итоговые коды ЭБУ в конце поездки не прочитаны.\n")
            val analysis = SessionFiles.compareRecent(ctx)
            info.appendText("\n=== АВТОАНАЛИЗ И СРАВНЕНИЕ С ПРОШЛЫМИ ПОЕЗДКАМИ ===\n$analysis\n")
        } catch (_: Exception) {
        }
        listOf(csv, info, SessionFiles.elmOf(csv)).filter { it.exists() && it.length() > 0 }.forEach { SessionFiles.exportToDownloads(ctx, it) }
        LoggerState.update { it.copy(savedTrips = it.savedTrips + 1) }
    }
}
