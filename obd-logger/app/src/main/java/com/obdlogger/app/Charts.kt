package com.obdlogger.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.obdlogger.core.SensorStats
import com.obdlogger.core.Values
import kotlin.math.max

object Palette {
    const val BG = 0xFF0E1116.toInt()
    const val PANEL = 0xFF161B22.toInt()
    const val GRID = 0xFF2A313C.toInt()
    const val TEXT = 0xFFE6EDF3.toInt()
    const val MUTED = 0xFF8B949E.toInt()
    const val ACCENT = 0xFF4FD1C5.toInt()
    const val WARN = 0xFFF6AD55.toInt()
    const val BAD = 0xFFFC8181.toInt()
    val LINES = intArrayOf(
        0xFF4FD1C5.toInt(), 0xFFF6AD55.toInt(), 0xFF63B3ED.toInt(), 0xFFF687B3.toInt(),
        0xFF9AE6B4.toInt(), 0xFFFC8181.toInt(), 0xFFB794F4.toInt(), 0xFFFAF089.toInt(),
    )
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

private fun fmt(v: Double) = Values.format(if (kotlin.math.abs(v) >= 100) Math.round(v).toDouble() else v) ?: "—"

/** Live multi-line chart; every line is scaled to its own min–max in the window (units differ). */
class LineChartView(context: Context) : View(context) {
    class Line(val name: String, val color: Int, val times: LongArray, val values: DoubleArray)

    var lines: List<Line> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var windowMs = 5 * 60_000L
    var endMs = 0L

    private val dp = resources.displayMetrics.density
    private val grid = Paint().apply { color = Palette.GRID; strokeWidth = dp }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = 11 * dp }
    private val legend = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * dp }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.PANEL)
        val legendH = (lines.size.coerceAtLeast(1) + 0) * 18 * dp + 8 * dp
        val plot = RectF(8 * dp, legendH, width - 8 * dp, height - 20 * dp)
        for (i in 0..4) {
            val y = plot.top + plot.height() * i / 4
            canvas.drawLine(plot.left, y, plot.right, y, grid)
        }
        val minutes = (windowMs / 60_000).toInt()
        for (m in 0..minutes) {
            val x = plot.right - plot.width() * m / minutes
            canvas.drawLine(x, plot.top, x, plot.bottom, grid)
            val label = if (m == 0) "сейчас" else "-$m мин"
            canvas.drawText(label, (x - text.measureText(label)).coerceAtLeast(plot.left), height - 6 * dp, text)
        }
        if (lines.isEmpty()) {
            legend.color = Palette.MUTED
            canvas.drawText("Нажмите на датчик в «Статистике» или «Нестабильности», чтобы добавить линию", 12 * dp, 22 * dp, legend)
            return
        }
        val start = endMs - windowMs
        lines.forEachIndexed { li, line ->
            legend.color = line.color
            val n = line.values.size
            if (n == 0) {
                canvas.drawText("${line.name}: нет данных", 12 * dp, (li + 1) * 18 * dp, legend)
                return@forEachIndexed
            }
            var lo = line.values.min()
            var hi = line.values.max()
            if (hi - lo < 1e-9) {
                lo -= 1
                hi += 1
            }
            canvas.drawText("${line.name}: ${fmt(line.values.last())}   (${fmt(line.values.min())} … ${fmt(line.values.max())})",
                12 * dp, (li + 1) * 18 * dp, legend)
            val path = Path()
            for (i in 0 until n) {
                val x = plot.left + plot.width() * ((line.times[i] - start).toFloat() / windowMs)
                val y = plot.bottom - plot.height() * ((line.values[i] - lo) / (hi - lo)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            stroke.color = line.color
            canvas.drawPath(path, stroke)
        }
    }
}

/** Distribution of one sensor with min / median / mean / max markers. */
class HistogramView(context: Context) : View(context) {
    var title = ""
    var stats: SensorStats? = null
    var bins: IntArray = IntArray(0)
    var color = Palette.ACCENT

    private val dp = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textSize = 13 * dp }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = 11 * dp }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * dp }

    fun update(title: String, stats: SensorStats?, bins: IntArray, color: Int) {
        this.title = title
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
        canvas.drawText(
            "$title   мин ${fmt(s.min)} · медиана ${fmt(s.median)} · среднее ${fmt(s.mean)} · макс ${fmt(s.max)} · замеров ${s.count}",
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
 * Sensor list drawn on one canvas. STATS: min–max range bar with median (white)
 * and mean (orange) ticks. INSTABILITY: bar of the chosen instability score.
 */
class SensorTableView(context: Context) : View(context) {
    enum class Mode { STATS, INSTABILITY }

    var mode = Mode.STATS
    var sortByCv = false
    var rows: List<SensorStats> = emptyList()
    /** Sensor name → line colour if it is on the chart. */
    var onChart: Map<String, Int> = emptyMap()
    var focused: String? = null
    var onRowClick: (String) -> Unit = {}

    private val dp = resources.displayMetrics.density
    private val rowH = 40 * dp
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textSize = 13 * dp }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.MUTED; textSize = 11 * dp }
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
        val nameW = width * 0.34f
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
            val label = TextUtilsEllipsize.fit(s.name, name, nameW - 12 * dp)
            canvas.drawText(label, 10 * dp, top + rowH / 2 + 5 * dp, name)
            val mid = top + rowH / 2
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
                    canvas.drawText("${fmt(s.min)} / ${fmt(s.median)} / ${fmt(s.mean)} / ${fmt(s.max)}",
                        barR + 8 * dp, mid + 4 * dp, small)
                }
                Mode.INSTABILITY -> {
                    val sc = score(s) ?: 0.0
                    val frac = (sc / maxScore).toFloat().coerceIn(0f, 1f)
                    fill.color = when {
                        frac > 0.66f -> Palette.BAD
                        frac > 0.33f -> Palette.WARN
                        else -> Palette.ACCENT
                    }
                    canvas.drawRect(barL, mid - 6 * dp, barL + (barR - barL) * frac, mid + 6 * dp, fill)
                    canvas.drawText("%.1f %%   сейчас %s".format(sc, fmt(s.last)), barR + 8 * dp, mid + 4 * dp, small)
                }
            }
        }
    }
}

private object TextUtilsEllipsize {
    fun fit(s: String, p: Paint, width: Float): String {
        if (p.measureText(s) <= width) return s
        var end = s.length
        while (end > 1 && p.measureText(s, 0, end) + p.measureText("…") > width) end--
        return s.substring(0, end) + "…"
    }
}
