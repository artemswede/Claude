package com.obdlogger.app.ui

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.obdlogger.app.Lamp
import com.obdlogger.app.LoggerState
import com.obdlogger.core.DtcCatalog
import com.obdlogger.core.DtcExplain
import com.obdlogger.core.FreezeFrame
import com.obdlogger.core.NormState
import com.obdlogger.core.Norms
import com.obdlogger.core.SensorNames
import com.obdlogger.core.SeriesStore
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.Values

/** What the codes screen asks the activity to do. */
interface DtcActions {
    fun closeCodes()
    /** Read codes and the freeze frame again (needs the ECU on line). */
    fun readCodes()
    /** Save the report, then clear the codes and read them back. */
    fun clearCodes()
    fun shareCodes(path: String)
}

/**
 * «Коды ошибок»: what the ECU stored, for each code what the app sees behind it
 * (freeze frame, the recording), the freeze frame itself, and a reset that first
 * saves all of that to a report — so the reason is not lost with the code.
 */
class DtcView(ctx: Context, private val sc: Bt.Scale, private val actions: DtcActions, private val store: () -> SeriesStore?) : FrameLayout(ctx) {
    private val p = Bt.LIGHT
    private val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(32)) }
    private var shownKey: Any? = null

    init {
        setBackgroundColor(p.bg)
        addView(ctx.stripOver(ctx.backStrip("Назад", sc, p) { actions.closeCodes() }, ScrollView(ctx).apply { addView(page) }))
    }

    /** Codes from the trip-start text when nothing was read on this screen yet. */
    private fun codesFrom(info: String, label: String): List<String>? =
        Regex("$label: ([^\\n]+)").find(info)?.groupValues?.get(1)?.let { Regex("[PCBU][0-9A-F]{4}").findAll(it).map { m -> m.value }.toList() }

    fun bind(s: LoggerState.Snapshot) {
        val key = listOf(s.dtcBusy, s.dtcSnap, s.freeze, s.dtcResult, s.dtcInfo, s.link)
        if (key == shownKey) return
        shownKey = key
        page.removeAllViews()
        val ctx = context
        addTo(page, ctx.text("Коды ошибок", if (sc.phone) 24f else sc.hl, p.t1, 700))

        val snap = s.dtcSnap
        val stored = snap?.stored ?: codesFrom(s.dtcInfo, "Ошибки")
        val pending = snap?.pending ?: codesFrom(s.dtcInfo, "Ожидающие")
        val permanent = snap?.permanent
        val all = (stored.orEmpty() + pending.orEmpty() + permanent.orEmpty()).distinct()
        val online = s.running && s.link == Lamp.OK
        val mil = snap?.milOn?.let { if (it) "Check Engine горит" else "Check Engine не горит" }
        val source = if (snap == null) "по чтению в начале поездки" else "прочитано сейчас"
        addTo(page, ctx.text(listOfNotNull(mil, source).joinToString(" · "), sc.p, p.t2), dp(4))

        // Status of the ECU operation, and the outcome of a reset.
        s.dtcBusy?.let { addTo(page, card(ctx.text(it, sc.p, p.t1, 600), p, dp(16), dp(12), border = p.acc), dp(12)) }
        s.dtcResult?.let { addTo(page, card(ctx.text(it, sc.p, p.t1), p, dp(16), dp(12), border = p.amb), dp(12)) }
        if (!online && s.dtcBusy == null) addTo(page, ctx.text("Нет связи с ЭБУ: включите зажигание — Бортач подключится сам. Пока показано то, что прочитано раньше.", sc.cap, p.amb), dp(10))

        val ff = s.freeze
        // Actions.
        val buttons = LinearLayout(ctx).apply { orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val busy = s.dtcBusy != null
        val read = ctx.button(if (ff == null) "Прочитать со стоп-кадром" else "Прочитать заново", sc, p, primary = all.isEmpty() || ff == null) { actions.readCodes() }
        read.isEnabled = online && !busy
        read.alpha = if (read.isEnabled) 1f else 0.5f
        addTo(buttons, read)
        if (all.isNotEmpty()) {
            val clear = ctx.button("Сбросить ошибки…", sc, p, primary = false) { confirmClear(all) }
            clear.isEnabled = online && !busy
            clear.alpha = if (clear.isEnabled) 1f else 0.5f
            addTo(buttons, clear, dp(12))
        }
        s.dtcReportFile?.let { path -> addTo(buttons, ctx.button("Отправить отчёт", sc, p, primary = false) { actions.shareCodes(path) }, dp(12)) }
        addTo(page, buttons, dp(14))
        if (all.isEmpty()) {
            addTo(page, ctx.text(if (stored == null) "Коды ещё не читались." else "Кодов нет.", sc.hs, p.t1, 600), dp(20))
        }
        val st = store()
        fun codeCard(code: String, kind: String) {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val head = row(ctx, dp(12), Gravity.CENTER_VERTICAL,
                ctx.text(code, if (sc.phone) 22f else 26f, p.t1, 600, mono = true),
                ctx.chip(kind, if (kind == "сохранён") p.red else p.amb, if (kind == "сохранён") p.redT else p.ambT, 13f))
            addTo(box, head)
            addTo(box, ctx.text(DtcCatalog.describe(code), sc.pl * 0.9f, p.t1, 500), dp(6))
            val facts = DtcExplain.explain(code, ff, st, all)
            if (facts.isNotEmpty()) {
                addTo(box, ctx.label("Что видно по данным", sc, p), dp(12))
                facts.forEach { f -> addTo(box, row(ctx, dp(8), Gravity.TOP, ctx.text("•", sc.p, p.t3), ctx.text(f, sc.p, p.t2, lineHeight = sc.p * 1.3f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }), dp(6)) }
            } else {
                addTo(box, ctx.text("Данных для разбора мало: прочитайте коды со стоп-кадром при включённом зажигании.", sc.cap, p.t3), dp(8))
            }
            addTo(page, card(box, p, dp(18), dp(16), border = if (kind == "сохранён") p.red else p.line2), dp(14))
        }
        stored.orEmpty().forEach { codeCard(it, "сохранён") }
        pending.orEmpty().filter { it !in stored.orEmpty() }.forEach { codeCard(it, "ожидает подтверждения") }
        permanent.orEmpty().filter { it !in stored.orEmpty() }.forEach { codeCard(it, "постоянный") }

        ff?.let { addTo(page, freezeCard(it), dp(14)) }

        addTo(page, ctx.text("Каждое чтение и сброс сохраняются в отчёт dtc_….txt (Загрузки / OBD-Logger) вместе со стоп-кадром и разбором.", sc.cap, p.t3), dp(10))
    }

    /** Freeze frame: the sensors when the ECU set the code; out-of-norm values marked. */
    private fun freezeCard(ff: FreezeFrame): View {
        val ctx = context
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addTo(box, ctx.label("Стоп-кадр${ff.cause?.let { " · код $it" } ?: ""}", sc, p))
        addTo(box, ctx.text("Значения датчиков в момент, когда ЭБУ записал ошибку: ${DtcExplain.conditions(ff)}", sc.cap, p.t2), dp(4))
        val mode = ff.mode
        val rows = ArrayList<Triple<String, String, Boolean>>()
        for (b in 1..2) ff.trim(b)?.let { t ->
            val out = Norms.of("trim_b$b", mode)?.state(t)?.let { it == NormState.LOW || it == NormState.HIGH } == true
            rows += Triple(SensorNames.label("trim_b$b"), TripAnalyzer.pct(t), out)
        }
        for (v in ff.values) {
            val out = Norms.of(v.code, mode)?.state(v.value)?.let { it == NormState.LOW || it == NormState.HIGH } == true
            rows += Triple(SensorNames.label(v.code), "${Values.format(v.value)} ${v.unit}".trim(), out)
        }
        for ((name, value, out) in rows) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(ctx.text(name, sc.p, p.t1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addTo(r, ctx.text(value + if (out) "  за нормой" else "", sc.p, if (out) p.amb else p.t1, if (out) 600 else 500, mono = true))
            addTo(box, r, dp(6))
        }
        return card(box, p, dp(18), dp(16))
    }

    private fun confirmClear(codes: List<String>) {
        AlertDialog.Builder(context)
            .setTitle("Сбросить ${codes.joinToString(", ")}?")
            .setMessage(
                "Сначала Бортач сохранит коды, стоп-кадр и разбор в отчёт — причина не потеряется.\n\n" +
                    "После сброса:\n" +
                    "• погаснет Check Engine;\n" +
                    "• обнулятся мониторы готовности (их проверяют на техосмотре) — восстановятся за несколько поездок;\n" +
                    "• ЭБУ забудет долгосрочные коррекции — первые минуты холостой может плавать, а коррекции в следующих поездках начнут «с нуля».\n\n" +
                    "Лучше делать при включённом зажигании и заглушенном моторе. Если причина не устранена, код вернётся.",
            )
            .setPositiveButton("Сохранить и сбросить") { _, _ -> actions.clearCodes() }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
