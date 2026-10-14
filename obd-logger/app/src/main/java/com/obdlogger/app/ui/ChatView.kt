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
    /** False when the question was not taken (busy, no key): the text then stays in the field. */
    fun sendQuestion(text: String): Boolean
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
    /** «Открыть в Графиках»: the live overlay on the record page, with its zoom and cursor. */
    fun openLive(codes: List<String>) {}
    /** «В Графиках ↗» under a chart: the same sensors, window and mark in «Графики». */
    fun openChart(req: ChartRequest) { if (req is ChartRequest.Live) openLive(req.sensors) }
    /** «Повторить» after an error: the last question again. */
    fun retry() {}
    /** «Отменить» while waiting: the request is dropped, the question stays with «Повторить». */
    fun cancelAnswer() {}
    /** «Глубоко / Быстро» in the chat's bar. */
    fun deep(): Boolean = true
    fun setDeep(on: Boolean) {}
    /** Buttons under an answer: a maintenance record or the mileage the assistant proposes. */
    fun saveRecord(tag: com.obdlogger.core.MaintenanceTag) {}
}

/** While the answer is awaited: what happens now, since when (elapsedRealtime), which model. */
data class ChatWait(val stage: String, val since: Long, val model: String)

/**
 * «Чат»: free questions about this car — answered by the AI from Бортач's own summary
 * of the trips, versions, codes and check logs. Voice through the system speech input
 * (or the keyboard's microphone); answers as text. The chat's controls («Глубоко», «Новый
 * чат», «⋯») live in [header] — in the top line next to the lamps, never scrolled away.
 */
