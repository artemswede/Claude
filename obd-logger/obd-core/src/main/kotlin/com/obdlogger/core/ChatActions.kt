package com.obdlogger.core

import java.util.Locale
import kotlin.math.abs

/**
 * A condition to watch for in the next trips, set by the assistant with
 * «[наблюдать: trim_b1 > 15 когда WARM_IDLE]». Only a whitelisted shape is accepted —
 * sensor code, comparison, number, optional mode — nothing is executed.
 */
data class WatchRule(val code: String, val op: String, val value: Double, val mode: LiveMode? = null, val tripsLeft: Int = TRIPS) {
    fun holds(v: Double, now: LiveMode): Boolean {
        if (v.isNaN() || now == LiveMode.OFF) return false
        if (mode != null && now != mode) return false
        return when (op) {
            ">" -> v > value
            ">=" -> v >= value
            "<" -> v < value
            "<=" -> v <= value
            else -> false
        }
    }

    /** «Коррекция Б1 > 15 % на холостом ходу» */
    val text: String
        get() = "${SensorNames.label(code)} ${op.replace(">=", "≥").replace("<=", "≤")} ${Values.format(value)}${SensorNames.unit(code).let { if (it.isEmpty()) "" else " $it" }}" +
            (mode?.let { " — ${it.ru}" } ?: "")

    /** Stored form: «code|op|value|MODE|tripsLeft». */
    fun encode() = "$code|$op|$value|${mode?.name.orEmpty()}|$tripsLeft"

    companion object {
        const val TRIPS = 3
        private val CODE = Regex("^[a-z][a-z0-9_]{1,30}$")
        private val tag = Regex("\\[наблюдать:\\s*([^\\]]+)]", RegexOption.IGNORE_CASE)
        private val body = Regex("^([a-zA-Z][a-zA-Z0-9_]*)\\s*(>=|<=|>|<|≥|≤)\\s*(-?\\d+(?:[.,]\\d+)?)\\s*(?:(?:когда|на|в)\\s+(.+))?$", RegexOption.IGNORE_CASE)

        fun mode(word: String): LiveMode? {
            val w = word.trim().lowercase(Locale.ROOT)
            return when {
                w.isEmpty() -> null
                w == "warm_idle" || w.startsWith("холост") || w == "хх" || w == "idle" -> LiveMode.IDLE
                w == "cruise" || w == "heavy" || w == "roll_to_stop" || w.startsWith("движ") || w.startsWith("езд") || w == "drive" -> LiveMode.DRIVE
                w == "cold" || w.startsWith("прогрев") || w.startsWith("холодн") -> LiveMode.COLD
                else -> null
            }
        }

        /** One rule from a tag body; null if it is not in the allowed shape. */
        fun of(text: String): WatchRule? {
            val m = body.find(text.trim()) ?: return null
            val code = m.groupValues[1].lowercase(Locale.ROOT)
            if (!CODE.matches(code)) return null
            val op = m.groupValues[2].replace("≥", ">=").replace("≤", "<=")
            val v = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return null
            val modeWord = m.groupValues[4]
            val mode = mode(modeWord)
            if (modeWord.isNotBlank() && mode == null) return null
            return WatchRule(code, op, v, mode)
        }

        fun parse(text: String): List<WatchRule> = tag.findAll(text).mapNotNull { of(it.groupValues[1]) }.distinct().take(3).toList()

        fun decode(line: String): WatchRule? = line.split('|').takeIf { it.size >= 5 }?.let { p ->
            val v = p[2].toDoubleOrNull() ?: return null
            if (!CODE.matches(p[0]) || p[1] !in setOf(">", ">=", "<", "<=")) return null
            WatchRule(p[0], p[1], v, p[3].takeIf { it.isNotEmpty() }?.let { runCatching { LiveMode.valueOf(it) }.getOrNull() }, p[4].toIntOrNull() ?: TRIPS)
        }
    }
}

/**
 * Watching one rule over a trip, row by row: how often the condition started, how long
 * it held against the time in the mode, the extreme value, and what the other sensors
 * read while it held (against the rest of the mode).
 */
class WatchTally(val rule: WatchRule) {
    var events = 0
        private set
    var secIn = 0.0
        private set
    var secMode = 0.0
        private set
    var extreme: Double? = null
        private set
    private var inside = false
    private var lastMs: Long? = null
    private val withSum = HashMap<String, Double>()
    private val withN = HashMap<String, Int>()
    private val restSum = HashMap<String, Double>()
    private val restN = HashMap<String, Int>()

