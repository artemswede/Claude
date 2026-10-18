package com.obdlogger.app.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.obdlogger.app.Lamp
import com.obdlogger.app.LoggerState
import com.obdlogger.core.Mode06Result

interface Mode06Actions {
    fun closeMode06()
    fun readMode06()
}

class Mode06View(ctx: Context, private val sc: Bt.Scale, private val actions: Mode06Actions) : FrameLayout(ctx) {
    private val p = Bt.theme
    private val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(32)) }
    private var shownKey: Any? = null

    init {
        setBackgroundColor(p.bg)
        addView(ctx.stripOver(ctx.backStrip("Назад", sc, p) { actions.closeMode06() }, ScrollView(ctx).apply { addView(page) }))
    }

    fun bind(s: LoggerState.Snapshot) {
        val key = listOf(s.mode06Busy, s.mode06, s.link)
        if (key == shownKey) return
        shownKey = key
        page.removeAllViews()
        val ctx = context
        addTo(page, ctx.text("Мониторы ЭБУ", if (sc.phone) 24f else sc.hl, p.t1, 700))
        addTo(page, ctx.text("Mode 06: результаты бортовых проверок — что ЭБУ протестировал и прошло ли", sc.p, p.t2), dp(4))

        s.mode06Busy?.let { addTo(page, card(ctx.text(it, sc.p, p.t1, 600), p, dp(16), dp(12), border = p.acc), dp(12)) }

        val online = s.running && s.link == Lamp.OK
        if (!online && s.mode06Busy == null) addTo(page, ctx.text("Нет связи с ЭБУ: включите зажигание — Бортач подключится сам.", sc.cap, p.amb), dp(10))

        val read = ctx.button("Прочитать мониторы", sc, p, primary = true) { actions.readMode06() }
        val busy = s.mode06Busy != null
        read.isEnabled = online && !busy
        read.alpha = if (read.isEnabled) 1f else 0.5f
        addTo(page, read, dp(14))

        val snap = s.mode06
        if (snap == null) {
            addTo(page, ctx.text("Мониторы ещё не читались.", sc.hs, p.t1, 600), dp(20))
            return
        }

        val summary = "Всего ${snap.totalCount}: пройдено ${snap.passedCount}, не пройдено ${snap.failedCount}"
        addTo(page, ctx.text(summary, sc.hs, if (snap.failedCount > 0) p.amb else p.acc, 600), dp(12))

        val grouped = snap.results.groupBy { it.mid }
        for ((mid, results) in grouped) {
            val midName = results.first().midName
            val allPassed = results.all { it.passed }
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            addTo(box, row(ctx, dp(8), Gravity.CENTER_VERTICAL,
                ctx.text(midName, sc.hs, p.t1, 600),
                ctx.chip(
                    if (allPassed) "ОК" else "НЕ ПРОЙДЕН",
                    if (allPassed) p.acc else p.red,
                    if (allPassed) p.accT else p.redT,
                    12f
                )))
            addTo(box, ctx.text("MID %02X".format(mid), sc.cap, p.t3), dp(2))

            for (r in results) {
                val rr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val label = "${r.tidName} · ${r.unitName}".trim()
                rr.addView(ctx.text(label, sc.p, p.t2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val valText = formatValue(r)
                addTo(rr, ctx.text(valText, sc.p, if (r.passed) p.t1 else p.amb, if (r.passed) 500 else 600, mono = true))
                addTo(box, rr, dp(4))
            }

            addTo(page, card(box, p, dp(18), dp(16), border = if (allPassed) p.line2 else p.red), dp(10))
        }
    }

    private fun formatValue(r: Mode06Result): String {
        val v = formatNum(r.value)
        val mn = formatNum(r.min)
        val mx = formatNum(r.max)
        val u = r.unitName
        return if (r.min == 0.0 && r.max == 0.0) {
            "$v $u".trim()
        } else {
            "$v $u  ($mn…$mx)".trim()
        }
    }

    private fun formatNum(d: Double): String {
        return if (d == d.toLong().toDouble()) d.toLong().toString()
        else "%.3f".format(d).trimEnd('0').trimEnd('.')
    }
}
