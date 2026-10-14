package com.obdlogger.app

import android.content.Context
import com.obdlogger.core.Interval
import com.obdlogger.core.Maintenance
import com.obdlogger.core.MaintenanceEntry
import com.obdlogger.core.Mileage
import com.obdlogger.core.Work
import java.io.File
import java.time.LocalDate

/**
 * The car's «медкарта» on the device: ТО and repair records, the mileage history the owner
 * enters, intervals. Per car (its key), next to the trips; written with fsync — the head
 * unit may lose power any second.
 */
object MaintenanceStore {
    class Data(val entries: List<MaintenanceEntry>, val mileage: List<Mileage>, val intervals: Map<Work, Interval>) {
        val current get() = Maintenance.current(mileage)
        /** Today — or, while the head unit's clock says 2007, the newest date the owner entered. */
        val today: LocalDate get() = LocalDate.now().takeIf { it.year >= 2015 }
            ?: (entries.map { it.date } + mileage.map { it.date }).maxOrNull() ?: LocalDate.now()
        fun checklist() = Maintenance.checklist(entries, mileage, intervals, today)
        fun render() = Maintenance.render(entries, mileage, intervals, today)
    }

    private fun key(ctx: Context) = (Prefs.currentCar(ctx) ?: "car").replace(Regex("[^A-Za-z0-9]"), "_").take(40)
    private fun entriesFile(ctx: Context) = File(SessionFiles.dir(ctx), "maintenance_${key(ctx)}.csv")
    private fun mileageFile(ctx: Context) = File(SessionFiles.dir(ctx), "mileage_${key(ctx)}.csv")
    private fun intervalsKey(ctx: Context) = "maint_intervals_${key(ctx)}"

    @Synchronized
    fun load(ctx: Context): Data = Data(
        try { entriesFile(ctx).takeIf { it.exists() }?.readText()?.let { Maintenance.decode(it) }.orEmpty() } catch (_: Exception) { emptyList() },
        try { mileageFile(ctx).takeIf { it.exists() }?.readText()?.let { Maintenance.decodeMileage(it) }.orEmpty() } catch (_: Exception) { emptyList() },
        Maintenance.decodeIntervals(Prefs.of(ctx).getString(intervalsKey(ctx), null)),
    )

    @Synchronized
    fun saveEntries(ctx: Context, list: List<MaintenanceEntry>) =
        SessionFiles.writeSynced(entriesFile(ctx), Maintenance.encode(list.sortedWith(compareBy<MaintenanceEntry> { it.date }.thenBy { it.km ?: 0 })))

    @Synchronized
    fun add(ctx: Context, e: MaintenanceEntry) {
        saveEntries(ctx, load(ctx).entries + e)
        // A record with mileage newer than the last entered one moves the mileage too.
        val cur = load(ctx).current
        if (e.km != null && (cur == null || e.km > cur.km)) addMileage(ctx, Mileage(e.date, e.km))
    }

    @Synchronized
    fun replace(ctx: Context, old: MaintenanceEntry, new: MaintenanceEntry?) =
        saveEntries(ctx, load(ctx).entries.filter { it != old } + listOfNotNull(new))

    @Synchronized
    fun addMileage(ctx: Context, m: Mileage) =
        SessionFiles.writeSynced(mileageFile(ctx), Maintenance.encodeMileage(load(ctx).mileage.filter { it.date != m.date } + m))

    fun setIntervals(ctx: Context, m: Map<Work, Interval>) =
        Prefs.of(ctx).edit().putString(intervalsKey(ctx), Maintenance.encodeIntervals(m)).apply()

    /** «Пора ТО: …» once a day at most, for the notification after a trip; null — nothing near. */
    fun reminder(ctx: Context): String? {
        val r = Maintenance.reminder(load(ctx).checklist()) ?: return null
        val p = Prefs.of(ctx)
        val day = LocalDate.now().toString()
        if (p.getString("maint_reminded", null) == day) return null
        p.edit().putString("maint_reminded", day).apply()
        return r
    }

    /** Both tables as one CSV for a spreadsheet (UTF-8 with BOM — Excel opens Cyrillic right). */
    fun exportCsv(ctx: Context): File {
        val d = load(ctx)
        val f = File(SessionFiles.dir(ctx), "ТО_" + Prefs.vehicle(ctx).ifBlank { "машина" }.replace(Regex("[^\\p{L}\\p{N}]+"), "_").take(30) + "_" + LocalDate.now() + ".csv")
        val text = "﻿" + Maintenance.encode(d.entries) + "\nmileage_date;km\n" + Maintenance.encodeMileage(d.mileage) + "\n"
        f.writeText(text)
        return f
    }
}
