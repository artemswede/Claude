package com.obdlogger.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.obdlogger.core.AttentionSort
import com.obdlogger.core.DriveMode
import com.obdlogger.core.Finding
import com.obdlogger.core.SensorDetail
import com.obdlogger.core.SensorNames
import com.obdlogger.core.SeriesStore
import com.obdlogger.core.Severity
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.TripDetail
import com.obdlogger.core.Verdict

/** What the trip and version screens can ask the activity to do. */
interface TripActions {
    fun back()
    fun share(files: List<java.io.File>, title: String)
    fun printReport(item: TripItem)
    /** Files of the trip plus its report as HTML. */
    fun shareTrip(item: TripItem)
    fun openVersion(f: Finding, item: TripItem?)
}

/**
 * Trip in detail (В2, В2 «Статистика», «Рейтинг», «Графики», Г7 итог): key figures,
 * version and observation, trim chart, files for the specialist; per-mode statistics
 * of every sensor; instability rating; lane charts of the whole trip.
 */
class TripDetailView(ctx: Context, private val sc: Bt.Scale, private val item: TripItem, private val actions: TripActions) : FrameLayout(ctx) {
    private val p = Bt.LIGHT
    private var detail: TripDetail? = null
    private val body = FrameLayout(ctx)
    private var focused: String? = null
    private var sort = AttentionSort.DEVIATION
    private val tabs = Tabs(ctx, sc, p, listOf("Итог", "Статистика", "Рейтинг", "Графики")) { render() }
    private val s = item.summary

