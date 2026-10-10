package com.obdlogger.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Exact numbers for the AI to reason from (a language model estimates them badly):
 * correlations between sensors per mode, which one moves first (lagged correlation),
 * what all sensors did around events (rpm dips, mixture swings, «lean» rear O2,
 * voltage dips), and the raw rows themselves as a compact table.
 */
object Relations {
    /** Couplings any healthy engine shows: reported, but not as findings. */
    private val EXPECTED = listOf(
        setOf("rpm", "maf_gs"), setOf("engine_load_pct", "maf_gs"), setOf("engine_load_pct", "throttle_pct"),
        setOf("map_kpa", "engine_load_pct"), setOf("map_kpa", "maf_gs"), setOf("throttle_pct", "maf_gs"),
        setOf("rpm", "speed_kmh"), setOf("speed_kmh", "maf_gs"), setOf("rpm", "timing_deg"),
        setOf("stft_b1_pct", "trim_b1"), setOf("stft_b2_pct", "trim_b2"), setOf("ltft_b1_pct", "trim_b1"), setOf("ltft_b2_pct", "trim_b2"),
        setOf("o2_b1s1_v", "o2_b1s1_trim_pct"), setOf("o2_b2s1_v", "o2_b2s1_trim_pct"),
        setOf("o2_b1s2_v", "o2_b1s2_trim_pct"), setOf("o2_b2s2_v", "o2_b2s2_trim_pct"),
        setOf("rpm", "map_kpa"), setOf("throttle_pct", "rpm"), setOf("engine_load_pct", "rpm"),
    )

