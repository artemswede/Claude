package com.obdlogger.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import com.obdlogger.core.AttentionItem
import com.obdlogger.core.LiveMode
import com.obdlogger.core.NormState
import com.obdlogger.core.Norms
import com.obdlogger.core.SensorNames
import com.obdlogger.core.SeriesStore
import com.obdlogger.core.Values
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Number formatting for live values: 3 significant digits, real minus, «+» on trims. */
object Num {
    fun fmt(code: String, v: Double): String {
        if (v.isNaN()) return "—"
        val a = abs(v)
        val volts = SensorNames.unit(code) == "В"
        val s = when {
            a >= 100 -> Math.round(v).toString()
            volts && a < 10 -> String.format(Locale.ROOT, "%.2f", v)
            else -> String.format(Locale.ROOT, "%.1f", v)
        }.replace("-", "−")
        return if (signed(code) && v > 0) "+$s" else s
    }

    fun signed(code: String) = code.startsWith("trim_b") || code.startsWith("stft") || code.startsWith("ltft") || code == "timing_deg"

    /** «19:51», or «03.10 19:51» when not today. */
    fun clock(ms: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (sameDay) "HH:mm" else "dd.MM HH:mm", Locale.ROOT).format(Date(ms))
    }

    fun clockFull(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(ms))
}

/** Paint helpers in the brand fonts; sizes in sp. */
internal fun View.paint(sizeSp: Float, color: Int, weight: Int = 400, mono: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = sizeSp * resources.displayMetrics.scaledDensityCompat()
    this.color = color
    typeface = if (mono) Bt.mono(context, weight) else Bt.sans(context, weight)
}

@Suppress("DEPRECATION")
internal fun android.util.DisplayMetrics.scaledDensityCompat(): Float = scaledDensity

internal fun ellipsize(s: String, p: Paint, w: Float): String {
    if (p.measureText(s) <= w) return s
    var end = s.length
    if (w <= 0f) return ""
    while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > w) end--
    return s.substring(0, end).trimEnd() + "…"
}

/**
 * Tile of the record panel (Г1): name, CSV code, big value with unit, norm status
 * and a 2-minute sparkline over the norm band. The left stripe is amber when the
 * value is out of norm, green when inside, absent without a norm.
 */
class TileView(ctx: Context, private val p: Bt.Palette) : View(ctx) {
    var code = ""
    private var value = Double.NaN
    private var times = LongArray(0)
    private var values = DoubleArray(0)
    private var mode = LiveMode.OFF
    /** Data of a finished recording: grey value, «последнее значение» and its time. */
    private var staleAt: Long? = null
    private var ageSec = 0L
    var onLongPick: (String) -> Unit = {}

    init {
        setOnLongClickListener { onLongPick(code); true }
        isLongClickable = true
    }

