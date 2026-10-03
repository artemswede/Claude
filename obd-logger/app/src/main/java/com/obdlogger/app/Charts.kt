package com.obdlogger.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.obdlogger.core.SensorNames
import com.obdlogger.core.SensorStats
import com.obdlogger.core.Values
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Colour tokens from the design (docs: obd-logger-ui, «Токены»). */
object Palette {
    const val BG = 0xFF0E1114.toInt()
    const val PANEL = 0xFF161A1F.toInt()
    const val S2 = 0xFF1E242B.toInt()
    const val GRID = 0xFF2C343D.toInt()
    const val TEXT = 0xFFECEFF2.toInt()
    const val T2 = 0xFFA9B3BD.toInt()
    const val MUTED = 0xFF7E8994.toInt()
    const val ACCENT = 0xFF5AAEFF.toInt()
    const val OK = 0xFF3DBE7A.toInt()
    const val WARN = 0xFFF2B33D.toInt()
    const val BAD = 0xFFFF6259.toInt()
    const val NEU = 0xFF5F6B77.toInt()
    const val MARK = 0xFFB794FF.toInt()
    const val OBS = 0xFF3FD0C9.toInt()
    const val DEMO = 0xFFFF5CC8.toInt()
    val LINES = intArrayOf(
        0xFF5AAEFF.toInt(), 0xFFF2B33D.toInt(), 0xFF3FD0C9.toInt(), 0xFFFF8FA3.toInt(),
        0xFFB794FF.toInt(), 0xFF9BE36B.toInt(), 0xFFFF9F5A.toInt(), 0xFFE6E6E6.toInt(),
    )

    fun lamp(l: Lamp) = when (l) {
        Lamp.OK -> OK
        Lamp.WAIT -> WARN
        Lamp.FAIL -> BAD
        Lamp.OFF -> NEU
    }
}

/** What a jumpy reading of a sensor usually points to. */
object SensorHints {
    fun hint(name: String): String? = when {
        name.startsWith("stft") || name.startsWith("ltft") ->
            "топливные коррекции: подсос воздуха, давление топлива, форсунки, ДМРВ или лямбда-зонд"
        name == "rpm" -> "неровные обороты: пропуски зажигания (свечи, катушки), грязный дроссель/РХХ, подсос воздуха"
        name.startsWith("maf") -> "ДМРВ: загрязнение или неисправность датчика, подсос воздуха после него"
        name.startsWith("map") -> "давление во впуске: подсос, клапаны, датчик MAP"
        name.matches(Regex("o2_b\\ds1_v")) ->
            "передняя лямбда: переключение 0.1–0.9 В — это норма; плохо, если она ровная или вялая"
        name.matches(Regex("o2_b\\ds2_v")) -> "задняя лямбда: если скачет как передняя — катализатор слабый"
        name.startsWith("battery") || name.startsWith("ecu_voltage") -> "напряжение: генератор, регулятор, клеммы, масса"
        name.startsWith("coolant") -> "температура ОЖ: термостат, датчик, воздух в системе"
        name.startsWith("timing") -> "угол опережения: ЭБУ убирает угол из-за детонации (топливо, нагар)"
        name.startsWith("throttle") || name.startsWith("pedal") -> "датчик положения дросселя/педали: износ дорожки"
        name.startsWith("engine_load") -> "нагрузка: следствие оборотов и расхода воздуха"
        name.startsWith("m21_") || name.startsWith("pid01_") -> "нерасшифрованный параметр: смотрите, с чем меняется синхронно"
        else -> null
    }
}

private fun fmt(v: Double) = Values.format(if (abs(v) >= 100) Math.round(v).toDouble() else Math.round(v * 100) / 100.0) ?: "—"

private fun fit(s: String, p: Paint, width: Float): String {
    if (p.measureText(s) <= width) return s
    var end = s.length
    while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > width) end--
    return s.substring(0, end) + "…"
}

/** «19:51», or «03.10 19:51» when not today. */
fun clock(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "dd.MM HH:mm", Locale.US).format(Date(ms))
}

/**
 * Live multi-line chart. Every line is scaled to its own min–max in the window
 * (units differ), so each line carries its own label at its right end: the name,
 * current value and range sit exactly where the line ends.
 */
class LineChartView(context: Context) : View(context) {
    class Line(val code: String, val color: Int, val times: LongArray, val values: DoubleArray)

    var lines: List<Line> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var windowMs = 5 * 60_000L
    var endMs = 0L
    /** False when showing an old recording: the right edge is a past time, not «now». */
    var live = true

