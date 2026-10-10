package com.obdlogger.app.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.obdlogger.core.ChartRequest
import com.obdlogger.core.ChatCharts
import com.obdlogger.core.ChatState

/** What the chat page asks the activity to do. */
interface ChatActions {
    fun sendQuestion(text: String)
    fun voiceQuestion()
    fun editAiKey()
    /** The one chat starts over: messages and its memory are erased. */
    fun resetChat()
    /** «Поиск гипотез»: the built-in deep research brief, sent as a question. */
    fun runResearch()
    /** A chart the assistant asked for, drawn from this car's data; null if there is nothing to draw. */
    fun chartView(req: ChartRequest): View?
    /** The owner pressed a check the assistant offered: opens it (the owner starts it there). */
    fun runCheck(kind: com.obdlogger.core.CheckKind) {}
    /** The owner agreed to watch a condition in the next trips. */
    fun watch(rule: com.obdlogger.core.WatchRule) {}
    fun unwatch(rule: com.obdlogger.core.WatchRule) {}
}

/**
 * «Чат»: free questions about this car — answered by the AI from Бортач's own summary
 * of the trips, versions, codes and check logs. Voice through the system speech input
 * (or the keyboard's microphone); answers as text.
 */
class ChatView(ctx: Context, private val sc: Bt.Scale, private val actions: ChatActions) : FrameLayout(ctx) {
    private val p = Bt.LIGHT
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(12), dp(sc.pad), dp(12)) }
    private val scroll = ScrollView(ctx).apply { addView(list); isFillViewport = true }
    private val input = EditText(ctx).apply {
        hint = "Спросите про машину…"
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (sc.phone) 16f else 17f)
        setTextColor(p.t1)
        setHintTextColor(p.t3)
        background = roundRect(p.s1, dp(14).toFloat(), dp(1), p.line2)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        maxLines = 3
        setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { send(); true } else false
        }
    }
    private var shownKey: Any? = null

    init {
        setBackgroundColor(p.bg)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(sc.pad), dp(8), dp(sc.pad), dp(10))
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addTo(bar, ctx.button("🎤", sc, p, primary = false) { actions.voiceQuestion() }.apply { contentDescription = "Спросить голосом" }, dp(8))
        addTo(bar, ctx.button("Спросить", sc, p, primary = true) { send() }, dp(8))
        col.addView(bar)
        addView(col)
    }

    private fun send() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        input.setText("")
        actions.sendQuestion(q)
    }

    /** [busy] — what is happening while waiting («Думаю…»), null when idle; [error] — the last failure. */
    fun bind(state: ChatState, busy: String?, hasKey: Boolean, context: String, error: String?,
        watches: List<com.obdlogger.core.WatchRule> = emptyList(), moving: Boolean = false) {
        val history = state.messages
        val key = listOf(history.size, history.lastOrNull()?.text?.length, state.summary.length, busy, hasKey, context, error, watches.map { it.encode() }, moving)
        if (key == shownKey) return
        shownKey = key
        list.removeAllViews()
        val ctx = getContext()
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(head, ctx.text("Чат по машине", if (sc.phone) 22f else sc.hm, p.t1, 700), 0, 1f)
        // One chat only: no list of chats, just a reset.
        if (history.isNotEmpty() || state.summary.isNotBlank()) addTo(head, ctx.button("Сброс чата", sc, p, primary = false) { actions.resetChat() }, dp(12))
        addTo(list, head)
        addTo(list, ctx.text(context, sc.cap, p.t3), dp(2))
        if (state.summary.isNotBlank()) {
            addTo(list, ctx.text("Ранние сообщения сжаты в память чата — факты, числа и гипотезы из них помнятся.", sc.cap, p.t3), dp(4))
        }
        if (!hasKey) {
            addTo(list, card(column(ctx, dp(8),
                ctx.text("Нужен ключ OpenRouter или DeepSeek", sc.hs, p.t1, 700),
                ctx.text("Ключ OpenRouter (sk-or-…, openrouter.ai → Keys) — тогда отвечает DeepSeek V4 Flash; или ключ platform.deepseek.com. Он хранится только на этом устройстве. " +
                    "К вопросу прикладывается сводка по вашим поездкам (выводы, сравнения, коды) — она уходит на серверы OpenRouter / DeepSeek.", sc.p, p.t2),
                ctx.button("Ввести ключ", sc, p, primary = true) { actions.editAiKey() }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(sc.btnH)) },
            ), p, dp(18), dp(16), p.acc), dp(14))
        }
        // What Бортач watches in the trips for the assistant, with a cancel each.
        if (watches.isNotEmpty()) {
            val box = column(ctx, dp(4), ctx.label("Наблюдаю в поездках", sc, p))
            for (w in watches) {
                val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                addTo(r, ctx.text("👁 ${w.text} · ещё ${w.tripsLeft} ${if (w.tripsLeft == 1) "поездка" else if (w.tripsLeft in 2..4) "поездки" else "поездок"}", sc.p, p.t1), 0, 1f)
                addTo(r, ctx.text("✕", 20f, p.t2, 600).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(48)
                    contentDescription = "Не наблюдать"
                    setOnClickListener { actions.unwatch(w) }
                }.tap())
                addTo(box, r)
            }
            addTo(list, card(box, p, dp(16), dp(8), p.line2), dp(10))
        }
        // The built-in research: one press, the whole engine study in a client-ready answer.
        addTo(list, ctx.button("🔬  Поиск гипотез — глубокое исследование", sc, p, primary = true) { if (hasKey) actions.runResearch() else actions.editAiKey() }.apply {
            isEnabled = busy == null
            alpha = if (isEnabled) 1f else 0.5f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(sc.btnH))
        }, dp(12))
        if (history.isEmpty()) {
            addTo(list, ctx.text("Примеры вопросов — нажмите, чтобы спросить:", sc.p, p.t2), dp(16))
            val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(
                "Что сейчас с машиной?",
                "Почему такая версия и как её проверить?",
                "Объясни коды ошибок",
                "Что записать в следующей поездке?",
                "Стало ли лучше за последние поездки?",
            ).forEach { q ->
                addTo(chips, ctx.text(q, sc.p, p.acc, 600).apply {
                    setPadding(dp(14), 0, dp(14), 0)
                    background = roundRect(p.accT, dp(20).toFloat())
                    setOnClickListener { if (hasKey) actions.sendQuestion(q) else actions.editAiKey() }
                }.tap(), dp(8))
            }
            addTo(list, HorizontalScrollView(ctx).apply { addView(chips); isHorizontalScrollBarEnabled = false }, dp(8))
        }
        for (m in history) {
            val mine = m.role == "user"
            val shown = if (mine) (if (m.text == com.obdlogger.core.ChatPrompt.HYPOTHESIS_BRIEF) "🔬 Поиск гипотез: глубокое исследование двигателя по всем данным" else m.text)
                else com.obdlogger.app.AiChat.plain(com.obdlogger.core.ChatTests.strip(ChatCharts.strip(m.text)))
            val bubble = ctx.text(shown, if (sc.phone) 15f else 17f, p.t1, lineHeight = if (sc.phone) 21f else 24f).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = if (mine) roundRect(p.accT, dp(14).toFloat()) else roundRect(p.s1, dp(14).toFloat(), dp(1), p.line)
                setTextIsSelectable(!mine)
            }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (mine) Gravity.END else Gravity.START }
            row.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (mine) leftMargin = dp(60) else rightMargin = dp(40)
            })
            addTo(list, row, dp(10))
            // Checks and watches the assistant offers: the owner starts them, never the app.
            if (!mine) {
                for (k in com.obdlogger.core.ChatTests.parse(m.text)) {
                    val blocked = moving && k.parked
                    val b = ctx.button(if (blocked) "▶ ${k.title}: на стоянке" else "▶ Проверка: ${k.title}", sc, p, primary = !blocked) { if (!blocked) actions.runCheck(k) }
                    b.isEnabled = !blocked
                    b.alpha = if (blocked) 0.5f else 1f
                    addTo(list, b, dp(8), width = ViewGroup.LayoutParams.WRAP_CONTENT)
                    addTo(list, ctx.text(k.short, sc.cap, p.t3), dp(2))
                }
                for (w in com.obdlogger.core.WatchRule.parse(m.text)) {
                    val on = watches.any { it.code == w.code && it.op == w.op && it.value == w.value && it.mode == w.mode }
                    val b = ctx.button(if (on) "✓ Наблюдаю: ${w.text}" else "👁 Наблюдать: ${w.text}", sc, p, primary = false) { if (!on) actions.watch(w) }
                    addTo(list, b, dp(8), width = ViewGroup.LayoutParams.WRAP_CONTENT)
                    if (!on) addTo(list, ctx.text("Бортач будет следить за этим в следующих ${com.obdlogger.core.WatchRule.TRIPS} поездках и пришлёт результат сюда.", sc.cap, p.t3), dp(2))
                }
            }
            // Charts the assistant asked for, under its answer.
            if (!mine) for (req in ChatCharts.parse(m.text)) {
                val v = actions.chartView(req) ?: continue
                addTo(list, card(v, p, dp(12), dp(10)).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (sc.phone) 200 else 230)).apply { topMargin = dp(8); rightMargin = dp(40) }
                })
            }
        }
        busy?.let { addTo(list, ctx.text(it, sc.p, p.t3), dp(10)) }
        error?.let { addTo(list, card(ctx.text(it, sc.p, p.amb, 600), p, dp(14), dp(10), p.amb), dp(10)) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}

