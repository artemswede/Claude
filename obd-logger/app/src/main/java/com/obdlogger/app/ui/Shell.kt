package com.obdlogger.app.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.obdlogger.app.Lamp
import com.obdlogger.app.LoggerState
import com.obdlogger.app.Prefs
import com.obdlogger.app.R

/**
 * Каркас «Бортача»: верхняя служебная строка с лампами связи, боковая колонка
 * разделов (на телефоне — нижняя панель) и область страницы. «Запись» — в тёмной
 * теме, остальное — в светлой «мануал».
 */
class Shell(private val activity: Activity) {
    enum class Page(val title: String, val icon: Int) {
        OVERVIEW("Обзор", R.drawable.ic_overview),
        RECORD("Запись", R.drawable.ic_record),
        TRIPS("Поездки", R.drawable.ic_trips),
        SETTINGS("Настройки", R.drawable.ic_settings),
    }

    val sc = Bt.scaleFor(activity)
    private val ctx = activity
    private var p = Bt.LIGHT
    var page = Page.OVERVIEW
        private set
    var onPage: (Page) -> Unit = {}
    /** Tap on the car name: edit this car's profile. */
    var onCar: () -> Unit = {}
    /** Tap on the trouble-code chip: the codes screen. */
    var onDtc: () -> Unit = {}
    /** Д7: the main screen is dark at night while a trip is recorded. */
    var nightHome = false
        set(value) {
            if (field == value) return
            field = value
            if (page == Page.OVERVIEW) applyTheme(if (value) Bt.DARK else Bt.LIGHT)
        }

    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val bar = LinearLayout(ctx)
    private val logo = ImageView(ctx)
    private val brand = ctx.text("Бортач", sc.brandFont, p.t1, 600)
    private val sep = View(ctx)
    private val car = ctx.text("", sc.sbarFont, p.t2, maxLines = 1)
    private val dtcChip = ctx.text("", sc.sbarFont - 2, p.red, 600)
    private val rec = ctx.text("", sc.sbarFont, p.t1, maxLines = 1)
    /** «21» over «мин» inside the lamp pill while recording. */
    private val recUnit = ctx.text("мин", maxOf(12f, sc.sbarFont - 2), p.t2)
    /** A page's own controls in the service line (the «Запись» tabs), in place of the brand. */
    private val slot = FrameLayout(ctx)
    private val pageBars = HashMap<Page, View>()
    private val pageInfos = HashMap<Page, String>()
    /** «движение» — the page's short state, next to the lamps. */
    private val info = ctx.text("", sc.sbarFont, p.t2, maxLines = 1)
    private val lampPill = LinearLayout(ctx)
    private val lamp1 = View(ctx)
    private val lamp2 = View(ctx)
    /** Third lamp: the trip is being written — green and blinking while rows arrive. */
    private val lamp3 = View(ctx)
    private val blink = android.animation.ObjectAnimator.ofFloat(lamp3, View.ALPHA, 1f, 0.25f).apply {
        duration = 600
        repeatMode = android.animation.ValueAnimator.REVERSE
        repeatCount = android.animation.ValueAnimator.INFINITE
    }
    private val upd = ctx.text("", sc.sbarFont - 1, p.t3, maxLines = 1)
    private val divider = View(ctx)
    private val nav = LinearLayout(ctx)
    private val navItems = LinkedHashMap<Page, LinearLayout>()
    /** One container per page; pages are built by the activity. */
    val containers = Page.entries.associateWith { FrameLayout(ctx) }
    private var snapshot = LoggerState.Snapshot()

