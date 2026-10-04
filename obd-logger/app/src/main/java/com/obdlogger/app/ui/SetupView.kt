package com.obdlogger.app.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Первый запуск (Р1–Р6): adapter, Bluetooth and notifications, battery, overlay,
 * auto recording, car profile. Steps on the left (top on a phone), one task per page.
 */
class SetupView(ctx: Context, private val sc: Bt.Scale, private val host: Host) : FrameLayout(ctx) {
    interface Host {
        /** Paired devices: name, address, looks like an OBD adapter. Null with a reason when Bluetooth is not usable. */
        fun devices(): Pair<List<Triple<String, String, Boolean>>, String?>
        fun selectedDevice(): String?
        fun selectDevice(address: String)
        fun openBluetoothSettings()
        fun permissionsGranted(): Boolean
        fun requestPermissions()
        fun batteryFree(): Boolean
        fun askBattery()
        fun overlayAllowed(): Boolean
        fun askOverlay()
        fun autoOn(): Boolean
        fun setAuto(on: Boolean)
        fun bootOn(): Boolean
        fun setBoot(on: Boolean)
        fun carText(): String
        fun setCar(text: String)
        fun finishSetup()
    }

    private val p = Bt.LIGHT
    private var step = 0
    private val titles = listOf("Сопряжение адаптера", "Bluetooth и уведомления", "Экономия батареи", "Поверх других окон", "Автозапись", "Профиль машины")
    private val rail = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val content = FrameLayout(ctx)
    private val stepText = ctx.text("", if (sc.phone) 13f else 17f, p.t2)
    private var carInput: EditText? = null

