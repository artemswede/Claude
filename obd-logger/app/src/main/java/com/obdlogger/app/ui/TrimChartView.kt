package com.obdlogger.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.obdlogger.core.DriveMode
import com.obdlogger.core.LiveMode
import com.obdlogger.core.Norm
import com.obdlogger.core.Norms
import com.obdlogger.core.SensorNames
import com.obdlogger.core.TripTrace
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * The main chart of a trip (Д1, В2): one sensor — the one the current version is
 * about (mixture, rpm, voltage, coolant…) — as one clean line over its norm band,
 * warm-idle bands underneath, the out-of-norm parts in amber, and the name and
 * value written at the line's end (no separate legend).
 */
class TrimChartView(ctx: Context) : View(ctx) {
    private var trace: TripTrace? = null
    private var code = "trim_b1"
    private var p: Bt.Palette = Bt.LIGHT
    private var sc: Bt.Scale = Bt.TABLET
    private var dim = false

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(trace: TripTrace?, palette: Bt.Palette, scale: Bt.Scale, dimmed: Boolean, code: String = "trim_b1") {
        this.trace = trace
        this.code = code
        p = palette
        sc = scale
        dim = dimmed
        alpha = if (dimmed) 0.5f else 1f
        contentDescription = SensorNames.label(code)
        invalidate()
    }

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensityCompat()

    private fun values(t: TripTrace): DoubleArray = t.of(code) ?: if (code == "trim_b1") t.trimB1 else DoubleArray(t.minutes.size) { Double.NaN }