    init {
        // Top service line.
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(ctx.dp(if (sc.phone) 14 else 24), 0, ctx.dp(if (sc.phone) 14 else 24), 0)
        bar.elevation = ctx.dp(2).toFloat()
        val ls = ctx.dp(if (sc === Bt.TABLET) 26 else 20)
        logo.layoutParams = LinearLayout.LayoutParams(ls, ls)
        // Short screens give the brand's room to the page: no logo, no name.
        if (!sc.compact) {
            bar.addView(logo)
            addTo(bar, brand, ctx.dp(8))
        }
        if (!sc.phone) {
            sep.layoutParams = LinearLayout.LayoutParams(ctx.dp(1), ctx.dp(22))
            addTo(bar, sep, ctx.dp(18))
            addTo(bar, car, ctx.dp(18))
        }
        bar.addView(slot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addTo(bar, info, ctx.dp(8))
        dtcChip.setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(4))
        dtcChip.setOnClickListener { onDtc() }
        dtcChip.tap()
        addTo(bar, dtcChip, ctx.dp(12))
        // One pill: engine · ECU link │ writing lamp and the minutes («21» over «мин»).
        lampPill.orientation = LinearLayout.HORIZONTAL
        lampPill.gravity = Gravity.CENTER_VERTICAL
        lampPill.setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        lampPill.minimumHeight = ctx.dp(48)
        lampPill.minimumWidth = ctx.dp(64)
        for ((i, l) in listOf(lamp1, lamp2).withIndex()) {
            lampPill.addView(l, LinearLayout.LayoutParams(ctx.dp(sc.lamp), ctx.dp(sc.lamp)).apply { if (i > 0) leftMargin = ctx.dp(10) })
        }
        lampPill.addView(divider, LinearLayout.LayoutParams(ctx.dp(1), ctx.dp(22)).apply { leftMargin = ctx.dp(12) })
        lampPill.addView(lamp3, LinearLayout.LayoutParams(ctx.dp(sc.lamp), ctx.dp(sc.lamp)).apply { leftMargin = ctx.dp(12) })
        rec.gravity = Gravity.CENTER
        recUnit.gravity = Gravity.CENTER
        lampPill.addView(column(ctx, 0, rec, recUnit).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(8) })
        lampPill.contentDescription = "Лампы: двигатель, связь с ЭБУ, запись — подробнее"
        lampPill.setOnClickListener { showLampTip() }
        addTo(bar, lampPill, ctx.dp(10))
        // «обновлено N с назад» is in the lamp tip; short screens keep the room for the rest.
        if (!sc.phone && !sc.compact) addTo(bar, upd, ctx.dp(14))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(sc.sbarH)))

        // Navigation + pages.
        val pages = FrameLayout(ctx)
        containers.values.forEach { pages.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        nav.orientation = if (sc.phone) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        nav.setPadding(0, ctx.dp(if (sc.phone) 0 else 8), 0, 0)
        for (pg in Page.entries) {
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { show(pg) }
                contentDescription = pg.title
            }
            item.addView(ctx.icon(pg.icon, p.t3, sc.riIcon))
            addTo(item, ctx.text(pg.title, sc.riFont, p.t3).apply { gravity = Gravity.CENTER }, ctx.dp(6), width = ViewGroup.LayoutParams.WRAP_CONTENT)
            navItems[pg] = item
            if (sc.phone) {
                nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            } else {
                nav.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(sc.riH)).apply {
                    leftMargin = ctx.dp(8); rightMargin = ctx.dp(8); bottomMargin = ctx.dp(2)
                })
            }
        }
        if (sc.phone) {
            root.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(64)))
        } else {
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(nav, LinearLayout.LayoutParams(ctx.dp(sc.railW), ViewGroup.LayoutParams.MATCH_PARENT))
            body.addView(pages, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        show(Page.OVERVIEW)
    }

    /** Puts [v] (e.g. the «Запись» tabs) into the service line while [pg] is shown; null removes it. */
    fun setPageBar(pg: Page, v: View?) {
        if (v == null) pageBars.remove(pg) else pageBars[pg] = v
        updateSlot()
    }

    /** Short state of [pg] shown next to the lamps («движение»). */
    fun setPageInfo(pg: Page, text: String) {
        pageInfos[pg] = text
        if (pg == page) info.text = text
    }

    private fun updateSlot() {
        slot.removeAllViews()
        val v = pageBars[page]
        if (v != null) {
            (v.parent as? ViewGroup)?.removeView(v)
            slot.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // The page's own bar takes the brand's and the car's place.
        val own = v != null
        logo.visibility = if (own || sc.compact) View.GONE else View.VISIBLE
        brand.visibility = logo.visibility
        if (own) { car.visibility = View.GONE; sep.visibility = View.GONE }
        info.text = pageInfos[page].orEmpty()
        info.visibility = if (info.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun show(pg: Page) {
        page = pg
        containers.forEach { (k, v) -> v.visibility = if (k == pg) View.VISIBLE else View.GONE }
        updateSlot()
        applyTheme(if (pg == Page.RECORD || (pg == Page.OVERVIEW && nightHome)) Bt.DARK else Bt.LIGHT)
        onPage(pg)
    }

    private fun applyTheme(palette: Bt.Palette) {
        p = palette
        root.setBackgroundColor(p.bg)
        bar.setBackgroundColor(p.bg)
        nav.setBackgroundColor(p.bg)
        logo.setImageResource(if (p.dark) R.drawable.ic_logo_dark else R.drawable.ic_logo_light)
        brand.setTextColor(p.t1)
        sep.setBackgroundColor(p.line2)
        car.setTextColor(p.t2)
        rec.setTextColor(p.t1)
        recUnit.setTextColor(p.t2)
        info.setTextColor(p.t2)
        divider.setBackgroundColor(p.line2)
        upd.setTextColor(p.t3)
        lampPill.background = roundRect(p.s1, ctx.dp(999).toFloat())
        dtcChip.background = roundRect(p.redT, ctx.dp(999).toFloat())
        dtcChip.setTextColor(p.red)
        for ((pg, item) in navItems) {
            val on = pg == page
            val color = if (on) p.t1 else p.t3
            (item.getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(if (on && sc.phone) p.acc else color)
            (item.getChildAt(1) as TextView).apply {
                setTextColor(color)
                typeface = Bt.sans(ctx, if (on) 600 else 400)
            }
            item.background = if (on && !sc.phone) roundRect(p.s2, ctx.dp(14).toFloat()) else null
        }
        activity.window.statusBarColor = p.bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = if (p.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        render(snapshot)
    }

    private fun lampColor(l: Lamp) = when (l) {
        Lamp.OK -> p.acc
        Lamp.WAIT -> p.amb
        Lamp.FAIL -> p.red
        Lamp.OFF -> p.l0
    }

    fun render(s: LoggerState.Snapshot) {
        snapshot = s
        val name = Prefs.vehicle(ctx)
        val unnamed = name.isBlank() && Prefs.currentCar(ctx) != null
        car.text = if (unnamed) (if (sc.compact) "" else "Новая машина · указать название") else name
        // Short screens: the car's name lives in «Поездки» and «Настройки»; the line is for the page.
        val showCar = !sc.phone && !sc.compact && car.text.isNotEmpty() && pageBars[page] == null
        car.visibility = if (showCar) View.VISIBLE else View.GONE
        sep.visibility = car.visibility
        car.setTextColor(if (unnamed) p.acc else p.t2)
        car.setOnClickListener { onCar() }
        lamp1.background = roundRect(lampColor(s.engine), ctx.dp(sc.lamp).toFloat())
        lamp2.background = roundRect(lampColor(s.link), ctx.dp(sc.lamp).toFloat())
        val writing = writeLamp(s)
        lamp3.background = roundRect(lampColor(writing), ctx.dp(sc.lamp).toFloat())
        lamp3.visibility = if (s.recording) View.VISIBLE else View.GONE
        divider.visibility = lamp3.visibility
        if (writing == Lamp.OK) {
            if (!blink.isStarted) blink.start()
        } else {
            blink.cancel()
            lamp3.alpha = 1f
        }
        val min = s.elapsedSec / 60
        rec.typeface = if (s.recording) Bt.mono(ctx, 600) else Bt.sans(ctx, 400)
        rec.text = when {
            s.recording -> if (min >= 60) "${min / 60}:%02d".format(min % 60) else "$min"
            sc.compact && s.auto -> "жду мотор"
            sc.compact && s.running -> "связь…"
            sc.compact -> "выкл."
            s.auto -> "Жду запуска двигателя"
            s.running -> "Подключение…"
            else -> "Запись выключена"
        }
        recUnit.text = if (min >= 60) "ч:мин" else "мин"
        recUnit.visibility = if (s.recording) View.VISIBLE else View.GONE
        rec.textSize = if (s.recording) sc.sbarFont + 1 else sc.sbarFont - 1
        upd.text = when {
            s.lastDataMs <= 0 -> ""
            s.recording -> "обновлено ${((System.currentTimeMillis() - s.lastDataMs) / 1000).coerceAtLeast(0)} с назад"
            else -> "данные от " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT).format(java.util.Date(s.lastDataMs))
        }
        val codes = Regex("Ошибки: ([^\\n]+)").find(s.dtcInfo)?.groupValues?.get(1)?.takeIf { it.contains(Regex("[PCBU][0-9A-F]{4}")) }
        dtcChip.text = codes?.let { if (sc.compact) it else "$it · Check Engine" } ?: ""
        dtcChip.visibility = if (codes != null) View.VISIBLE else View.GONE
    }

    private fun dataAgeSec(s: LoggerState.Snapshot) = ((System.currentTimeMillis() - s.lastDataMs) / 1000).coerceAtLeast(0)

    /** Green while rows keep coming, amber when the trip is open but nothing arrives for 5 s, grey otherwise. */
    private fun writeLamp(s: LoggerState.Snapshot): Lamp = when {
        !s.recording -> Lamp.OFF
        s.lastDataMs > 0 && dataAgeSec(s) <= 5 -> Lamp.OK
        else -> Lamp.WAIT
    }

    private fun showLampTip() {
        val s = snapshot
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            background = roundRect(p.s1, ctx.dp(14).toFloat(), ctx.dp(1), p.line2)
            elevation = ctx.dp(8).toFloat()
        }
        fun line(l: Lamp, title: String, state: String) {
            val dot = View(ctx).apply { background = roundRect(lampColor(l), ctx.dp(9).toFloat()) }
            dot.layoutParams = LinearLayout.LayoutParams(ctx.dp(sc.lamp), ctx.dp(sc.lamp))
            val texts = column(ctx, 0, ctx.text(title, sc.p, p.t1, 600), ctx.text(state, sc.p, p.t2))
            addTo(box, row(ctx, ctx.dp(10), Gravity.CENTER_VERTICAL, dot, texts), ctx.dp(if (box.childCount > 0) 10 else 0))
        }
        line(s.engine, "ЭБУ ↔ двигатель", s.engineText)
        line(s.link, "Бортач ↔ ЭБУ", s.linkText)
        val ago = dataAgeSec(s)
        line(writeLamp(s), "Запись", when {
            !s.recording -> "не идёт"
            writeLamp(s) == Lamp.OK -> "пишется: ${s.rows} строк, последняя ${ago} с назад"
            else -> "запись открыта, но строк нет уже ${ago} с"
        })
        val cap = listOfNotNull(
            s.protocol.takeIf { it.isNotBlank() },
            if (s.cycleMs > 0) "1 строка / %.1f с".format(s.cycleMs / 1000.0) else null,
            if (s.rows > 0) "${s.rows} строк" else null,
        ).joinToString(" · ")
        if (cap.isNotEmpty()) addTo(box, ctx.text(cap, sc.cap, p.t3), ctx.dp(10))
        PopupWindow(box, ctx.dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(0))
            elevation = ctx.dp(8).toFloat()
            showAsDropDown(lampPill, -ctx.dp(300), ctx.dp(8))
        }
    }
}
