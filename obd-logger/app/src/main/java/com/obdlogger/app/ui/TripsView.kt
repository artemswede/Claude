package com.obdlogger.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.obdlogger.core.DriveMode
import com.obdlogger.core.HomeLogic
import com.obdlogger.core.TripComparison
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Verdict of a trip in one chip: text, ink, background. */
class TripVerdict(val text: String, val fg: Int, val bg: Int) {
    companion object {
        fun of(it: TripItem, p: Bt.Palette): TripVerdict {
            val s = it.summary
            val top = s.top
            return when {
                it.isCheck -> TripVerdict("Проверочный лог", p.t1, p.s3)
                !s.dtcs.isNullOrEmpty() -> TripVerdict("Коды: ${s.dtcs!!.joinToString(", ")}", p.red, p.redT)
                top != null && s.durationMin >= HomeLogic.NEED_TRIP_MIN -> TripVerdict("▲ Есть версия: ${top.headline.replaceFirstChar { c -> c.lowercase() }}", p.amb, p.ambT)
                s.durationMin < HomeLogic.NEED_TRIP_MIN -> TripVerdict("Мало данных: короткая", p.t2, p.s3)
                s.warmIdleSec < HomeLogic.NEED_IDLE_SEC -> TripVerdict("Мало данных: ХХ ${(s.warmIdleSec / 60).toInt()} мин", p.t2, p.s3)
                else -> TripVerdict("Отклонений нет", p.acc, p.accT)
            }
        }
    }
}

