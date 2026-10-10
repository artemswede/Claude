package com.obdlogger.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.obdlogger.app.Prefs
import com.obdlogger.core.Hypotheses
import com.obdlogger.core.Hypothesis

/** What the version and plan pages can ask the activity to do. */
interface VersionActions {
    fun back()
    fun openPlan(h: Hypothesis)
    fun startCheck(kind: com.obdlogger.core.CheckKind)
    fun openCompare()
    fun printPlan(h: Hypothesis)
    fun sharePlan(h: Hypothesis)
}

private fun Context.sectionTitle(n: Int, title: String, sc: Bt.Scale, p: Bt.Palette): View {
    val row = row(this, dp(10), Gravity.BOTTOM,
        text("$n", sc.cap, p.t3, 400, mono = true),
        text(title, if (sc.phone) 20f else 26f, p.t1, 700))
    return column(this, dp(8), row, View(this).apply {
        setBackgroundColor(p.t1)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
    })
}

/**
 * Карточка версии (К2): what the version means, proof from the logs, what speaks
 * against alternatives, the next step, how to tell it helped and where it shows.
 */
class VersionView(ctx: Context, private val sc: Bt.Scale, private val h: Hypothesis, backTitle: String, private val actions: VersionActions) : FrameLayout(ctx) {
    private val p = Bt.LIGHT
    /** Head unit (≈730 dp wide): one column, as on a phone. */
    private val narrow = sc.phone || sc === Bt.WIDE