    private val dp = resources.displayMetrics.density
    private val grid = Paint().apply { color = Palette.GRID; strokeWidth = dp }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = 11 * dp }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * dp; isFakeBoldText = true }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = 11 * dp }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val leader = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.PANEL)
        val labelW = minOf(250 * dp, width * 0.4f)
        val plot = RectF(8 * dp, 12 * dp, width - labelW - 12 * dp, height - 22 * dp)
        for (i in 0..4) {
            val y = plot.top + plot.height() * i / 4
            canvas.drawLine(plot.left, y, plot.right, y, grid)
        }
        val minutes = (windowMs / 60_000).toInt().coerceAtLeast(1)
        for (m in 0..minutes) {
            val x = plot.right - plot.width() * m / minutes
            canvas.drawLine(x, plot.top, x, plot.bottom, grid)
            val label = when {
                m == 0 && live -> "сейчас"
                m == 0 -> clock(endMs)
                else -> "−$m мин"
            }
            canvas.drawText(label, (x - axis.measureText(label) / 2).coerceIn(plot.left, plot.right - axis.measureText(label)), height - 6 * dp, axis)
        }
        if (lines.isEmpty()) {
            sub.color = Palette.MUTED
            canvas.drawText("Нажмите на датчик во вкладке «Статистика» или «Нестабильность», чтобы добавить линию", 12 * dp, 28 * dp, sub)
            return
        }
        val start = endMs - windowMs

        class Tag(val line: Line, val endX: Float, val endY: Float, var y: Float)
        val tags = mutableListOf<Tag>()
        for (line in lines) {
            val n = line.values.size
            if (n == 0) continue
            var lo = line.values.min()
            var hi = line.values.max()
            if (hi - lo < 1e-9) {
                lo -= 1
                hi += 1
            }
            val path = Path()
            var lastX = 0f
            var lastY = 0f
            for (i in 0 until n) {
                val x = plot.left + plot.width() * ((line.times[i] - start).toFloat() / windowMs)
                val y = plot.bottom - plot.height() * ((line.values[i] - lo) / (hi - lo)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                lastX = x
                lastY = y
            }
            stroke.color = line.color
            canvas.drawPath(path, stroke)
            dot.color = line.color
            canvas.drawCircle(lastX, lastY, 3.5f * dp, dot)
            tags += Tag(line, lastX, lastY, lastY)
        }

        // Labels sit at the height of their line's last point; overlapping ones are pushed apart.
        val tagH = 34 * dp
        val top = plot.top + tagH / 2
        val bottom = plot.bottom - tagH / 2
        tags.sortBy { it.endY }
        for (i in tags.indices) {
            tags[i].y = tags[i].y.coerceIn(top, bottom)
            if (i > 0) tags[i].y = max(tags[i].y, tags[i - 1].y + tagH)
        }
        for (i in tags.indices.reversed()) {
            if (tags[i].y > bottom) tags[i].y = bottom
            if (i < tags.size - 1) tags[i].y = minOf(tags[i].y, tags[i + 1].y - tagH)
        }
        val labelX = plot.right + 12 * dp
        for (tag in tags) {
            val line = tag.line
            leader.color = line.color
            canvas.drawLine(tag.endX, tag.endY, labelX - 3 * dp, tag.y, leader)
            name.color = line.color
            val unit = SensorNames.unit(line.code).let { if (it.isEmpty()) "" else " $it" }
            val value = fmt(line.values.last()) + unit
            val valueW = name.measureText(value)
            val title = fit(SensorNames.label(line.code), name, width - labelX - valueW - 12 * dp)
            canvas.drawText(title, labelX, tag.y - 2 * dp, name)
            canvas.drawText(value, width - valueW - 6 * dp, tag.y - 2 * dp, name)
            canvas.drawText("${fmt(line.values.min())} … ${fmt(line.values.max())}$unit за окно", labelX, tag.y + 12 * dp, sub)
        }
    }
}

/**
 * Two connection lamps — tablet↔ECU and ECU↔engine — plus mode, poll rate and data age.
 * Minimal on purpose: a dot, a short word, nothing to read while driving.
 */