class ChatView(ctx: Context, private val sc: Bt.Scale, private val actions: ChatActions) : FrameLayout(ctx) {
    private val p = Bt.theme
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(8), dp(sc.pad), dp(12)) }
    private val scroll = ScrollView(ctx).apply { addView(list); isFillViewport = true }
    private val input = EditText(ctx).apply {
        hint = "Спросите про машину…"
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (sc.phone) 16f else 17f)
        setTextColor(p.t1)
        setHintTextColor(p.t3)
        background = roundRect(p.s1, dp(14).toFloat(), dp(1), p.line2)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        minHeight = dp(44)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        maxLines = 3
        setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { send(); true } else false
        }
    }
    private val sendBtn = ctx.text("➤", 22f, android.graphics.Color.WHITE, 700).apply {
        gravity = Gravity.CENTER
        contentDescription = "Отправить"
        background = roundRect(p.acc, dp(14).toFloat())
        setOnClickListener { send() }
    }.tap()
    private var shownKey: Any? = null
    private var waiting: ChatWait? = null
    private var timerText: android.widget.TextView? = null
    private var dots: Dots? = null
    private val tick = object : Runnable {
        override fun run() {
            val w = waiting ?: return
            timerText?.text = waitText(w)
            postDelayed(this, 1000)
        }
    }

    /** «Глубоко | Быстро» · «Новый чат» · «⋯» — for the top line (or above the chat on a phone). */
    val header: LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
    private val deepSeg = Segment(ctx, sc, p, listOf("Глубоко", "Быстро"), if (actions.deep()) 0 else 1) { actions.setDeep(it == 0) }

    init {
        setBackgroundColor(p.bg)
        header.addView(android.view.View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(deepSeg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        header.addView(ctx.text("Новый чат", if (sc.phone) 15f else 16f, p.t1, 600).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            contentDescription = "Новый чат: сбросить переписку"
            background = roundRect(p.s1, dp(10).toFloat(), dp(1), p.line2)
            setOnClickListener { actions.resetChat() }
        }.tap(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(8) })
        header.addView(ctx.text("⋯", 22f, p.t1, 600).apply {
            gravity = Gravity.CENTER
            contentDescription = "Ещё"
            setOnClickListener { v ->
                val m = android.widget.PopupMenu(ctx, v)
                m.menu.add(0, 1, 1, "Поиск гипотез — глубокое исследование")
                m.menu.add(0, 2, 2, "Ключ ИИ или свой сервер…")
                m.setOnMenuItemClickListener { when (it.itemId) { 1 -> actions.runResearch(); 2 -> actions.editAiKey() }; true }
                m.show()
            }
        }.tap(), LinearLayout.LayoutParams(dp(48), dp(48)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (sc.phone) col.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { leftMargin = dp(sc.pad); rightMargin = dp(sc.pad) })
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // The input line (макет «Чат»): microphone — the biggest target, the field, send.
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(sc.pad), dp(6), dp(sc.pad), dp(8))
            setBackgroundColor(p.bg)
        }
        bar.addView(ctx.text("🎤", 24f, p.t1).apply {
            gravity = Gravity.CENTER
            contentDescription = "Спросить голосом"
            background = roundRect(p.s1, dp(14).toFloat(), dp(1), p.line2)
            setOnClickListener { actions.voiceQuestion() }
        }.tap(), LinearLayout.LayoutParams(dp(56), dp(48)))
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        bar.addView(sendBtn, LinearLayout.LayoutParams(dp(56), dp(48)))
        col.addView(bar)
        addView(col)
    }

    private fun send() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        if (waiting != null) {
            android.widget.Toast.makeText(context, "Дождитесь ответа или нажмите «Отменить» — вопрос останется в поле", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // Cleared only when the question was taken: never lost.
        if (actions.sendQuestion(q)) input.setText("")
    }

    private fun waitText(w: ChatWait): String {
        val s = ((android.os.SystemClock.elapsedRealtime() - w.since) / 1000).coerceAtLeast(0)
        val t = "%d:%02d".format(s / 60, s % 60)
        val head = listOf(w.stage, t, w.model).filter { it.isNotBlank() }.joinToString(" · ")
        return if (s >= 90) "$head\nДолго — можно отменить и спросить в режиме «Быстро»." else head
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
        dots?.stop()
    }

    /**
     * [wait] — the answer is awaited (stage, timer, model), null when idle; [error] — the last
     * failure; [meta] — under the newest answer: «Gemini Pro · 3 из 15 сегодня».
     */
    fun bind(state: ChatState, wait: ChatWait?, hasKey: Boolean, context: String, error: String?,
        watches: List<com.obdlogger.core.WatchRule> = emptyList(), moving: Boolean = false, meta: String = "") {
        val history = state.messages
        if (deepSeg.selected != (if (actions.deep()) 0 else 1)) deepSeg.select(if (actions.deep()) 0 else 1)
        sendBtn.alpha = if (wait == null) 1f else 0.4f
        val key = listOf(history.size, history.lastOrNull()?.text?.length, state.summary.length, wait?.stage, wait?.model, hasKey, context, error, watches.map { it.encode() }, moving, meta)
        waiting = wait
        if (key == shownKey) return
        shownKey = key
        list.removeAllViews()
        removeCallbacks(tick)
        dots?.stop()
        dots = null
        timerText = null
        val ctx = getContext()
        addTo(list, ctx.text(context, sc.cap, p.t3))
        if (state.summary.isNotBlank()) {
            addTo(list, ctx.text("Ранние сообщения сжаты в память чата — факты, числа и гипотезы из них помнятся.", sc.cap, p.t3), dp(4))
        }
        if (!hasKey) {
            addTo(list, card(column(ctx, dp(8),
                ctx.text("Нужен ключ ИИ или свой сервер", sc.hs, p.t1, 700),
                ctx.text("Без VPN: свой сервер на Vercel с ключом Gemini (Настройки → ИИ-чат → «Свой сервер»). Или ключ OpenRouter (sk-or-…) / platform.deepseek.com — он хранится только на этом устройстве. " +
                    "К вопросу прикладывается сводка по вашим поездкам (выводы, сравнения, коды) — она уходит выбранному ИИ (через ваш сервер или напрямую).", sc.p, p.t2),
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
        if (history.isEmpty()) {
            addTo(list, ctx.text("Спросите голосом или текстом. Примеры — нажмите, чтобы спросить:", sc.p, p.t2), dp(16))
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
            addTo(list, ctx.button("🔬  Поиск гипотез — глубокое исследование", sc, p, primary = false) { if (hasKey) actions.runResearch() else actions.editAiKey() }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(sc.btnH))
            }, dp(12), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        var lastQuestion: View? = null
        val lastAnswer = history.indexOfLast { it.role == "assistant" }
        for ((idx, m) in history.withIndex()) {
            val mine = m.role == "user"
            if (mine && m.text.startsWith("📋")) {
                // From Бортач (a check result, a watch report): not a person, not the AI — a measurement.
                addTo(list, system(m.text.removePrefix("📋").trim()), dp(10))
                continue
            }
            if (mine) {
                val shown = if (m.text == com.obdlogger.core.ChatPrompt.HYPOTHESIS_BRIEF) "Поиск гипотез: глубокое исследование двигателя по всем данным" else m.text
                // The question above a new answer folds into one line: the answer gets the screen.
                val folded = idx == history.lastIndex - 1 && history.last().role == "assistant"
                val bubble = ctx.text(shown, 15f, p.t1, maxLines = if (folded) 1 else Int.MAX_VALUE).apply {
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(p.s2)
                        val r = dp(16).toFloat()
                        cornerRadii = floatArrayOf(r, r, r, r, dp(4).toFloat(), dp(4).toFloat(), r, r)
                    }
                }
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
                row.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(60) })
                // The last question's state: sent and awaited, or not sent.
                if (idx == history.lastIndex) {
                    val st = when {
                        wait != null -> "✓ отправлено"
                        error != null -> "не отправлено"
                        else -> null
                    }
                    st?.let { row.addView(ctx.text(it, sc.cap, if (error != null && wait == null) p.amb else p.t3), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }) }
                }
                addTo(list, row, dp(10))
                lastQuestion = row
                continue
            }
            answer(m.text, watches, moving, if (idx == lastAnswer) meta else "")
        }
        wait?.let { w ->
            // Waiting (макет «Думаю»): running dots, the stage, a timer and the model; «Отменить».
            val d = Dots(ctx, p.acc).also { dots = it }
            val t = ctx.text(waitText(w), sc.p, p.t2).also { timerText = it }
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(d, LinearLayout.LayoutParams(dp(44), dp(24)))
            addTo(r, t, 0, 1f)
            addTo(r, ctx.button("Отменить", sc, p, primary = false) { actions.cancelAnswer() }, 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
            addTo(list, card(r, p, dp(14), dp(8), p.line2), dp(12))
            d.start()
            postDelayed(tick, 1000)
        }
        error?.let { e ->
            val box = column(ctx, dp(8), ctx.text(e, sc.p, p.amb, 600))
            addTo(box, ctx.button("Повторить", sc, p, primary = false) { actions.retry() }, dp(4), width = ViewGroup.LayoutParams.WRAP_CONTENT)
            addTo(list, card(box, p, dp(14), dp(10), p.amb), dp(10))
        }
        // After a new answer the question is the first line on screen and the answer follows (Ч2):
        // «Коротко» and the chart are seen at once, the rest below.
        val q = lastQuestion
        scroll.post {
            if (q != null && history.lastOrNull()?.role == "assistant" && error == null && wait == null) scroll.scrollTo(0, (q.top - dp(4)).coerceAtLeast(0))
            else scroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Three running dots: the request is alive. Still when the system's animations are off. */
    private class Dots(ctx: Context, private val color: Int) : View(ctx) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private var phase = 0f
        private var anim: android.animation.ValueAnimator? = null

        fun start() {
            val scale = try { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Exception) { 1f }
            if (scale == 0f) { phase = 0.5f; invalidate(); return }
            anim = android.animation.ValueAnimator.ofFloat(0f, 3f).apply {
                duration = 1200
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() { anim?.cancel(); anim = null }

        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }

        override fun onDraw(c: android.graphics.Canvas) {
            val d = resources.displayMetrics.density
            val cy = height / 2f
            for (i in 0..2) {
                val k = 1f - (kotlin.math.abs(phase - i - 0.5f).coerceAtMost(1.5f) / 1.5f)
                paint.alpha = (90 + 165 * k).toInt()
                c.drawCircle(8 * d + i * 12 * d, cy, (3.5f + 1.5f * k) * d, paint)
            }
        }
    }

    /** A message from Бортач: dashed frame on s2, «Результат проверки · от Бортача». */
    private fun system(text: String): View {
        val ctx = context
        val title = when {
            text.startsWith("Результат проверки") -> "Результат проверки"
            text.startsWith("Наблюдение") -> "Наблюдение"
            else -> "Сообщение"
        }
        val box = column(ctx, dp(4),
            row(ctx, dp(6), Gravity.CENTER_VERTICAL, ctx.text(title, sc.p, p.t1, 700), ctx.text("· от Бортача", sc.cap, p.t3)),
            ctx.text(text, if (sc.phone) 14f else 15f, p.t2, lineHeight = if (sc.phone) 20f else 21f))
        box.setPadding(dp(14), dp(10), dp(14), dp(10))
        box.background = roundRect(p.s2, dp(12).toFloat(), dp(1), p.line2, dash = dp(5).toFloat())
        return box
    }

    /**
     * The AI's answer, without a bubble, across the width (Ч1): «Коротко», the chart card,
     * ПОЧЕМУ with the series marker before each fact, ЧТО СДЕЛАТЬ, the action buttons and the
     * confidence. Free text (an older answer, a deep research) is shown as it is.
     */
    private fun answer(text: String, watches: List<com.obdlogger.core.WatchRule>, moving: Boolean, meta: String = "") {
        val ctx = context
        val body = com.obdlogger.app.AiChat.plain(com.obdlogger.core.MaintenanceTag.strip(com.obdlogger.core.ChatTests.strip(ChatCharts.strip(text))))
        val a = com.obdlogger.core.ChatAnswer.parse(body)
        val charts = ChatCharts.parse(text)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addTo(box, ctx.label(if (meta.isBlank()) "Ответ ИИ" else "Ответ ИИ · $meta", sc, p))
        if (a.structured) {
            addTo(box, ctx.text(a.short, if (moving) 22f else 17f, p.t1, 600, lineHeight = if (moving) 28f else 23f).apply { setTextIsSelectable(true) }, dp(2))
        }
        // Charts right under «Коротко»: the picture with the moment marked.
        val sensors = charts.firstOrNull()?.let { r -> when (r) { is ChartRequest.Overlay -> r.sensors; is ChartRequest.Live -> r.sensors; else -> listOf(r.sensor) } }.orEmpty()
        for (req in charts) chartCard(box, req)
        if (a.structured) {
            if (a.why.isNotEmpty()) {
                addTo(box, ctx.label("Почему", sc, p), dp(10))
                a.why.forEachIndexed { i, fact ->
                    val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                    // The marker of the series the fact is about (facts follow the legend order).
                    val k = i.takeIf { it < sensors.size && it < Series.MAX }
                    addTo(r, ctx.text(k?.let { Series.glyph(it) } ?: "–", 14f, k?.let { Series.color(p, it) } ?: p.t3, 700).apply { minWidth = dp(20) }, 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
                    addTo(r, ctx.text(fact, if (sc.phone) 15f else 16f, p.t1, lineHeight = if (sc.phone) 21f else 22f).apply { setTextIsSelectable(true) }, 0, 1f)
                    addTo(box, r, dp(4))
                }
            }
            if (a.todo.isNotBlank()) {
                addTo(box, ctx.label("Что сделать", sc, p), dp(10))
                addTo(box, ctx.text(a.todo, if (sc.phone) 15f else 16f, p.t1, lineHeight = if (sc.phone) 21f else 22f).apply { setTextIsSelectable(true) }, dp(4))
            }
            if (a.rest.isNotBlank()) addTo(box, ctx.text(a.rest, if (sc.phone) 15f else 16f, p.t1, lineHeight = if (sc.phone) 21f else 22f).apply { setTextIsSelectable(true) }, dp(8))
        } else {
            addTo(box, ctx.text(body, if (sc.phone) 15f else 16f, p.t1, lineHeight = if (sc.phone) 21f else 23f).apply { setTextIsSelectable(true) }, dp(4))
        }
        // Checks and watches the assistant offers: the owner starts them, never the app.
        for (k in com.obdlogger.core.ChatTests.parse(text)) {
            val blocked = moving && k.parked
            val b = ctx.button(if (blocked) "▶  Проверка · на стоянке" else "▶  Проверка: ${k.title.lowercase()} · ${k.short.substringBefore(':')}", sc, p, primary = false) { if (!blocked) actions.runCheck(k) }
            b.isEnabled = !blocked
            if (blocked) b.background = roundRect(p.s2, dp(12).toFloat())
            addTo(box, b, dp(8), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        for (w in com.obdlogger.core.WatchRule.parse(text)) {
            val on = watches.any { it.code == w.code && it.op == w.op && it.value == w.value && it.mode == w.mode }
            val b = ctx.button(if (on) "✓  Наблюдаю: ${w.text}" else "◎  Наблюдать: ${w.text} · ${com.obdlogger.core.WatchRule.TRIPS} поездки", sc, p, primary = false) { if (!on) actions.watch(w) }
            if (on) b.background = roundRect(p.accT, dp(12).toFloat())
            addTo(box, b, dp(8), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Records for the journal the assistant proposes: saved only after the owner presses.
        for (t in com.obdlogger.core.MaintenanceTag.parse(text)) {
            addTo(box, ctx.button(t.button(), sc, p, primary = false) { actions.saveRecord(t) }, dp(8), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (a.structured && a.confidence.isNotBlank()) addTo(box, ctx.text("Уверенность: ${a.confidence}", sc.cap, p.t3), dp(8))
        addTo(list, box, dp(12))
    }

    /** Chart card (Ч1): header «датчики · окно · В Графиках ↗», the chart with legend and marks, tap — full screen. */
    private fun chartCard(box: LinearLayout, req: ChartRequest) {
        val ctx = context
        val v = actions.chartView(req) ?: return
        val names = when (req) {
            is ChartRequest.Overlay -> req.sensors
            is ChartRequest.Live -> req.sensors
            else -> listOf(req.sensor)
        }.joinToString(", ") { com.obdlogger.core.SensorNames.label(it) }
        val win = when (req) {
            is ChartRequest.Overlay -> windowText(req.window)
            is ChartRequest.Trip -> windowText(req.window)
            is ChartRequest.Live -> "сейчас · ${req.minutes} мин"
            is ChartRequest.Trend -> "по поездкам"
        }
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(head, ctx.text(listOf(names, win).filter { it.isNotBlank() }.joinToString(" · "), sc.cap + 1, p.t2, 600, maxLines = 1), 0, 1f)
        if (req !is ChartRequest.Trend) addTo(head, ctx.text("В Графиках ↗", sc.cap + 1, p.acc, 600).apply {
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { actions.openChart(req) }
        }.tap(), 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(card, head)
        card.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (sc.phone) 220 else 150)).apply { topMargin = dp(2) })
        v.setOnClickListener { actions.openChart(req) }
        addTo(box, card(card, p, dp(12), dp(6)), dp(8))
    }

    private fun windowText(w: ChartRequest.Window?): String = when {
        w == null -> ""
        w.lastMin != null -> "последние ${w.lastMin} мин"
        w.from != null && w.to != null -> "${w.from.toString().take(5)}–${w.to.toString().take(5)}"
        else -> ""
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
