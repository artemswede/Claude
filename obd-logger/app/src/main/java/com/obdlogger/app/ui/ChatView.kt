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
import com.obdlogger.core.ChatMessage

/** What the chat page asks the activity to do. */
interface ChatActions {
    fun sendQuestion(text: String)
    fun voiceQuestion()
    fun editAiKey()
    fun clearChat()
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

    /** [busy] — waiting for the answer; [error] — the last failure, shown under the history. */
    fun bind(history: List<ChatMessage>, busy: Boolean, hasKey: Boolean, context: String, error: String?) {
        val key = listOf(history.size, history.lastOrNull()?.text?.length, busy, hasKey, context, error)
        if (key == shownKey) return
        shownKey = key
        list.removeAllViews()
        val ctx = getContext()
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(head, ctx.text("Чат по машине", if (sc.phone) 22f else sc.hm, p.t1, 700), 0, 1f)
        if (history.isNotEmpty()) addTo(head, ctx.text("Очистить", sc.p, p.acc, 600).apply { setOnClickListener { actions.clearChat() } }.tap(), dp(12))
        addTo(list, head)
        addTo(list, ctx.text(context, sc.cap, p.t3), dp(2))
        if (!hasKey) {
            addTo(list, card(column(ctx, dp(8),
                ctx.text("Нужен ключ DeepSeek API", sc.hs, p.t1, 700),
                ctx.text("Создайте ключ на platform.deepseek.com и введите его здесь — он хранится только на этом устройстве. " +
                    "К вопросу прикладывается сводка по вашим поездкам (выводы, сравнения, коды) — она уходит на серверы DeepSeek.", sc.p, p.t2),
                ctx.button("Ввести ключ", sc, p, primary = true) { actions.editAiKey() }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(sc.btnH)) },
            ), p, dp(18), dp(16), p.acc), dp(14))
        }
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
            val bubble = ctx.text(if (mine) m.text else com.obdlogger.app.AiChat.plain(m.text), if (sc.phone) 15f else 17f, p.t1, lineHeight = if (sc.phone) 21f else 24f).apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = if (mine) roundRect(p.accT, dp(14).toFloat()) else roundRect(p.s1, dp(14).toFloat(), dp(1), p.line)
                setTextIsSelectable(!mine)
            }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (mine) Gravity.END else Gravity.START }
            row.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (mine) leftMargin = dp(60) else rightMargin = dp(40)
            })
            addTo(list, row, dp(10))
        }
        if (busy) addTo(list, ctx.text("Думаю… (обычно 5–30 секунд)", sc.p, p.t3), dp(10))
        error?.let { addTo(list, card(ctx.text(it, sc.p, p.amb, 600), p, dp(14), dp(10), p.amb), dp(10)) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
