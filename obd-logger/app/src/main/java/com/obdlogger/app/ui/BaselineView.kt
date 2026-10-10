package com.obdlogger.app.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.obdlogger.app.CarProfile
import com.obdlogger.core.Baseline
import com.obdlogger.core.SensorNames
import com.obdlogger.core.Severity
import java.time.format.DateTimeFormatter

interface BaselineActions {
    fun closeBaseline()
    /** «Обнулить профиль» after a repair; [note] — what was done. */
    fun resetProfile(note: String?)
    /** Ask the AI about one change. */
    fun askAbout(question: String)
}

/**
 * «Как обычно у вашей машины»: what changed against this car's own normal (strongest
 * first, with the trend over trips), then the normal itself per sensor and mode, and
 * a reset for after a repair.
 */
class BaselineView(ctx: Context, private val sc: Bt.Scale, private val actions: BaselineActions) : FrameLayout(ctx) {
    private val p = Bt.LIGHT
    private val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(12), dp(sc.pad), dp(32)) }
    private var shownKey: Any? = null

    init {
        setBackgroundColor(p.bg)
        addView(ctx.stripOver(ctx.backStrip("Обзор", sc, p) { actions.closeBaseline() }, ScrollView(ctx).apply { addView(page) }))
    }

    fun bind(st: CarProfile.State?) {
        val key = st?.key
        if (key == shownKey && page.childCount > 0) return
        shownKey = key
        page.removeAllViews()
        val ctx = context
        addTo(page, ctx.text("Как обычно у вашей машины", if (sc.phone) 22f else sc.hl, p.t1, 700))
        if (st == null) {
            addTo(page, ctx.text("Считаю профиль по поездкам…", sc.p, p.t2), dp(8))
            return
        }
        val date = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val sinceText = st.since?.let { " · с ${it.format(date)}" + (st.sinceNote?.let { n -> " (после: $n)" } ?: "") } ?: ""
        val (have, need) = st.learning
        if (!st.ready) {
            addTo(page, ctx.text("Поездок в профиле: $have$sinceText", sc.cap, p.t3), dp(4))
            addTo(page, card(column(ctx, dp(8),
                ctx.text("Изучаю вашу машину: $have из $need поездок", sc.hs, p.t1, 700),
                ctx.text("Бортач запоминает, как каждый датчик работает именно у этой машины — в прогреве, на холостом, в движении. " +
                    "После $need поездок он начнёт замечать, когда обычное поведение меняется: например, температура ОЖ была 97–98 °C, а стала 100–101 °C — " +
                    "ещё в пределах общей нормы, но уже не как обычно. Так поломка видна заранее.", sc.p, p.t2),
            ), p, dp(18), dp(16), p.line2), dp(12))
            return
        }
        addTo(page, ctx.text("Норма — по ${have - Baseline.RECENT} поездкам, «сейчас» — последние ${Baseline.RECENT}$sinceText", sc.cap, p.t3), dp(4))

        val drifts = st.drifts
        if (drifts.isEmpty()) {
            addTo(page, card(column(ctx, dp(6),
                ctx.text("✓ Всё как обычно", sc.hs, p.acc, 700),
                ctx.text("Последние поездки не отличаются от обычного для этой машины ни по одному датчику.", sc.p, p.t2),
            ), p, dp(18), dp(14), p.acc), dp(12))
        } else {
            addTo(page, ctx.label("Изменилось по сравнению с обычным", sc, p), dp(16))
            for (d in drifts) {
                val color = if (d.level >= Severity.WARN) p.amb else p.acc
                val box = column(ctx, dp(6),
                    ctx.text(d.level.ru.uppercase() + " · " + d.mode.ru.lowercase(), sc.lbl, color, 700),
                    ctx.text(d.short, sc.hs, p.t1, 700),
                    ctx.text(d.text, sc.p, p.t2),
                    ctx.text("Возможные причины: ${d.causes}.", sc.p, p.t2),
                )
                val pts = Baseline.series(st.profiles, d.code, d.mode).takeLast(12)
                if (pts.size >= 2) {
                    val unit = SensorNames.unit(d.code)
                    val chart = TrendChartView(ctx, p, "${SensorNames.label(d.code)} по поездкам · полоса — обычно у вас", unit, pts, d.norm.lo, d.norm.hi)
                    box.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (sc.phone) 170 else 190)).apply { topMargin = dp(8) })
                }
                val ask = ctx.button("Спросить ИИ об этом", sc, p, primary = false) {
                    actions.askAbout("${d.short}. ${d.text} Что это может значить для моей машины и что проверить сейчас?")
                }
                addTo(box, ask, dp(8), width = ViewGroup.LayoutParams.WRAP_CONTENT)
                addTo(page, card(box, p, dp(18), dp(14), color), dp(10))
            }
        }

        // The normal itself: one line per sensor and mode.
        addTo(page, ctx.label("Обычно у вашей машины", sc, p), dp(20))
        val rows = st.norms.filter { Baseline.shown(it) }.sortedBy { SensorNames.label(it.code) }
        val last = st.profiles.maxByOrNull { it.start ?: java.time.LocalDateTime.MIN }
        val table = column(ctx, 0)
        for (n in rows) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(40) }
            val unit = SensorNames.unit(n.code).let { if (it.isEmpty()) "" else " $it" }
            addTo(r, ctx.text(SensorNames.label(n.code), sc.p, p.t1, 500, maxLines = 1), 0, 1.4f)
            addTo(r, ctx.text(n.mode.ru.lowercase(), sc.cap, p.t3, maxLines = 2), 0, 1f)
            addTo(r, ctx.text(Baseline.range(n.code, n.lo, n.hi) + unit, sc.p, p.t1, 600, mono = true, maxLines = 1), 0, 1f)
            val now = last?.of(n.code, n.mode)?.median
            val off = now != null && (now < n.lo - n.spread || now > n.hi + n.spread)
            addTo(r, ctx.text(now?.let { "сейчас " + Baseline.fmt(n.code, it) } ?: "", sc.cap, if (off) p.amb else p.t3, if (off) 600 else 400, mono = true, maxLines = 1), 0, 1f)
            addTo(table, r)
            addTo(table, hline(ctx, p.line))
        }
        addTo(page, card(table, p, dp(16), dp(6)), dp(8))

        addTo(page, ctx.text("После ремонта обнулите профиль: Бортач заново выучит обычное для машины, и старое состояние не будет считаться нормой.", sc.p, p.t2), dp(20))
        addTo(page, ctx.button("Обнулить профиль (после ремонта)", sc, p, primary = false) { confirmReset() }, dp(8), width = ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun confirmReset() {
        val field = android.widget.EditText(context).apply { hint = "Что сделано (необязательно)"; setSingleLine() }
        val box = FrameLayout(context).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        android.app.AlertDialog.Builder(context)
            .setTitle("Обнулить профиль машины?")
            .setMessage("Поездки останутся в журнале, но обычное для машины Бортач начнёт учить заново — с этой минуты.")
            .setView(box)
            .setPositiveButton("Обнулить") { _, _ -> actions.resetProfile(field.text.toString().takeIf { it.isNotBlank() }) }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
