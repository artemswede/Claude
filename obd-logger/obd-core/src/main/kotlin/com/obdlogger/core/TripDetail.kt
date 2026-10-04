package com.obdlogger.core

import kotlin.math.abs

/** How a sensor did in one mode of a trip. */
enum class Verdict(val ru: String) { OK("норма"), OUT("за нормой"), NO_NORM("без нормы"), NOT_RATED("не оценивается") }

class ModeStat(
    val mode: DriveMode,
    /** Share of the trip's classified rows in this mode, 0–1. */
    val share: Double,
    val count: Int,
    val median: Double?,
    val min: Double?,
    val max: Double?,
    val verdict: Verdict,
)

class SensorDetail(
    val code: String,
    val count: Int,
    val min: Double,
    val median: Double,
    val mean: Double,
    val max: Double,
    val byMode: List<ModeStat>,
    /** 0–100: share of rated time outside the norm. Null when no mode has a norm. */
    val deviation: Double?,
    /** Jitter like the live store: mean jump between readings, % of p5–p95 range. */
    val jitter: Double?,
    /** «60 из 60 с вне нормы», ««бедно» на ХХ», «провалов нет». */
    val note: String,
) {
    val worst: Verdict
        get() = when {
            byMode.any { it.verdict == Verdict.OUT } -> Verdict.OUT
            byMode.any { it.verdict == Verdict.OK } -> Verdict.OK
            else -> Verdict.NO_NORM
        }
}

/**
 * Everything the trip screens need beyond [TripSummary]: per-sensor statistics by
 * operating mode («Статистика»), the instability ranking («Рейтинг») and series for
 * the charts. Built from a [TripTable] — heavy, call off the main thread.
 */
class TripDetail(val table: TripTable, val summary: TripSummary) {
    val modeShares: Map<DriveMode, Double> = run {
        val classified = table.modes.count { it != null }.coerceAtLeast(1)
        DriveMode.entries.associateWith { m -> table.modes.count { it == m }.toDouble() / classified }
    }

    /** Minutes per mode, by real time between rows. */
    val modeMinutes: Map<DriveMode, Double> = run {
        val out = DriveMode.entries.associateWith { 0.0 }.toMutableMap()
        for (i in 1 until table.rows.size) {
            val m = table.modes[i] ?: continue
            out[m] = out.getValue(m) + (table.ms[i] - table.ms[i - 1]).coerceIn(0L, 10_000L) / 60_000.0
        }
        out
    }

    /** Mean seconds between rows. */
    val pollSec: Double = if (table.rows.size > 1) table.ms.last() / 1000.0 / (table.rows.size - 1) else 0.0

    val sensors: List<SensorDetail> by lazy { table.sensors.mapNotNull(::detail) }

    /** Ranked by deviation from norm (or jitter), most suspicious first. */
    fun rating(sort: AttentionSort): List<SensorDetail> = when (sort) {
        AttentionSort.DEVIATION -> sensors.filter { it.code in Attention.INTERESTING }
            .sortedWith(compareByDescending<SensorDetail> { it.deviation ?: -1.0 }.thenByDescending { it.jitter ?: 0.0 })
        AttentionSort.JUMPS -> sensors.filter { it.jitter != null }.sortedByDescending { it.jitter }
    }

    fun series(code: String): List<Double?> = if (code == "coolant_c" || code.startsWith("ltft")) table.carried(code) else table.raw(code)

    private fun detail(code: String): SensorDetail? {
        val v = series(code)
        val all = v.filterNotNull()
        if (all.isEmpty()) return null
        val sorted = all.sorted()
        val front = Norms.isFrontO2(code)
        var ratedSec = 0.0
        var outSec = 0.0
        val byMode = DriveMode.entries.mapNotNull { m ->
            val idx = v.indices.filter { table.modes[it] == m && v[it] != null }
            if (idx.isEmpty()) return@mapNotNull null
            val x = idx.map { v[it]!! }.sorted()
            val live = LiveMode.of(m)
            val norm = Norms.of(code, live)
            val verdict = when {
                m == DriveMode.COLD -> Verdict.NOT_RATED
                front -> if (Norms.frontO2Switching(x.toDoubleArray()) == false) Verdict.OUT else Verdict.OK
                norm == null -> Verdict.NO_NORM
                else -> {
                    for (i in idx) {
                        val dt = if (i == 0) 0.0 else ((table.ms[i] - table.ms[i - 1]) / 1000.0).coerceIn(0.0, 10.0)
                        ratedSec += dt
                        if (norm.state(v[i]!!) != NormState.IN) outSec += dt
                    }
                    if (norm.state(median(x)) != NormState.IN) Verdict.OUT else Verdict.OK
                }
            }
            ModeStat(m, modeShares.getValue(m), x.size, median(x), x.first(), x.last(), verdict)
        }
        val deviation = if (ratedSec > 0) outSec / ratedSec * 100 else null
        val spread = sorted[((sorted.size - 1) * 0.95).toInt()] - sorted[((sorted.size - 1) * 0.05).toInt()]
        val jitter = if (all.size >= 3 && spread > 0) (1 until all.size).sumOf { abs(all[it] - all[it - 1]) } / (all.size - 1) / spread * 100 else null
        val idle = byMode.firstOrNull { it.mode == DriveMode.WARM_IDLE }
        val note = when {
            code == "rpm" -> summary.metrics[Metric.RPM_DIPS]?.let { if (it > 0) "провалов ${it.toInt()}" else "провалов нет" } ?: ""
            code.startsWith("o2_b") && code.endsWith("s2_v") && idle?.verdict == Verdict.OUT -> "«бедно» на ХХ".takeIf { (idle.median ?: 1.0) < 0.45 } ?: "«богато» на ХХ"
            deviation != null && outSec > 0 -> "${outSec.toInt()} из ${ratedSec.toInt()} с вне нормы"
            front -> "ожидаемый коридор"
            deviation == null -> "нормы нет"
            else -> "в норме"
        }
        return SensorDetail(code, all.size, sorted.first(), median(sorted), all.average(), sorted.last(), byMode, deviation, jitter, note)
    }

    private fun median(s: List<Double>) = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}