    init {
        setBackgroundColor(p.bg)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (sc.phone) 14 else 24), 0, dp(if (sc.phone) 14 else 24), 0)
        }
        bar.addView(android.widget.ImageView(ctx).apply { setImageResource(com.obdlogger.app.R.drawable.ic_logo_light) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addTo(bar, ctx.text("Бортач", sc.brandFont, p.t1, 600), dp(8))
        addTo(bar, ctx.text("Первый запуск", sc.sbarFont, p.t2), dp(18))
        addTo(bar, spacer(ctx))
        addTo(bar, stepText)
        addTo(bar, ctx.text("Пропустить", if (sc.phone) 13f else 17f, p.acc, 600).apply { setOnClickListener { host.finishSetup() } }, dp(18))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(sc.sbarH)))
        root.addView(hline(ctx, p.line))
        val body = LinearLayout(ctx).apply { orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        if (!sc.phone) {
            body.addView(rail, LinearLayout.LayoutParams(dp(if (sc === Bt.TABLET) 330 else 260), ViewGroup.LayoutParams.MATCH_PARENT))
            body.addView(View(ctx).apply { setBackgroundColor(p.line) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        body.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (sc.phone) { width = ViewGroup.LayoutParams.MATCH_PARENT; height = 0 } })
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root)
        render()
    }

    fun show(i: Int) {
        step = i.coerceIn(0, titles.lastIndex)
        render()
    }

    /** Back from a system screen (permission, battery…): refresh what is shown. */
    fun refresh() = render()

    private fun done(i: Int): Boolean = when (i) {
        0 -> host.selectedDevice() != null
        1 -> host.permissionsGranted()
        2 -> host.batteryFree()
        3 -> host.overlayAllowed()
        4 -> host.autoOn()
        else -> false
    }

    private fun render() {
        stepText.text = "шаг ${step + 1} из ${titles.size}"
        rail.removeAllViews()
        rail.setPadding(0, dp(20), 0, 0)
        titles.forEachIndexed { i, t ->
            val on = i == step
            val ok = i < step && done(i)
            val dot = context.text(if (ok) "✓" else "${i + 1}", 14f, if (ok) p.accInk else if (on) p.acc else p.t3, 600).apply {
                gravity = Gravity.CENTER
                background = if (ok) roundRect(p.acc, dp(17).toFloat()) else roundRect(0, dp(17).toFloat(), dp(2), if (on) p.acc else p.line2)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            }
            val r = row(context, dp(14), Gravity.CENTER_VERTICAL, dot, context.text(t, if (sc === Bt.TABLET) 19f else 16f, if (on) p.t1 else p.t2, if (on) 600 else 400))
            r.setPadding(dp(28), dp(14), dp(16), dp(14))
            if (on) r.setBackgroundColor(p.s2)
            r.setOnClickListener { show(i) }
            rail.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.removeAllViews()
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (sc.phone) 16 else 48), dp(if (sc.phone) 16 else 36), dp(if (sc.phone) 16 else 48), dp(24)) }
        fun title(s: String) = addTo(page, context.text(s, if (sc.phone) 26f else if (sc === Bt.TABLET) 40f else 30f, p.t1, 700))
        fun lead(s: String) = addTo(page, context.text(s, if (sc.phone) 16f else if (sc === Bt.TABLET) 23f else 18f, p.t2, lineHeight = if (sc.phone) 22f else 29f), dp(14))
        fun state(ok: Boolean, s: String) = addTo(page, context.chip(if (ok) "✓ $s" else s, if (ok) p.acc else p.amb, if (ok) p.accT else p.ambT, 16f), dp(16), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        when (step) {
            0 -> {
                title("Сопрягите адаптер")
                lead("Вставьте ELM327 в разъём OBD под рулём, включите зажигание. В настройках Bluetooth найдите «OBDII», «V-LINK» или «ELM327», код обычно 1234 или 0000.")
                val (list, problem) = host.devices()
                if (problem != null) state(false, problem)
                val sel = host.selectedDevice()
                list.forEach { (name, addr, obd) ->
                    val on = addr == sel
                    val radio = View(context).apply {
                        background = if (on) roundRect(p.acc, dp(11).toFloat()) else roundRect(0, dp(11).toFloat(), dp(2), p.line2)
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                    }
                    val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    addTo(r, radio)
                    addTo(r, column(context, dp(2), context.text(name, if (sc.phone) 17f else 21f, p.t1, 600), context.text(addr, 13f, p.t3, 400, mono = true)), dp(16), 1f)
                    if (obd) addTo(r, context.chip("похож на OBD", p.acc, p.accT, 14f))
                    r.setOnClickListener { host.selectDevice(addr); render() }
                    addTo(page, card(r, p, dp(20), dp(14), if (on) p.acc else 0), dp(12))
                }
                if (list.isEmpty() && problem == null) state(false, "сопряжённых устройств нет")
                bottom(page, context.button("Настройки Bluetooth", sc, p, primary = false) { host.openBluetoothSettings() }, host.selectedDevice() != null)
            }
            1 -> {
                title("Разрешите Bluetooth и уведомления")
                lead("Bluetooth — чтобы говорить с адаптером. Уведомление показывает, что идёт запись, и не даёт системе её остановить.")
                state(host.permissionsGranted(), if (host.permissionsGranted()) "разрешено" else "ещё не разрешено")
                if (!host.permissionsGranted()) addTo(page, context.button("Разрешить", sc, p) { host.requestPermissions() }, dp(16), width = ViewGroup.LayoutParams.WRAP_CONTENT)
                bottom(page, null, true)
            }
            2 -> {
                title("Не давайте системе усыплять запись")
                lead("Иначе Android остановит Бортач через несколько минут в фоне, и поездка не запишется.")
                state(host.batteryFree(), if (host.batteryFree()) "ограничение снято" else "ограничено")
                if (!host.batteryFree()) addTo(page, context.button("Убрать из экономии батареи", sc, p) { host.askBattery() }, dp(16), width = ViewGroup.LayoutParams.WRAP_CONTENT)
                val tips = LinearLayout(context).apply { orientation = if (sc.phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
                for ((brand, text) in listOf(
                    "Huawei · Honor" to "Настройки → Батарея → Запуск приложений → Бортач → «Управлять вручную»: включить автозапуск, косвенный запуск и работу в фоне.",
                    "Xiaomi · Redmi · POCO" to "Настройки → Приложения → Бортач → Контроль активности: «Нет ограничений»; там же — Автозапуск: вкл.",
                )) {
                    val c = card(column(context, dp(6), context.text(brand, 18f, p.t1, 700), context.text(text, if (sc.phone) 14f else 16f, p.t2)), p, dp(18), dp(14))
                    if (sc.phone) addTo(tips, c, dp(10)) else tips.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (tips.childCount > 0) leftMargin = dp(14) })
                }
                addTo(page, tips, dp(18))
                bottom(page, null, true)
            }
            3 -> {
                title("Открывать Бортач при запуске мотора")
                lead("С разрешением «поверх других окон» планшет сам покажет Бортач, когда начнётся поездка. Без него запись всё равно идёт в фоне.")
                state(host.overlayAllowed(), if (host.overlayAllowed()) "разрешено" else "не разрешено — можно пропустить")
                if (!host.overlayAllowed()) addTo(page, context.button("Разрешить", sc, p) { host.askOverlay() }, dp(16), width = ViewGroup.LayoutParams.WRAP_CONTENT)
                bottom(page, null, true)
            }
            4 -> {
                title("Автозапись")
                lead("Завели мотор — запись идёт сама. Заглушили — поездка сохранена и разобрана. Короткий перезапуск (до 2 минут) — та же поездка.")
                addTo(page, toggleRow("Автозапись поездок", host.autoOn()) { host.setAuto(it); render() }, dp(18))
                addTo(page, toggleRow("Запускать при включении планшета", host.bootOn()) { host.setBoot(it); render() }, dp(10))
                bottom(page, null, true)
            }
            else -> {
                title("Профиль машины")
                lead("Как назвать машину, к которой подключён адаптер: марка, модель, год, двигатель. У каждой машины своё название и своя история поездок — Бортач различает их сам.")
                val input = EditText(context).apply {
                    setText(host.carText())
                    hint = "Марка, модель, год, двигатель, пробег"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    textSize = if (sc.phone) 17f else 21f
                    typeface = Bt.sans(context)
                    setTextColor(p.t1)
                    background = roundRect(p.s1, dp(12).toFloat(), dp(1), p.line2)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }
                carInput = input
                addTo(page, input, dp(18))
                bottom(page, null, true, last = true)
            }
        }
        content.addView(ScrollView(context).apply { addView(page); isFillViewport = true })
    }

    private fun toggleRow(title: String, on: Boolean, change: (Boolean) -> Unit): View {
        val sw = SettingsView.Toggle(context, on, p)
        val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(r, context.text(title, if (sc.phone) 17f else 21f, p.t1, 600), 0, 1f)
        addTo(r, sw)
        r.setOnClickListener { change(!sw.on) }
        return card(r, p, dp(20), dp(16))
    }

    private fun bottom(page: LinearLayout, extra: View?, canNext: Boolean, last: Boolean = false) {
        addTo(page, View(context), 0, 1f)
        val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (step > 0) addTo(r, context.text("Назад", 18f, p.acc, 600).apply { setPadding(dp(8), dp(12), dp(16), dp(12)); setOnClickListener { show(step - 1) } })
        extra?.let { addTo(r, it, dp(8)) }
        addTo(r, spacer(context))
        val next = context.button(if (last) "Готово" else "Дальше", sc, p) {
            if (last) {
                carInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(host::setCar)
                host.finishSetup()
            } else show(step + 1)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(sc.btnBigH))
            alpha = if (canNext) 1f else 0.5f
        }
        addTo(r, next)
        addTo(page, r, dp(24))
    }
}