/** Per-trip values of one sensor (a trend), drawn for the chat: points, a line, labels. */
class TrendChartView(ctx: Context, private val p: Bt.Palette, private val title: String, private val unit: String,
    private val points: List<Pair<String, Double>>, private val normLo: Double?, private val normHi: Double?) : View(ctx) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        paint.typeface = Bt.sans(context, 600)
        paint.textSize = 14 * d
        paint.color = p.t1
        c.drawText(title, 0f, 16 * d, paint)
        if (points.isEmpty()) return
        var lo = points.minOf { it.second }
        var hi = points.maxOf { it.second }
        normLo?.let { lo = minOf(lo, it) }
        normHi?.let { hi = maxOf(hi, it) }
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.12
        lo -= pad; hi += pad
        val top = 28 * d
        val bottom = h - 22 * d
        val left = 8 * d
        val right = w - 8 * d
        fun x(i: Int) = if (points.size == 1) (left + right) / 2 else left + (right - left) * i / (points.size - 1)
        fun y(v: Double) = (bottom - (bottom - top) * ((v - lo) / (hi - lo))).toFloat()
        if (normLo != null && normHi != null) {
            paint.color = p.accZ
            c.drawRect(left, y(normHi), right, y(normLo), paint)
        }
        paint.color = p.chart
        paint.strokeWidth = 2 * d
        for (i in 1 until points.size) c.drawLine(x(i - 1), y(points[i - 1].second), x(i), y(points[i].second), paint)
        paint.typeface = Bt.mono(context, 500)
        paint.textSize = 12 * d
        points.forEachIndexed { i, (label, v) ->
            paint.color = if (normLo != null && normHi != null && (v < normLo || v > normHi)) p.amb else p.chart
            c.drawCircle(x(i), y(v), 4 * d, paint)
            val txt = com.obdlogger.core.Values.format(v).orEmpty() + if (unit.isNotEmpty() && i == points.lastIndex) " $unit" else ""
            val tw = paint.measureText(txt)
            c.drawText(txt, (x(i) - tw / 2).clamp(0f, w - tw), y(v) - 8 * d, paint)
            paint.color = p.t3
            val lw = paint.measureText(label.take(5))
            if (points.size <= 8 || i % 2 == 0 || i == points.lastIndex) c.drawText(label.take(5), (x(i) - lw / 2).clamp(0f, w - lw), h - 4 * d, paint)
        }
    }
}