class StatusStrip(context: Context) : View(context) {
    private var s = LoggerState.Snapshot()
    private val dp = resources.displayMetrics.density
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = 11 * dp }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textSize = 14 * dp }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * dp; isFakeBoldText = true }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun update(snapshot: LoggerState.Snapshot) {
        s = snapshot
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (44 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.PANEL)
        fill.color = Palette.GRID
        canvas.drawRect(0f, height - dp, width.toFloat(), height.toFloat(), fill)
        var x = 14 * dp
        x = lamp(canvas, x, "Планшет ↔ ЭБУ", s.link, s.linkText)
        x = lamp(canvas, x + 22 * dp, "ЭБУ ↔ двигатель", s.engine, s.engineText)

        // Right side: mode badge and figures.
        val (mode, color) = when {
            s.demo && s.running -> "ДЕМО" to Palette.DEMO
            s.auto && s.recording -> "АВТО · ЗАПИСЬ" to Palette.OK
            s.auto -> "АВТО · ОЖИДАНИЕ" to Palette.ACCENT
            s.recording -> "ЗАПИСЬ" to Palette.OK
            s.running -> "ПОДКЛЮЧЕНИЕ" to Palette.WARN
            else -> "СТОП" to Palette.NEU
        }
        val info = when {
            s.recording -> "1 стр / %.1f с · %d:%02d · %d строк".format(s.cycleMs / 1000.0, s.elapsedSec / 60, s.elapsedSec % 60, s.rows)
            s.lastDataMs > 0 -> "данные от ${clock(s.lastDataMs)}"
            else -> ""
        }
        val infoW = value.measureText(info)
        val badgeW = badge.measureText(mode) + 16 * dp
        var right = width - 12 * dp
        if (info.isNotEmpty() && right - infoW - badgeW - 12 * dp > x) {
            value.color = Palette.T2
            canvas.drawText(info, right - infoW, height / 2f + 5 * dp, value)
            right -= infoW + 12 * dp
        }
        if (right - badgeW > x) {
            fill.color = color
            fill.alpha = 40
            val r = RectF(right - badgeW, height / 2f - 11 * dp, right, height / 2f + 11 * dp)
            canvas.drawRoundRect(r, 6 * dp, 6 * dp, fill)
            fill.alpha = 255
            badge.color = color
            canvas.drawText(mode, r.left + 8 * dp, height / 2f + 4.5f * dp, badge)
        }
    }

    private fun lamp(canvas: Canvas, x0: Float, label: String, lamp: Lamp, text: String): Float {
        val cy = height / 2f
        fill.color = Palette.lamp(lamp)
        canvas.drawCircle(x0 + 6 * dp, cy, 6 * dp, fill)
        val tx = x0 + 18 * dp
        canvas.drawText(label, tx, cy - 4 * dp, title)
        value.color = if (lamp == Lamp.OFF) Palette.MUTED else Palette.TEXT
        val shown = fit(text, value, 190 * dp)
        canvas.drawText(shown, tx, cy + 13 * dp, value)
        return tx + max(title.measureText(label), value.measureText(shown))
    }
}

/** Distribution of one sensor with min / median / mean / max markers. */
class HistogramView(context: Context) : View(context) {
    private var code = ""
    private var stats: SensorStats? = null
    private var bins: IntArray = IntArray(0)
    private var color = Palette.ACCENT

    private val dp = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textSize = 13 * dp }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = 11 * dp }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * dp }

    fun update(code: String, stats: SensorStats?, bins: IntArray, color: Int) {
        this.code = code
        this.stats = stats
        this.bins = bins
        this.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.PANEL)
        val s = stats
        if (s == null || bins.isEmpty()) {
            canvas.drawText("Нажмите на датчик ниже — здесь появится распределение его значений", 12 * dp, 22 * dp, small)
            return
        }
        val unit = SensorNames.unit(code)
        canvas.drawText(
            fit("${SensorNames.label(code)}, $unit   мин ${fmt(s.min)} · медиана ${fmt(s.median)} · среднее ${fmt(s.mean)} · макс ${fmt(s.max)} · замеров ${s.count}", text, width - 24 * dp),
            12 * dp, 20 * dp, text,
        )
        val plot = RectF(12 * dp, 30 * dp, width - 12 * dp, height - 18 * dp)
        val top = max(1, bins.max())
        val w = plot.width() / bins.size
        bar.color = color
        bins.forEachIndexed { i, c ->
            val h = plot.height() * c / top
            canvas.drawRect(plot.left + i * w + 1, plot.bottom - h, plot.left + (i + 1) * w - 1, plot.bottom, bar)
        }
        canvas.drawText(fmt(s.min), plot.left, height - 4 * dp, small)
        val maxLabel = fmt(s.max)
        canvas.drawText(maxLabel, plot.right - small.measureText(maxLabel), height - 4 * dp, small)
        val span = if (s.max > s.min) s.max - s.min else 1.0
        fun mark(v: Double, c: Int) {
            val x = plot.left + plot.width() * ((v - s.min) / span).toFloat()
            marker.color = c
            canvas.drawLine(x, plot.top, x, plot.bottom, marker)
        }
        mark(s.median, Palette.TEXT)
        mark(s.mean, Palette.WARN)
    }
}

