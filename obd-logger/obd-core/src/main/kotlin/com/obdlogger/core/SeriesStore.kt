package com.obdlogger.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Summary of one numeric column for the live monitor. */
class SensorStats(
    val name: String,
    val count: Int,
    val min: Double,
    val max: Double,
    val mean: Double,
    val median: Double,
    val std: Double,
    val last: Double,
    /** Coefficient of variation std/|mean|, %; null when the mean is ~0 (trims, angles crossing zero). */
    val cv: Double?,
    /**
     * Jitter: mean jump between consecutive readings over the recent window, as % of the
     * column's typical range (p5–p95). Smooth signals score low, erratic ones high.
     */
    val jitter: Double?,
)

/**
 * In-memory copy of the recorded rows for live charts and statistics. Keeps the
 * last [capacity] rows; the CSV on disk always has everything.
 */
class SeriesStore(private val capacity: Int = 20_000) {
    private class Row(val time: Long, val values: DoubleArray)

    private val rows = ArrayDeque<Row>()
    var columns: List<String> = emptyList()
        private set

    /** Derived «trim_bN» = STFT + last LTFT (LTFT is polled slowly): index of stft, ltft. */
    private var derived: List<Pair<Int, Int>> = emptyList()
    private var lastLtft = DoubleArray(0)
    private var raw = 0

    /** [columns] as written to the CSV; derived columns (total trim per bank) are appended. */
    @Synchronized
    fun reset(columns: List<String>) {
        raw = columns.size
        val d = (1..2).mapNotNull { b ->
            val st = columns.indexOf("stft_b${b}_pct")
            val lt = columns.indexOf("ltft_b${b}_pct")
            if (st >= 0 && lt >= 0) "trim_b$b" to (st to lt) else null
        }
        derived = d.map { it.second }
        lastLtft = DoubleArray(d.size) { Double.NaN }
        this.columns = columns + d.map { it.first }
        rows.clear()
    }

    @Synchronized
    fun add(timeMs: Long, values: List<String?>) {
        val v = DoubleArray(columns.size) { if (it < raw) values.getOrNull(it)?.toDoubleOrNull() ?: Double.NaN else Double.NaN }
        derived.forEachIndexed { k, (st, lt) ->
            if (!v[lt].isNaN()) lastLtft[k] = v[lt]
            v[raw + k] = v[st] + lastLtft[k]
        }
        rows.addLast(Row(timeMs, v))
        while (rows.size > capacity) rows.removeFirst()
    }

    /** Last known value of [name], NaN if never seen. */
    @Synchronized
    fun last(name: String): Double {
        val i = columns.indexOf(name)
        if (i < 0) return Double.NaN
        for (r in rows.reversed()) if (!r.values[i].isNaN()) return r.values[i]
        return Double.NaN
    }

    /** Time of the last row where [name] had a value. */
    @Synchronized
    fun lastTimeOf(name: String): Long? {
        val i = columns.indexOf(name)
        if (i < 0) return null
        for (r in rows.reversed()) if (!r.values[i].isNaN()) return r.time
        return null
    }

    @Synchronized
    fun size() = rows.size

    @Synchronized
    fun lastTime(): Long? = rows.lastOrNull()?.time

    /** Times and values of [name] from [fromMs] on, gaps (not polled) skipped. */
    @Synchronized
    fun series(name: String, fromMs: Long): Pair<LongArray, DoubleArray> {
        val i = columns.indexOf(name)
        if (i < 0) return LongArray(0) to DoubleArray(0)
        val picked = rows.filter { it.time >= fromMs && !it.values[i].isNaN() }
        return LongArray(picked.size) { picked[it].time } to DoubleArray(picked.size) { picked[it].values[i] }
    }

    /** All values of [name] (oldest first), gaps skipped. */
    @Synchronized
    fun values(name: String): DoubleArray {
        val i = columns.indexOf(name)
        if (i < 0) return DoubleArray(0)
        return rows.map { it.values[i] }.filterNot { it.isNaN() }.toDoubleArray()
    }

    /** Statistics of every numeric column that has data, in column order. */
    fun stats(jitterWindow: Int = 300): List<SensorStats> = columns.mapNotNull { stats(it, jitterWindow) }

    fun stats(name: String, jitterWindow: Int = 300): SensorStats? {
        val v = values(name)
        if (v.isEmpty()) return null
        val sorted = v.sortedArray()
        val n = v.size
        val mean = v.average()
        val std = sqrt(v.sumOf { (it - mean) * (it - mean) } / n)
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        val spread = percentile(sorted, 0.95) - percentile(sorted, 0.05)
        val recent = v.copyOfRange(maxOf(0, n - jitterWindow), n)
        val jitter = if (recent.size >= 3 && spread > 0) {
            (1 until recent.size).sumOf { abs(recent[it] - recent[it - 1]) } / (recent.size - 1) / spread * 100
        } else if (recent.size >= 3) 0.0 else null
        return SensorStats(
            name = name, count = n, min = sorted.first(), max = sorted.last(), mean = mean, median = median,
            std = std, last = v.last(),
            cv = if (abs(mean) > 1e-9 && abs(mean) > std / 50) std / abs(mean) * 100 else null,
            jitter = jitter,
        )
    }

    /** Histogram of [name] over its full range. */
    fun histogram(name: String, bins: Int): IntArray {
        val v = values(name)
        val out = IntArray(bins)
        if (v.isEmpty()) return out
        val lo = v.min()
        val hi = v.max()
        for (x in v) {
            val b = if (hi > lo) ((x - lo) / (hi - lo) * bins).toInt().coerceIn(0, bins - 1) else bins / 2
            out[b]++
        }
        return out
    }

    private fun percentile(sorted: DoubleArray, p: Double) = sorted[((sorted.size - 1) * p).toInt()]
}
