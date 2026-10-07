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
        bar.addView(logo)
        addTo(bar, brand, ctx.dp(8))
        if (!sc.phone) {
            sep.layoutParams = LinearLayout.LayoutParams(ctx.dp(1), ctx.dp(22))
            addTo(bar, sep, ctx.dp(18))
            addTo(bar, car, ctx.dp(18))
        }
        addTo(bar, spacer(ctx))
        dtcChip.setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(4))
        dtcChip.setOnClickListener { onDtc() }
        dtcChip.tap()
        addTo(bar, dtcChip, ctx.dp(12))
        lampPill.orientation = LinearLayout.HORIZONTAL
        lampPill.gravity = Gravity.CENTER_VERTICAL
        lampPill.setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
        lampPill.minimumHeight = ctx.dp(48)
        lampPill.minimumWidth = ctx.dp(64)
        // Left to right as the chain goes: ECU sees the engine → Бортач sees the ECU → Бортач writes.
        for ((i, l) in listOf(lamp1, lamp2).withIndex()) {
            lampPill.addView(l, LinearLayout.LayoutParams(ctx.dp(sc.lamp), ctx.dp(sc.lamp)).apply { if (i > 0) leftMargin = ctx.dp(10) })
        }
        lampPill.elevation = ctx.dp(1).toFloat()
        lampPill.contentDescription = "Лампы: двигатель, связь с ЭБУ, запись — подробнее"
        lampPill.setOnClickListener { showLampTip() }
        addTo(bar, lampPill, ctx.dp(14))
        // The writing lamp blinks right at «REC»: chain reads engine · ECU link → ● REC.
        lamp3.layoutParams = LinearLayout.LayoutParams(ctx.dp(sc.lamp), ctx.dp(sc.lamp))
        addTo(bar, row(ctx, ctx.dp(8), Gravity.CENTER_VERTICAL, lamp3, rec).apply {
            setPadding(ctx.dp(6), 0, ctx.dp(6), 0)
            setOnClickListener { showLampTip() }
            tap()
        }, ctx.dp(8))
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

    fun show(pg: Page) {
        page = pg
        containers.forEach { (k, v) -> v.visibility = if (k == pg) View.VISIBLE else View.GONE }
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
        car.text = if (unnamed) (if (sc.compact) "Назвать машину" else "Новая машина · указать название") else name
        val showCar = !sc.phone && car.text.isNotEmpty()
        car.visibility = if (showCar) View.VISIBLE else View.GONE
        sep.visibility = car.visibility
        car.setTextColor(if (unnamed) p.acc else p.t2)
        car.setOnClickListener { onCar() }
        lamp1.background = roundRect(lampColor(s.engine), ctx.dp(sc.lamp).toFloat())
        lamp2.background = roundRect(lampColor(s.link), ctx.dp(sc.lamp).toFloat())
        val writing = writeLamp(s)
        lamp3.background = roundRect(lampColor(writing), ctx.dp(sc.lamp).toFloat())
        lamp3.visibility = if (s.recording) View.VISIBLE else View.GONE
        if (writing == Lamp.OK) {
            if (!blink.isStarted) blink.start()
        } else {
            blink.cancel()
            lamp3.alpha = 1f
        }
        val min = s.elapsedSec / 60
        rec.typeface = if (s.recording) Bt.mono(ctx, 600) else Bt.sans(ctx, 400)
        rec.text = when {
            s.recording -> if (min >= 60) "REC · ${min / 60} ч %02d мин".format(min % 60) else "REC · $min мин"
            s.auto -> "Жду запуска двигателя"
            s.running -> "Подключение…"
            else -> "Запись выключена"
        }
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
