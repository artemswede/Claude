package com.obdlogger.app.ui

import com.obdlogger.core.DriveMode
import com.obdlogger.core.Norm
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.TripComparison
import com.obdlogger.core.TripTrace
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Inline SVG charts for the printable report. Plain strings, no Android: the
 * WebView prints them as vectors, so they stay sharp on A4. Colours are the
 * light «мануал» palette of the app.
 */
object ReportCharts {
    private const val INK = "#23211E"
    private const val INK2 = "#4C4840"
    private const val INK3 = "#645E54"
    private const val LINE = "#CFC8BA"
    private const val IDLE = "#E3DED3"
    private const val NORM = "rgba(47,107,94,0.16)"
    private const val AMB = "#8F5A0E"
    private const val AMB_FILL = "rgba(176,116,24,0.22)"
    private const val RED = "#A3352B"
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    private fun f(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private fun text(x: Double, y: Double, s: String, size: Int = 10, color: String = INK3, anchor: String = "start", weight: Int = 400) =
        "<text x='${f(x)}' y='${f(y)}' font-size='$size' fill='$color' text-anchor='$anchor' font-weight='$weight'>${s.replace("&", "&amp;").replace("<", "&lt;")}</text>"

    /** Spans of consecutive rows in warm idle, as (from, to) indices. */
    private fun idleSpans(modes: List<DriveMode?>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < modes.size) {
            if (modes[i] == DriveMode.WARM_IDLE) {
                var j = i
                while (j + 1 < modes.size && modes[j + 1] == DriveMode.WARM_IDLE) j++
                out += i to j
                i = j + 1
            } else i++
        }
        return out
    }

    /** Short moving average for display: takes out the STFT flicker, keeps where the norm is crossed. */
    private fun smooth(v: List<Double?>, k: Int = 2): List<Double?> = v.indices.map { i ->
        if (v[i] == null) null else {
            val w = (max(0, i - k)..min(v.lastIndex, i + k)).mapNotNull { v[it] }
            w.average()
        }
    }

