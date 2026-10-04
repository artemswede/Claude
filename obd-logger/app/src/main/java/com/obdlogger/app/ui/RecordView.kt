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
import com.obdlogger.core.SensorNames
import com.obdlogger.core.SeriesStore

/**
 * «Запись» (Г1–Г3, Н2), dark theme: panel of 8 tiles, «Внимание» with arc gauges of
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
    private val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val attn = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val charts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val tiles = ArrayList<TileView>()
    private val gauges = ArrayList<GaugeView>()
    private val lanes = LanesView(ctx, p)
    private var sort = AttentionSort.DEVIATION
    private var attnPage = 0
    private var lastRanks: Map<String, Int> = emptyMap()
    private val attnCount = ctx.text("", if (sc.phone) 13f else 16f, p.t2)
    private val windowSeg = Segment(ctx, sc, p, listOf("1 мин", "5 мин", "15 мин"), 1) { i ->
        lanes.windowMs = listOf(1, 5, 15)[i] * 60_000L
        refresh()
    }
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
        charts.addView(lanes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addTo(charts, ctx.text("точка = реальный замер · подпись у конца линии · подложка — норма", 13f, p.t3).apply { gravity = Gravity.END }, dp(4))
        for (v in listOf(panel, attn, charts)) pages.addView(v, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabs.select(0)
    }

    private fun tileCodes(columns: List<String>): List<String> {
        val saved = Prefs.tiles(context)
        val base = saved.ifEmpty { DEFAULT_TILES }
        val chosen = base.filter { it in columns }.toMutableList()
        // Fill up with what this car has, most useful first.
        for (c in Attention.INTERESTING + columns) {
            if (chosen.size >= 8) break
            if (c !in chosen && c in columns && !c.startsWith("pid01_") && !c.startsWith("m21_")) chosen += c
        }
        return chosen.take(8)
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
                t.onLongPick = { code -> pickSensor(tiles.indexOf(t), code) }
                tiles += t
                row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (c > 0) leftMargin = gap })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (sc.phone) dp(176) else 0, if (sc.phone) 0f else 1f).apply { if (r > 0) topMargin = gap })
        }
        if (sc.phone) panel.addView(ScrollView(context).apply { addView(grid) }) else panel.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        attnCount.setOnClickListener { attnPage = 1 - attnPage; refresh() }
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
        attn.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = gap })
    }

    private fun show(i: Int) {
        panel.visibility = if (i == 0) VISIBLE else GONE
        attn.visibility = if (i == 1) VISIBLE else GONE
        charts.visibility = if (i == 2) VISIBLE else GONE
        tabs.setRight(if (i == 2) windowSeg.also { (it.parent as? ViewGroup)?.removeView(it) } else modeText.also { (it.parent as? ViewGroup)?.removeView(it) })
        refresh()
    }

    private fun pickSensor(index: Int, current: String) {
        val s = store ?: return
        val cols = s.columns.filter { s.values(it).isNotEmpty() && it != "t_s" }
        val names = cols.map { "${SensorNames.label(it)}  ·  ${SensorNames.source(it)}" }
        AlertDialog.Builder(context)
            .setTitle("Датчик для плитки")
            .setSingleChoiceItems(names.toTypedArray(), cols.indexOf(current)) { dlg, i ->
                val codes = tiles.map { it.code }.toMutableList()
                if (index in codes.indices) codes[index] = cols[i]
                Prefs.setTiles(context, codes)
                dlg.dismiss()
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun showTab(i: Int) = tabs.select(i)

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
            else -> "режим: ${mode.ru} · долгое нажатие на плитку — выбрать датчик"
        }
        val codes = tileCodes(s.columns)
        when (tabs.selected) {
            0 -> tiles.forEachIndexed { i, t ->
                val code = codes.getOrNull(i)
                t.visibility = if (code == null) INVISIBLE else VISIBLE
                if (code != null) t.set(code, s, mode, if (stale) last else null, if (stale) last!! else now)
            }
            1 -> {
                val ranked = Attention.rank(s, sort = sort)
                val unstable = Attention.unstableCount(ranked)
                val pagesN = if (ranked.size > 6) 2 else 1
                if (attnPage >= pagesN) attnPage = 0
                val from = attnPage * 6
                attnCount.text = when {
                    ranked.isEmpty() -> "данных пока нет"
                    else -> "${from + 1}–${minOf(from + 6, ranked.size)} из ${ranked.size} · нестабильных $unstable" + if (pagesN > 1) "   ${if (attnPage == 0) "●○" else "○●"}" else ""
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
            2 -> lanes.set(s, (LANES.filter { it in s.columns } + codes).distinct().take(if (sc.phone) 4 else 6), !stale, mode)
        }
    }

    companion object {
        val DEFAULT_TILES = listOf("trim_b1", "trim_b2", "o2_b1s2_v", "rpm", "maf_gs", "o2_b1s1_v", "coolant_c", "battery_v")
        val LANES = listOf("rpm", "trim_b1", "o2_b1s1_v", "o2_b1s2_v", "maf_gs", "coolant_c")
    }
}
