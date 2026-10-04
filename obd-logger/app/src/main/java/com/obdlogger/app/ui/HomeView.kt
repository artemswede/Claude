package com.obdlogger.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.obdlogger.app.Lamp
import com.obdlogger.app.LoggerState
import com.obdlogger.app.R
import com.obdlogger.core.Finding
import com.obdlogger.core.HomeLogic
import com.obdlogger.core.HomeState
import com.obdlogger.core.Metric
import com.obdlogger.core.TripAnalyzer

/**
 * Главный экран (Д1–Д9): вывод слева, график коррекции справа, внизу последняя
 * поездка, тренд и проверочный лог. Светлая тема днём.
 */
class HomeView(
    ctx: Context,
    private val sc: Bt.Scale,
    private val onDetails: () -> Unit,
    private val onSettings: () -> Unit,
    private val onCheck: () -> Unit = {},
    /** Opens the last saved trip (Г7: «поездка сохранена»). */
    private val onOpenLast: () -> Unit = {},
    /** Д7: the dark palette at night while driving. */
    private val p: Bt.Palette = Bt.LIGHT,
) : FrameLayout(ctx) {
    private val leftCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val chartTitle = ctx.label("Коррекция смеси за поездку", sc, p)
    private val chartNorm = ctx.text("норма ±10 %", sc.cap, p.t3)
    private val chart = TrimChartView(ctx)
    private val chartCap = ctx.text("", sc.cap + 1, p.t3)
    private val lastTrip = ctx.text("", if (sc.phone) 16f else if (sc === Bt.TABLET) 19f else 15f, p.t1, maxLines = 2)
    private val spark = Sparkline(ctx)
    private val trendTitle = ctx.text("", if (sc.phone) 15f else if (sc === Bt.TABLET) 17f else 14f, p.t1, 600, maxLines = 2)
    private val trendValues = ctx.text("", if (sc.phone) 13f else if (sc === Bt.TABLET) 15f else 13f, p.t2, 400, mono = true, maxLines = 1)
    private val testButton = ctx.text("Записать проверочный лог", sc.btnBigFont, p.accInk, 600).apply {
        gravity = Gravity.CENTER
        setPadding(dp(28), 0, dp(28), 0)
        background = roundRect(p.acc, dp(16).toFloat())
        val ic = context.getDrawable(R.drawable.ic_timer)!!.tinted(p.accInk)
        val s = dp(if (sc === Bt.TABLET) 28 else 22)
        ic.setBounds(0, 0, s, s)
        setCompoundDrawables(ic, null, null, null)
        compoundDrawablePadding = dp(12)
        elevation = dp(2).toFloat()
        setOnClickListener { onCheck() }
    }
    private val testCap = ctx.text("4 минуты: ХХ → 2500 об/мин → ХХ", sc.cap, p.t3).apply { if (sc === Bt.S1024) visibility = View.GONE }
    private val testBlock = column(ctx, dp(4), testButton, testCap).apply { gravity = Gravity.END }
    private val noTest = ctx.text("", if (sc.phone) 15f else if (sc === Bt.TABLET) 17f else 14f, p.t2).apply { gravity = Gravity.END }
    private val bottomBar = LinearLayout(ctx)

    init {
        setBackgroundColor(p.bg)
        val chartCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(head, chartTitle, 0, 1f)
        addTo(head, chartNorm)
        addTo(chartCol, head)
        chartCol.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (sc.phone) dp(260) else 0, if (sc.phone) 0f else 1f).apply { topMargin = dp(8) })
        addTo(chartCol, chartCap, dp(8))

        // Bottom panel: last trip · trend · check log.
        bottomBar.orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        bottomBar.gravity = Gravity.CENTER_VERTICAL
        bottomBar.setPadding(dp(sc.pad), dp(14), dp(sc.pad), dp(14))
        bottomBar.background = roundRect(p.s1, 0f).apply {
            cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
        }
        bottomBar.elevation = dp(6).toFloat()
        val c1 = column(ctx, dp(4), ctx.label("Последняя поездка", sc, p), lastTrip)
        val c2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        c2.addView(spark, LinearLayout.LayoutParams(dp(104), dp(40)))
        addTo(c2, column(ctx, dp(2), trendTitle, trendValues), dp(16), 1f)
        val c3 = FrameLayout(ctx)
        c3.addView(testBlock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        c3.addView(noTest, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        testButton.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(sc.btnBigH))
        if (sc.phone) {
            addTo(bottomBar, c1)
            addTo(bottomBar, c2, dp(12))
            addTo(bottomBar, c3, dp(12))
        } else {
            addTo(bottomBar, c1, 0, 1f)
            addTo(bottomBar, c2, dp(sc.gap), 1.4f)
            addTo(bottomBar, c3, dp(sc.gap))
        }

        if (sc.phone) {
            val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
            addTo(content, leftCol)
            addTo(content, chartCol, dp(20))
            addTo(content, bottomBar, dp(20))
            addView(ScrollView(ctx).apply { addView(content) })
        } else {
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(sc.pad), dp(28), dp(sc.pad), dp(18))
            }
            top.addView(ScrollView(ctx).apply { addView(leftCol); isVerticalScrollBarEnabled = false },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f))
            top.addView(chartCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f).apply { leftMargin = dp(sc.gap) })
            val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            page.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            bottomBar.minimumHeight = dp(sc.botH)
            page.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(page)
        }
    }

    /** Е1–Е4: a problem that blocks recording, shown over the main screen. */
    class Problem(
        val label: String,
        val title: String,
        val text: String,
        val action: String,
        val onAction: () -> Unit,
        val hint: String? = null,
        val dismiss: (() -> Unit)? = null,
    )

    private var problemView: View? = null

    fun problem(pr: Problem?) {
        problemView?.let { removeView(it) }
        problemView = null
        if (pr == null) return
        val icon = context.icon(R.drawable.ic_warn, p.amb, 40)
        val box = column(context, dp(12), icon,
            context.label(pr.label, sc, p),
            context.text(pr.title, if (sc.phone) 24f else sc.hl, p.t1, 700),
            context.text(pr.text, if (sc.phone) 16f else sc.pl, p.t2, lineHeight = if (sc.phone) 21f else sc.pl * 1.25f))
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(actions, button(pr.action) { pr.onAction() })
        pr.dismiss?.let { d -> addTo(actions, context.text("Понятно", sc.btnFont, p.acc, 600).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { d() } }.tap(), dp(12)) }
        pr.hint?.let { addTo(actions, context.text(it, sc.cap, p.t2).apply { maxWidth = dp(300) }, dp(16)) }
        addTo(box, actions, dp(18))
        val cardView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(p.s1, dp(18).toFloat())
            elevation = dp(8).toFloat()
        }
        cardView.addView(View(context).apply { setBackgroundColor(p.amb) }, LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT))
        box.setPadding(dp(if (sc.phone) 20 else 40), dp(if (sc.phone) 20 else 36), dp(if (sc.phone) 20 else 40), dp(if (sc.phone) 20 else 36))
        cardView.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val veil = FrameLayout(context).apply {
            setBackgroundColor(p.bg)
            isClickable = true
            addView(cardView, LayoutParams(if (sc.phone) ViewGroup.LayoutParams.MATCH_PARENT else dp(820), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = dp(16); rightMargin = dp(16)
            })
        }
        problemView = veil
        addView(veil)
    }

    fun bind(m: HomeModel, s: LoggerState.Snapshot) {
        val t = m.trip
        leftCol.removeAllViews()
        when (m.state) {
            HomeState.VERSION -> version(t!!.top!!)
            HomeState.CALM -> calm(m)
            HomeState.DTC -> dtc(m)
            HomeState.COLLECTING -> collecting(m)
            HomeState.WAIT -> waiting(m, s)
            HomeState.NO_CONNECTION -> noConnection(m)
            HomeState.OFF -> off(m)
            HomeState.NO_TRIPS -> noTrips()
        }

        chartTitle.text = when {
            t == null -> "КОРРЕКЦИЯ СМЕСИ"
            m.live -> "КОРРЕКЦИЯ СМЕСИ ЗА ПОЕЗДКУ"
            else -> "КОРРЕКЦИЯ СМЕСИ · ПРОШЛАЯ ПОЕЗДКА ${HomeModel.tripRange(t)}"
        }
        chart.set(t?.trace, p, sc, dimmed = !m.live)
        chartCap.text = when {
            t == null -> "Здесь появится график первой поездки."
            !m.live -> "Это запись прошлой поездки, не текущее состояние."
            m.state == HomeState.CALM -> "Все режимы в пределах нормы."
            m.state == HomeState.COLLECTING -> "Подложкой отмечен прогретый холостой ход — его нужно не меньше 2 минут."
            t.top?.headline?.contains("холост") == true -> "Отклонение проявляется только на холостом ходу."
            else -> "Подложкой отмечен прогретый холостой ход."
        }
        chartCap.visibility = if (sc === Bt.S1024) View.GONE else View.VISIBLE

        lastTrip.text = m.lastTripText
        val tr = m.trend
        spark.set(tr?.values ?: emptyList(), p)
        trendTitle.text = tr?.title ?: "Тренд появится после 2 поездок"
        trendValues.text = tr?.valuesText ?: ""

        val speed = s.values.firstOrNull { it.first == "speed_kmh" }?.second?.toDoubleOrNull() ?: 0.0
        val moving = s.recording && speed > 0
        val canTest = s.recording && !moving
        testBlock.visibility = if (canTest) View.VISIBLE else View.GONE
        noTest.visibility = if (canTest) View.GONE else View.VISIBLE
        noTest.text = if (moving) "Проверочный лог доступен на стоянке" else "Проверочный лог — после запуска двигателя"
    }

    // ---- left column per state ----

    private fun tag(text: String, color: Int = p.t3, iconRes: Int = R.drawable.ic_overview): View {
        val ic = context.icon(iconRes, color, if (sc === Bt.TABLET) 20 else 16)
        return row(context, dp(8), Gravity.CENTER_VERTICAL, ic, context.label(text, sc, p, color))
    }

    private fun gap(v: View, top: Int) = addTo(leftCol, v, dp(top))

    /** Headline that never takes more than two lines: shrinks from h-xl down to h-m. */
    private fun headline(s: String): View = FitText(context, s, sc.hxl, sc.hm, 2, p.t1)

    private fun version(f: Finding) {
        gap(tag("Есть версия"), 0)
        gap(headline(f.headline), 12)
        f.urgency?.let { gap(context.text(it, sc.pl * 0.85f, p.t2, lineHeight = sc.pl * 1.15f), 12) }
        gap(Confidence(context, f.confidence, sc, p), 14)
        if (f.why.isNotEmpty()) {
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            addTo(head, context.label("Почему Бортач так думает", sc, p), 0, 1f)
            addTo(head, link("Подробнее →") { onDetails() })
            gap(head, 18)
            f.why.take(if (sc === Bt.S1024) 2 else 3).forEach { gap(evidence(it.label, it.value, it.deviating, it.emphasis), 0) }
        }
    }

    private fun calm(m: HomeModel) {
        gap(tag("Всё спокойно", p.acc), 0)
        gap(headline("Отклонений не найдено"), 12)
        gap(context.text("Поездка разобрана по всем режимам — смесь, холостой и зарядка в норме.", sc.pl, p.t2, lineHeight = sc.pl * 1.35f), 12)
        val t = m.trip ?: return
        t.metrics[Metric.IDLE_TRIM_B1]?.let { gap(evidence("Коррекция на холостом", TripAnalyzer.pct(it), false, false, small = true), 14) }
        t.metrics[Metric.IDLE_RPM]?.let { gap(evidence("Обороты холостого", "${it.toInt()}", false, false, small = true), 0) }
        t.metrics[Metric.CHARGE_V]?.let { gap(evidence("Напряжение на работающем", "${TripAnalyzer.fmt(it)} В", false, false, small = true), 0) }
    }

    private fun dtc(m: HomeModel) {
        val t = m.trip!!
        val box = column(
            context, dp(8),
            tag("Код записан блоком управления", p.red, R.drawable.ic_ecu),
            context.text(t.dtcs!!.joinToString(", "), sc.hxl, p.t1, 600, mono = true),
            context.text("Коды прочитаны из ЭБУ", sc.p, p.t2),
        ).apply {
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = roundRect(p.s1, dp(10).toFloat(), dp(2), p.red)
        }
        gap(box, 0)
        t.top?.let { f ->
            val obs = column(
                context, dp(6),
                tag("Есть версия причины", p.acc),
                context.text(f.headline, sc.hs, p.t1, 600),
                Confidence(context, f.confidence, sc, p),
            ).apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = roundRect(p.s1, dp(14).toFloat(), dp(2), p.acc, dash = dp(5).toFloat())
            }
            gap(obs, 14)
        }
        gap(context.text("Ехать можно, если мотор работает ровно. Проверьте в ближайшие дни.", sc.p, p.t2), 14)
        gap(link("Подробнее →") { onDetails() }, 8)
    }

    private fun collecting(m: HomeModel) {
        val t = m.trip
        val min = t?.durationMin?.toInt() ?: 0
        val idle = t?.warmIdleSec?.toInt() ?: 0
        val box = column(
            context, dp(10),
            tag("Идёт диагностика", p.t3),
            FitText(context, "Собираю данные для вывода", sc.hl, sc.hm, 2, p.t1),
            context.text(
                "Нужно не меньше ${HomeLogic.NEED_TRIP_MIN.toInt()} минут поездки и ${(HomeLogic.NEED_IDLE_SEC / 60).toInt()} минут " +
                    "прогретого холостого хода. Сейчас: $min мин поездки, $idle с холостого.",
                sc.pl * 0.85f, p.t2, lineHeight = sc.pl * 1.15f,
            ),
            Progress(context, HomeLogic.progress(t).toFloat(), p.t3, p.s3),
            context.text("Вывод появится сам. Можно ехать как обычно — постойте пару минут на прогретом моторе.", sc.p, p.t2),
        ).apply {
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundRect(p.s1, dp(14).toFloat(), dp(2), p.line2, dash = dp(5).toFloat())
        }
        gap(box, 0)
    }

    private fun off(m: HomeModel) {
        justSaved(m)
        val dot = View(context).apply { background = roundRect(p.l0, dp(9).toFloat()) }
        dot.layoutParams = LinearLayout.LayoutParams(dp(sc.lamp), dp(sc.lamp))
        gap(row(context, dp(8), Gravity.CENTER_VERTICAL, dot, context.label("Машина не подключена", sc, p)), 0)
        gap(headline("Запись выключена"), 12)
        gap(context.text("Включите автозапись — Бортач будет сам записывать и разбирать каждую поездку.", sc.pl * 0.85f, p.t2, lineHeight = sc.pl * 1.15f), 12)
        gap(button("Включить автозапись") { onSettings() }, 16)
        pastVersion(m)
    }

    private fun noTrips() {
        gap(tag("Первый запуск"), 0)
        gap(headline("Поездок ещё нет"), 12)
        val steps = listOf(
            "Вставьте адаптер ELM327 в разъём OBD под рулём",
            "Сопрягите его в настройках Bluetooth (код 1234 или 0000)",
            "Выберите адаптер и включите автозапись",
        )
        steps.forEachIndexed { i, st ->
            gap(row(context, dp(14), Gravity.TOP, context.text("${i + 1}", sc.p, p.t3, 600, mono = true), context.text(st, sc.p, p.t1)), if (i == 0) 16 else 10)
        }
        gap(button("Настроить подключение") { onSettings() }, 20)
        gap(context.text("Вывод о машине появится после первой поездки: нужно 5 минут езды и 2 минуты прогретого холостого.", sc.cap, p.t3), 14)
    }

    /** Г7: right after a trip the main screen says it was saved and opens it. */
    /**
     * Г7: the previous trip's result, shown at the next start until it is opened —
     * the tablet usually switches off together with the car.
     */
    private fun justSaved(m: HomeModel) {
        val t = m.trip ?: return
        if (!m.unseen) return
        val top = t.top?.takeIf { t.durationMin >= HomeLogic.NEED_TRIP_MIN }
        val verdict = when {
            !t.dtcs.isNullOrEmpty() -> "Блок записал коды: ${t.dtcs!!.joinToString(", ")}"
            t.durationMin < HomeLogic.NEED_TRIP_MIN -> "Короткая поездка — для вывода мало данных"
            top != null -> "Есть версия: ${top.headline.replaceFirstChar { it.lowercase() }}"
            t.warmIdleSec < HomeLogic.NEED_IDLE_SEC -> "Мало прогретого холостого — вывод неполный"
            else -> "Отклонений не найдено"
        }
        val box = column(context, dp(6),
            row(context, dp(8), Gravity.CENTER_VERTICAL, context.icon(R.drawable.ic_check, p.acc, 18), context.label("Прошлая поездка сохранена", sc, p, p.acc)),
            context.text(HomeModel.tripRange(t), sc.hs, p.t1, 600, mono = true),
            context.text(verdict, sc.p, if (top != null || !t.dtcs.isNullOrEmpty()) p.amb else p.t2, 500),
            link("Разбор и файлы →") { onOpenLast() })
        box.setPadding(dp(18), dp(14), dp(18), dp(14))
        box.background = roundRect(p.s1, dp(14).toFloat(), dp(1), p.acc, dp(5).toFloat())
        gap(box, 0)
        gap(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(18)) }, 0)
    }

    private fun waiting(m: HomeModel, s: LoggerState.Snapshot) {
        justSaved(m)
        gap(context.label("Автозапись", sc, p), 0)
        gap(headline("Жду запуска двигателя"), 12)
        gap(context.text("Запись начнётся сама. Ничего нажимать не нужно.", sc.pl, p.t2, lineHeight = sc.pl * 1.35f), 12)
        gap(lampLine(s.link, "Планшет ↔ ЭБУ", s.linkText), 14)
        gap(lampLine(s.engine, "ЭБУ ↔ двигатель", s.engineText), 10)
        pastVersion(m)
    }

    private fun noConnection(m: HomeModel) {
        val dot = View(context).apply { background = roundRect(p.red, dp(9).toFloat()) }
        dot.layoutParams = LinearLayout.LayoutParams(dp(sc.lamp), dp(sc.lamp))
        gap(row(context, dp(8), Gravity.CENTER_VERTICAL, dot, context.label("Связь", sc, p)), 0)
        gap(headline("Нет связи с адаптером"), 12)
        gap(context.text("Адаптер не отвечает по Bluetooth. Чаще всего выключено зажигание или адаптер вынут из разъёма.", sc.pl, p.t2, lineHeight = sc.pl * 1.35f), 12)
        pastVersion(m)
    }

    private fun pastVersion(m: HomeModel) {
        // Already shown in the «Прошлая поездка сохранена» card above.
        if (m.unseen && m.past?.name == m.trip?.name) return
        val t = m.past
        if (t == null) {
            gap(divider(), 18)
            gap(context.text("Прошлые поездки были слишком короткими для вывода.", sc.p, p.t3), 14)
            return
        }
        val f = t.top
        val box = column(
            context, dp(6),
            context.label("Вывод по поездке ${HomeModel.tripRange(t)}", sc, p),
            context.text(if (f != null) "Есть версия: ${f.headline.replaceFirstChar { it.lowercase() }}" else "Отклонений не найдено", sc.hs, p.t1, 600),
        ).apply { alpha = 0.5f; setPadding(0, dp(14), 0, 0) }
        f?.let { box.addView(Confidence(context, it.confidence, sc, p)) }
        gap(divider(), 18)
        gap(box, 0)
    }

    // ---- pieces ----

    private fun lampLine(l: Lamp, title: String, state: String): View {
        val c = when (l) {
            Lamp.OK -> p.acc
            Lamp.WAIT -> p.amb
            Lamp.FAIL -> p.red
            Lamp.OFF -> p.l0
        }
        val dot = View(context).apply { background = roundRect(c, dp(11).toFloat()) }
        dot.layoutParams = LinearLayout.LayoutParams(dp(sc.lamp + 4), dp(sc.lamp + 4))
        val t = context.text("$title · $state", sc.p, p.t1)
        return row(context, dp(12), Gravity.CENTER_VERTICAL, dot, t)
    }

    private fun evidence(label: String, value: String, deviating: Boolean, emphasis: Boolean, small: Boolean = false): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(sc.evPad), 0, dp(sc.evPad))
        }
        addTo(box, context.text(label, if (small) sc.p else sc.evLabel * 0.9f, if (small) p.t2 else p.t1, if (emphasis) 600 else 400), 0, 1f)
        addTo(box, context.text(value, if (small) sc.evValue * 0.75f else sc.evValue * 0.85f, if (deviating) p.amb else p.t1, 500, mono = true), dp(16))
        return column(context, 0, divider(), box)
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(p.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun button(title: String, onClick: () -> Unit): View =
        context.text(title, sc.btnFont, p.accInk, 600).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(28), 0, dp(28), 0)
            // Measured text can be a hair narrower than drawn; never clip the label.
            minWidth = (paint.measureText(title) * 1.08f).toInt() + dp(56)
            background = roundRect(p.acc, dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(sc.btnH))
            setOnClickListener { onClick() }
        }

    private fun link(text: String, onClick: () -> Unit): TextView =
        context.text(text, if (sc.phone) 16f else if (sc === Bt.TABLET) 20f else 16f, p.acc, 600).apply { setOnClickListener { onClick() } }.tap()

    /** Trend over trips: three dots on a norm band. */
    class Sparkline(ctx: Context) : View(ctx) {
        private var v: List<Double> = emptyList()
        private var p = Bt.LIGHT
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun set(values: List<Double>, palette: Bt.Palette) {
            v = values
            p = palette
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (v.isEmpty()) return
            val lo = minOf(-10.0, v.min()) - 2
            val hi = maxOf(10.0, v.max()) + 2
            fun y(x: Double) = (height * (1 - (x - lo) / (hi - lo))).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = p.accZ
            canvas.drawRect(0f, y(10.0), width.toFloat(), y(-10.0), paint)
            val step = if (v.size > 1) (width - 16f) / (v.size - 1) else 0f
            val path = Path()
            v.forEachIndexed { i, x -> if (i == 0) path.moveTo(8f + i * step, y(x)) else path.lineTo(8f + i * step, y(x)) }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 1.75f
            paint.color = p.t1
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            v.forEachIndexed { i, x -> canvas.drawCircle(8f + i * step, y(x), resources.displayMetrics.density * 3f, paint) }
        }
    }

    class Progress(ctx: Context, private val fraction: Float, private val color: Int, private val track: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(6)) }
        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = track
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            paint.color = color
            canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), r, r, paint)
        }
    }

    /** Text that shrinks (down to [minSp]) until it fits in [maxLines]. */
    class FitText(ctx: Context, s: String, private val maxSp: Float, private val minSp: Float, private val lines: Int, color: Int) :
        TextView(ctx) {
        init {
            text = s
            setTextColor(color)
            typeface = Bt.sans(ctx, 600)
            includeFontPadding = false
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, maxSp)
            setLineSpacing(0f, 0.96f)
        }

        private var fittedFor = -1

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            if (width > 0 && width != fittedFor) {
                fittedFor = width
                val tp = android.text.TextPaint(paint)
                val density = resources.displayMetrics.scaledDensity
                var size = maxSp
                while (size > minSp) {
                    tp.textSize = size * density
                    @Suppress("DEPRECATION")
                    val l = android.text.StaticLayout(text, tp, width, android.text.Layout.Alignment.ALIGN_NORMAL, 0.96f, 0f, false)
                    if (l.lineCount <= lines) break
                    size -= 2f
                }
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size.coerceAtLeast(minSp))
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
