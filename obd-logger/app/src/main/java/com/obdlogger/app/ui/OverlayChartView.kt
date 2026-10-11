package com.obdlogger.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.obdlogger.core.SensorNames
import com.obdlogger.core.TripDetail
import com.obdlogger.core.Values

/**
 * One to six sensors of one trip on one time axis («наложение»), each on its own scale (by
 * the values in the window), in its series colour with its marker shape. [range] — the part
 * of the trip in ms from its start (null: all of it); [marks] — moments to mark («провал»),
 * the legend shows the values at the first one. Used in the chat and the trip report.
 */
class OverlayChartView(
    ctx: Context, private val p: Bt.Palette, private val d: TripDetail, private val codes: List<String>,
    private val range: LongRange? = null,
    private val marks: List<Pair<Long, String>> = emptyList(),
) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onDraw(c: Canvas) {
        val dens = resources.displayMetrics.density
        // Inside the card's padding.
        c.save()
        c.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        draw(c, dens, (width - paddingLeft - paddingRight).toFloat(), (height - paddingTop - paddingBottom).toFloat())
        c.restore()
    }

    /** Index of the last row at or before [t] ms. */
    private fun at(ms: List<Long>, t: Long): Int {
        var lo = 0
        var hi = ms.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (ms[mid] <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    private fun draw(c: Canvas, dens: Float, w: Float, h: Float) {
        val ms = d.table.ms
        if (ms.size < 2) return
        val from = range?.first ?: 0L
        val to = range?.last ?: ms.last().coerceAtLeast(1L)
        val span = (to - from).coerceAtLeast(1L).toFloat()
        val i0 = at(ms, from)
        val i1 = at(ms, to)
        val main = marks.firstOrNull()?.first
        text.typeface = Bt.sans(context, 600)
        text.textSize = 12 * dens
        // Legend: marker, name, the value at the first mark (or at the end of the window), unit.
        var lx = 0f
        var ly = 14 * dens
        val series = codes.map { d.series(it) }
        codes.forEachIndexed { k, code ->
            val s = series[k]
            val vi = (if (main != null) at(ms, main) else i1).let { idx -> (idx downTo i0).firstOrNull { s.getOrNull(it) != null } }
            val value = vi?.let { s[it] }?.let { Num.fmt(code, it) } ?: "—"
            val label = "${SensorNames.label(code)} $value ${SensorNames.unit(code)}".trim()
            val lw = text.measureText(label) + 26 * dens
            if (lx > 0 && lx + lw > w) { lx = 0f; ly += 17 * dens }
            fill.color = Series.color(p, k)
            Series.shape(c, k, lx + 5 * dens, ly - 4 * dens, 4.5f * dens, fill)
            text.color = p.t1
            c.drawText(label, lx + 13 * dens, ly, text)
            lx += lw
        }
        val markBand = if (marks.isEmpty()) 0f else 16 * dens
        val top = ly + 8 * dens + markBand
        val bottom = h - 18 * dens
        if (bottom - top < 20 * dens) return
        fun x(t: Long) = w * ((t - from) / span)
        codes.forEachIndexed { k, _ ->
            val s = series[k]
            val v = (i0..i1).mapNotNull { s.getOrNull(it) }.sorted()
            if (v.size < 2) return@forEachIndexed
            var lo = v[(v.size * 0.02).toInt()]
            var hi = v[(v.size * 0.98).toInt().coerceAtMost(v.size - 1)]
            if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
            fun y(x: Double) = (bottom - (bottom - top) * ((x - lo) / (hi - lo)).coerceIn(-0.05, 1.05)).toFloat()
            val path = Path()
            var started = false
            for (i in i0..i1) {
                val x0 = s.getOrNull(i)
                // An ECU silence longer than 15 s breaks the line.
                if (x0 == null || (started && ms[i] - ms[(i - 1).coerceAtLeast(0)] > 15_000)) { started = false; if (x0 == null) continue }
                if (!started) { path.moveTo(x(ms[i]), y(x0)); started = true } else path.lineTo(x(ms[i]), y(x0))
            }
            line.color = Series.color(p, k)
            line.strokeWidth = (if (k == 0) 2.2f else 1.8f) * dens
            c.drawPath(path, line)
            main?.let { m ->
                val mi = at(ms, m)
                s.getOrNull(mi)?.let { val yy = y(it); fill.color = Series.color(p, k); Series.shape(c, k, x(ms[mi]), yy, 4.5f * dens, fill) }
            }
        }
        // Marks: a dashed line, the first one on a dark label, the others on light ones.
        dash.strokeWidth = 1.5f * dens
        dash.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * dens, 4 * dens), 0f)
        text.textSize = 11 * dens
        marks.forEachIndexed { n, (t, label) ->
            if (t < from || t > to) return@forEachIndexed
            val mx = x(t)
            dash.color = if (n == 0) p.t1 else p.t3
            c.drawLine(mx, top - 2 * dens, mx, bottom, dash)
            val tt = d.summary.start?.plusNanos(t * 1_000_000)?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")).orEmpty()
            val lbl = listOf(tt, label).filter { it.isNotBlank() }.joinToString(" · ")
            val tw = text.measureText(lbl) + 10 * dens
            val bx = (mx - tw / 2).clamp(0f, w - tw)
            val by = top - markBand - 4 * dens
            fill.color = if (n == 0) p.t1 else p.s2
            c.drawRoundRect(bx, by, bx + tw, by + 15 * dens, 4 * dens, 4 * dens, fill)
            text.color = if (n == 0) p.bg else p.t1
            c.drawText(lbl, bx + 5 * dens, by + 11 * dens, text)
        }
        // Time axis: start, middle, end of the window.
        text.typeface = Bt.mono(context, 400)
        text.textSize = 11 * dens
        text.color = p.t3
        val start = d.summary.start
        val fmt = java.time.format.DateTimeFormatter.ofPattern(if (to - from <= 3 * 60_000) "HH:mm:ss" else "HH:mm")
        for (f in listOf(0f, 0.5f, 1f)) {
            val t = from + (span * f).toLong()
            val lbl = start?.plusNanos(t * 1_000_000)?.format(fmt) ?: "${t / 60_000} мин"
            val tw = text.measureText(lbl)
            c.drawText(lbl, (w * f - tw / 2).clamp(0f, w - tw), h - 4 * dens, text)
        }
    }
}