    init {
        setBackgroundColor(p.bg)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val back = ctx.text("← Поездки", if (sc.phone) 16f else 19f, p.t1, 600).apply {
            setPadding(dp(sc.pad), 0, dp(16), 0)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { actions.back() }
            tap()
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        head.addView(ctx.text(HomeModel.tripRange(s) + if (item.isCheck) " · проверочный лог" else "", if (sc.phone) 15f else 18f, p.t2, 500, mono = true))
        col.addView(head)
        col.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Tabs.height(sc))))
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col)
        render()
    }

    /** Heavy detail arrives later (built off the main thread). */
    fun bind(d: TripDetail?) {
        detail = d
        render()
    }

    fun showTab(i: Int) = tabs.select(i)

    private fun render() {
        body.removeAllViews()
        val d = detail
        when (tabs.selected) {
            0 -> body.addView(ScrollView(context).apply { addView(summary(d)) })
            1 -> body.addView(d?.let { stats(it) } ?: loading())
            2 -> body.addView(d?.let { rating(it) } ?: loading())
            3 -> body.addView(d?.let { charts(it) } ?: loading())
        }
    }

    private fun loading() = context.text("Разбираю поездку…", sc.p, p.t3).apply { setPadding(dp(sc.pad), dp(24), 0, 0) }

    // ---- Итог ----

    private fun figure(label: String, value: String): View = column(context, dp(4),
        context.text(label, if (sc.phone) 13f else 16f, p.t2),
        context.text(value, if (sc.phone) 20f else 26f, p.t1, 400, mono = true))

    private fun summary(d: TripDetail?): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(8), dp(sc.pad), dp(28)) }
        val figs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val idleMin = d?.modeMinutes?.get(DriveMode.WARM_IDLE) ?: item.minutes(DriveMode.WARM_IDLE)
        val poll = d?.pollSec?.let { String.format(java.util.Locale.ROOT, "%.1f с", it) } ?: "—"
        val list = listOfNotNull(
            "длительность" to "${s.durationMin.toInt()} мин",
            s.metrics[com.obdlogger.core.Metric.COOLANT_MAX]?.let { "мотор прогрет до" to "${it.toInt()} °C" },
            "холостой" to "${idleMin.toInt()} мин",
            "строк · опрос" to "${s.rows} · $poll",
            "коды ЭБУ" to (s.dtcs?.let { if (it.isEmpty()) "нет" else it.joinToString(", ") } ?: "—"),
        )
        val shown = if (sc.phone) list.take(3) else list
        shown.forEach { (l, v) -> figs.addView(figure(l, v), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        addTo(box, hline(context, p.line), dp(8))
        addTo(box, figs, dp(14))
        addTo(box, hline(context, p.line), dp(14))

        val top = s.top
        val watch = s.findings.firstOrNull { it.severity == Severity.WATCH }
        val cards = LinearLayout(context).apply { orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val versionCard = when {
            item.isCheck -> versionBox("Проверочный лог", item.check?.idleTrim?.let { "Коррекция Б1 на ХХ ${TripAnalyzer.pct(it)}" } ?: "Записан", null, p.line2)
            top != null && s.durationMin >= com.obdlogger.core.HomeLogic.NEED_TRIP_MIN -> versionBox("Версия по этой поездке", top.headline, top, p.acc)
            s.durationMin < com.obdlogger.core.HomeLogic.NEED_TRIP_MIN -> versionBox("Недостаточно данных", "Поездка короче ${com.obdlogger.core.HomeLogic.NEED_TRIP_MIN.toInt()} минут — вывод не делается", null, p.line2)
            else -> versionBox("Разбор готов", "Отклонений не найдено", null, p.acc)
        }
        if (sc.phone) addTo(cards, versionCard) else cards.addView(versionCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        watch?.let { w ->
            val obs = card(column(context, dp(8),
                context.label("Наблюдаем", sc, p),
                context.text(w.headline, if (sc.phone) 17f else 21f, p.t1, 600),
                context.text(w.evidence + " — пока не версия", if (sc.phone) 14f else 16f, p.t2)), p, dp(20), dp(16), p.line2, dashed = true)
            if (sc.phone) addTo(cards, obs, dp(12)) else cards.addView(obs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(16) })
        }
        addTo(box, cards, dp(18))

        if (s.trace != null && !item.isCheck) {
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val focus = HomeModel.focusOf(s)
            addTo(head, context.label("${SensorNames.label(focus)} за поездку", sc, p), 0, 1f)
            val normText = com.obdlogger.core.Norms.of(focus, com.obdlogger.core.LiveMode.IDLE)?.text?.let { "$it · " } ?: ""
            if (!sc.phone) addTo(head, context.text("${normText}подложка — холостой", 14f, p.t3))
            addTo(box, head, dp(22))
            val chart = TrimChartView(context).apply { set(s.trace, p, sc, dimmed = false, code = focus) }
            box.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (sc.phone) 220 else 300)).apply { topMargin = dp(8) })
        }

        addTo(box, hline(context, p.line), dp(20))
        val files = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!sc.phone) addTo(files, context.label("Для специалиста", sc, p), 0, 1f)
        addTo(files, context.button("Отчёт PDF", sc, p, primary = false) { actions.printReport(item) }, if (sc.phone) 0 else dp(12))
        addTo(files, context.button("Поделиться", sc, p, primary = true) { actions.shareTrip(item) }, dp(12))
        addTo(box, files, dp(16))
        val names = item.files().joinToString(" · ") { it.name }
        addTo(box, context.text("Файлы: $names — CSV с данными, разбор и журнал адаптера.", if (sc.phone) 12f else 14f, p.t3), dp(10))
        return box
    }

    private fun versionBox(label: String, title: String, f: Finding?, border: Int): View {
        val col = column(context, dp(8),
            context.label(label, sc, p, if (f != null) p.acc else p.t3),
            context.text(title, if (sc.phone) 18f else 22f, p.t1, 600))
        f?.let {
            val conf = Confidence(context, it.confidence, sc, p, " · подробнее →")
            conf.setOnClickListener { _ -> actions.openVersion(it, item) }
            conf.tap()
            addTo(col, conf, dp(4))
            col.setOnClickListener { _ -> actions.openVersion(it, item) }
        }
        return card(col, p, dp(20), dp(16), border, dashed = true)
    }

    // ---- Статистика ----

    private fun mark(v: Verdict) = when (v) {
        Verdict.OUT -> "▲"
        Verdict.OK -> "✓"
        else -> "—"
    }

    private fun stats(d: TripDetail): View {
        val sensors = d.rating(AttentionSort.DEVIATION).ifEmpty { d.sensors }.let { r -> r + d.sensors.filter { it !in r && it.code in RecordView.DEFAULT_TILES } }
        val f = sensors.firstOrNull { it.code == focused } ?: sensors.firstOrNull() ?: return loading()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (x in sensors) {
            val on = x.code == f.code
            val r = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = if (on) roundRect(p.s2, 0f) else null
                setOnClickListener { focused = x.code; render() }
            }
            addTo(r, context.text(SensorNames.label(x.code), if (sc.phone) 14f else 17f, p.t1, if (on) 600 else 400, maxLines = 1), 0, 1f)
            addTo(r, context.text(mark(x.worst), 16f, if (x.worst == Verdict.OUT) p.amb else if (x.worst == Verdict.OK) p.acc else p.t3, 600), dp(8))
            list.addView(r)
            list.addView(hline(context, p.line))
        }
        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addTo(right, context.text(SensorNames.label(f.code), if (sc.phone) 20f else 28f, p.t1, 700))
        val norm = com.obdlogger.core.Norms.of(f.code, com.obdlogger.core.LiveMode.IDLE)
        addTo(right, context.text(listOfNotNull(SensorNames.source(f.code), norm?.text).joinToString(" · "), 13f, p.t3, 400, mono = true), dp(4))
        addTo(right, hline(context, p.line), dp(14))
        val nums = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val idleMed = f.byMode.firstOrNull { it.mode == DriveMode.WARM_IDLE }
        for ((l, v) in listOf("мин" to f.min, "медиана" to f.median, "среднее" to f.mean, "макс" to f.max)) {
            nums.addView(figure(l, Num.fmt(f.code, v)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        nums.addView(figure("замеров", "${f.count}"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addTo(right, nums, dp(12))
        addTo(right, hline(context, p.line), dp(12))
        // By mode.
        val table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val cols = floatArrayOf(2.2f, 0.8f, 1f, 1.1f, 1.9f, 1.8f)
        fun tr(cells: List<View>, bg: Int? = null) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            bg?.let { setBackgroundColor(it) }
            cells.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, cols[i])) }
        }
        val headers = if (sc.phone) listOf("Режим", "Доля", "N", "Мед.", "Мин…макс", "Оценка") else listOf("Режим", "Доля", "Замеров", "Медиана", "Мин…макс", "Оценка")
        table.addView(tr(headers.mapIndexed { i, h ->
            context.label(h, sc, p).apply {
                letterSpacing = 0.04f
                if (i in 1..4) gravity = Gravity.END
                if (i == 5) setPadding(dp(12), 0, 0, 0)
            }
        }))
        table.addView(hline(context, p.line2))
        val fs = if (sc.phone) 13f else 17f
        for (m in f.byMode.sortedByDescending { it.mode == DriveMode.WARM_IDLE }) {
            val (fg, bg) = when (m.verdict) {
                Verdict.OUT -> p.amb to p.ambT
                Verdict.OK -> p.acc to p.accT
                else -> p.t2 to p.s3
            }
            val dim = m.verdict == Verdict.NOT_RATED
            val cells = listOf(
                context.text(m.mode.ru, fs, if (dim) p.t2 else p.t1, if (m.verdict == Verdict.OUT) 600 else 400),
                context.text("${(m.share * 100).toInt()} %", fs, p.t2, 400, mono = true).apply { gravity = Gravity.END },
                context.text("${m.count}", fs, p.t2, 400, mono = true).apply { gravity = Gravity.END },
                context.text(m.median?.let { Num.fmt(f.code, it) } ?: "—", fs, p.t1, if (m.verdict == Verdict.OUT) 600 else 400, mono = true).apply { gravity = Gravity.END },
                context.text(if (dim) "—" else "${m.min?.let { Num.fmt(f.code, it) }}…${m.max?.let { Num.fmt(f.code, it) }}", fs, p.t2, 400, mono = true).apply { gravity = Gravity.END },
                FrameLayout(context).apply { setPadding(dp(12), 0, 0, 0); addView(context.chip(m.verdict.ru, fg, bg, if (sc.phone) 11f else 14f)) },
            )
            table.addView(tr(cells, if (m.verdict == Verdict.OUT) p.ambT else null))
            table.addView(hline(context, p.line))
        }
        addTo(right, table, dp(8))
        // The «average hides the problem» note.
        val overallIn = norm?.state(f.mean) == com.obdlogger.core.NormState.IN
        if (idleMed?.verdict == Verdict.OUT && overallIn) {
            addTo(right, context.text("Средняя за поездку ${Num.fmt(f.code, f.mean)} выглядит нормой, но скрывает проблему: отклонение только на холостом.", if (sc.phone) 14f else 17f, p.t2), dp(14))
        }
        val root = LinearLayout(context).apply { orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(16)) }
        if (sc.phone) {
            val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addTo(wrap, right)
            addTo(wrap, context.label("Другие датчики", sc, p), dp(22))
            addTo(wrap, list, dp(8))
            return ScrollView(context).apply { addView(wrap.apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }) }
        }
        root.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(ScrollView(context).apply { addView(right) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(24) })
        return root
    }

    // ---- Рейтинг ----

    private fun rating(d: TripDetail): View {
        val ranked = d.rating(sort)
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(head, context.text("Нестабильность по датчикам", if (sc.phone) 18f else 24f, p.t1, 700), 0, 1f)
        addTo(head, Segment(context, sc, p, listOf("Отклонение", "Скачки"), if (sort == AttentionSort.DEVIATION) 0 else 1) { i ->
            sort = if (i == 0) AttentionSort.DEVIATION else AttentionSort.JUMPS
            render()
        }, dp(12))
        addTo(left, head)
        addTo(left, context.text(if (sort == AttentionSort.DEVIATION) "Отклонение — сколько времени значение было вне нормы, в тех режимах, где норма есть." else "Скачки — средний скачок между соседними замерами, в % от обычного диапазона датчика.", if (sc.phone) 13f else 15f, p.t2), dp(8))
        addTo(left, hline(context, p.line), dp(10))
        val maxScore = ranked.mapNotNull { score(it) }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        ranked.take(12).forEachIndexed { i, x ->
            val sc0 = score(x)
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
            r.addView(context.text("${i + 1}", 17f, p.t2, 400, mono = true), LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
            r.addView(column(context, dp(2),
                context.text(SensorNames.label(x.code), if (sc.phone) 15f else 19f, p.t1, 600, maxLines = 1),
                context.text("${SensorNames.source(x.code)} · ${x.note}", if (sc.phone) 12f else 14f, p.t2, maxLines = 2)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val bar = HomeView.Progress(context, ((sc0 ?: 0.0) / maxScore).toFloat().coerceIn(0f, 1f),
                if ((sc0 ?: 0.0) > 30 && sort == AttentionSort.DEVIATION) p.amb else p.t2, p.s3)
            r.addView(bar, LinearLayout.LayoutParams(dp(if (sc.phone) 70 else 150), dp(12)).apply { leftMargin = dp(12) })
            r.addView(context.text(sc0?.let { "${it.toInt()} %" } ?: "—", 18f, p.t1, 400, mono = true).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT))
            left.addView(r)
            left.addView(hline(context, p.line))
        }

        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addTo(right, context.text("Что это значит", if (sc.phone) 18f else 24f, p.t1, 700))
        val top = s.top
        if (top != null && !item.isCheck) addTo(right, versionBox("Есть версия", top.headline, top, p.acc), dp(14))
        else addTo(right, versionBox("Версии нет", "Отклонений, которые складываются в версию, не найдено", null, p.line2), dp(14))
        if (d.pollSec > 1.5) {
            addTo(right, card(column(context, dp(6),
                context.label("Недостаточно данных", sc, p),
                context.text("Скорость переключения лямбды до кат. не оценить: опрос раз в ${String.format(java.util.Locale.ROOT, "%.1f", d.pollSec)} с — реже переключений зонда.", if (sc.phone) 14f else 16f, p.t2)),
                p, dp(18), dp(14), p.line2, dashed = true), dp(12))
        }
        addTo(right, card(column(context, dp(6),
            context.label("Коды ЭБУ", sc, p),
            context.text(s.dtcs?.let { if (it.isEmpty()) "Кодов нет" else it.joinToString(", ") } ?: "Коды не прочитаны", if (sc.phone) 15f else 18f, p.t1)),
            p, dp(18), dp(14)), dp(12))

        if (sc.phone) {
            val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
            addTo(wrap, left)
            addTo(wrap, right, dp(20))
            return ScrollView(context).apply { addView(wrap) }
        }
        val root = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(sc.pad), dp(18), dp(sc.pad), dp(16)) }
        root.addView(ScrollView(context).apply { addView(left) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))
        root.addView(ScrollView(context).apply { addView(right) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(28) })
        return root
    }

    private fun score(x: SensorDetail): Double? = if (sort == AttentionSort.DEVIATION) x.deviation else x.jitter

    // ---- Графики ----

    private fun charts(d: TripDetail): View {
        val store = SeriesStore(capacity = 100_000)
        val t = d.table
        val cols = t.header.filter { it != "time" && it != "t_s" && it != "marker" }
        val idx = cols.map { t.header.indexOf(it) }
        store.reset(cols)
        t.rows.forEachIndexed { i, r -> store.add(t.ms[i] + startMs(), idx.map { r.getOrNull(it)?.ifEmpty { null } }) }
        val lanes = LanesView(context, p)
        lanes.windowMs = (t.ms.lastOrNull() ?: 60_000L).coerceAtLeast(60_000L) + 1000
        val codes = (RecordView.LANES + d.rating(AttentionSort.DEVIATION).map { it.code }).filter { it in store.columns }.distinct().take(if (sc.phone) 4 else 6)
        lanes.set(store, codes, false, com.obdlogger.core.LiveMode.IDLE)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(lanes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addTo(this, context.text("вся поездка · подложка — норма для прогретого холостого", 13f, p.t3).apply { gravity = Gravity.END }, dp(4))
        }
    }

    private fun startMs(): Long = s.start?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L
}