    fun set(code: String, store: SeriesStore, mode: LiveMode, staleAt: Long?, nowMs: Long) {
        this.code = code
        this.mode = mode
        this.staleAt = staleAt
        val end = store.lastTime() ?: 0L
        val s = store.series(code, end - 120_000)
        times = s.first
        values = s.second
        value = store.last(code)
        // Age against the newest row: a slow or silent sensor shows how old its value is.
        ageSec = store.lastTimeOf(code)?.let { (end - it) / 1000 } ?: 0
        contentDescription = SensorNames.label(code)
        invalidate()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 14 * d
        fill.color = p.s1
        c.drawRoundRect(0f, 0f, w, h, r, r, fill)
        fill.color = p.line
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = d
        c.drawRoundRect(d / 2, d / 2, w - d / 2, h - d / 2, r, r, fill)
        fill.style = Paint.Style.FILL

        val front = Norms.isFrontO2(code)
        val norm = Norms.of(code, mode)
        val state = when {
            staleAt != null -> NormState.NONE
            front -> if (Norms.frontO2Switching(values.takeLast(12).toDoubleArray()) == false) NormState.LOW else NormState.IN
            norm != null -> norm.state(value)
            else -> NormState.NONE
        }
        val out = state == NormState.LOW || state == NormState.HIGH
        if (out && staleAt == null) {
            // Out of norm reads at a glance: an amber frame, not only the words under the number.
            fill.color = p.amb
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 2 * d
            c.drawRoundRect(d, d, w - d, h - d, r, r, fill)
            fill.style = Paint.Style.FILL
        }
        if (staleAt == null && (norm != null || front)) {
            fill.color = if (out) p.amb else p.acc
            c.save()
            c.clipRect(0f, 0f, 5 * d, h)
            c.drawRoundRect(0f, 0f, w, h, r, r, fill)
            c.restore()
        }

        val pad = (if (h < 170 * d) 12 else 18) * d
        // Sizes follow the tile, so 8 tiles fit on 1024×600 and on a phone alike.
        val base = min(h / 9.5f, w / 11f)
        val name = paint(0f, p.t1, 500).apply { textSize = base * 0.95f }
        val sub = paint(0f, p.t3, 400, mono = true).apply { textSize = base * 0.62f }
        val big = paint(0f, if (staleAt != null) p.t3 else if (front && !out) p.t2 else p.t1, 400, mono = true).apply { textSize = base * 3.0f }
        val unitP = paint(0f, p.t2, 400).apply { textSize = base * 1.0f }
        val note = paint(0f, if (out) p.amb else p.t3, 400).apply { textSize = base * 0.78f }

        // Short tile (head unit): no code line, and the value never runs into the note below it.
        val short = h < 170 * d
        var y = pad + name.textSize
        c.drawText(ellipsize(SensorNames.label(code), name, w - 2 * pad), pad, y, name)
        if (!short) {
            y += sub.textSize + 4 * d
            c.drawText(ellipsize(SensorNames.source(code), sub, w - 2 * pad), pad, y, sub)
        }
        val noteTop = h - pad - h * 0.12f - 10 * d - note.textSize - 4 * d
        big.textSize = min(big.textSize, ((noteTop - y - 6 * d) / 0.95f).coerceAtLeast(10 * d))

        val v = Num.fmt(code, value)
        val unit = SensorNames.unit(code)
        val uw = if (unit.isEmpty()) 0f else unitP.measureText(unit) + 6 * d
        while (big.measureText(v) > w - 2 * pad - uw && big.textSize > 10 * d) big.textSize *= 0.92f
        y += big.textSize * 0.95f + 6 * d
        c.drawText(v, pad, y, big)
        if (unit.isNotEmpty()) c.drawText(unit, pad + big.measureText(v) + 6 * d, y, unitP)

        val sparkH = h * 0.12f
        val sparkTop = h - pad - sparkH
        val noteY = sparkTop - 10 * d
        if (staleAt != null) {
            c.drawText("последнее значение", pad, noteY, note)
            val tp = paint(0f, p.t3, 400, mono = true).apply { textSize = note.textSize }
            c.drawText(Num.clockFull(staleAt!!), pad, noteY + note.textSize * 1.5f, tp)
            return
        }
        val text = when {
            ageSec > 15 -> "${ageSec} с назад"
            front -> if (out) "не переключается" else "переключается"
            norm != null && out -> "${norm.word(state)} · ${mode.ru}"
            norm != null -> norm.text
            else -> "без нормы · ${mode.ru}"
        }
        c.drawText(ellipsize(text, note, w - 2 * pad), pad, noteY, note)

        // Sparkline with the norm band.
        if (values.size >= 2 && staleAt == null) {
            var lo = values.min()
            var hi = values.max()
            norm?.let { lo = min(lo, it.lo); hi = max(hi, it.hi) }
            if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
            fun yOf(x: Double) = (sparkTop + sparkH * (1 - (x - lo) / (hi - lo))).toFloat()
            norm?.let {
                fill.color = p.accZ
                c.drawRect(pad, yOf(it.hi), w - pad, yOf(it.lo), fill)
            }
            val t0 = times.first()
            val span = (times.last() - t0).coerceAtLeast(1)
            val path = Path()
            values.forEachIndexed { i, x ->
                val px = pad + (w - 2 * pad) * (times[i] - t0) / span
                if (i == 0) path.moveTo(px, yOf(x)) else path.lineTo(px, yOf(x))
            }
            line.color = p.t2
            line.strokeWidth = 1.6f * d
            c.drawPath(path, line)
        }
    }
}

/**
 * Arc gauge of the «Внимание» page (Г3): norm zone in green, outside in amber,
 * a needle at the value, rank badge, status chip, name and note.
 */
class GaugeView(ctx: Context, private val p: Bt.Palette) : View(ctx) {
    private var item: AttentionItem? = null
    private var rank = 0
    private var lo = 0.0
    private var hi = 1.0
    private var moved: String? = null

    fun set(item: AttentionItem, rank: Int, recent: DoubleArray, moved: String?) {
        this.item = item
        this.rank = rank
        this.moved = moved
        val n = item.norm
        var a = if (recent.isEmpty()) item.last else recent.min()
        var b = if (recent.isEmpty()) item.last else recent.max()
        if (n != null) {
            a = min(a, n.lo); b = max(b, n.hi)
            val pad = (n.hi - n.lo) * 0.6
            a = min(a, n.lo - pad); b = max(b, n.hi + pad)
        } else if (Norms.isFrontO2(item.code)) { a = 0.0; b = 1.0 }
        if (b - a < 1e-9) { a -= 1; b += 1 }
        lo = a; hi = b
        contentDescription = SensorNames.label(item.code)
        invalidate()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }

    override fun onDraw(c: Canvas) {
        val it = item ?: return
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val out = (it.score ?: 0.0) > 5 || it.state == NormState.LOW || it.state == NormState.HIGH
        val r = 14 * d
        fill.style = Paint.Style.FILL
        fill.color = p.s1
        c.drawRoundRect(0f, 0f, w, h, r, r, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = if (out) 2 * d else d
        fill.color = if (out) p.amb else p.line
        c.drawRoundRect(fill.strokeWidth / 2, fill.strokeWidth / 2, w - fill.strokeWidth / 2, h - fill.strokeWidth / 2, r, r, fill)
        fill.style = Paint.Style.FILL

        // A short wide card (head unit): gauge on the left, words on the right, instead of a squeezed column.
        val side = h < 190 * d && w > h * 1.5f
        val pad = (if (side) 12 else 16) * d
        val base = if (side) min(h / 8f, w / 20f) else min(h / 12f, w / 14f)
        // The words need more than half of the card.
        val sideR = if (side) max(min((h - 2 * pad) / 1.55f, w * 0.19f), 14 * d) else 0f
        // Left edge of the text column.
        val tx = if (side) pad + 2 * sideR + 16 * d else pad
        // Rank badge and chip.
        val badge = paint(0f, p.bg, 600, mono = true).apply { textSize = base * 0.9f }
        val bt = "№$rank"
        val bw = badge.measureText(bt) + 16 * d
        val bh = badge.textSize + 12 * d
        fill.color = p.t1
        c.drawRoundRect(tx, pad, tx + bw, pad + bh, 6 * d, 6 * d, fill)
        c.drawText(bt, tx + 8 * d, pad + bh - 6 * d - badge.descent() / 2, badge)
        moved?.let { m ->
            val mp = paint(0f, p.acc, 600).apply { textSize = base * 0.8f }
            c.drawText(m, tx + bw + 10 * d, pad + bh - 6 * d, mp)
        }
        val chipText = when {
            it.norm == null && !Norms.isFrontO2(it.code) -> "без нормы"
            out -> "за нормой"
            else -> "в норме"
        }
        val chip = paint(0f, if (out) p.amb else if (it.norm == null && !Norms.isFrontO2(it.code)) p.t2 else p.acc, 600).apply { textSize = base * 0.8f }
        val cw = chip.measureText(chipText) + 20 * d
        // In a narrow text column the chip would sit on the badge; the frame colour says the same.
        if (!side || w - pad - cw > tx + bw + 8 * d) {
            fill.color = if (out) p.ambT else if (it.norm == null && !Norms.isFrontO2(it.code)) p.s2 else p.accT
            c.drawRoundRect(w - pad - cw, pad, w - pad, pad + bh, bh / 2, bh / 2, fill)
            c.drawText(chipText, w - pad - cw + 10 * d, pad + bh - 6 * d - chip.descent() / 2, chip)
        }

        // Arc: 240° sweep starting at 150°.
        val nameP = paint(0f, p.t1, 600).apply { textSize = base * 1.05f }
        val noteP = paint(0f, p.t2).apply { textSize = base * 0.78f }
        val textBlock = nameP.textSize + noteP.textSize + 14 * d
        val top = pad + bh + 8 * d
        val bottom = h - pad - textBlock
        // Never zero or negative: on a short head-unit screen the card can be tiny,
        // and a non-positive radius used to spin the font-fitting loop below forever.
        val radius = if (side) sideR else max(min((bottom - top) / 1.55f, (w - 2 * pad) / 2.4f), 14 * d)
        val cx = if (side) pad + radius else w / 2
        val cy = if (side) pad + radius + 2 * d else top + radius + 4 * d
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val stroke = radius * 0.13f
        arc.strokeWidth = stroke
        fun ang(v: Double) = 150f + 240f * ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
        arc.color = p.s3
        c.drawArc(oval, 150f, 240f, false, arc)
        val n = it.norm
        if (n != null) {
            arc.color = p.amb
            arc.pathEffect = DashPathEffect(floatArrayOf(3 * d, 2.5f * d), 0f)
            c.drawArc(oval, 150f, ang(n.lo) - 150f, false, arc)
            c.drawArc(oval, ang(n.hi), 390f - ang(n.hi), false, arc)
            arc.pathEffect = null
            arc.color = p.acc
            c.drawArc(oval, ang(n.lo), ang(n.hi) - ang(n.lo), false, arc)
        } else if (Norms.isFrontO2(it.code)) {
            arc.color = p.acc
            c.drawArc(oval, ang(0.1), ang(0.9) - ang(0.1), false, arc)
        }
        // Needle.
        val a = Math.toRadians(ang(it.last).toDouble())
        val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.t1; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
        val r1 = radius - stroke * 1.6f
        val r2 = radius + stroke * 0.6f
        c.drawLine(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(), cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), needle)

        val vP = paint(0f, p.t1, 500, mono = true).apply { textSize = radius * 0.42f }
        val v = Num.fmt(it.code, it.last)
        while (vP.measureText(v) > radius * 1.5f && vP.textSize > 6 * d) vP.textSize *= 0.92f
        c.drawText(v, cx - vP.measureText(v) / 2, cy + vP.textSize * 0.3f, vP)
        val uP = paint(0f, p.t2).apply { textSize = radius * 0.17f }
        val u = SensorNames.unit(it.code)
        c.drawText(u, cx - uP.measureText(u) / 2, cy + vP.textSize * 0.3f + uP.textSize * 1.3f, uP)

        val codeP = paint(0f, p.t3, 500, mono = true).apply { textSize = base * 0.7f }
        val name = SensorNames.label(it.code)
        val tw = w - pad - tx
        val ny = h - pad - noteP.textSize - 8 * d
        val nameShown = ellipsize(name, nameP, tw - (if (side) 0f else codeP.measureText(" · ${it.code}")))
        c.drawText(nameShown, tx, ny, nameP)
        // The code line is for the specialist: dropped when the card is short.
        if (!side) c.drawText(" · ${SensorNames.source(it.code)}".let { s -> ellipsize(s, codeP, tw - nameP.measureText(nameShown)) }, tx + nameP.measureText(nameShown), ny, codeP)
        c.drawText(ellipsize(it.note, noteP, tw), tx, h - pad, noteP)
    }
}