    override fun onDraw(canvas: Canvas) {
        val t = trace
        val axisW = sp(sc.axis) * 3.2f
        val endW = sp(sc.endLbl) * 7.5f
        val top = sp(sc.lbl) * 1.8f
        val bottom = height - sp(sc.axis) * 1.9f
        val plot = RectF(axisW, top, width - endW, bottom)
        if (plot.width() <= 0 || plot.height() <= 0) return

        val norm: Norm? = Norms.of(code, LiveMode.IDLE)
        val raw = t?.let(::values) ?: DoubleArray(0)
        val pts = raw.indices.filter { !raw[it].isNaN() }
        // Display only: a short moving average takes out sensor flicker; the norm crossing stays where it is.
        val v = DoubleArray(raw.size) { Double.NaN }
        pts.forEachIndexed { k, i ->
            // Only the trims flicker; rpm dips must stay visible.
            val r = if (code.startsWith("trim")) 2 else 0
            val win = pts.subList(max(0, k - r), min(pts.size, k + r + 1))
            v[i] = win.sumOf { raw[it] } / win.size
        }
        var lo = pts.minOfOrNull { v[it] } ?: 0.0
        var hi = pts.maxOfOrNull { v[it] } ?: 1.0
        norm?.let { lo = min(lo, it.lo); hi = max(hi, it.hi) }
        if (code.startsWith("trim")) { lo = min(lo, -15.0); hi = max(hi, 30.0) }
        val pad = (hi - lo).coerceAtLeast(1e-6) * 0.06
        lo -= pad; hi += pad
        val tMax = max(1.0, t?.minutes?.lastOrNull() ?: 1.0)
        fun x(m: Double) = (plot.left + plot.width() * (m / tMax)).toFloat()
        fun y(x: Double) = (plot.bottom - plot.height() * ((x - lo) / (hi - lo))).toFloat()

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
                if (m == DriveMode.WARM_IDLE) {
                    val x0 = x(t.minutes[i])
                    val x1 = max(x(t.minutes[j]), x0 + dp(2))
                    fill.color = p.s2
                    canvas.drawRect(x0, plot.top, x1, plot.bottom, fill)
                    val word = "ХОЛОСТОЙ"
                    if (x1 - x0 > text.measureText(word) + dp(8)) {
                        text.color = p.t3
                        canvas.drawText(word, x0 + dp(4), plot.top - dp(6), text)
                    }
                }
                i = j + 1
            }
        }

        // Norm band, and its edges as axis labels.
        text.typeface = Bt.sans(context, 400)
        text.letterSpacing = 0f
        text.textSize = sp(sc.axis)
        text.color = p.t3
        if (norm != null) {
            fill.color = p.accZ
            canvas.drawRect(plot.left, y(norm.hi), plot.right, y(norm.lo), fill)
        }
        if (code.startsWith("trim")) {
            stroke.pathEffect = null
            stroke.color = p.line2
            stroke.strokeWidth = dp(1).toFloat()
            canvas.drawLine(plot.left, y(0.0), plot.right, y(0.0), stroke)
        }
        val ticks = (listOfNotNull(norm?.lo, norm?.hi) + if (code.startsWith("trim")) listOf(0.0, 30.0) else listOf(lo + pad, hi - pad)).distinct()
        var lastY = Float.MAX_VALUE
        for (tv in ticks.sortedBy { y(it) }) {
            if (tv < lo || tv > hi) continue
            val ty = y(tv)
            if (lastY != Float.MAX_VALUE && kotlin.math.abs(ty - lastY) < text.textSize * 1.2f) continue
            lastY = ty
            val label = Num.fmt(code, tv).removeSuffix(".0").removeSuffix(".00")
            canvas.drawText(label, plot.left - text.measureText(label) - dp(8), ty + text.textSize * 0.35f, text)
        }
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        t?.start?.let { start ->
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
        pts.forEachIndexed { k, i -> if (k == 0) line.moveTo(x(t.minutes[i]), y(v[i])) else line.lineTo(x(t.minutes[i]), y(v[i])) }
        stroke.color = p.chart
        stroke.strokeWidth = dp(2).toFloat()
        canvas.drawPath(line, stroke)

        if (norm != null) {
            // A norm that exists only for warm idle (rpm) is not broken by driving at 3000 rpm.
            val idleOnly = Norms.of(code, LiveMode.DRIVE) == null
            for (above in listOf(true, false)) {
                val edge = if (above) norm.hi else norm.lo
                fun out(i: Int) = (!idleOnly || t.modes.getOrNull(i) == DriveMode.WARM_IDLE) && (if (above) v[i] > edge else v[i] < edge)
                var k = 0
                while (k < pts.size) {
                    if (!out(pts[k])) { k++; continue }
                    var e = k
                    while (e + 1 < pts.size && out(pts[e + 1])) e++
                    val dev = Path()
                    val area = Path()
                    area.moveTo(x(t.minutes[pts[k]]), y(edge))
                    for (q in k..e) {
                        val px = x(t.minutes[pts[q]])
                        val py = y(v[pts[q]])
                        if (q == k) dev.moveTo(px, py) else dev.lineTo(px, py)
                        area.lineTo(px, py)
                    }
                    area.lineTo(x(t.minutes[pts[e]]), y(edge))
                    area.close()
                    fill.color = p.ambZ
                    canvas.drawPath(area, fill)
                    stroke.color = p.amb
                    stroke.strokeWidth = dp(2.5).toFloat()
                    canvas.drawPath(dev, stroke)
                    k = e + 1
                }
            }
        }

        // Name and value at the end of the line, at its height.
        val lv = v[pts.last()]
        val ly = y(lv).coerceIn(plot.top + sp(sc.endLbl), plot.bottom - sp(sc.endLbl))
        text.typeface = Bt.sans(context, 400)
        text.textSize = sp(sc.endLbl)
        text.color = p.t1
        val lx = plot.right + dp(10)
        canvas.drawText(ellipsize(SensorNames.label(code), text, width - lx), lx, ly - dp(2), text)
        text.typeface = Bt.mono(context, 500)
        val lastIdleOk = Norms.of(code, LiveMode.DRIVE) != null || t.modes.getOrNull(pts.last()) == DriveMode.WARM_IDLE
        text.color = if (norm != null && lastIdleOk && (lv > norm.hi || lv < norm.lo)) p.amb else p.t1
        val unit = SensorNames.unit(code).let { if (it.isEmpty()) "" else " $it" }
        canvas.drawText(Num.fmt(code, lv) + unit, lx, ly + text.textSize, text)
    }
}
