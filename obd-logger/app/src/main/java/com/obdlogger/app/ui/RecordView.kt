package com.obdlogger.app.ui

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.obdlogger.app.LoggerState
import com.obdlogger.app.Prefs
import com.obdlogger.app.R
import com.obdlogger.core.Attention
import com.obdlogger.core.AttentionSort
import com.obdlogger.core.LiveMode
import com.obdlogger.core.Panel
import com.obdlogger.core.PanelSort
import com.obdlogger.core.SensorNames
import com.obdlogger.core.SeriesStore

/**
 * «Запись» (Г1–Г3, Н2), dark theme: panel of every sensor, 8 tiles a page (swipe for
 * the next; problem sensors first, by jumps or in the owner's order, minus the ones
 * switched off), «Внимание» with arc gauges of
 * the most unstable sensors, and lane charts. When nothing is being recorded the
 * last recording is shown greyed with a banner saying when it stopped.
 */
class RecordView(ctx: Context, private val sc: Bt.Scale) : FrameLayout(ctx) {
    private val p = Bt.DARK
    private val banner = ctx.text("", if (sc.phone) 14f else 17f, p.t1).apply {
        setPadding(dp(sc.pad), dp(12), dp(sc.pad), dp(12))
        setBackgroundColor(p.s1)
        val ic = context.getDrawable(R.drawable.ic_timer)!!.tinted(p.t2)
        val s = dp(22)
        ic.setBounds(0, 0, s, s)
        setCompoundDrawables(ic, null, null, null)
        compoundDrawablePadding = dp(14)
        visibility = GONE
    }
    private val modeText = ctx.text("", if (sc.phone) 12f else 15f, p.t2, maxLines = 1)
    private val pages = FrameLayout(ctx)
    private val panel = SwipePager(ctx) { step -> panelPage += step; refresh() }
    private var panelPage = 0
    /** Sensors in the order the panel shows them, all pages. */
    private var panelCodes: List<String> = emptyList()
    private var orderAt = 0L
    private var orderDirty = true
    private val pageText = ctx.text("", if (sc.phone) 13f else 15f, p.t2, 600, maxLines = 1).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { panelPage++; refresh() }
    }.tap()
    private val menuText = ctx.text("Датчики ▾", if (sc.phone) 13f else 15f, p.acc, 600, maxLines = 1).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { panelMenu() }
    }.tap()
    private val panelRight = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val attn = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val charts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val tiles = ArrayList<TileView>()
    private val gauges = ArrayList<GaugeView>()
    private val lanes = LanesView(ctx, p)
    private var sort = AttentionSort.DEVIATION
    private var attnPage = 0
    private var chartPage = 0
    /** «2/4 ›» next to the time window on «Графики». */
    private val chartPageText = ctx.text("", if (sc.phone) 13f else 15f, p.t2, 600, maxLines = 1).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { chartPage++; refresh() }
    }.tap()
    private val chartsRight = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var lastRanks: Map<String, Int> = emptyMap()
    private val attnCount = ctx.text("", if (sc.phone) 13f else 16f, p.t2)
    private val windowSeg = Segment(ctx, sc, p, listOf("1 мин", "5 мин", "15 мин"), 1) { i ->
        lanes.windowMs = listOf(1, 5, 15)[i] * 60_000L
        refresh()
    }
    private var windowIdx = 1
    private val windowText = ctx.text("окно 5 мин ▾", if (sc.phone) 13f else 15f, p.acc, 600, maxLines = 1).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener {
            windowIdx = (windowIdx + 1) % 3
            val m = listOf(1, 5, 15)[windowIdx]
            lanes.windowMs = m * 60_000L
            text = "окно $m мин ▾"
            refresh()
        }
    }.tap()
    private var store: SeriesStore? = null
    private var snapshot = LoggerState.Snapshot()
    private val tabs = Tabs(ctx, sc, p, listOf("Панель", "Внимание", "Графики")) { show(it) }

    init {
        setBackgroundColor(p.bg)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(banner)
        col.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(Tabs.height(sc))))
        col.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(col)
        buildPanel()
        buildAttention()
        charts.setPadding(dp(if (sc.phone) 8 else 16), dp(8), dp(if (sc.phone) 8 else 16), dp(8))
        // Swipe through every sensor, 6 lanes a page (4 on a phone).
        charts.addView(SwipePager(ctx) { step -> chartPage += step; refresh() }.apply { addView(lanes) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addTo(chartsRight, chartPageText)
        // Short screens: one button that cycles the window instead of a three-part switch.
        if (sc.compact) addTo(chartsRight, windowText, dp(4)) else addTo(chartsRight, windowSeg, dp(8))
        addTo(charts, ctx.text("точка = реальный замер · подпись у конца линии · подложка — норма", 13f, p.t3).apply { gravity = Gravity.END }, dp(4))
        // Short screens: the mode is written on the tiles anyway; the room goes to pages and «Датчики».
        if (!sc.compact) addTo(panelRight, modeText)
        addTo(panelRight, pageText, dp(4))
        addTo(panelRight, menuText, dp(4))
        for (v in listOf(panel, attn, charts)) pages.addView(v, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabs.select(0)
    }

    private fun panelOrder(s: SeriesStore): List<String> {
        val custom = Prefs.tiles(context).ifEmpty { DEFAULT_TILES }
        return Panel.order(s, Prefs.panelSort(context), custom, Prefs.panelHidden(context))
    }

    private fun buildPanel() {
        val gap = dp(if (sc.phone) 8 else 12)
        panel.setPadding(gap, gap, gap, gap)
        val cols = if (sc.phone) 2 else 4
        val rows = 8 / cols
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until rows) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val t = TileView(context, p)
                t.onLongPick = { code -> tileMenu(code) }
                tiles += t
                row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (c > 0) leftMargin = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (sc.phone) dp(176) else 0, if (sc.phone) 0f else 1f).apply { if (r > 0) topMargin = gap })
        }
        if (sc.phone) panel.addView(ScrollView(context).apply { addView(grid) }) else panel.addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildAttention() {
        val gap = dp(if (sc.phone) 8 else 12)
        attn.setPadding(gap, gap, gap, gap)
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Segment(context, sc, p, listOf("Отклонение от нормы", "Скачки"), 0) { i ->
            sort = if (i == 0) AttentionSort.DEVIATION else AttentionSort.JUMPS
            attnPage = 0
            refresh()
        })
        addTo(head, spacer(context))
        attnCount.setOnClickListener { attnPage++; refresh() }
        attnCount.tap()
        addTo(head, attnCount)
        attn.addView(head)
        val cols = if (sc.phone) 2 else 3
        val rows = 6 / cols
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until rows) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val g = GaugeView(context, p)
                gauges += g
                row.addView(g, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (c > 0) leftMargin = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { if (r > 0) topMargin = gap })
        }
        // Swipe through every sensor of the panel, 6 a page.
        attn.addView(SwipePager(context) { step -> attnPage += step; refresh() }.apply { addView(grid) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = gap })
    }

    private fun show(i: Int) {
        panel.visibility = if (i == 0) VISIBLE else GONE
        attn.visibility = if (i == 1) VISIBLE else GONE
        charts.visibility = if (i == 2) VISIBLE else GONE
        val right: View = when (i) {
            0 -> panelRight
            2 -> chartsRight
            else -> modeText
        }
        if (i != 0 && modeText.parent === panelRight) panelRight.removeView(modeText)
        if (i == 0 && !sc.compact && modeText.parent !== panelRight) { (modeText.parent as? ViewGroup)?.removeView(modeText); panelRight.addView(modeText, 0) }
        (right.parent as? ViewGroup)?.removeView(right)
        tabs.setRight(right)
        refresh()
    }

    private fun label(code: String) = "${SensorNames.label(code)}  ·  ${SensorNames.source(code)}"

    /** «Датчики ▾»: order of the panel and which sensors it shows. */
    private fun panelMenu() {
        val st = store ?: return
        val sort = Prefs.panelSort(context)
        val items = arrayOf("Порядок: ${sort.ru}", "Какие датчики показывать…", "Сбросить свой порядок")
        AlertDialog.Builder(context)
            .setTitle("Датчики панели")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickSort()
                    1 -> pickVisible(st)
                    2 -> { Prefs.setTiles(context, emptyList()); orderDirty = true; refresh() }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun pickSort() {
        val all = PanelSort.entries
        AlertDialog.Builder(context)
            .setTitle("Порядок плиток")
            .setSingleChoiceItems(all.map { it.ru }.toTypedArray(), all.indexOf(Prefs.panelSort(context))) { dlg, i ->
                setSort(all[i])
                dlg.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setSort(sort: PanelSort) {
        // Own order starts from what is on the screen now, so nothing jumps.
        if (sort == PanelSort.CUSTOM && Prefs.panelSort(context) != PanelSort.CUSTOM) Prefs.setTiles(context, panelCodes)
        Prefs.setPanelSort(context, sort)
        panelPage = 0
        orderDirty = true
        refresh()
    }

    /** Ticks: what the panel shows. Raw bytes are off until ticked. */
    private fun pickVisible(st: SeriesStore) {
        val all = Panel.sensors(st)
        val hidden = Prefs.panelHidden(context) ?: all.filter { Panel.isRaw(it) }.toSet()
        val checked = BooleanArray(all.size) { all[it] !in hidden }
        AlertDialog.Builder(context)
            .setTitle("Показывать на панели")
            .setMultiChoiceItems(all.map(::label).toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Готово") { _, _ ->
                Prefs.setPanelHidden(context, all.filterIndexed { i, _ -> !checked[i] }.toSet())
                panelPage = 0
                orderDirty = true
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Long press on a tile: move it, swap the sensor or switch it off. Moving turns on the own order. */
    private fun tileMenu(code: String) {
        val st = store ?: return
        val items = arrayOf("Поставить первым", "Сдвинуть раньше", "Сдвинуть позже", "Заменить другим датчиком…", "Не показывать")
        AlertDialog.Builder(context)
            .setTitle(SensorNames.label(code))
            .setItems(items) { _, which ->
                val order = panelCodes.toMutableList()
                val i = order.indexOf(code)
                if (i < 0) return@setItems
                when (which) {
                    0 -> { order.removeAt(i); order.add(0, code) }
                    1 -> if (i > 0) { order.removeAt(i); order.add(i - 1, code) }
                    2 -> if (i < order.lastIndex) { order.removeAt(i); order.add(i + 1, code) }
                    3 -> { replaceSensor(st, code, order); return@setItems }
                    4 -> {
                        val hidden = (Prefs.panelHidden(context) ?: Panel.sensors(st).filter { Panel.isRaw(it) }.toSet()) + code
                        Prefs.setPanelHidden(context, hidden)
                        orderDirty = true
                        refresh()
                        return@setItems
                    }
                }
                Prefs.setTiles(context, order)
                Prefs.setPanelSort(context, PanelSort.CUSTOM)
                orderDirty = true
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun replaceSensor(st: SeriesStore, code: String, order: MutableList<String>) {
        val cols = Panel.sensors(st)
        AlertDialog.Builder(context)
            .setTitle("Датчик вместо «${SensorNames.label(code)}»")
            .setSingleChoiceItems(cols.map(::label).toTypedArray(), cols.indexOf(code)) { dlg, i ->
                val pick = cols[i]
                val at = order.indexOf(code)
                order.remove(pick)
                order.add(at.coerceIn(0, order.size), pick)
                order.remove(code)
                order.add(code)
                Prefs.setTiles(context, order)
                Prefs.setPanelSort(context, PanelSort.CUSTOM)
                Prefs.panelHidden(context)?.let { if (pick in it) Prefs.setPanelHidden(context, it - pick) }
                dlg.dismiss()
                orderDirty = true
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun showTab(i: Int) = tabs.select(i)

    /** Shows the panel page [i] (0-based), as a swipe would. */
    fun showPanelPage(i: Int) { panelPage = i; refresh() }

    fun bind(store: SeriesStore, s: LoggerState.Snapshot) {
        this.store = store
        snapshot = s
        refresh()
    }

    fun refresh() {
        val s = store ?: return
        val snap = snapshot
        val last = s.lastTime()
        val stale = !snap.recording && last != null
        val now = System.currentTimeMillis()
        banner.visibility = if (stale) VISIBLE else GONE
        if (stale) {
            banner.text = android.text.SpannableStringBuilder().apply {
                val head = "Запись остановлена ${Num.clockFull(last!!)}"
                append(head)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, head.length, 0)
                append(" — показаны данные прошлой записи." + if (snap.auto) " Новая начнётся сама при запуске мотора." else "")
            }
        }
        val mode = if (stale) LiveMode.OFF else LiveMode.of(s.last("rpm"), s.last("speed_kmh"), s.last("coolant_c"))
        modeText.text = when {
            last == null -> "данных пока нет"
            stale -> "последняя точка ${Num.clock(last)}"
            sc.phone -> "режим: ${mode.ru}"
            sc.compact -> "режим: ${mode.ru}"
            else -> "режим: ${mode.ru} · долгое нажатие на плитку — порядок"
        }
        // Problem order is recounted every 10 s, not every second: tiles must not jump under the finger.
        if (orderDirty || panelCodes.isEmpty() || now - orderAt > 10_000) {
            panelCodes = panelOrder(s)
            orderAt = now
            orderDirty = false
        }
        val codes = panelCodes
        when (tabs.selected) {
            0 -> {
                val n = Panel.pages(codes.size)
                panelPage = ((panelPage % n) + n) % n
                pageText.text = if (n > 1) "${panelPage + 1}/$n ›" else ""
                pageText.visibility = if (n > 1) VISIBLE else GONE
                val from = panelPage * Panel.PER_PAGE
                tiles.forEachIndexed { i, t ->
                    val code = codes.getOrNull(from + i)
                    t.visibility = if (code == null) INVISIBLE else VISIBLE
                    if (code != null) t.set(code, s, mode, if (stale) last else null, if (stale) last!! else now)
                }
            }
            1 -> {
                // Every sensor the panel shows (minus the switched-off ones), ranked.
                val ranked = Attention.rank(s, sort = sort, codes = codes)
                val unstable = Attention.unstableCount(ranked)
                val pagesN = maxOf(1, (ranked.size + 5) / 6)
                attnPage = ((attnPage % pagesN) + pagesN) % pagesN
                val from = attnPage * 6
                attnCount.text = when {
                    ranked.isEmpty() -> "данных пока нет"
                    else -> "${from + 1}–${minOf(from + 6, ranked.size)} из ${ranked.size} · нестабильных $unstable" + if (pagesN > 1) "   ${attnPage + 1}/$pagesN ›" else ""
                }
                val end = last ?: 0L
                gauges.forEachIndexed { i, g ->
                    val it = ranked.getOrNull(from + i)
                    g.visibility = if (it == null) INVISIBLE else VISIBLE
                    if (it != null) {
                        val rank = from + i + 1
                        val prev = lastRanks[it.code]
                        val moved = when {
                            prev == null || prev == rank -> null
                            prev > rank -> "↑ с №$prev"
                            else -> "↓ с №$prev"
                        }
                        g.set(it, rank, s.series(it.code, end - 60_000).second, moved)
                    }
                }
                if (sort == AttentionSort.DEVIATION) lastRanks = ranked.withIndex().associate { (i, x) -> x.code to i + 1 }
            }
            2 -> {
                val all = (LANES.filter { it in codes } + codes).distinct()
                val per = if (sc.phone) 4 else 6
                val n = maxOf(1, (all.size + per - 1) / per)
                chartPage = ((chartPage % n) + n) % n
                chartPageText.text = if (n > 1) "${chartPage + 1}/$n ›" else ""
                chartPageText.visibility = if (n > 1) VISIBLE else GONE
                lanes.set(s, all.drop(chartPage * per).take(per), !stale, mode)
            }
        }
    }

    companion object {
        val DEFAULT_TILES = listOf("trim_b1", "trim_b2", "o2_b1s2_v", "rpm", "maf_gs", "o2_b1s1_v", "coolant_c", "battery_v")
        val LANES = listOf("rpm", "trim_b1", "o2_b1s1_v", "o2_b1s2_v", "maf_gs", "coolant_c")
    }
}