/**
 * The «Графики» chart (Г2, макет Claude Design «Графики»). Two modes:
 * - «Дорожки»: one lane per chosen sensor with its own scale, the value and name at the end
 *   of the line, 1–4 lanes a screen, the rest scroll under a fixed time axis;
 * - «Наложение»: one big chart, up to [Series.MAX] sensors, each line on its own range, a
 *   legend of chips (marker, name, value) on top; the scale shown is the chosen chip's.
 * Gestures, each with a button in [ChartBar]: pinch — time zoom, sideways swipe — back in
 * time, tap — cursor with every value at that second, long press on a lane — onto the overlay.
 */
class LanesView(ctx: Context, private val p: Bt.Palette) : View(ctx) {
    private var store: SeriesStore? = null
    private var codes: List<String> = emptyList()
    var windowMs = 5 * 60_000L
        set(v) { field = v.coerceIn(MIN_WINDOW, maxWindow()); panMs = panMs.coerceIn(0L, maxPan()); invalidate() }
    /** How far back from the newest reading the right edge is. */
    var panMs = 0L
        set(v) { field = v; invalidate() }
    /** The second under the cursor; null — no cursor. */
    var cursorT: Long? = null
        set(v) { field = v; invalidate() }
    /** «Наложение»: one big chart of [overlay]. */
    var overlayMode = false
        set(v) { field = v; offset = 0f; invalidate(); onChange() }
    /** Sensors of the overlay, in the order chosen (colour = position). */
    val overlay = ArrayList<String>()
    /** The overlay chip whose scale is shown. */
    var selected = 0
        set(v) { field = v; invalidate() }
    // View switches («⋯ → Вид»).
    var showNorm = true
        set(v) { field = v; invalidate() }
    var showDots = true
        set(v) { field = v; invalidate() }
    var scaleByWindow = false
        set(v) { field = v; invalidate() }
    var showGrid = true
        set(v) { field = v; invalidate() }
    /** Something the bar shows changed (window, pan, overlay, cursor). */
    var onChange: () -> Unit = {}
    /** «+» in the overlay legend: open the sensor list. */
    var onAdd: () -> Unit = {}
    private var live = true
    private var mode = LiveMode.OFF
    /** Lanes visible at once (0 = all); the rest scroll under a fixed time axis. */
    var perScreen = 0
        set(v) { field = v; offset = offset.coerceIn(0f, maxOffset()); invalidate() }
    /** Scroll position in lanes. */
    private var offset = 0f

    private fun lanes(): List<String> = codes
    private fun shown() = if (overlayMode) 1 else if (perScreen <= 0) lanes().size.coerceAtLeast(1) else perScreen
    private fun maxOffset() = if (overlayMode) 0f else (lanes().size - shown()).coerceAtLeast(0).toFloat()
    private fun span(): Long {
        val s = store ?: return 0L
        val first = s.firstTime() ?: return 0L
        return (s.lastTime() ?: first) - first
    }
    /** Up to the whole recording (6 h at most); before any data — no limit yet. */
    private fun maxWindow() = span().let { if (it <= 0) 6 * 3600_000L else maxOf(MIN_WINDOW, minOf(it + 1000, 6 * 3600_000L)) }
    private fun maxPan() = (span() - windowMs).coerceAtLeast(0L)

    /** Series index of [code]: its place in the overlay, else among the lanes; null — 7th and on, no colour. */
    fun seriesOf(code: String): Int? {
        val i = if (overlayMode) overlay.indexOf(code) else codes.indexOf(code)
        return i.takeIf { it in 0 until Series.MAX }
    }

    fun addOverlay(code: String) {
        if (code in overlay || overlay.size >= Series.MAX) return
        overlay += code
        invalidate(); onChange()
    }

    fun removeOverlay(code: String) {
        overlay.remove(code)
        selected = selected.coerceIn(0, (overlay.size - 1).coerceAtLeast(0))
        invalidate(); onChange()
    }

    fun clearOverlay() {
        overlay.clear()
        selected = 0
        invalidate(); onChange()
    }

    fun setOverlay(list: List<String>) {
        overlay.clear()
        overlay.addAll(list.distinct().take(Series.MAX))
        selected = 0
        invalidate(); onChange()
    }

    /** Back to «сейчас» (or the end of the trip), no cursor. */
    fun reset() {
        panMs = 0L
        cursorT = null
        onChange()
    }

    /** Half a window back (−1) or forward (+1). */
    fun shift(dir: Int) {
        panMs = (panMs - dir * windowMs / 2).coerceIn(0L, maxPan())
        onChange()
    }

    val pannedBack get() = panMs > 0

    // ---- gestures ----