/** Horizontal bar of a trip's modes: warm-up, driving, warm idle (amber when the version is on idle). */
class ModeBar(ctx: Context, private val p: Bt.Palette) : View(ctx) {
    private var parts: List<Pair<Double, Int>> = emptyList()
    private var outlined = -1
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(item: TripItem) {
        val idleVersion = item.summary.top?.kind in setOf("air_leak", "rich_idle", "dips", "low_rpm")
        if (item.isCheck) {
            parts = listOf(1.0 to p.t3)
            outlined = -1
        } else {
            val cold = item.minutes(DriveMode.COLD)
            val idle = item.minutes(DriveMode.WARM_IDLE)
            val drive = (item.summary.durationMin - cold - idle).coerceAtLeast(0.0)
            parts = listOf(cold to p.s3, drive to p.line2, idle to if (idleVersion) p.ambT else p.t3)
            outlined = if (idleVersion) 2 else -1
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val total = parts.sumOf { it.first }.takeIf { it > 0 } ?: return
        var x = 0f
        val d = resources.displayMetrics.density
        parts.forEachIndexed { i, (v, color) ->
            val w = (width * v / total).toFloat()
            fill.style = Paint.Style.FILL
            fill.color = color
            c.drawRect(x, 0f, x + w, height.toFloat(), fill)
            if (i == outlined && w > 0) {
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = d
                fill.color = p.amb
                c.drawRect(x + d / 2, d / 2, x + w - d / 2, height - d / 2, fill)
            }
            x += w
        }
    }
}

/**
 * «Поездки» (В1, В1а, В3, В3а): journal with filters and the comparison table with
 * a forecast. Tapping a trip opens [TripDetailView].
 */
class TripsView(
    ctx: Context,
    private val sc: Bt.Scale,
    private val onOpen: (TripItem) -> Unit,
    /** Another car was chosen: rebuild the model for it. */
    private val onPickCar: (String) -> Unit = {},
) : FrameLayout(ctx) {
    private val p = Bt.LIGHT
    private var model: TripsModel? = null
    private var filter = 0
    private var compareChecks = false
    private val body = FrameLayout(ctx)
    private val journalFilter = Segment(ctx, sc, p, listOf("Все", "С версией", "Проверочные"), 0) { filter = it; render() }
    private val compareFilter = Segment(ctx, sc, p, listOf("Поездки", "Проверочные логи"), 0) { compareChecks = it == 1; render() }
    private val tabs = Tabs(ctx, sc, p, listOf("Журнал", "Сравнение")) { render() }
    private val dayFmt = DateTimeFormatter.ofPattern("dd.MM")
    /** Journal columns: date, time, modes, verdict — on short screens the verdict gets the most room. */
    private val colW = if (sc.compact) floatArrayOf(1.1f, 0.9f, 2f, 3f) else floatArrayOf(1.3f, 1f, 3f, 2.4f)
    private val timeFmt = DateTimeFormatter.ofPattern("EE · HH:mm", Locale.forLanguageTag("ru"))

    init {
        setBackgroundColor(p.bg)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Tabs.height(sc))))
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col)
        body.addView(context.text("Загрузка поездок…", sc.p, p.t3).apply { setPadding(dp(sc.pad), dp(24), 0, 0) })
    }

    fun bind(m: TripsModel) {
        model = m
        render()
    }

    fun showTab(i: Int) = tabs.select(i)

    private fun render() {
        val m = model ?: return
        body.removeAllViews()
        if (tabs.selected == 0) {
            val withVersion = m.trips.count { it.summary.top != null && it.summary.durationMin >= HomeLogic.NEED_TRIP_MIN }
            journalFilter.setTitles(listOf("Все · ${m.items.size}", "С версией · $withVersion", (if (sc.compact) "Провер. · " else "Проверочные · ") + m.checks.size))
            tabs.setRight(if (sc.phone) null else journalFilter.detached())
            body.addView(ScrollView(context).apply { addView(journal(m)) })
        } else {
            tabs.setRight(if (sc.phone) null else compareFilter.detached())
            body.addView(ScrollView(context).apply { addView(compare(m)) })
        }
    }

    private fun View.detached(): View = also { (parent as? ViewGroup)?.removeView(it) }

    // ---- Журнал ----

    /** «Машина: …» — trips of one car only; another car is picked here. */
    private fun carBar(m: TripsModel): View? {
        val car = m.car ?: return null
        val own = com.obdlogger.app.Prefs.carName(context, car.key)?.takeIf { it.isNotBlank() }
        val name = own ?: car.name
        val many = m.cars.size > 1
        val t = context.text(if (many) "Машина: $name · другая ▾" else "Машина: $name", if (sc.phone) 14f else 16f, if (many) p.acc else p.t2, 600)
        if (many) t.tap()
        if (many) t.setOnClickListener {
            val names = m.cars.map { c -> com.obdlogger.app.Prefs.carName(context, c.key)?.takeIf { it.isNotBlank() } ?: c.name }
            android.app.AlertDialog.Builder(context)
                .setTitle("Поездки какой машины показать")
                .setItems(names.toTypedArray()) { _, i -> onPickCar(m.cars[i].key) }
                .show()
        }
        // The app is meant for several cars: an unnamed one is asked for a name right here, prominently.
        val rename = context.text(if (own == null) "Назвать машину" else "Переименовать", if (sc.phone) 14f else 16f, if (own == null) p.acc else p.t3, 600).tap()
        rename.setPadding(dp(12), dp(8), dp(12), dp(8))
        if (own == null) rename.background = roundRect(p.accZ, dp(14).toFloat())
        rename.setOnClickListener {
            val field = android.widget.EditText(context).apply { setText(own.orEmpty()); hint = "Например: марка, модель, двигатель"; setSingleLine() }
            android.app.AlertDialog.Builder(context)
                .setTitle("Название машины")
                .setMessage("По нему поездки разных машин не перепутаются ни в журнале, ни в отчётах.")
                .setView(field)
                .setPositiveButton("Сохранить") { _, _ ->
                    com.obdlogger.app.Prefs.setCarName(context, car.key, field.text.toString().trim())
                    onPickCar(car.key)
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val bar = row(context, dp(12), Gravity.CENTER_VERTICAL, t, rename)
        bar.setPadding(dp(sc.pad), dp(10), dp(sc.pad), dp(4))
        return bar
    }

    private fun journal(m: TripsModel): View {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        carBar(m)?.let { addTo(list, it) }
        if (sc.phone) addTo(list, journalFilter.detached().apply { }, dp(8))
        val items = when (filter) {
            1 -> m.trips.filter { it.summary.top != null && it.summary.durationMin >= HomeLogic.NEED_TRIP_MIN }
            2 -> m.checks
            else -> m.items
        }
        if (items.isEmpty()) {
            val empty = column(
                context, dp(10),
                context.text(if (m.items.isEmpty()) "Поездок ещё нет" else "Таких записей нет", sc.hm, p.t1, 600),
                context.text(
                    if (m.items.isEmpty()) "Каждая поездка с автозаписью появится здесь сама: дата, длительность, режимы и вывод. Нужен только адаптер и включённая автозапись."
                    else "Проверочный лог записывается с главного экрана на стоянке, на прогретом моторе.",
                    sc.p, p.t2,
                ),
            )
            empty.setPadding(dp(sc.pad), dp(32), dp(sc.pad), 0)
            return empty
        }
        if (!sc.phone) {
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(sc.pad), dp(14), dp(sc.pad), dp(10)) }
            head.addView(context.label("Дата", sc, p), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, colW[0]))
            head.addView(context.label("Время", sc, p), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, colW[1]))
            head.addView(context.label("Режимы", sc, p), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, colW[2]))
            head.addView(context.label("Вывод", sc, p), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, colW[3]).apply { leftMargin = dp(20) })
            list.addView(head)
            list.addView(hline(context, p.line))
        }
        for (it in items) list.addView(journalRow(it))
        return list
    }

    private fun journalRow(it: TripItem): View {
        val s = it.summary
        val dur = s.durationMin.let { m -> if (m >= 60) "${(m / 60).toInt()} ч ${(m % 60).toInt()} мин" else "${m.toInt().coerceAtLeast(1)} мин" }
        val date = column(
            context, dp(2),
            context.text(s.start?.format(dayFmt) ?: s.name, if (sc.phone) 18f else 21f, p.t1, 700),
            context.text(s.start?.format(timeFmt) ?: "", if (sc.phone) 13f else 15f, p.t2),
        )
        val bar = ModeBar(context, p).apply { set(it) }
        val idle = it.minutes(DriveMode.WARM_IDLE)
        val caption = if (it.isCheck) "проверочный лог" else listOfNotNull(
            "прогрев".takeIf { _ -> it.minutes(DriveMode.COLD) > 0.5 },
            "движение",
            if (idle >= 0.5) "ХХ ${idle.toInt().coerceAtLeast(1)} мин" else null,
        ).joinToString(" · ")
        val modes = column(context, dp(6), bar.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)) },
            context.text(caption, if (sc.phone) 13f else 15f, p.t2))
        val v = TripVerdict.of(it, p)
        val chip = context.chip(v.text, v.fg, v.bg, if (sc.phone) 13f else 15f).apply { maxLines = 2 }
        val row = LinearLayout(context).apply {
            orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(16))
            setOnClickListener { _ -> onOpen(it) }
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(p.s2), null, android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt()))
        }
        if (sc.phone) {
            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            addTo(top, date, 0, 1f)
            addTo(top, context.text(dur, 17f, p.t1, 400, mono = true))
            addTo(row, top)
            addTo(row, modes, dp(10))
            addTo(row, chip, dp(10), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            row.addView(date, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, colW[0]))
            row.addView(context.text(dur, if (sc.compact) 17f else 20f, p.t1, 400, mono = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, colW[1]))
            row.addView(modes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, colW[2]))
            // The verdict is what the row is for: it may take two lines, never an ellipsis.
            val chipBox = FrameLayout(context).apply { addView(chip, FrameLayout.LayoutParams(if (sc.compact) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            row.addView(chipBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, colW[3]).apply { leftMargin = dp(20) })
        }
        return column(context, 0, row, hline(context, p.line))
    }

    // ---- Сравнение ----

    private fun compare(m: TripsModel): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(28)) }
        carBar(m)?.let { addTo(box, it.apply { setPadding(0, 0, 0, dp(8)) }) }
        if (sc.phone) addTo(box, compareFilter.detached(), 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        if (compareChecks) {
            addTo(box, checks(m), dp(8))
            return box
        }
        val t = m.comparison
        if (t.trips.size < 2) {
            addTo(box, context.text("Сравнивать пока не с чем", sc.hm, p.t1, 600), dp(8))
            addTo(box, context.text("Нужно хотя бы 2 поездки с прогретым холостым ходом. Сейчас: ${t.trips.size}. Тренды и прогноз появятся сами.", sc.p, p.t2), dp(10))
            return box
        }
        addTo(box, context.text("Сверху — самое проблемное сейчас: устраните одно — поднимется следующее. Значения на прогретом холостом стоя, если не указано иное; для точного «до / после» — проверочный лог.", if (sc.phone) 13f else 15f, p.t2))
        val days = t.trips.map { it.start?.format(dayFmt) ?: "?" }
        // Several trips on one day: add the time so the columns differ.
        val dates = when {
            // All on one day: the time alone tells the columns apart.
            days.toSet().size == 1 && days.size > 1 -> t.trips.map { it.start?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "?" }
            days.toSet().size < days.size -> t.trips.map { it.start?.format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) ?: "?" }
            else -> days
        }
        val table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val nameW = dp(if (sc.phone) 170 else if (sc.compact) 200 else 300)
        val valW = dp(if (sc.phone) 80 else if (sc.compact) 96 else 124)
        fun rowOf(cells: List<View>, name: View): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), 0, dp(14))
            addView(name, LinearLayout.LayoutParams(nameW, ViewGroup.LayoutParams.WRAP_CONTENT))
            cells.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(if (i < dates.size) valW else if (i == dates.size) dp(76) else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val head = rowOf(dates.map { d -> context.label(d, sc, p).apply { gravity = Gravity.END } } +
            listOf(context.label("Тренд", sc, p).apply { gravity = Gravity.CENTER }, context.label("Вывод", sc, p).apply { setPadding(dp(16), 0, 0, 0) }),
            context.label("Показатель", sc, p))
        table.addView(head)
        table.addView(hline(context, p.line2))
        for (r in t.rows) {
            val name = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            name.addView(context.text(r.title, if (sc.phone) 15f else 19f, p.t1, 600, maxLines = 2))
            if (r.code.isNotEmpty() && !sc.compact) addTo(name, context.text(r.code, if (sc.phone) 12f else 13f, p.t3, 400, mono = true), dp(2))
            val last = r.values.lastOrNull()
            val cells = r.values.mapIndexed { i, v ->
                context.text(v?.let { x -> fmtMetric(r.unit, x) } ?: "—", if (sc.phone) 14f else 18f, p.t1, if (i == r.values.lastIndex && last != null) 600 else 400, mono = true).apply { gravity = Gravity.END }
            }
            val (fg, bg) = when (r.tone) {
                TripComparison.Tone.BAD -> p.red to p.redT
                TripComparison.Tone.WARN -> p.amb to p.ambT
                TripComparison.Tone.OK -> p.acc to p.accT
                TripComparison.Tone.NEUTRAL -> p.t2 to p.s3
            }
            val arrow = context.text(r.arrow, 18f, if (r.tone == TripComparison.Tone.WARN) p.amb else p.acc, 600).apply { gravity = Gravity.CENTER }
            val chip = FrameLayout(context).apply {
                setPadding(dp(16), 0, 0, 0)
                addView(context.chip(r.verdict, fg, bg, if (sc.phone) 12f else 15f))
            }
            table.addView(rowOf(cells + listOf(arrow, chip), name))
            table.addView(hline(context, p.line))
        }
        addTo(box, HorizontalScrollView(context).apply { addView(table); isHorizontalScrollBarEnabled = false }, dp(16))
        t.forecast?.let { f -> addTo(box, forecast(f), dp(20)) }
        return box
    }

    private fun fmtMetric(unit: String, v: Double): String = when (unit) {
        "%" -> com.obdlogger.core.TripAnalyzer.pct(v)
        "об/мин", "раз", "°C" -> "${v.toInt()}"
        else -> "${com.obdlogger.core.TripAnalyzer.fmt(v)} $unit"
    }

    private fun forecast(f: TripComparison.Forecast): View {
        val textCol = column(
            context, dp(10),
            context.label("Прогноз · ориентир, не диагноз", sc, p),
            context.text(f.text, if (sc.phone) 16f else 21f, p.t1, lineHeight = if (sc.phone) 22f else 30f),
            row(context, dp(10), Gravity.CENTER_VERTICAL, Confidence(context, f.confidence, sc, p, ": ${f.confidenceWhy}")),
        )
        val boxRow = LinearLayout(context).apply { orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (sc.phone) {
            addTo(boxRow, textCol)
            boxRow.addView(ForecastChart(context, p, f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)).apply { topMargin = dp(12) })
        } else {
            boxRow.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            boxRow.addView(ForecastChart(context, p, f), LinearLayout.LayoutParams(0, dp(130), 1f).apply { leftMargin = dp(28) })
        }
        return card(boxRow, p, dp(22), dp(20))
    }

    private fun checks(m: TripsModel): View {
        val list = m.checks.sortedBy { it.summary.start }
        if (list.size < 2) {
            return column(context, dp(10),
                context.text(if (list.isEmpty()) "Проверочных логов ещё нет" else "Нужен второй проверочный лог", sc.hm, p.t1, 600),
                context.text("Проверочный лог — 4 минуты на стоянке: холостой, 2500 об/мин, холостой. Сделайте его до ремонта и после — Бортач сравнит честно, в одинаковых условиях.", sc.p, p.t2))
        }
        val shown = list.takeLast(4)
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(10), 0, dp(10)) }
        head.addView(context.label("Показатель", sc, p), LinearLayout.LayoutParams(dp(if (sc.phone) 150 else 320), ViewGroup.LayoutParams.WRAP_CONTENT))
        shown.forEach { c -> head.addView(context.label(c.summary.start?.format(dayFmt) ?: "?", sc, p).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(if (sc.phone) 76 else 120), ViewGroup.LayoutParams.WRAP_CONTENT)) }
        box.addView(head)
        box.addView(hline(context, p.line2))
        val rows = listOf<Pair<String, (com.obdlogger.core.CheckResult) -> String?>>(
            "Коррекция Б1 на ХХ" to { c -> c.idleTrim?.let { com.obdlogger.core.TripAnalyzer.pct(it) } },
            "Коррекция Б1 на 2500" to { c -> c.revTrim?.let { com.obdlogger.core.TripAnalyzer.pct(it) } },
            "Коррекция Б2 на ХХ" to { c -> c.idleTrimB2?.let { com.obdlogger.core.TripAnalyzer.pct(it) } },
            "Обороты ХХ" to { c -> c.idleRpm?.toInt()?.toString() },
            "Лямбда после кат., ХХ" to { c -> c.rearO2Idle?.let { "${com.obdlogger.core.TripAnalyzer.fmt(it)} В" } },
            "Лямбда после кат., 2500" to { c -> c.rearO2Rev?.let { "${com.obdlogger.core.TripAnalyzer.fmt(it)} В" } },
            "Расход воздуха на ХХ" to { c -> c.idleMaf?.let { "${com.obdlogger.core.TripAnalyzer.fmt(it)} г/с" } },
        )
        for ((title, get) in rows) {
            val vals = shown.map { it.check?.let(get) }
            if (vals.all { it == null }) continue
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(14), 0, dp(14)) }
            r.addView(context.text(title, if (sc.phone) 15f else 19f, p.t1, 600), LinearLayout.LayoutParams(dp(if (sc.phone) 150 else 320), ViewGroup.LayoutParams.WRAP_CONTENT))
            vals.forEach { v -> r.addView(context.text(v ?: "—", if (sc.phone) 14f else 18f, p.t1, 400, mono = true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(if (sc.phone) 76 else 120), ViewGroup.LayoutParams.WRAP_CONTENT)) }
            box.addView(r)
            box.addView(hline(context, p.line))
        }
        return HorizontalScrollView(context).apply { addView(box) }
    }
}