    /**
     * Main chart: total trim bank 1 over the trip — norm band ±10 %, warm-idle bands,
     * the part above the norm in amber, the name and last value at the line's end.
     */
    fun trimChart(t: TripTrace, w: Int = 700, h: Int = 230): String {
        val left = 34.0
        val right = w - 110.0
        val top = 18.0
        val bottom = h - 22.0
        val raw = t.trimB1.map { if (it.isNaN()) null else it }
        val v = smooth(raw)
        val have = v.filterNotNull()
        val lo = min(-15.0, (have.minOrNull() ?: 0.0) - 2)
        val hi = max(30.0, (have.maxOrNull() ?: 0.0) + 2)
        val tMax = max(1.0, t.minutes.lastOrNull() ?: 1.0)
        fun x(m: Double) = left + (right - left) * m / tMax
        fun y(x: Double) = bottom - (bottom - top) * (x - lo) / (hi - lo)
        val sb = StringBuilder("<svg viewBox='0 0 $w $h' width='100%' xmlns='http://www.w3.org/2000/svg' font-family='IBM Plex Sans, sans-serif'>")
        for ((a, b) in idleSpans(t.modes.toList())) {
            val x0 = x(t.minutes[a])
            val x1 = max(x(t.minutes[b]), x0 + 2)
            sb.append("<rect x='${f(x0)}' y='${f(top)}' width='${f(x1 - x0)}' height='${f(bottom - top)}' fill='$IDLE'/>")
            if (x1 - x0 > 46) sb.append(text(x0 + 3, top - 4, "ХОЛОСТОЙ", 8, INK3, weight = 600))
        }
        sb.append("<rect x='${f(left)}' y='${f(y(10.0))}' width='${f(right - left)}' height='${f(y(-10.0) - y(10.0))}' fill='$NORM'/>")
        sb.append("<line x1='${f(left)}' x2='${f(right)}' y1='${f(y(0.0))}' y2='${f(y(0.0))}' stroke='$LINE'/>")
        for (g in listOf(30.0, 10.0, 0.0, -10.0)) if (g in lo..hi) sb.append(text(left - 6, y(g) + 3, if (g > 0) "+${g.toInt()}" else if (g < 0) "−${(-g).toInt()}" else "0", 9, INK3, "end"))
        t.start?.let { st ->
            for (k in 0..4) {
                val m = tMax * k / 4
                sb.append(text(x(m), h - 6.0, st.plusSeconds((m * 60).toLong()).format(HM), 9, INK3, if (k == 0) "start" else if (k == 4) "end" else "middle"))
            }
        }
        // Line, then the out-of-norm parts filled in amber.
        val pts = v.indices.filter { v[it] != null }
        if (pts.isNotEmpty()) {
            sb.append("<polyline fill='none' stroke='$INK' stroke-width='1.4' stroke-linejoin='round' points='")
            pts.forEach { i -> sb.append("${f(x(t.minutes[i]))},${f(y(v[i]!!))} ") }
            sb.append("'/>")
            var k = 0
            while (k < pts.size) {
                if (v[pts[k]]!! <= 10) { k++; continue }
                var e = k
                while (e + 1 < pts.size && v[pts[e + 1]]!! > 10) e++
                sb.append("<polygon fill='$AMB_FILL' stroke='$AMB' stroke-width='1.6' points='${f(x(t.minutes[pts[k]]))},${f(y(10.0))} ")
                for (q in k..e) sb.append("${f(x(t.minutes[pts[q]]))},${f(y(v[pts[q]]!!))} ")
                sb.append("${f(x(t.minutes[pts[e]]))},${f(y(10.0))}'/>")
                k = e + 1
            }
            val lv = v[pts.last()]!!
            val ly = y(lv).coerceIn(top + 10, bottom - 12)
            sb.append(text(right + 8, ly - 2, "Коррекция Б1", 10, INK))
            sb.append(text(right + 8, ly + 11, TripAnalyzer.pct(lv), 11, if (lv > 10) AMB else INK, weight = 600))
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /**
     * One sensor over the trip in a small lane: own scale, norm band where there is
     * one, warm-idle bands, the name and the last value on the right.
     */
    fun lane(code: String, title: String, unit: String, ms: List<Long>, values: List<Double?>, modes: List<DriveMode?>, norm: Norm?, start: LocalDateTime?, w: Int = 700, h: Int = 96): String {
        val left = 6.0
        val right = w - 150.0
        val top = 8.0
        val bottom = h - 16.0
        val have = values.filterNotNull()
        if (have.isEmpty()) return ""
        var lo = have.min()
        var hi = have.max()
        norm?.let { lo = min(lo, it.lo); hi = max(hi, it.hi) }
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val tMax = max(1L, ms.lastOrNull() ?: 1L).toDouble()
        fun x(t: Long) = left + (right - left) * t / tMax
        fun y(v: Double) = bottom - (bottom - top) * (v - lo) / (hi - lo)
        val sb = StringBuilder("<svg viewBox='0 0 $w $h' width='100%' xmlns='http://www.w3.org/2000/svg' font-family='IBM Plex Sans, sans-serif'>")
        for ((a, b) in idleSpans(modes)) sb.append("<rect x='${f(x(ms[a]))}' y='${f(top)}' width='${f(max(x(ms[b]) - x(ms[a]), 1.5))}' height='${f(bottom - top)}' fill='$IDLE'/>")
        norm?.let { sb.append("<rect x='${f(left)}' y='${f(y(it.hi))}' width='${f(right - left)}' height='${f(y(it.lo) - y(it.hi))}' fill='$NORM'/>") }
        sb.append("<line x1='${f(left)}' x2='$w' y1='${f(bottom)}' y2='${f(bottom)}' stroke='$LINE'/>")
        sb.append(text(left + 2, top + 9, "${fmt(lo)}…${fmt(hi)}", 8, INK3))
        // Thin out to ~600 points: enough for A4.
        val idx = values.indices.filter { values[it] != null }
        val step = max(1, idx.size / 600)
        sb.append("<polyline fill='none' stroke='$INK' stroke-width='1.1' stroke-linejoin='round' points='")
        idx.filterIndexed { i, _ -> i % step == 0 || i == idx.lastIndex }.forEach { i -> sb.append("${f(x(ms[i]))},${f(y(values[i]!!))} ") }
        sb.append("'/>")
        val last = values[idx.last()]!!
        val out = norm != null && (last < norm.lo || last > norm.hi)
        sb.append(text(right + 10, h / 2.0 - 4, title, 10, INK))
        sb.append(text(right + 10, h / 2.0 + 10, "${Num.fmt(code, last)} $unit", 11, if (out) AMB else INK, weight = 600))
        start?.let {
            sb.append(text(left, h - 3.0, it.format(HM), 8, INK3))
            sb.append(text(right, h - 3.0, it.plusSeconds((tMax / 1000).toLong()).format(HM), 8, INK3, "end"))
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** Trend of one metric across trips: dots on the norm band, the last one emphasised. */
    fun sparkline(values: List<Double?>, normLo: Double?, normHi: Double?, w: Int = 110, h: Int = 30): String {
        val v = values.mapIndexedNotNull { i, x -> x?.let { i to it } }
        if (v.size < 2) return ""
        var lo = v.minOf { it.second }
        var hi = v.maxOf { it.second }
        normLo?.let { lo = min(lo, it) }
        normHi?.let { hi = max(hi, it) }
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.1
        lo -= pad; hi += pad
        val n = max(1, values.size - 1)
        fun x(i: Int) = 5.0 + (w - 10.0) * i / n
        fun y(x: Double) = h - 3.0 - (h - 6.0) * (x - lo) / (hi - lo)
        val sb = StringBuilder("<svg viewBox='0 0 $w $h' width='$w' height='$h' xmlns='http://www.w3.org/2000/svg'>")
        if (normLo != null || normHi != null) {
            val a = y(normHi ?: hi)
            val b = y(normLo ?: lo)
            sb.append("<rect x='0' y='${f(a)}' width='$w' height='${f(b - a)}' fill='$NORM'/>")
        }
        sb.append("<polyline fill='none' stroke='$INK' stroke-width='1.3' points='${v.joinToString(" ") { "${f(x(it.first))},${f(y(it.second))}" }}'/>")
        v.forEachIndexed { k, (i, x0) -> sb.append("<circle cx='${f(x(i))}' cy='${f(y(x0))}' r='${if (k == v.lastIndex) 3.2 else 2.2}' fill='$INK'/>") }
        sb.append("</svg>")
        return sb.toString()
    }

    /** Forecast: trips as dots, the norm band, the limit as a dashed red line and the projection. */
    fun forecast(fc: TripComparison.Forecast, w: Int = 260, h: Int = 110): String {
        val v = fc.series
        val n = v.size
        val steps = n + 3
        val lo = min(v.min(), -10.0) - 2
        val hi = max(v.max(), fc.limit) + 4
        fun x(i: Double) = 8.0 + (w - 16.0) * i / (steps - 1)
        fun y(x: Double) = h - 6.0 - (h - 12.0) * (x - lo) / (hi - lo)
        val slope = TripComparison.slope(v)
        val sb = StringBuilder("<svg viewBox='0 0 $w $h' width='$w' height='$h' xmlns='http://www.w3.org/2000/svg' font-family='IBM Plex Sans, sans-serif'>")
        if (fc.metric.unit == "%") sb.append("<rect x='0' y='${f(y(10.0))}' width='$w' height='${f(y(-10.0) - y(10.0))}' fill='$NORM'/>")
        sb.append("<line x1='0' x2='$w' y1='${f(y(fc.limit))}' y2='${f(y(fc.limit))}' stroke='$RED' stroke-dasharray='4 3'/>")
        sb.append(text(w - 4.0, y(fc.limit) - 4, fc.limitLabel, 9, RED, "end"))
        sb.append("<line x1='${f(x(n - 1.0))}' y1='${f(y(v.last()))}' x2='${f(x(steps - 1.0))}' y2='${f(y(v.last() + slope * 3))}' stroke='$INK2' stroke-dasharray='3 3'/>")
        sb.append("<polyline fill='none' stroke='$INK' stroke-width='1.5' points='${v.indices.joinToString(" ") { "${f(x(it.toDouble()))},${f(y(v[it]))}" }}'/>")
        v.indices.forEach { sb.append("<circle cx='${f(x(it.toDouble()))}' cy='${f(y(v[it]))}' r='3' fill='$INK'/>") }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun fmt(v: Double): String = when {
        kotlin.math.abs(v) >= 100 -> Math.round(v).toString()
        kotlin.math.abs(v) >= 10 -> f(v)
        else -> String.format(Locale.ROOT, "%.2f", v)
    }.replace("-", "−")
}