    init {
        setBackgroundColor(p.bg)
        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(32)) }
        val f = h.finding
        val seen = h.seenIn
        val span = if (seen.size >= 2) "${seen.size} ${if (seen.size in 2..4) "поездки" else "поездок"} · ${Hypotheses.date(seen.first().first)}–${Hypotheses.date(seen.last().first)}" else null
        val head = LinearLayout(ctx).apply { orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val left = column(ctx, dp(8),
            ctx.label("Есть версия · наблюдение по записям, не код ЭБУ", sc, p, p.acc),
            HomeView.FitText(ctx, f.headline, if (sc.phone) 28f else if (narrow) 32f else 44f, if (narrow) 22f else 30f, 2, p.t1))
        val right = column(ctx, dp(4), Confidence(ctx, f.confidence, sc, p))
        span?.let { addTo(right, ctx.text(it, sc.cap, p.t2).apply { gravity = Gravity.END }) }
        if (sc.phone) {
            addTo(head, left); addTo(head, right, dp(8))
        } else {
            head.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(24) })
        }
        addTo(page, head, dp(12))
        addTo(page, ctx.text(h.explanation, if (narrow) 16f else 21f, p.t2, lineHeight = if (narrow) 22f else 30f), dp(10))

        // Left column: proof + alternatives. Right: next step, how to tell, where it shows.
        val l = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addTo(l, ctx.sectionTitle(1, "Доказательства из лога", sc, p))
        val cols = if (narrow) 1 else 2
        h.proofs.chunked(cols).forEach { pair ->
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, card ->
                val c = column(ctx, dp(8), ctx.text(card.title, if (sc.phone) 15f else 17f, p.t1, 500))
                card.figures.forEach { e ->
                    // The label takes the rest of the row, so the number always stays in view.
                    val label = ctx.text(e.label, if (sc.phone) 13f else 15f, p.t2).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                    val value = ctx.text(e.value, if (sc.phone) 17f else 21f, if (e.deviating) p.amb else p.t1, 500, mono = true)
                    // Long values (a trend over trips) go under their label.
                    addTo(c, if (e.value.length > 10) column(ctx, dp(2), label, value) else row(ctx, dp(10), Gravity.CENTER_VERTICAL, label, value))
                }
                card.note?.let { addTo(c, ctx.text(it, if (sc.phone) 12f else 14f, p.t2), dp(4)) }
                r.addView(card(c, p, dp(16), dp(14)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = dp(12) })
            }
            if (pair.size < cols) r.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f).apply { leftMargin = dp(12) })
            addTo(l, r, dp(12))
        }
        if (f.kind != "dtc") {
            addTo(l, card(ctx.text("Кодов ЭБУ нет, Check Engine не горит — это нормально: блок пишет код позже, когда отклонение упрётся в предел.", if (sc.phone) 14f else 16f, p.t1), p, dp(16), dp(12)), dp(12))
        }
        if (h.alternatives.isNotEmpty()) {
            addTo(l, ctx.sectionTitle(2, "Чем это не объясняется", sc, p), dp(24))
            h.alternatives.forEach { a ->
                if (narrow) {
                    // Name and verdict on one line, the reason under them in full.
                    val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    top.addView(ctx.text(a.name, if (sc.phone) 15f else 17f, p.t1, 600), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addTo(top, ctx.chip(a.verdict, p.t2, p.s3, 13f), dp(12))
                    val c = column(ctx, dp(6), top, ctx.text(a.why, if (sc.phone) 13f else 15f, p.t2))
                    c.setPadding(dp(8), dp(12), 0, dp(12))
                    addTo(l, c)
                } else {
                    val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(14), 0, dp(14)) }
                    r.addView(ctx.text(a.name, if (sc.phone) 15f else 17f, p.t1, 600), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    r.addView(ctx.text(a.why, if (sc.phone) 13f else 15f, p.t2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f).apply { leftMargin = dp(12) })
                    r.addView(ctx.chip(a.verdict, p.t2, p.s3, 13f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
                    addTo(l, r)
                }
                addTo(l, hline(ctx, p.line))
            }
        }

        val rcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addTo(rcol, ctx.sectionTitle(3, "Следующий шаг", sc, p))
        val next = column(ctx, dp(8),
            ctx.text(h.nextTitle, if (sc.phone) 17f else 21f, p.t1, 700),
            ctx.text(h.nextText, if (sc.phone) 15f else 17f, p.t2))
        h.checklist.forEachIndexed { i, s ->
            addTo(next, row(ctx, dp(10), Gravity.TOP, ctx.text("${i + 1}", 15f, p.t3, 400, mono = true), ctx.text(s, if (sc.phone) 15f else 17f, p.t1)))
        }
        addTo(rcol, card(next, p, dp(18), dp(16), p.acc), dp(12))
        // A short check only where it answers this version; otherwise the next trip says enough.
        val check = com.obdlogger.core.CheckKind.forFinding(h.finding.kind)
        addTo(rcol, card(column(ctx, dp(6),
            ctx.label("Как понять, что помогло", sc, p),
            ctx.text(h.success, if (sc.phone) 15f else 17f, p.t1),
            ctx.text(check?.let { "Проверка «${it.title}» до и после ремонта сравнится честно, в одинаковых условиях. Или просто ездите — Бортач сравнит поездки с обычным для машины." }
                ?: "Просто ездите как обычно — Бортач сравнит следующие поездки с обычным для машины.", if (sc.phone) 13f else 14f, p.t2)), p, dp(18), dp(14)), dp(12))
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(ctx.button("План для мастера", sc, p, primary = true) { actions.openPlan(h) }, LinearLayout.LayoutParams(0, dp(sc.btnH), 1f))
        check?.let { k -> btns.addView(ctx.button("Проверка: ${k.title.lowercase()}", sc, p, primary = false) { actions.startCheck(k) }, LinearLayout.LayoutParams(0, dp(sc.btnH), 1f).apply { leftMargin = dp(10) }) }
        addTo(rcol, btns, dp(12))
        if (seen.isNotEmpty()) {
            addTo(rcol, ctx.sectionTitle(4, "Где это видно", sc, p), dp(24))
            seen.reversed().forEach { (t, v) ->
                val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
                r.addView(ctx.text(Hypotheses.date(t), 15f, p.t1, 400, mono = true), LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
                val idle = t.warmIdleSec / 60
                r.addView(ctx.text("${t.durationMin.toInt()} мин · ХХ ${idle.toInt()} мин", 15f, p.t2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(ctx.text(v, 15f, p.amb, 500, mono = true))
                addTo(rcol, r)
                addTo(rcol, hline(ctx, p.line))
            }
            addTo(rcol, ctx.text("Сравнить поездки →", if (sc.phone) 16f else 18f, p.acc, 600).apply { setOnClickListener { actions.openCompare() } }.tap(), dp(12))
        }

        if (narrow) {
            addTo(page, rcol, dp(24))
            addTo(page, l, dp(24))
        } else {
            val two = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            two.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            two.addView(rcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(28) })
            addTo(page, two, dp(24))
        }
        addView(ctx.stripOver(ctx.backStrip(backTitle, sc, p) { actions.back() }, ScrollView(ctx).apply { addView(page) }))
    }
}

/**
 * План проверки для мастера (К3): car and key figures, numbered steps with how to
 * check and what to expect, «сделано» ticks with a note. Print / PDF and share.
 */
class PlanView(ctx: Context, private val sc: Bt.Scale, private val h: Hypothesis, car: String, private val actions: VersionActions) : FrameLayout(ctx) {
    private val p = Bt.LIGHT
    /** Head unit: a card per step instead of a five-column table. */
    private val narrow = sc.phone || sc === Bt.WIDE

    init {
        setBackgroundColor(p.bg)
        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(32)) }
        val head = LinearLayout(ctx).apply { orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val f = h.finding
        val n = h.seenIn.size
        val title = column(ctx, dp(8),
            ctx.text("План проверки для мастера", if (sc.phone) 26f else if (narrow) 28f else 38f, p.t1, 700),
            ctx.text("Составлен ${java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM"))}" +
                (if (n > 0) " по $n ${if (n in 2..4) "поездкам" else if (n == 1) "поездке" else "поездкам"}" else "") +
                " · версия: ${f.headline.replaceFirstChar { it.lowercase() }} · уверенность ${f.confidence}", if (sc.phone) 14f else 18f, p.t2))
        val btns = row(ctx, dp(10), Gravity.CENTER_VERTICAL,
            ctx.button("Печать / PDF", sc, p, primary = false) { actions.printPlan(h) },
            ctx.button("Поделиться", sc, p, primary = true) { actions.sharePlan(h) })
        if (sc.phone) { addTo(head, title); addTo(head, btns, dp(12)) } else {
            head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(20) })
        }
        addTo(page, head, dp(12))

        // Car card with key figures.
        val carBox = LinearLayout(ctx).apply { orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        carBox.addView(column(ctx, dp(6), ctx.label("Машина", sc, p), ctx.text(car, if (sc.phone) 18f else 22f, p.t1, 700)),
            if (sc.phone) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val figs = h.keyFigures.filter { it.value != "—" && it.value != "— / —" }
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        figs.chunked(3).forEach { r ->
            val rr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            r.forEach { e ->
                rr.addView(column(ctx, dp(2), ctx.text(e.label, if (sc.phone) 12f else 14f, p.t2), ctx.text(e.value, if (sc.phone) 15f else 19f, if (e.deviating) p.amb else p.t1, 500, mono = true)),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
            }
            repeat(3 - r.size) { rr.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f)) }
            addTo(grid, rr, dp(10))
        }
        carBox.addView(grid, if (sc.phone) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { leftMargin = dp(20) })
        addTo(page, card(carBox, p, dp(20), dp(16)), dp(18))

        // Steps.
        val weights = floatArrayOf(0.3f, 2.2f, 1.4f, 2f, 1.8f)
        fun tr(cells: List<View>) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(14))
            cells.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]).apply { if (i > 0) leftMargin = dp(12) }) }
        }
        if (!narrow) {
            addTo(page, tr(listOf("№", "Что проверить", "Чем", "Ожидаемый результат", "Сделано · результат").map { ctx.label(it, sc, p) }), dp(16))
            addTo(page, hline(ctx, p.line2))
        }
        val prefs = Prefs.of(ctx)
        h.plan.forEachIndexed { i, st ->
            val key = "plan_${f.kind}_$i"
            val what = column(ctx, dp(2), ctx.text(st.what, if (sc.phone) 16f else 18f, p.t1, 700))
            st.detail?.let { addTo(what, ctx.text(it, if (sc.phone) 13f else 15f, p.t2)) }
            val done = CheckBox(ctx).apply {
                isChecked = prefs.getBoolean(key, false)
                text = if (isChecked) "сделано" else "результат…"
                setTextColor(p.t2)
                buttonTintList = android.content.res.ColorStateList.valueOf(p.acc)
                setOnCheckedChangeListener { b, on -> prefs.edit().putBoolean(key, on).apply(); b.text = if (on) "сделано" else "результат…" }
            }
            if (narrow) {
                val fs = if (sc.phone) 14f else 16f
                what.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val c = column(ctx, dp(6),
                    row(ctx, dp(10), Gravity.TOP, ctx.text("${i + 1}", 16f, p.t3, 400, mono = true), what),
                    ctx.text("Чем: ${st.how}", fs, p.t1),
                    ctx.text("Ожидаем: ${st.expected}", fs, p.t2), done)
                c.setPadding(0, dp(14), 0, dp(14))
                addTo(page, c)
            } else {
                addTo(page, tr(listOf(ctx.text("${i + 1}", 17f, p.t2, 400, mono = true), what,
                    ctx.text(st.how, 17f, p.t1), ctx.text(st.expected, 17f, p.t1), done)))
            }
            addTo(page, hline(ctx, p.line))
        }
        addTo(page, ctx.text("План — проверяемые шаги по данным записи, а не диагноз. Если ни один шаг не подтвердился, версия ослабевает — Бортач пересчитает её после следующих поездок.", if (sc.phone) 13f else 15f, p.t2), dp(14))
        addView(ctx.stripOver(ctx.backStrip("Версия", sc, p) { actions.back() }, ScrollView(ctx).apply { addView(page) }))
    }
}
