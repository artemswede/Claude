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
 * Two to four sensors of one trip on one time axis («наложение»), each on its own scale
 * (5–95 % of its values), in its own colour, named with its range at the top — to see
 * what moves together and what comes first.
 */
class OverlayChartView(ctx: Context, private val p: Bt.Palette, private val d: TripDetail, private val codes: List<String>) : View(ctx) {
    private val colors = listOf(p.chart, p.acc, p.amb, p.red)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val dens = resources.displayMetrics.density
        // Inside the card's padding.
        c.save()
        c.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        draw(c, dens, (width - paddingLeft - paddingRight).toFloat(), (height - paddingTop - paddingBottom).toFloat())
        c.restore()
    }

    private fun draw(c: Canvas, dens: Float, w: Float, h: Float) {
        val ms = d.table.ms
        if (ms.size < 2) return
        val tMax = ms.last().coerceAtLeast(1L).toFloat()
        text.typeface = Bt.sans(context, 600)
        text.textSize = 12 * dens
        // Legend: «● Коррекция Б1 −5…+28 %», wrapping onto a second line when needed.
        var lx = 0f
        var ly = 14 * dens
        val series = codes.map { d.series(it) }
        codes.forEachIndexed { k, code ->
            val v = series[k].filterNotNull().sorted()
            if (v.isEmpty()) return@forEachIndexed
            val lo = v[(v.size * 0.05).toInt()]
            val hi = v[(v.size * 0.95).toInt().coerceAtMost(v.size - 1)]
            val label = "● ${SensorNames.label(code)} ${Values.format(lo)}…${Values.format(hi)} ${SensorNames.unit(code)}".trim()
            val lw = text.measureText(label) + 14 * dens
            if (lx > 0 && lx + lw > w) { lx = 0f; ly += 16 * dens }
            text.color = colors[k % colors.size]
            c.drawText(label, lx, ly, text)
            lx += lw
        }
        val top = ly + 10 * dens
        val bottom = h - 18 * dens
        if (bottom - top < 20 * dens) return
        fun x(i: Int) = w * (ms[i] / tMax)
        codes.forEachIndexed { k, _ ->
            val s = series[k]
            val v = s.filterNotNull().sorted()
            if (v.size < 2) return@forEachIndexed
            var lo = v[(v.size * 0.05).toInt()]
            var hi = v[(v.size * 0.95).toInt().coerceAtMost(v.size - 1)]
            if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
            fun y(x: Double) = (bottom - (bottom - top) * ((x - lo) / (hi - lo)).coerceIn(-0.05, 1.05)).toFloat()
            val path = Path()
            var started = false
            for (i in s.indices) {
                val x0 = s[i] ?: run { started = false; null } ?: continue
                if (!started) { path.moveTo(x(i), y(x0)); started = true } else path.lineTo(x(i), y(x0))
            }
            line.color = colors[k % colors.size]
            line.strokeWidth = (if (k == 0) 2f else 1.6f) * dens
            c.drawPath(path, line)
        }
        // Time axis: start, middle, end.
        text.typeface = Bt.mono(context, 400)
        text.textSize = 11 * dens
        text.color = p.t3
        val start = d.summary.start
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        for (f in listOf(0f, 0.5f, 1f)) {
            val lbl = start?.plusSeconds((tMax * f / 1000).toLong())?.format(fmt) ?: "${(tMax * f / 60000).toInt()} мин"
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
    private val colors = listOf(p.chart, p.acc, p.amb, p.red)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
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
            text.color = colors[k % colors.size]
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
            line.color = colors[k % colors.size]
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
