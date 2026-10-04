package com.obdlogger.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.obdlogger.core.DriveMode
import com.obdlogger.core.TripTrace
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * «Коррекция смеси за поездку» (Д1): one clean line, norm band ±10 %, warm-idle
 * bands underneath, the out-of-norm part in amber, and the line's name and value
 * written at its end (no separate legend).
 */
class TrimChartView(ctx: Context) : View(ctx) {
    private var trace: TripTrace? = null
    private var p: Bt.Palette = Bt.LIGHT
    private var sc: Bt.Scale = Bt.TABLET
    private var dim = false
    var endName = "Коррекция Б1"

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(trace: TripTrace?, palette: Bt.Palette, scale: Bt.Scale, dimmed: Boolean) {
        this.trace = trace
        p = palette
        sc = scale
        dim = dimmed
        alpha = if (dimmed) 0.5f else 1f
        invalidate()
    }

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensityCompat()

    override fun onDraw(canvas: Canvas) {
        val t = trace
        val axisW = sp(sc.axis) * 2.6f
        val endW = sp(sc.endLbl) * 7.5f
        val top = sp(sc.lbl) * 1.8f
        val bottom = height - sp(sc.axis) * 1.9f
        val plot = RectF(axisW, top, width - endW, bottom)
        if (plot.width() <= 0 || plot.height() <= 0) return

        val pts = t?.let { tr -> tr.minutes.indices.filter { !tr.trimB1[it].isNaN() } } ?: emptyList()
        // Display only: a short moving average takes out the STFT flicker; the norm crossing stays where it is.
        val smooth = DoubleArray(t?.trimB1?.size ?: 0) { Double.NaN }
        if (t != null) pts.forEachIndexed { k, i ->
            val win = pts.subList(maxOf(0, k - 2), minOf(pts.size, k + 3))
            smooth[i] = win.sumOf { t.trimB1[it] } / win.size
        }
        var lo = -15.0
        var hi = 30.0
        if (t != null && pts.isNotEmpty()) {
            lo = min(lo, pts.minOf { t.trimB1[it] } - 2)
            hi = max(hi, pts.maxOf { t.trimB1[it] } + 2)
        }
        val tMax = max(1.0, t?.minutes?.lastOrNull() ?: 1.0)
        fun x(m: Double) = (plot.left + plot.width() * (m / tMax)).toFloat()
        fun y(v: Double) = (plot.bottom - plot.height() * ((v - lo) / (hi - lo))).toFloat()

        // Warm-idle bands with their mode label on top (only if there is room for the word).
        text.typeface = Bt.sans(context, 600)
        text.textSize = sp(sc.lbl) * 0.92f
        text.letterSpacing = 0.08f
        if (t != null) {
            var i = 0
            while (i < t.modes.size) {
                val m = t.modes[i]
                var j = i
                while (j + 1 < t.modes.size && t.modes[j + 1] == m) j++
                if (m == DriveMode.WARM_IDLE || m == DriveMode.ROLL_TO_STOP) {
                    val x0 = x(t.minutes[i])
                    val x1 = max(x(t.minutes[j]), x0 + dp(2))
                    if (m == DriveMode.WARM_IDLE) {
                        fill.color = p.s2
                        canvas.drawRect(x0, plot.top, x1, plot.bottom, fill)
                        val word = "ХОЛОСТОЙ"
                        if (x1 - x0 > text.measureText(word) + dp(8)) {
                            text.color = p.t3
                            canvas.drawText(word, x0 + dp(4), plot.top - dp(6), text)
                        }
                    }
                }
                i = j + 1
            }
        }

        // Norm band and zero line.
        fill.color = p.accZ
        canvas.drawRect(plot.left, y(10.0), plot.right, y(-10.0), fill)
        stroke.pathEffect = null
        stroke.color = p.line2
        stroke.strokeWidth = dp(1).toFloat()
        canvas.drawLine(plot.left, y(0.0), plot.right, y(0.0), stroke)

        // Axes.
        text.typeface = Bt.sans(context, 400)
        text.letterSpacing = 0f
        text.textSize = sp(sc.axis)
        text.color = p.t3
        for (v in listOf(30.0, 10.0, 0.0, -10.0)) {
            if (v < lo || v > hi) continue
            val label = when {
                v > 0 -> "+${v.toInt()}"
                v < 0 -> "−${(-v).toInt()}"
                else -> "0"
            }
            canvas.drawText(label, plot.left - text.measureText(label) - dp(8), y(v) + text.textSize * 0.35f, text)
        }
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        val start = t?.start
        if (start != null) {
            for (k in 0..4) {
                val m = tMax * k / 4
                val label = start.plusSeconds((m * 60).toLong()).format(clock)
                val w = text.measureText(label)
                val cx = (x(m) - w / 2).coerceIn(plot.left - dp(4), plot.right - w)
                canvas.drawText(label, cx, height - dp(4).toFloat(), text)
            }
        }
        if (t == null || pts.isEmpty()) return

        // The line, then the out-of-norm parts over it.
        val line = Path()
        pts.forEachIndexed { k, i -> if (k == 0) line.moveTo(x(t.minutes[i]), y(smooth[i])) else line.lineTo(x(t.minutes[i]), y(smooth[i])) }
        stroke.color = p.chart
        stroke.strokeWidth = dp(2).toFloat()
        canvas.drawPath(line, stroke)

        var k = 0
        while (k < pts.size) {
            if (smooth[pts[k]] <= 10) {
                k++
                continue
            }
            var e = k
            while (e + 1 < pts.size && smooth[pts[e + 1]] > 10) e++
            val dev = Path()
            val area = Path()
            area.moveTo(x(t.minutes[pts[k]]), y(10.0))
            for (q in k..e) {
                val px = x(t.minutes[pts[q]])
                val py = y(smooth[pts[q]])
                if (q == k) dev.moveTo(px, py) else dev.lineTo(px, py)
                area.lineTo(px, py)
            }
            area.lineTo(x(t.minutes[pts[e]]), y(10.0))
            area.close()
            fill.color = p.ambZ
            canvas.drawPath(area, fill)
            stroke.color = p.amb
            stroke.strokeWidth = dp(2.5).toFloat()
            canvas.drawPath(dev, stroke)
            k = e + 1
        }

        // Name and value at the end of the line, at its height.
        val last = pts.last()
        val lv = smooth[last]
        val ly = y(lv).coerceIn(plot.top + sp(sc.endLbl), plot.bottom - sp(sc.endLbl))
        text.typeface = Bt.sans(context, 400)
        text.textSize = sp(sc.endLbl)
        text.color = p.t1
        val lx = plot.right + dp(10)
        canvas.drawText(endName, lx, ly - dp(2), text)
        text.typeface = Bt.mono(context, 500)
        text.color = if (lv > 10) p.amb else p.t1
        val value = (if (lv < 0) "−" else "+") + String.format(java.util.Locale.ROOT, "%.1f", kotlin.math.abs(lv)) + " %"
        canvas.drawText(value, lx, ly + text.textSize, text)
    }
}