/**
 * Sensor list drawn on one canvas, Russian name with the CSV code under it.
 * STATS: min–max range bar with median (white) and mean (orange) ticks.
 * INSTABILITY: bar of the chosen instability score.
 */
class SensorTableView(context: Context) : View(context) {
    enum class Mode { STATS, INSTABILITY }

    var mode = Mode.STATS
    var sortByCv = false
    var rows: List<SensorStats> = emptyList()
    /** Sensor code → line colour if it is on the chart. */
    var onChart: Map<String, Int> = emptyMap()
    var focused: String? = null
    var onRowClick: (String) -> Unit = {}

    private val dp = resources.displayMetrics.density
    private val rowH = 46 * dp
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textSize = 14 * dp }
    private val code = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = 10 * dp }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.T2; textSize = 12 * dp }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun update(rows: List<SensorStats>) {
        this.rows = if (mode == Mode.INSTABILITY) {
            rows.filter { score(it) != null }.sortedByDescending { score(it) }
        } else rows
        requestLayout()
        invalidate()
    }

    fun score(s: SensorStats): Double? = if (sortByCv) s.cv else s.jitter

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rows.size.coerceAtLeast(1) * rowH).toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            rows.getOrNull((event.y / rowH).toInt())?.let { onRowClick(it.name) }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.BG)
        if (rows.isEmpty()) {
            canvas.drawText("Данных пока нет — начните запись или демо", 12 * dp, 24 * dp, small)
            return
        }
        val nameW = width * 0.36f
        val numW = width * 0.30f
        val barL = nameW + 8 * dp
        val barR = width - numW - 8 * dp
        val maxScore = rows.mapNotNull { score(it) }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        rows.forEachIndexed { i, s ->
            val top = i * rowH
            if (s.name == focused) {
                fill.color = Palette.PANEL
                canvas.drawRect(0f, top, width.toFloat(), top + rowH, fill)
            }
            onChart[s.name]?.let {
                fill.color = it
                canvas.drawRect(0f, top + 8 * dp, 4 * dp, top + rowH - 8 * dp, fill)
            }
            canvas.drawText(fit(SensorNames.label(s.name), name, nameW - 12 * dp), 10 * dp, top + 21 * dp, name)
            canvas.drawText(fit(s.name, code, nameW - 12 * dp), 10 * dp, top + 36 * dp, code)
            val mid = top + rowH / 2
            val unit = SensorNames.unit(s.name).let { if (it.isEmpty()) "" else " $it" }
            when (mode) {
                Mode.STATS -> {
                    fill.color = Palette.GRID
                    canvas.drawRect(barL, mid - 3 * dp, barR, mid + 3 * dp, fill)
                    val span = if (s.max > s.min) s.max - s.min else 1.0
                    fun tick(v: Double, c: Int) {
                        val x = barL + (barR - barL) * ((v - s.min) / span).toFloat()
                        fill.color = c
                        canvas.drawRect(x - 1.5f * dp, mid - 9 * dp, x + 1.5f * dp, mid + 9 * dp, fill)
                    }
                    tick(s.median, Palette.TEXT)
                    tick(s.mean, Palette.WARN)
                    canvas.drawText(fit("${fmt(s.min)} / ${fmt(s.median)} / ${fmt(s.mean)} / ${fmt(s.max)}$unit", small, numW),
                        barR + 8 * dp, mid + 4 * dp, small)
                }
                Mode.INSTABILITY -> {
                    val sc = score(s) ?: 0.0
                    val frac = (sc / maxScore).toFloat().coerceIn(0f, 1f)
                    fill.color = when {
                        frac > 0.66f -> Palette.BAD
                        frac > 0.33f -> Palette.WARN
                        else -> Palette.OK
                    }
                    canvas.drawRect(barL, mid - 6 * dp, barL + (barR - barL) * frac, mid + 6 * dp, fill)
                    canvas.drawText(fit("%.1f %% · сейчас %s%s".format(sc, fmt(s.last), unit), small, numW), barR + 8 * dp, mid + 4 * dp, small)
                }
            }
        }
    }
}