/**
 * The same overlay on live data: the last [windowMs] of the recording from [store],
 * redrawn every second while on screen; «запись не идёт» when nothing arrives.
 */
class LiveOverlayView(ctx: Context, private val p: Bt.Palette, private val store: com.obdlogger.core.SeriesStore,
    private val codes: List<String>, private val recording: () -> Boolean, private val windowMs: Long = 5 * 60_000L) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun color(k: Int) = Series.color(p, k)
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val dens = resources.displayMetrics.density
        // Inside the card's padding.
        c.save()
        c.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        draw(c, dens, w, h)
        c.restore()
    }

    private fun draw(c: Canvas, dens: Float, w: Float, h: Float) {
        val end = store.lastTime()
        text.typeface = Bt.sans(context, 600)
        text.textSize = 12 * dens
        val live = recording()
        text.color = if (live) p.red else p.t3
        val status = if (live) "● сейчас · последние ${windowMs / 60_000} мин" else "запись не идёт — последние данные"
        c.drawText(status, 0f, 14 * dens, text)
        if (end == null) return
        val from = end - windowMs
        val series = codes.filter { it in store.columns }.map { it to store.series(it, from) }
        var lx = 0f
        var ly = 30 * dens
        series.forEachIndexed { k, (code, s) ->
            val last = s.second.lastOrNull { !it.isNaN() }
            val label = "● ${SensorNames.label(code)} ${last?.let { Num.fmt(code, it) } ?: "—"} ${SensorNames.unit(code)}".trim()
            val lw = text.measureText(label) + 14 * dens
            if (lx > 0 && lx + lw > w) { lx = 0f; ly += 16 * dens }
            text.color = color(k)
            c.drawText(label, lx, ly, text)
            lx += lw
        }
        val top = ly + 10 * dens
        val bottom = h - 18 * dens
        if (bottom - top < 20 * dens) return
        series.forEachIndexed { k, (_, s) ->
            val (ts, vs) = s
            val v = vs.filter { !it.isNaN() }.sorted()
            if (v.size < 2) return@forEachIndexed
            var lo = v.first()
            var hi = v.last()
            if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
            val path = Path()
            var started = false
            for (i in ts.indices) {
                if (vs[i].isNaN()) { started = false; continue }
                val x = w * ((ts[i] - from).toFloat() / windowMs)
                val y = (bottom - (bottom - top) * ((vs[i] - lo) / (hi - lo))).toFloat()
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            line.color = color(k)
            line.strokeWidth = (if (k == 0) 2f else 1.6f) * dens
            c.drawPath(path, line)
        }
        text.typeface = Bt.mono(context, 400)
        text.textSize = 11 * dens
        text.color = p.t3
        for ((f, lbl) in listOf(0f to "−${windowMs / 60_000} мин", 1f to "сейчас")) {
            val tw = text.measureText(lbl)
            c.drawText(lbl, (w * f - tw / 2).clamp(0f, w - tw), h - 4 * dens, text)
        }
    }
}