    /** Fuel trim family of a bank: STFT, LTFT, their sum and the front O2's trim are one quantity. */
    private fun trimBank(c: String): Int? = Regex("^(?:stft_b|ltft_b|trim_b)(\\d)|^o2_b(\\d)s1_trim").find(c)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt() }

    fun expected(a: String, b: String): Boolean {
        if (setOf(a, b) in EXPECTED) return true
        val ba = trimBank(a)
        val bb = trimBank(b)
        return ba != null && ba == bb
    }

    /** Cross-bank trim pairs say one thing («both banks together»); only trim_b1↔trim_b2 is kept for it. */
    private fun redundant(a: String, b: String): Boolean {
        val ba = trimBank(a) ?: return false
        val bb = trimBank(b) ?: return false
        return ba != bb && setOf(a, b) != setOf("trim_b1", "trim_b2") && setOf(a, b) != setOf("ltft_b1_pct", "ltft_b2_pct")
    }

    /** Sensors worth relating: decoded, varying, not counters or distance. */
    fun codes(t: TripTable): List<String> = t.sensors.filter { c ->
        !c.startsWith("pid01_") && !c.startsWith("m21_") && c !in setOf("run_time_s", "dist_with_mil_km", "dist_since_clear_km", "warmups_since_clear", "fuel_level_pct", "baro_kpa")
    }

    class Corr(val a: String, val b: String, val mode: DriveMode?, val r: Double, val n: Int) {
        val expected get() = expected(a, b)
    }

    fun pearson(x: List<Double?>, y: List<Double?>, idx: List<Int>): Pair<Double, Int>? {
        var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in idx) {
            val a = x.getOrNull(i) ?: continue
            val b = y.getOrNull(i) ?: continue
            n++; sx += a; sy += b; sxx += a * a; syy += b * b; sxy += a * b
        }
        if (n < 20) return null
        val vx = sxx - sx * sx / n
        val vy = syy - sy * sy / n
        if (vx <= 1e-12 || vy <= 1e-12) return null
        return (sxy - sx * sy / n) / sqrt(vx * vy) to n
    }

    /** Strong correlations (|r| ≥ [minR]) in the whole trip, warm idle and cruise. */
    fun correlations(t: TripTable, minR: Double = 0.5): List<Corr> {
        val codes = codes(t)
        val series = codes.associateWith { t.carried(it) }
        val scopes = listOf<DriveMode?>(null, DriveMode.WARM_IDLE, DriveMode.CRUISE)
        val out = ArrayList<Corr>()
        for (mode in scopes) {
            val idx = t.rows.indices.filter { mode == null || t.modes[it] == mode }
            if (idx.size < 20) continue
            for (i in codes.indices) for (j in i + 1 until codes.size) {
                if (redundant(codes[i], codes[j])) continue
                val (r, n) = pearson(series.getValue(codes[i]), series.getValue(codes[j]), idx) ?: continue
                if (abs(r) >= minR) out += Corr(codes[i], codes[j], mode, r, n)
            }
        }
        return out.sortedWith(compareBy<Corr> { it.expected }.thenByDescending { abs(it.r) })
    }

    class Lead(val first: String, val second: String, val lagSec: Double, val r: Double)

    /** For strong unexpected pairs: which sensor moves first (lag up to [maxLag] rows each way). */
    fun leads(t: TripTable, pairs: List<Corr>, maxLag: Int = 5): List<Lead> {
        val poll = if (t.ms.size > 1) t.ms.last() / 1000.0 / (t.ms.size - 1) else 1.0
        return pairs.filter { !it.expected && it.mode == null }.take(12).mapNotNull { c ->
            val x = t.carried(c.a)
            val y = t.carried(c.b)
            var best = 0
            var bestR = c.r
            for (lag in -maxLag..maxLag) {
                if (lag == 0) continue
                // x shifted by lag: x[i] against y[i + lag].
                val ys = List(y.size) { i -> y.getOrNull(i + lag) }
                val (r, _) = pearson(x, ys, x.indices.toList()) ?: continue
                if (abs(r) > abs(bestR) + 0.05) { best = lag; bestR = r }
            }
            if (best == 0) null
            else if (best > 0) Lead(c.a, c.b, best * poll, bestR) else Lead(c.b, c.a, -best * poll, bestR)
        }
    }

    class Event(val kind: String, val count: Int, val around: Map<String, Pair<Double, Double>>)

    /**
     * Events and what the other sensors did: mean in the [window] rows before the event
     * against the trip's mean in the same mode. Shows what comes with a dip.
     */
    fun events(t: TripTable, window: Int = 3): List<Event> {
        val rpm = t.raw("rpm")
        val idle = t.rows.indices.filter { t.modes[it] == DriveMode.WARM_IDLE }
        val kinds = LinkedHashMap<String, List<Int>>()
        val idleMedian = idle.mapNotNull { rpm[it] }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val dip = TripAnalyzer.dipBelow(idleMedian)
        kinds["провал оборотов ниже ${dip.toInt()} на ХХ (обычный холостой минус 15 %)"] = idle.filter { (rpm[it] ?: 1e9) < dip }
        val trim = t.raw("trim_b1")
        kinds["коррекция Б1 выше +15 %"] = trim.indices.filter { (trim[it] ?: 0.0) > 15 }
        val rear = t.raw("o2_b1s2_v")
        kinds["задняя лямбда Б1 «бедно» (< 0.1 В) на прогретом"] = rear.indices.filter { (rear[it] ?: 1.0) < 0.1 && t.modes[it] != DriveMode.COLD && t.modes[it] != null }
        val v = t.carried("battery_v")
        kinds["напряжение ниже 13.2 В на работающем"] = v.indices.filter { (v[it] ?: 14.0) < 13.2 && (rpm[it] ?: 0.0) > 500 }
        val codes = codes(t)
        val series = codes.associateWith { t.carried(it) }
        return kinds.mapNotNull { (kind, rows) ->
            if (rows.size < 2) return@mapNotNull null
            // Rows just before each event (the lead-up), against all rows of the same modes.
            val before = rows.flatMap { r -> (r - window until r).filter { it >= 0 } }.distinct()
            val modes = rows.mapNotNull { t.modes[it] }.toSet()
            // Compared with the engine running in the same modes (not with the stops).
            val base = t.rows.indices.filter { t.modes[it] in modes && (rpm[it] ?: 0.0) > 400 }
            val around = codes.mapNotNull { c ->
                val s = series.getValue(c)
                val b = before.mapNotNull { s[it] }
                val all = base.mapNotNull { s[it] }
                if (b.size < 3 || all.size < 10) null else c to (b.average() to all.average())
            }.toMap()
            Event(kind, rows.size, around)
        }
    }

    private fun n(v: Double): String = String.format(Locale.ROOT, if (abs(v) >= 100) "%.0f" else "%.2f", v).trimEnd('0').trimEnd('.')
    private fun scope(m: DriveMode?) = m?.name ?: "вся поездка"

    /** All of the above for one trip, as text for the prompt. */
    fun render(d: TripDetail): String = buildString {
        val t = d.table
        appendLine("РАСЧЁТЫ БОРТАЧА · поездка ${d.summary.label} (точные числа; r — коэффициент корреляции Пирсона, n — пар замеров):")
        val corr = correlations(t)
        val odd = corr.filter { !it.expected }.take(25)
        if (odd.isEmpty()) appendLine("Сильных неожиданных связей (|r| ≥ 0.5) нет.")
        else {
            appendLine("Сильные связи, не объяснимые нормальной работой мотора (коррекции банков 1↔2 вместе — признак общей причины для всего мотора):")
            odd.forEach { appendLine("  ${it.a} ↔ ${it.b} [${scope(it.mode)}]: r = ${n(it.r)}, n = ${it.n}") }
        }
        val norm = corr.filter { it.expected }.take(8)
        if (norm.isNotEmpty()) appendLine("Ожидаемые связи (контроль): " + norm.joinToString("; ") { "${it.a}↔${it.b} [${scope(it.mode)}] ${n(it.r)}" })
        val leads = leads(t, corr)
        if (leads.isNotEmpty()) {
            appendLine("Что меняется раньше (корреляция со сдвигом):")
            leads.forEach { appendLine("  ${it.first} опережает ${it.second} на ~${n(it.lagSec)} с (r = ${n(it.r)})") }
        }
        val ev = events(t)
        if (ev.isNotEmpty()) {
            appendLine("События и что им предшествовало (среднее за ${3} замера до события против обычного в тех же режимах):")
            for (e in ev) {
                val moved = e.around.entries
                    .map { (c, p) -> Triple(c, p.first, p.second) }
                    .sortedByDescending { (_, b, a) -> abs(b - a) / (abs(a) + 1e-6) }
                    .take(8)
                appendLine("  ${e.kind}: ${e.count} раз; " + moved.joinToString("; ") { (c, b, a) -> "$c ${n(b)} (обычно ${n(a)})" })
            }
        }
    }

    /**
     * The trip's rows as a compact table for the model: seconds from start, mode letter, then
     * every relating sensor. ≈ 45–50K tokens for a 46-minute trip.
     */
    fun rawTable(d: TripDetail, maxRows: Int = 4000): String = buildString {
        val t = d.table
        val codes = codes(t)
        val series = codes.map { t.raw(it) }
        val letter = mapOf(DriveMode.COLD to "П", DriveMode.WARM_IDLE to "Х", DriveMode.ROLL_TO_STOP to "К", DriveMode.CRUISE to "Д", DriveMode.HEAVY to "Г")
        val step = ((t.rows.size + maxRows - 1) / maxRows).coerceAtLeast(1)
        appendLine("СЫРЫЕ ДАННЫЕ · поездка ${d.summary.label}, ${t.rows.size} строк" + (if (step > 1) " (каждая $step-я)" else "") +
            ". Режим: П прогрев, Х холостой, К подкат, Д движение, Г сильный газ; пусто — нет замера.")
        appendLine("t_s,режим," + codes.joinToString(","))
        for (i in t.rows.indices step step) {
            append((t.ms[i] / 1000).toString()).append(',').append(t.modes[i]?.let { letter[it] } ?: "")
            for (s in series) { append(','); s[i]?.let { append(n(it)) } }
            append('\n')
        }
    }
}