/** Small chart of the forecast: trips as dots, the norm band, a dotted projection to the limit. */
class ForecastChart(ctx: Context, private val p: Bt.Palette, private val f: TripComparison.Forecast) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val v = f.series
        val n = v.size
        val steps = n + 3
        val lo = minOf(v.min(), -10.0)
        val hi = maxOf(v.max(), f.limit) + 3
        val w = width.toFloat()
        val h = height.toFloat()
        fun x(i: Double) = (w * 0.04f + (w * 0.9f) * (i / (steps - 1))).toFloat()
        fun y(x: Double) = (h - h * (x - lo) / (hi - lo)).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = p.accZ
        c.drawRect(0f, y(10.0), w, y(-10.0).coerceAtMost(h), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        paint.color = p.red
        paint.pathEffect = DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
        c.drawLine(0f, y(f.limit), w, y(f.limit), paint)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.red; textSize = 12 * d; typeface = Bt.sans(context) }
        c.drawText(f.limitLabel, w - lp.measureText(f.limitLabel) - 4 * d, y(f.limit) - 4 * d, lp)
        // Projection.
        val slope = TripComparison.slope(v)
        paint.color = p.t2
        c.drawLine(x((n - 1).toDouble()), y(v.last()), x((steps - 1).toDouble()), y(v.last() + slope * 3), paint)
        paint.pathEffect = null
        paint.strokeWidth = 1.6f * d
        paint.color = p.t1
        val path = Path()
        v.forEachIndexed { i, x0 -> if (i == 0) path.moveTo(x(i.toDouble()), y(x0)) else path.lineTo(x(i.toDouble()), y(x0)) }
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        v.forEachIndexed { i, x0 -> c.drawCircle(x(i.toDouble()), y(x0), 4 * d, paint) }
    }
}