    private val density get() = resources.displayMetrics.density
    private fun labelW() = if (overlayMode) 0f else min(124 * density, width * 0.26f)
    private fun plotLeft() = if (overlayMode) 48 * density else 0f
    private fun plotRight() = width - labelW() - if (overlayMode) 8 * density else 12 * density
    private var axisLock = 0 // 0 undecided, 1 horizontal, 2 vertical
    private val chipRects = ArrayList<Pair<android.graphics.RectF, Int>>() // index, -1 = «+»
    private val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: android.view.ScaleGestureDetector): Boolean {
            val prev = det.previousSpanX.coerceAtLeast(1f)
            val cur = det.currentSpanX.coerceAtLeast(1f)
            windowMs = (windowMs * (prev / cur)).toLong()
            onChange()
            return true
        }
    })
    private val gestures = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent): Boolean {
            axisLock = 0
            return true
        }

        override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaler.isInProgress) return true
            if (axisLock == 0) axisLock = if (abs(dx) > abs(dy)) 1 else 2
            if (axisLock == 1) {
                // Finger to the right = further back in time.
                panMs = (panMs - (dx / (plotRight() - plotLeft()).coerceAtLeast(1f) * windowMs).toLong()).coerceIn(0L, maxPan())
                onChange()
            } else if (maxOffset() > 0f) {
                val laneH = (height - 24 * density) / shown()
                offset = (offset + dy / laneH).coerceIn(0f, maxOffset())
                invalidate()
            } else parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }

        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
            // Legend chips: the scale of that series, or «+».
            chipRects.firstOrNull { it.first.contains(e.x, e.y) }?.let { (_, k) ->
                if (k < 0) onAdd() else selected = k
                return true
            }
            val t = timeAt(e.x)
            // A second tap near the cursor takes it away.
            cursorT = if (t == null || (cursorT != null && abs(x(cursorT!!) - e.x) < 24 * density)) null else t
            onChange()
            return true
        }

        override fun onLongPress(e: android.view.MotionEvent) {
            if (overlayMode) return
            val laneH = (height - 24 * density) / shown()
            val k = (e.y / laneH + offset).toInt()
            lanes().getOrNull(k)?.let { addOverlay(it) }
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    })

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
            // Settle on a whole lane so names and scales line up.
            offset = kotlin.math.round(offset).coerceIn(0f, maxOffset())
            invalidate()
        }
        return true
    }

    /** A full-screen copy that follows this one's data (see [ChartBar]). */
    var mirror: LanesView? = null

    fun set(store: SeriesStore, codes: List<String>, live: Boolean, mode: LiveMode) {
        this.store = store
        this.codes = codes
        this.live = live
        this.mode = mode
        offset = offset.coerceIn(0f, maxOffset())
        invalidate()
        mirror?.set(store, codes, live, mode)
    }

    /** Same data and view in [other]: mode, window, pan, cursor, overlay, switches. */
    fun copyTo(other: LanesView) {
        store?.let { other.set(it, codes, live, mode) }
        other.overlay.clear()
        other.overlay.addAll(overlay)
        other.selected = selected
        other.overlayMode = overlayMode
        other.windowMs = windowMs
        other.panMs = panMs
        other.cursorT = cursorT
        other.showNorm = showNorm; other.showDots = showDots; other.scaleByWindow = scaleByWindow; other.showGrid = showGrid
        other.perScreen = perScreen
    }

    /** The sensors that have data here. */
    fun has(code: String) = store?.columns?.contains(code) == true
    fun columns(): List<String> = store?.columns.orEmpty()
    fun last(code: String): Double = store?.last(code) ?: Double.NaN
    /** The rows of the window on screen as CSV («⋯ → Окно в CSV»). */
    fun windowCsv(): String {
        val s = store ?: return ""
        val end = (s.lastTime() ?: return "") - panMs
        val start = end - windowMs
        val cols = (if (overlayMode) overlay else codes).filter { it in s.columns }
        val series = cols.map { s.series(it, start) }
        val times = series.flatMap { it.first.toList() }.filter { it in start..end }.distinct().sorted()
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
        return buildString {
            append("time,").append(cols.joinToString(",")).append('\n')
            for (t in times) {
                append(f.format(Date(t)))
                for ((ts, vs) in series) { append(','); val i = ts.indexOf(t); if (i >= 0 && !vs[i].isNaN()) append(Values.format(vs[i])) }
                append('\n')
            }
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val dashP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var drawStart = 0L
    private var drawLeft = 0f
    private var drawRight = 1f

    private fun x(t: Long) = drawLeft + (drawRight - drawLeft) * ((t - drawStart).toFloat() / windowMs)
    private fun timeAt(px: Float): Long? = if (px < drawLeft || px > drawRight || store?.lastTime() == null) null
        else drawStart + ((px - drawLeft) / (drawRight - drawLeft) * windowMs).toLong()

    /** Index of the last reading at or before [t] (-1 if none). */
    private fun at(t: LongArray, time: Long): Int {
        var lo = 0
        var hi = t.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (t[mid] <= time) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** Range of [code] for its line: its whole recording (stable while panning) or, when chosen, the window. */
    private val rangeCache = HashMap<String, Triple<Int, Double, Double>>()

    private fun rangeOf(s: SeriesStore, code: String, t: LongArray, v: DoubleArray, n: Int, from: Int): Pair<Double, Double> {
        if (!scaleByWindow) rangeCache[code]?.takeIf { it.first == s.size() }?.let { return it.second to it.third }
        val vals = if (scaleByWindow) (from.coerceAtLeast(0) until n).map { v[it] } else s.values(code).toList()
        val sorted = vals.filter { !it.isNaN() }.sorted()
        if (sorted.isEmpty()) return 0.0 to 1.0
        var lo = sorted[(sorted.size * 0.01).toInt()]
        var hi = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)]
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        if (!scaleByWindow) rangeCache[code] = Triple(s.size(), lo, hi)
        return lo to hi
    }

    override fun onDraw(c: Canvas) {
        val s = store ?: return
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val newest = s.lastTime() ?: run {
            val e = paint(16f, p.t3)
            c.drawText("Данных пока нет — графики появятся, когда начнётся запись.", 16 * d, 40 * d, e)
            return
        }
        val end = newest - panMs
        val start = end - windowMs
        drawStart = start
        val axisP = paint(13f, p.t3, 400, mono = true)
        val left = plotLeft()
        val right = plotRight()
        drawLeft = left
        drawRight = right
        val axisH = 24 * d
        val laneH = (h - axisH) / shown()

        // Gaps (ECU silent > 15 s), over all lanes: hatch and «ЭБУ молчал N с».
        val (allT, _) = s.series("rpm".takeIf { it in s.columns } ?: s.columns.first(), start)
        val gapP = paint(13f, p.t2, 600)
        for (i in 1 until allT.size) {
            if (allT[i - 1] > end) break
            if (allT[i] - allT[i - 1] > 15_000) {
                val x1 = x(allT[i - 1]).coerceAtLeast(left); val x2 = min(x(allT[i]), right)
                if (x2 <= x1) continue
                fill.color = p.s2
                c.drawRect(x1, 0f, x2, h - axisH, fill)
                fill.color = p.line
                var hx = x1 - (h - axisH)
                while (hx < x2) {
                    c.save(); c.clipRect(x1, 0f, x2, h - axisH)
                    line.color = p.line; line.strokeWidth = d
                    c.drawLine(hx, h - axisH, hx + (h - axisH), 0f, line)
                    c.restore()
                    hx += 10 * d
                }
                val txt = "ЭБУ молчал ${(allT[i] - allT[i - 1]) / 1000} с"
                if (x2 - x1 > gapP.measureText(txt) + 8 * d) c.drawText(txt, (x1 + x2) / 2 - gapP.measureText(txt) / 2, (h - axisH) / 2, gapP)
            }
        }

        val cur = cursorT?.takeIf { it in start..end }
        chipRects.clear()
        c.save()
        c.clipRect(0f, 0f, w, h - axisH)
        if (overlayMode) drawOverlay(c, s, 0f, h - axisH, start, end, cur, left, right, w)
        else drawLanes(c, s, laneH, h - axisH, start, end, cur, left, right, w)
        c.restore()
        // The cursor: one line through every lane.
        cur?.let {
            fill.color = p.amb
            c.drawRect(x(it) - d, if (overlayMode) 48 * d else 0f, x(it) + d, h - axisH, fill)
        }
        // Where in the list we are: a thin bar at the right edge while there are more lanes than fit.
        if (maxOffset() > 0f) {
            val trackH = h - axisH
            val barH = trackH * shown() / lanes().size
            val barTop = (trackH - barH) * (offset / maxOffset())
            fill.color = p.line2
            c.drawRoundRect(w - 4 * d, barTop, w, barTop + barH, 2 * d, 2 * d, fill)
            val below = (lanes().size - shown() - offset.toInt()).coerceAtLeast(0)
            if (below > 0) {
                val more = "ещё $below ниже ⌄"
                val mp = paint(12f, p.t2, 600)
                val mw = mp.measureText(more) + 16 * d
                fill.color = p.s1
                c.drawRoundRect(right - mw, h - axisH - 22 * d, right, h - axisH - 2 * d, 10 * d, 10 * d, fill)
                c.drawText(more, right - mw + 8 * d, h - axisH - 8 * d, mp)
            }
        }
        // Time axis (fixed under the scrolling lanes).
        fill.color = p.line
        c.drawRect(left, h - axisH, right, h - axisH + d, fill)
        val step = when {
            windowMs <= 60_000 -> 15_000L
            windowMs <= 2 * 60_000 -> 30_000L
            windowMs <= 6 * 60_000 -> 60_000L
            windowMs <= 20 * 60_000 -> 180_000L
            windowMs <= 60 * 60_000 -> 600_000L
            else -> 1_800_000L
        }
        val fmt = SimpleDateFormat(if (windowMs <= 2 * 60_000) "HH:mm:ss" else "HH:mm", Locale.ROOT)
        var tick = (start / step + 1) * step
        while (tick < end - step / 3) {
            val tx = x(tick)
            val lbl = fmt.format(Date(tick))
            c.drawText(lbl, (tx - axisP.measureText(lbl) / 2).coerceAtLeast(left), h - 6 * d, axisP)
            tick += step
        }
        val endLbl = if (live && panMs == 0L) "сейчас" else Num.clock(end)
        c.drawText(endLbl, right - axisP.measureText(endLbl), h - 6 * d, axisP)
        cur?.let {
            val lbl = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(it)) + "  ✕"
            val tw = axisP.measureText(lbl) + 12 * d
            val cx = (x(it) - tw / 2).clamp(left, right - tw)
            fill.color = p.amb
            c.drawRoundRect(cx, h - axisH + 2 * d, cx + tw, h - 2 * d, 6 * d, 6 * d, fill)
            val ink = paint(13f, p.accInk, 600, mono = true)
            c.drawText(lbl, cx + 6 * d, h - 7 * d, ink)
        }
    }

    private fun drawLanes(c: Canvas, s: SeriesStore, laneH: Float, plotBottom: Float, start: Long, end: Long, cur: Long?, left: Float, right: Float, w: Float) {
        val d = density
        val nameP = paint(13f, p.t2)
        val valP = paint(20f, p.t1, 500, mono = true)
        val unitP = paint(13f, p.t2)
        val scaleP = paint(12f, p.t3, 400, mono = true)
        val noP = paint(13f, p.t3)
        lanes().forEachIndexed { k, code ->
            val top = (k - offset) * laneH
            if (top + laneH < 0 || top > plotBottom) return@forEachIndexed
            val bottom = top + laneH
            fill.color = p.line
            c.drawRect(left, bottom - d, w, bottom, fill)
            val (t, v) = s.series(code, start - windowMs)
            val n = at(t, end) + 1
            val i0 = at(t, start).coerceAtLeast(0)
            val norm = Norms.of(code, mode)
            var (lo, hi) = rangeOf(s, code, t, v, n, i0)
            if (showNorm) norm?.let { if (it.lo > -1e6) { lo = min(lo, it.lo); hi = max(hi, it.hi) } }
            val pt = top + laneH * 0.18f
            val pb = bottom - laneH * 0.12f
            fun y(x: Double) = (pb - (pb - pt) * ((x - lo) / (hi - lo)).coerceIn(-0.05, 1.05)).toFloat()
            if (showGrid) { fill.color = p.line; c.drawRect(left, (pt + pb) / 2, right, (pt + pb) / 2 + d / 2, fill) }
            if (showNorm) norm?.let {
                fill.color = p.accZ
                c.drawRect(left, y(it.hi).coerceAtLeast(pt), right, y(it.lo).coerceAtMost(pb), fill)
            }
            c.drawText("${Num.fmt(code, hi)}", left + 6 * d, top + scaleP.textSize + 4 * d, scaleP)
            c.drawText("${Num.fmt(code, lo)}", left + 6 * d, bottom - 5 * d, scaleP)
            val k6 = seriesOf(code)
            val color = k6?.let { Series.color(p, it) } ?: p.t1
            val inWindow = (i0 until n).any { !v[it].isNaN() }
            val lx = right + 12 * d
            c.drawText(ellipsize(SensorNames.label(code), nameP, w - lx - 4 * d), lx, top + nameP.textSize + 6 * d, nameP)
            if (!inWindow) {
                // No data in this window: a dashed frame and the time of the last value.
                dashP.color = p.line2; dashP.strokeWidth = d
                dashP.pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
                c.drawRect(left + 2 * d, top + 4 * d, right - 2 * d, bottom - 4 * d, dashP)
                val lastAt = s.lastTimeOf(code)
                val msg = if (lastAt == null) "нет данных" else "нет данных в окне · последнее ${Num.clock(lastAt)}"
                c.drawText(msg, left + 16 * d, (top + bottom) / 2 + 5 * d, noP)
                c.drawText("—", lx, top + nameP.textSize + valP.textSize + 10 * d, valP)
                return@forEachIndexed
            }
            line.color = color
            line.strokeWidth = 1.8f * d
            val path = Path()
            var started = false
            for (i in i0 until n) {
                if (v[i].isNaN()) { started = false; continue }
                val px = x(t[i]); val py = y(v[i])
                if (!started || (i > 0 && t[i] - t[i - 1] > 15_000)) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            }
            c.drawPath(path, line)
            fill.color = color
            if (showDots && n - i0 < 400) for (i in i0 until n) if (!v[i].isNaN()) c.drawCircle(x(t[i]), y(v[i]), 2f * d, fill)
            val li = cur?.let { at(t, it) }?.takeIf { it >= 0 } ?: (n - 1)
            if (li >= 0 && !v[li].isNaN()) {
                fill.color = color
                if (k6 != null) Series.shape(c, k6, x(t[li]), y(v[li]), 4.5f * d, fill) else c.drawCircle(x(t[li]), y(v[li]), 3.5f * d, fill)
                val vt = Num.fmt(code, v[li])
                val vy = top + nameP.textSize + valP.textSize + 10 * d
                valP.color = p.t1
                c.drawText(vt, lx, vy, valP)
                c.drawText(" " + SensorNames.unit(code), lx + valP.measureText(vt), vy, unitP)
                val st = norm?.state(v[li])
                if (st == NormState.LOW || st == NormState.HIGH) {
                    val ap = paint(12f, p.amb, 600)
                    c.drawText(norm.word(st), lx, vy + 16 * d, ap)
                }
            }
        }
    }

    private fun drawOverlay(c: Canvas, s: SeriesStore, top: Float, bottom: Float, start: Long, end: Long, cur: Long?, left: Float, right: Float, w: Float) {
        val d = density
        // Legend: chips with marker, name and value (at the cursor, else the latest), «+» while there is room.
        val nameP = paint(13f, p.t2)
        val valP = paint(15f, p.t1, 500, mono = true)
        var cx = 0f
        val cy = top + 4 * d
        val chipH = 40 * d
        overlay.forEachIndexed { k, code ->
            val (t, v) = s.series(code, start - windowMs)
            val i = (cur ?: end).let { at(t, it) }
            val value = if (i >= 0 && !v[i].isNaN()) Num.fmt(code, v[i]) else "—"
            val nm = SensorNames.label(code)
            val vt = "$value ${SensorNames.unit(code)}".trim()
            val cw = 26 * d + max(nameP.measureText(nm), valP.measureText(vt)) + 12 * d
            if (cx + cw > w) return@forEachIndexed
            val r = android.graphics.RectF(cx, cy, cx + cw, cy + chipH)
            fill.color = if (k == selected) p.s1 else p.bg
            c.drawRoundRect(r, 10 * d, 10 * d, fill)
            if (k == selected) { line.color = Series.color(p, k); line.strokeWidth = 2 * d; c.drawRoundRect(r, 10 * d, 10 * d, line) }
            fill.color = Series.color(p, k)
            Series.shape(c, k, cx + 13 * d, cy + chipH / 2, 5 * d, fill)
            c.drawText(ellipsize(nm, nameP, cw - 32 * d), cx + 24 * d, cy + 16 * d, nameP)
            c.drawText(vt, cx + 24 * d, cy + 34 * d, valP)
            chipRects += r to k
            cx += cw + 6 * d
        }
        if (overlay.size < Series.MAX && cx + 44 * d <= w) {
            val r = android.graphics.RectF(cx, cy, cx + 44 * d, cy + chipH)
            line.color = p.line2; line.strokeWidth = d
            c.drawRoundRect(r, 10 * d, 10 * d, line)
            val pp = paint(22f, p.acc, 600)
            c.drawText("+", r.centerX() - pp.measureText("+") / 2, r.centerY() + 8 * d, pp)
            chipRects += r to -1
        }
        val pt = top + 56 * d
        val pb = bottom - 8 * d
        if (overlay.isEmpty()) {
            c.drawText("Выберите датчики: «Датчики ⌄» или «+»", left, (pt + pb) / 2, paint(15f, p.t3))
            return
        }
        if (showGrid) {
            fill.color = p.line
            for (f in listOf(0f, 0.5f, 1f)) c.drawRect(left, pb - (pb - pt) * f, right, pb - (pb - pt) * f + d / 2, fill)
        }
        overlay.forEachIndexed { k, code ->
            val (t, v) = s.series(code, start - windowMs)
            val n = at(t, end) + 1
            val i0 = at(t, start).coerceAtLeast(0)
            val (lo, hi) = rangeOf(s, code, t, v, n, i0)
            fun y(x: Double) = (pb - (pb - pt) * ((x - lo) / (hi - lo)).coerceIn(-0.05, 1.05)).toFloat()
            if (k == selected) {
                // The one scale on screen: the chosen series', with its colour bar and its norm.
                val norm = Norms.of(code, mode)
                if (showNorm) norm?.let { nr ->
                    fill.color = p.accZ
                    c.drawRect(left, y(nr.hi).coerceAtLeast(pt), right, y(nr.lo).coerceAtMost(pb), fill)
                }
                fill.color = Series.color(p, k)
                c.drawRect(0f, pt, 3 * d, pb, fill)
                val sp = paint(12f, p.t2, 400, mono = true)
                for (f in listOf(0.0, 0.5, 1.0)) {
                    val value = lo + (hi - lo) * f
                    c.drawText(Num.fmt(code, value), 6 * d, y(value) + 4 * d, sp)
                }
                c.drawText(SensorNames.unit(code), 6 * d, pt - 4 * d, paint(11f, p.t3))
            }
            line.color = Series.color(p, k)
            line.strokeWidth = (if (k == selected) 2.4f else 1.8f) * d
            val path = Path()
            var started = false
            for (i in i0 until n) {
                if (v[i].isNaN()) { started = false; continue }
                if (!started || (i > 0 && t[i] - t[i - 1] > 15_000)) { path.moveTo(x(t[i]), y(v[i])); started = true } else path.lineTo(x(t[i]), y(v[i]))
            }
            c.drawPath(path, line)
            val li = cur?.let { at(t, it) }?.takeIf { it >= 0 } ?: (n - 1)
            if (li >= 0 && li < v.size && !v[li].isNaN()) {
                fill.color = Series.color(p, k)
                Series.shape(c, k, x(t[li]).coerceAtMost(right), y(v[li]), 5 * d, fill)
            }
        }
    }

    companion object {
        const val MIN_WINDOW = 30_000L
    }
}
