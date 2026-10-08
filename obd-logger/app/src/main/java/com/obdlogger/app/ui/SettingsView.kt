package com.obdlogger.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Настройки (Н1): разделы «Запись», «Фон и запуск», «Файлы», «Вручную»,
 * «О приложении». Строка = заголовок + пояснение + переключатель / сегмент / стрелка.
 */
class SettingsView(ctx: Context, private val sc: Bt.Scale) : FrameLayout(ctx) {
    /** Actions and current values supplied by the activity. */
    interface Host {
        fun autoOn(): Boolean
        fun setAuto(on: Boolean)
        fun bootOn(): Boolean
        fun setBoot(on: Boolean)
        fun extendedOn(): Boolean
        fun setExtended(on: Boolean)
        fun marksOn(): Boolean
        fun setMarks(on: Boolean)
        fun traceOn(): Boolean
        fun setTrace(on: Boolean)
        fun keepDays(): Int
        fun setKeepDays(days: Int)
        fun adapterText(): String
        fun pickAdapter()
        fun carText(): String
        fun editCar()
        fun batteryText(): String
        fun askBattery()
        fun overlayText(): String
        fun askOverlay()
        fun storageText(): String
        fun exportAll()
        fun canShareLast(): Boolean
        fun shareLast()
        fun manualText(): String
        fun toggleManual()
        fun version(): String
        /** «3 отчёта» when crash reports exist, else null. */
        fun crashText(): String?
        fun shareCrashes()
        /** What automatic start and recording need, and whether it is in place. */
        fun readiness(): List<Pair<String, Boolean>>
        /** Interface size in percent (100 = automatic) and the screen it is applied to. */
        fun uiScale(): Int = 100
        fun setUiScale(percent: Int) {}
        fun screenText(): String = ""
        fun openCodes() {}
        /** AI chat: the DeepSeek key («sk-…ab12» / «не задан») and the model. */
        fun aiKeyText(): String = "не задан"
        fun editAiKey() {}
        fun aiOpenRouter(): Boolean = false
        fun checkAiKey() {}
        fun aiOrModel(): Int = 0
        fun setAiOrModel(i: Int) {}
        fun aiModel(): Int = 0
        fun setAiModel(i: Int) {}
    }

    private val p = Bt.LIGHT
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var host: Host? = null
    private var status: String = ""

    init {
        setBackgroundColor(p.bg)
        list.setPadding(dp(sc.pad + 8), dp(20), dp(sc.pad + 8), dp(32))
        addView(ScrollView(ctx).apply { addView(list) })
    }

    fun bind(h: Host, statusLine: String) {
        host = h
        status = statusLine
        rebuild()
    }

    fun rebuild() {
        val h = host ?: return
        list.removeAllViews()

        if (status.isNotBlank()) {
            addTo(list, context.text(status, sc.p, p.t2).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = roundRect(p.s1, dp(14).toFloat())
            })
        }

        readinessCard(h.readiness())

        section("Запись")
        toggle("Автозапись поездок", "завели мотор — запись, заглушили — сохранено; перезапуск до 2 мин — та же поездка", h.autoOn()) { h.setAuto(it) }
        chevron("Адаптер", h.adapterText()) { h.pickAdapter() }
        chevron("Профиль машины", h.carText()) { h.editCar() }
        toggle("Искать скрытые параметры производителя", "Toyota / Lexus: блоки режима 21, старт дольше на ~1 мин. У других марок просто пропускается", h.extendedOn()) { h.setExtended(it) }
        toggle("Метки водителя", "необязательно: разбор работает без них. Кнопка «Метка» в уведомлении во время записи", h.marksOn()) { h.setMarks(it) }

        chevron("Коды ошибок ЭБУ", "прочитать со стоп-кадром, посмотреть причины, сбросить") { h.openCodes() }

        section("Фон и запуск")
        toggle("Запускать при включении планшета", "после включения или перезагрузки Бортач сам ждёт запуска двигателя — открывать приложение не нужно", h.bootOn()) { h.setBoot(it) }
        chevron("Не ограничивать в фоне", h.batteryText()) { h.askBattery() }
        chevron("Открывать при запуске двигателя", h.overlayText()) { h.askOverlay() }

        section("Экран")
        segment("Масштаб интерфейса", "для магнитол и маленьких экранов · ${h.screenText()}",
            com.obdlogger.app.UiScale.LEVELS.map { it.second to (it.first * 100).toInt() }, h.uiScale()) { h.setUiScale(it) }

        section("ИИ-чат")
        chevron("Ключ API (OpenRouter или DeepSeek)", "${h.aiKeyText()} · хранится только на этом устройстве") { h.editAiKey() }
        chevron("Проверить ключ", "баланс, лимит ключа и пробный вопрос модели") { h.checkAiKey() }
        if (h.aiOpenRouter()) segment("Модель", "через OpenRouter — только DeepSeek V4 Flash, другие не подставляются",
            com.obdlogger.app.AiChat.OR_MODELS.mapIndexed { i, m -> m.second to i }, h.aiOrModel()) { h.setAiOrModel(it) }
        segment("Режим ответа", "«Думающий» (по умолчанию): глубокое рассуждение, сам в быстрый не переключается",
            com.obdlogger.app.AiChat.MODES.mapIndexed { i, m -> m.first to i }, h.aiModel()) { h.setAiModel(it) }

