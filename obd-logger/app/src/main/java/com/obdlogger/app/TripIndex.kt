package com.obdlogger.app

import android.content.Context
import java.io.File

/**
 * Recordings are numbered in the order they were made: «Поездка 42». The journal is ordered
 * by these numbers, not by dates — the head unit's clock may start in 2007, the order of
 * recording is always right. Kept in `sessions/trips.idx` as «obd_…;42» lines; the number
 * moves with the files when they are renamed.
 */
object TripIndex {
    private var map: MutableMap<String, Int>? = null
    private var dir: File? = null

    private fun file(d: File) = File(d, "trips.idx")

    private fun loaded(ctx: Context): MutableMap<String, Int> = loaded(SessionFiles.dir(ctx))

    @Synchronized
    private fun loaded(d: File): MutableMap<String, Int> {
        if (dir == d) map?.let { return it }
        dir = d
        val m = HashMap<String, Int>()
        try {
            file(d).takeIf { it.exists() }?.readLines()?.forEach { l ->
                val n = l.substringAfter(';', "").trim().toIntOrNull() ?: return@forEach
                m[l.substringBefore(';').trim()] = n
            }
        } catch (_: Exception) {}
        map = m
        return m
    }

    @Synchronized
    private fun save(ctx: Context) = save()

    @Synchronized
    private fun save() {
        val m = map ?: return
        val d = dir ?: return
        SessionFiles.writeSynced(file(d), m.entries.sortedBy { it.value }.joinToString("\n") { "${it.key};${it.value}" })
    }

    /** The number of a recording by its base name («obd_20261014_081800»), null if not numbered yet. */
    @Synchronized
    fun number(name: String): Int? = map?.get(name)

    /** A new recording: the next number (or its own if it already has one). */
    @Synchronized
    fun assign(ctx: Context, name: String): Int {
        val m = loaded(ctx)
        m[name]?.let { return it }
        val n = (m.values.maxOrNull() ?: 0) + 1
        m[name] = n
        save(ctx)
        return n
    }

    /** Files renamed after a wrong clock: the number goes with them. */
    @Synchronized
    fun rename(dir: File, old: String, new: String) {
        val m = loaded(dir)
        val n = m.remove(old) ?: return
        m[new] = n
        save()
    }

    /**
     * Every recording on disk gets a number: on the first start the old ones are numbered by
     * their (corrected) start; later a file without a number (restored after a power loss)
     * gets the next one.
     */
    @Synchronized
    fun ensure(ctx: Context) {
        val m = loaded(ctx)
        val files = (SessionFiles.tripCsvs(ctx) + SessionFiles.checkCsvs(ctx)).filter { it.nameWithoutExtension !in m }
        if (files.isEmpty()) return
        var n = m.values.maxOrNull() ?: 0
        for (f in files.sortedWith(compareBy<File> { SessionFiles.stampOf(it) ?: Long.MAX_VALUE }.thenBy { it.name })) m[f.nameWithoutExtension] = ++n
        save(ctx)
    }

    /** «Поездка 42» / «Проверка 43»; without a number — just «Поездка». */
    fun title(name: String, check: Boolean = false): String =
        (if (check) "Проверка" else "Поездка") + (number(name)?.let { " $it" } ?: "")
}