    /** One row: [values] by code (NaN when unknown), the engine mode now. */
    fun add(timeMs: Long, values: Map<String, Double>, mode: LiveMode) {
        val dt = lastMs?.let { ((timeMs - it) / 1000.0).coerceIn(0.0, 5.0) } ?: 0.0
        lastMs = timeMs
        val v = values[rule.code] ?: Double.NaN
        val inMode = mode != LiveMode.OFF && (rule.mode == null || mode == rule.mode)
        if (!inMode || v.isNaN()) {
            inside = false
            return
        }
        secMode += dt
        val holds = rule.holds(v, mode)
        if (holds) {
            if (!inside) events++
            secIn += dt
            extreme = extreme?.let { if (rule.op.startsWith(">")) maxOf(it, v) else minOf(it, v) } ?: v
        }
        inside = holds
        for ((c, x) in values) {
            if (c == rule.code || x.isNaN()) continue
            if (holds) { withSum[c] = (withSum[c] ?: 0.0) + x; withN[c] = (withN[c] ?: 0) + 1 }
            else { restSum[c] = (restSum[c] ?: 0.0) + x; restN[c] = (restN[c] ?: 0) + 1 }
        }
    }

    /** For the chat: the trip's answer to the rule, with the numbers. */
    fun report(trip: String, minutes: Double): String = buildString {
        append("Наблюдение «${rule.text}», поездка $trip (${minutes.toInt()} мин): ")
        if (secMode < 5) {
            append("нужного режима почти не было — проверить не удалось.")
            return@buildString
        }
        if (events == 0) {
            append("условие не выполнилось ни разу за ${fmtSec(secMode)} в режиме.")
            return@buildString
        }
        append("выполнялось $events раз, всего ${fmtSec(secIn)} из ${fmtSec(secMode)} в режиме (${(100 * secIn / secMode).toInt()} %)")
        extreme?.let { append(", крайнее значение ${Values.format(it)}${SensorNames.unit(rule.code).let { u -> if (u.isEmpty()) "" else " $u" }}") }
        append(".")
        // What moved with it: the biggest relative differences against the rest of the mode.
        val moved = withSum.keys.mapNotNull { c ->
            val a = (withSum[c] ?: return@mapNotNull null) / (withN[c] ?: return@mapNotNull null)
            val bN = restN[c] ?: return@mapNotNull null
            if (bN < 5 || (withN[c] ?: 0) < 3) return@mapNotNull null
            val b = restSum.getValue(c) / bN
            Triple(c, a, b)
        }.filter { abs(it.second - it.third) > 1e-6 }.sortedByDescending { abs(it.second - it.third) / (abs(it.third) + 1e-3) }.take(4)
        if (moved.isNotEmpty()) append(" В эти моменты: " + moved.joinToString("; ") { (c, a, b) -> "${SensorNames.label(c)} ${Values.format(a)} (в остальное время ${Values.format(b)})" } + ".")
    }

    private fun fmtSec(s: Double) = if (s < 90) "${s.toInt()} с" else "${(s / 60).toInt()} мин ${(s % 60).toInt()} с"
}

/** Check buttons the assistant offers: «[проверка: зарядка]». */
object ChatTests {
    private val tag = Regex("\\[проверка:\\s*([^\\]]+)]", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<CheckKind> = tag.findAll(text).mapNotNull { CheckKind.of(it.groupValues[1]) }.distinct().toList()

    /** The text without action tags (checks and watches); charts are stripped by [ChatCharts.strip]. */
    fun strip(text: String): String = text.replace(tag, "").replace(Regex("\\[наблюдать:[^\\]]*]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\n{3,}"), "\n\n").trim()
}

/** The last minutes of live data as a compact table for the prompt. */
object LiveTable {
    fun render(store: SeriesStore, minutes: Int = 3, stepSec: Int = 2): String {
        val end = store.lastTime() ?: return ""
        val from = end - minutes * 60_000L
        val skip = setOf("run_time_s", "dist_with_mil_km", "dist_since_clear_km", "warmups_since_clear", "fuel_level_pct", "baro_kpa")
        val codes = store.columns.filter { !it.startsWith("pid01_") && !it.startsWith("m21_") && it !in skip }
        val series = codes.associateWith { store.series(it, from) }.filter { it.value.first.isNotEmpty() }
        if (series.isEmpty()) return ""
        val cols = series.keys.toList()
        return buildString {
            appendLine("ЖИВЫЕ ДАННЫЕ · последние $minutes мин записи, шаг $stepSec с (t — секунды до последнего замера, значения — последние известные):")
            appendLine("t," + cols.joinToString(","))
            val idx = IntArray(cols.size)
            var t = from
            while (t <= end) {
                val row = cols.mapIndexed { k, c ->
                    val (ts, vs) = series.getValue(c)
                    while (idx[k] + 1 < ts.size && ts[idx[k] + 1] <= t) idx[k]++
                    if (ts[idx[k]] <= t && !vs[idx[k]].isNaN()) Values.format(vs[idx[k]]) ?: "" else ""
                }
                appendLine("${(t - end) / 1000}," + row.joinToString(","))
                t += stepSec * 1000L
            }
        }
    }
}