        section("Файлы")
        segment("Хранение", "Загрузки / OBD-Logger · ${h.storageText()}", listOf("30 дней" to 30, "90 дней" to 90, "Всегда" to 0), h.keepDays()) { h.setKeepDays(it) }
        toggle("Журнал адаптера", "_elm.log — для отладки связи, нужен специалисту", h.traceOn()) { h.setTrace(it) }
        chevron("Скопировать все записи в Загрузки", "если приложение закрывали во время записи") { h.exportAll() }
        if (h.canShareLast()) chevron("Поделиться последней записью", "CSV, разбор и журнал — в мессенджер или почту") { h.shareLast() }

        section("Вручную")
        chevron("Запись вручную", h.manualText()) { h.toggleManual() }

        section("О приложении")
        h.crashText()?.let { chevron("Отправить отчёт об ошибке", it) { h.shareCrashes() } }
        addTo(list, context.text("Бортач ${h.version()}", sc.p, p.t3).apply { setPadding(0, dp(14), 0, 0) })
    }

    /** «Автозапуск готов» or the list of what is missing, each with a tick or a cross. */
    private fun readinessCard(items: List<Pair<String, Boolean>>) {
        val ok = items.filter { !it.first.contains("необязательно") }.all { it.second }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addTo(box, context.text(if (ok) "Автозапуск и автозапись готовы" else "Автозапуск настроен не до конца", sc.hs, if (ok) p.acc else p.amb, 700))
        addTo(box, context.text(if (ok) "Включили планшет или завели мотор — Бортач сам подключится и запишет поездку. Если питание пропадёт вместе с машиной, записанное сохранится и разберётся при следующем запуске."
            else "Ниже — чего не хватает. Нажмите на нужную строку настроек.", sc.cap, p.t2), dp(6))
        for ((title, done) in items) {
            addTo(box, row(context, dp(10), Gravity.CENTER_VERTICAL,
                // An optional item that is off is not a problem: a grey dash, not an amber cross.
                context.text(if (done) "✓" else if (title.contains("необязательно")) "–" else "✗", sc.p, if (done) p.acc else if (title.contains("необязательно")) p.t3 else p.amb, 700),
                context.text(title, sc.p, p.t1)), dp(8))
        }
        box.setPadding(dp(18), dp(16), dp(18), dp(16))
        box.background = roundRect(p.s1, dp(14).toFloat(), dp(1), if (ok) p.acc else p.amb)
        addTo(list, box, dp(8))
    }

    // ---- rows ----

    private fun section(title: String) {
        // Sentence case and a size you can read from the driver's seat.
        addTo(list, context.text(title.lowercase().replaceFirstChar { it.uppercase() }, if (sc.phone) 16f else 18f, p.t2, 600).apply { setPadding(0, dp(28), 0, dp(10)) })
        addTo(list, divider())
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(p.line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun texts(title: String, sub: String): LinearLayout = column(
        context, dp(4),
        context.text(title, sc.hs, p.t1, 600),
        context.text(sub, sc.cap, p.t2),
    )

    private fun rowBox(title: String, sub: String, trailing: View?, onClick: (() -> Unit)?): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(18))
            onClick?.let { c -> setOnClickListener { c() } }
            minimumHeight = dp(76)
        }
        addTo(box, texts(title, sub), 0, 1f)
        trailing?.let { addTo(box, it, dp(24)) }
        addTo(list, box)
        addTo(list, divider())
        return box
    }

    private fun toggle(title: String, sub: String, on: Boolean, change: (Boolean) -> Unit) {
        val sw = Toggle(context, on, p)
        rowBox(title, sub, sw) {
            sw.on = !sw.on
            change(sw.on)
        }
    }

    private fun chevron(title: String, sub: String, onClick: () -> Unit) {
        val ch = context.text("›", sc.hm, p.t3)
        rowBox(title, sub, ch, onClick)
    }

    private fun segment(title: String, sub: String, options: List<Pair<String, Int>>, value: Int, change: (Int) -> Unit) {
        val seg = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(p.s1, dp(12).toFloat())
            elevation = dp(1).toFloat()
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        for ((label, v) in options) {
            val on = v == value
            val b = context.text(label, sc.p * 0.9f, if (on) p.bg else p.t2, if (on) 600 else 400).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = if (on) roundRect(p.t1, dp(9).toFloat()) else null
                setOnClickListener { change(v); rebuild() }
            }
            seg.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (sc.phone) 44 else 48)))
        }
        rowBox(title, sub, seg, null)
    }

    /** Pill switch in the accent colour (bt.css .sw). */
    class Toggle(ctx: Context, initial: Boolean, private val p: Bt.Palette) : View(ctx) {
        var on = initial
            set(value) {
                field = value
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(60), ctx.dp(34))
            isClickable = false
        }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = if (on) p.acc else p.s3
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            paint.color = if (on) p.accInk else p.t3
            val knob = r - dp(3)
            canvas.drawCircle(if (on) width - r else r, r, knob, paint)
        }
    }
}
