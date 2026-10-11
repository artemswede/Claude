package com.obdlogger.app.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.obdlogger.app.Prefs
import com.obdlogger.core.SensorNames

/**
 * The control row of every chart (макет «Графики», Г1): one row of 48 dp —
 * `[Дорожки | Наложение]  Датчики ⌄ N  ···  ‹ 5 мин ›  ● Сейчас  ⋯`.
 * «Датчики» is a list with ticks (like «Select data» in Excel) and the sets on top; «⋯» holds
 * the sets, «Сохранить текущий…», «Окно в CSV» and the view switches. Every gesture of
 * [LanesView] has a button here. Choices are remembered per [place] («record», «trip»).
 */
class ChartBar(
    ctx: Context,
    private val sc: Bt.Scale,
    private val p: Bt.Palette,
    private val lanes: LanesView,
    private val place: String,
    /** «⤢» in this row (pages without a tab line); null — the host shows it elsewhere or this is the full screen. */
    private val onFull: (() -> Unit)? = null,
    /** In the full-screen copy: «⤡» closes it. */
    private val onCloseFull: (() -> Unit)? = null,
) : LinearLayout(ctx) {
    private val prefs = Prefs.of(ctx)
    private val font = if (sc.phone) 14f else 15f
    private val mode = Segment(ctx, sc, p, listOf("Дорожки", "Наложение"), if (lanes.overlayMode) 1 else 0) { setMode(it == 1) }
    private val sensors = ctx.text("", font, p.t1, 600, maxLines = 1).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(10), 0)
        background = roundRect(p.s1, dp(10).toFloat(), dp(1), p.line2)
        contentDescription = "Выбрать датчики"
        setOnClickListener { pick() }
    }.tap()
    private val back = icon("‹", "Назад во времени на полокна") { lanes.shift(-1) }
    private val fwd = icon("›", "Вперёд на полокна") { lanes.shift(+1) }
    private val window = ctx.text("", font, p.t1, 600, mono = true, maxLines = 1).apply {
        gravity = Gravity.CENTER
        minWidth = dp(64)
        contentDescription = "Окно на экране"
        setOnClickListener { windowMenu(this) }
    }.tap()
    private val now = ctx.text("", font, p.t1, 600, maxLines = 1).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { lanes.reset() }
    }.tap()
    private val more = icon("⋯", "Ещё") { moreMenu(it) }

    /** Lanes chosen in «Дорожки» (null — the default set). */
    private var laneSet: List<String>? = prefs.getString("chart_set_$place", null)?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }

    private fun icon(t: String, desc: String, onClick: (View) -> Unit) = context.text(t, if (sc.phone) 20f else 22f, p.t1, 600, maxLines = 1).apply {
        gravity = Gravity.CENTER
        minWidth = dp(48)
        contentDescription = desc
        setOnClickListener { onClick(it) }
    }.tap()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        minimumHeight = dp(48)
        // Restore the view of this place.
        lanes.overlayMode = prefs.getBoolean("chart_mode_$place", false)
        prefs.getString("chart_ov_$place", null)?.split(',')?.filter { it.isNotBlank() }?.let { lanes.setOverlay(it) }
        prefs.getInt("chart_view_$place", 0b0111).let { v ->
            lanes.showNorm = v and 1 != 0; lanes.showDots = v and 2 != 0; lanes.showGrid = v and 4 != 0; lanes.scaleByWindow = v and 8 != 0
        }
        lanes.perScreen = perScreen
        mode.select(if (lanes.overlayMode) 1 else 0)
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(row, mode, 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(row, sensors.apply { layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(4) } })
        row.addView(View(ctx), LayoutParams(dp(4), 1, 1f))
        val group = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(p.s1, dp(10).toFloat(), dp(1), p.line2)
        }
        group.addView(back, LayoutParams(dp(44), dp(40)))
        group.addView(window, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        group.addView(fwd, LayoutParams(dp(44), dp(40)))
        row.addView(group, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { leftMargin = dp(4) })
        row.addView(now, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { leftMargin = dp(4) })
        onFull?.let { f -> row.addView(icon("⤢", "На весь экран") { f() }, LayoutParams(dp(48), dp(48))) }
        onCloseFull?.let { f -> row.addView(icon("⤡", "Свернуть") { f() }, LayoutParams(dp(48), dp(48))) }
        row.addView(more, LayoutParams(dp(48), dp(48)))
        addView(HorizontalScrollView(ctx).apply { addView(row); isHorizontalScrollBarEnabled = false; isFillViewport = true },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        lanes.onChange = { update() }
        lanes.onAdd = { pick() }
        update()
    }

    var perScreen: Int
        get() = prefs.getInt("chart_lanes_$place", prefs.getInt("chart_lanes", if (sc === Bt.TABLET) 4 else 3)).coerceIn(1, 4)
        set(v) {
            prefs.edit().putInt("chart_lanes_$place", v).apply()
            lanes.perScreen = v
            update()
        }

    /** The lanes to show from [available] (in their order): the chosen set, else all of them. */
    fun visible(available: List<String>): List<String> = laneSet?.filter { it in available }?.ifEmpty { null } ?: available

    fun setMode(overlay: Boolean) {
        if (overlay && lanes.overlay.isEmpty()) {
            // The first three sensors of the lanes, so the overlay is never empty at first.
            lanes.setOverlay(visible(lanes.columns()).take(3))
        }
        lanes.overlayMode = overlay
        prefs.edit().putBoolean("chart_mode_$place", overlay).apply()
        update()
    }

    fun update() {
        val n = if (lanes.overlayMode) lanes.overlay.size else visible(lanes.columns()).size
        sensors.text = "Датчики ⌄ $n"
        window.text = label(lanes.windowMs)
        fwd.alpha = if (lanes.pannedBack) 1f else 0.35f
        fwd.isEnabled = lanes.pannedBack
        now.text = if (lanes.pannedBack) "Сейчас ›" else "● Сейчас"
        now.setTextColor(if (lanes.pannedBack) p.acc else p.t2)
        if (mode.selected != (if (lanes.overlayMode) 1 else 0)) mode.select(if (lanes.overlayMode) 1 else 0)
        prefs.edit().putString("chart_ov_$place", lanes.overlay.joinToString(",")).apply()
    }

    private fun windowMenu(anchor: View) {
        val m = android.widget.PopupMenu(context, anchor)
        STEPS.forEachIndexed { i, ms -> m.menu.add(0, i, i, label(ms)) }
        m.menu.add(0, 99, 99, "Вся запись")
        m.setOnMenuItemClickListener { item ->
            lanes.windowMs = if (item.itemId == 99) Long.MAX_VALUE / 4 else STEPS[item.itemId]
            update(); true
        }
        m.show()
    }

    // ---- «Датчики ⌄»: the list with ticks and the sets ----

    private fun pick() {
        val overlay = lanes.overlayMode
        val all = lanes.columns().filter { it != "time" && it != "t_s" && it != "marker" && !it.startsWith("pid01_") }
        // Checked first (in the order they were ticked), then the rest in the usual order.
        val checked = ArrayList(if (overlay) lanes.overlay.filter { it in all } else visible(all))
        val order = checked + all.filter { it !in checked }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        val sets = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val caption = context.text("", sc.cap, p.t2)
        val full = context.text("Наложение заполнено: 6 из 6. Чтобы добавить датчик, снимите отметку с другого.", sc.cap, p.amb, 600)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = HashMap<String, TextView>()
        fun render() {
            val limit = overlay && checked.size >= Series.MAX
            caption.text = if (overlay) "Наложение · до ${Series.MAX} датчиков · отмечено ${checked.size}" else "Дорожки · отмечено ${checked.size} · с 7-го — без цвета, только дорожка"
            full.visibility = if (limit) View.VISIBLE else View.GONE
            for (code in order) {
                val tv = rows[code] ?: continue
                val k = checked.indexOf(code)
                val mark = when {
                    k < 0 -> "☐"
                    k < Series.MAX -> "☑"
                    else -> "☑"
                }
                val shape = if (k in 0 until Series.MAX) Series.glyph(k) else if (k >= 0) "–" else " "
                val v = lanes.last(code).takeIf { !it.isNaN() }?.let { "  ${Num.fmt(code, it)} ${SensorNames.unit(code)}" }.orEmpty()
                val only = if (k >= Series.MAX) "  · без цвета · только дорожка" else ""
                tv.text = "$mark  $shape  ${SensorNames.label(code)}$v$only"
                tv.setTextColor(if (k in 0 until Series.MAX) p.t1 else p.t2)
                tv.alpha = if (limit && k < 0) 0.4f else 1f
            }
        }
        fun preset(name: String, codes: List<String>, ms: Long?) = context.text(name, sc.p, p.acc, 600).apply {
            setPadding(dp(14), 0, dp(14), 0)
            background = roundRect(p.accT, dp(20).toFloat())
            setOnClickListener {
                checked.clear()
                checked.addAll(codes.filter { it in all }.let { if (overlay) it.take(Series.MAX) else it })
                ms?.let { lanes.windowMs = it }
                render()
            }
        }.tap()
        for (pr in builtIn() + saved()) addTo(sets, preset(pr.name, pr.codes, pr.windowMs), dp(6), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(sets, context.text("+ Свой", sc.p, p.t1, 600).apply {
            setPadding(dp(14), 0, dp(14), 0)
            background = roundRect(p.s1, dp(20).toFloat(), dp(1), p.line2)
            setOnClickListener { saveDialog(checked.toList()) }
        }.tap(), dp(6), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(box, context.label("Наборы", sc, p))
        addTo(box, HorizontalScrollView(context).apply { addView(sets); isHorizontalScrollBarEnabled = false }, dp(4))
        addTo(box, caption, dp(10))
        addTo(box, full, dp(4))
        for (code in order) {
            val tv = context.text("", if (sc.phone) 15f else 16f, p.t1, 500, maxLines = 1).apply {
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (code in checked) checked.remove(code)
                    else if (!(overlay && checked.size >= Series.MAX)) checked.add(code)
                    render()
                }
            }
            rows[code] = tv
            addTo(list, tv)
            addTo(list, hline(context, p.line))
        }
        render()
        val scroll = ScrollView(context).apply { addView(list) }
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (sc.phone) 420 else 220)).apply { topMargin = dp(6) })
        AlertDialog.Builder(context)
            .setTitle(if (overlay) "Датчики на наложении" else "Дорожки на экране")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                if (overlay) lanes.setOverlay(checked)
                else {
                    laneSet = checked.toList()
                    prefs.edit().putString("chart_set_$place", checked.joinToString(",")).apply()
                    lanes.onChange()
                }
                update()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---- «⋯» ----

    private fun moreMenu(anchor: View) {
        val m = android.widget.PopupMenu(context, anchor)
        val sets = m.menu.addSubMenu(0, 1, 1, "Мои наборы")
        val all = builtIn() + saved()
        all.forEachIndexed { i, pr -> sets.add(0, 100 + i, i, pr.name) }
        m.menu.add(0, 2, 2, "Сохранить текущий…")
        m.menu.add(0, 3, 3, "Окно в CSV")
        if (!lanes.overlayMode) m.menu.add(0, 4, 4, "Дорожек на экране: $perScreen")
        m.menu.add(0, 10, 10, "Подложка нормы").apply { isCheckable = true; isChecked = lanes.showNorm }
        m.menu.add(0, 11, 11, "Точки замеров").apply { isCheckable = true; isChecked = lanes.showDots }
        m.menu.add(0, 12, 12, "Шкала по окну").apply { isCheckable = true; isChecked = lanes.scaleByWindow }
        m.menu.add(0, 13, 13, "Сетка").apply { isCheckable = true; isChecked = lanes.showGrid }
        if (saved().isNotEmpty()) m.menu.add(0, 20, 20, "Удалить свои наборы")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> saveDialog(if (lanes.overlayMode) lanes.overlay.toList() else visible(lanes.columns()))
                3 -> exportWindow()
                4 -> perScreen = perScreen % 4 + 1
                10 -> lanes.showNorm = !lanes.showNorm
                11 -> lanes.showDots = !lanes.showDots
                12 -> lanes.scaleByWindow = !lanes.scaleByWindow
                13 -> lanes.showGrid = !lanes.showGrid
                20 -> store(emptyList())
                else -> all.getOrNull(item.itemId - 100)?.let { apply(it) }
            }
            if (item.itemId in 10..13) {
                prefs.edit().putInt("chart_view_$place",
                    (if (lanes.showNorm) 1 else 0) or (if (lanes.showDots) 2 else 0) or (if (lanes.showGrid) 4 else 0) or (if (lanes.scaleByWindow) 8 else 0)).apply()
            }
            true
        }
        m.show()
    }

    private fun exportWindow() {
        val csv = lanes.windowCsv()
        if (csv.isBlank()) return
        Thread {
            val f = java.io.File(com.obdlogger.app.SessionFiles.dir(context), "window_" +
                java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".csv")
            f.writeText(csv)
            val r = com.obdlogger.app.SessionFiles.exportToDownloads(context, f)
            post {
                android.widget.Toast.makeText(context, if (r.ok) "Окно сохранено: Загрузки/${com.obdlogger.app.SessionFiles.DOWNLOAD_FOLDER}/${f.name}" else "Не удалось сохранить окно",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ---- sets ----

    private class Preset(val name: String, val overlay: Boolean, val windowMs: Long?, val codes: List<String>)

    private fun builtIn() = listOf(
        Preset("Смесь", true, 5 * 60_000L, listOf("trim_b1", "maf_gs", "o2_b1s2_v", "rpm")),
        Preset("Зарядка", true, 5 * 60_000L, listOf("battery_v", "rpm", "engine_load_pct")),
        Preset("Температуры", true, 15 * 60_000L, listOf("coolant_c", "intake_air_c", "speed_kmh")),
        Preset("Холостой", true, 2 * 60_000L, listOf("rpm", "maf_gs", "timing_deg", "trim_b1")),
    )

    private fun saved(): List<Preset> = prefs.getString("chart_sets", null)?.lines()?.mapNotNull { l ->
        val f = l.split('|')
        if (f.size < 4) null else Preset(f[0], f[1] == "1", f[2].toLongOrNull(), f[3].split(',').filter { it.isNotBlank() })
    }.orEmpty()

    private fun store(list: List<Preset>) = prefs.edit()
        .putString("chart_sets", list.joinToString("\n") { "${it.name.replace('|', ' ')}|${if (it.overlay) 1 else 0}|${it.windowMs ?: ""}|${it.codes.joinToString(",")}" }).apply()

    private fun apply(pr: Preset) {
        lanes.setOverlay(pr.codes.filter { lanes.has(it) })
        pr.windowMs?.let { lanes.windowMs = it }
        setMode(pr.overlay)
        lanes.reset()
    }

    private fun saveDialog(codes: List<String>) {
        val field = android.widget.EditText(context).apply { hint = "Название, например «Подсос»"; setSingleLine() }
        val box = FrameLayout(context).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        AlertDialog.Builder(context)
            .setTitle("Сохранить набор")
            .setMessage(codes.joinToString(", ") { SensorNames.label(it) } + "\nОкно и режим тоже запомнятся: " +
                (if (lanes.overlayMode) "Наложение" else "Дорожки") + ", " + label(lanes.windowMs) + ".")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = field.text.toString().trim().ifEmpty { "Набор ${saved().size + 1}" }
                store((saved().filter { it.name != name } + Preset(name, lanes.overlayMode, lanes.windowMs, codes)).takeLast(8))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    companion object {
        val STEPS = listOf(30_000L, 60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L)

        fun label(ms: Long): String = when {
            ms < 60_000 -> "${ms / 1000} с"
            ms < 3600_000 -> "${ms / 60_000} мин"
            ms % 3600_000 == 0L -> "${ms / 3600_000} ч"
            else -> "%.1f ч".format(ms / 3600_000.0)
        }

        /**
         * The chart on the whole screen (no tabs, no menu): a copy of [lanes] that follows its data;
         * closing brings the view back. Exit — «⤡» or «Назад».
         */
        fun full(ctx: Context, sc: Bt.Scale, p: Bt.Palette, lanes: LanesView, place: String, onClosed: () -> Unit = {}) {
            val d = Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
            val big = LanesView(ctx, p)
            lanes.copyTo(big)
            lanes.mirror = big
            val col = LinearLayout(ctx).apply {
                orientation = VERTICAL
                setBackgroundColor(p.bg)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            val keep = lanes.onChange
            col.addView(big, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            col.addView(ChartBar(ctx, sc, p, big, place, onCloseFull = { d.dismiss() }), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            d.setContentView(col)
            d.setOnDismissListener {
                lanes.mirror = null
                big.copyTo(lanes)
                lanes.onChange = keep
                keep()
                onClosed()
            }
            d.show()
        }
    }
}

/**
 * «В движении» (макет «Графики» 7д): two lanes from the «В пути» set — coolant and voltage —
 * with 40 sp numbers and «в норме» in words; the last 5 minutes, the norm as a band.
 */
class DriveCharts(ctx: Context, private val p: Bt.Palette) : View(ctx) {
    private var store: com.obdlogger.core.SeriesStore? = null
    private var mode = com.obdlogger.core.LiveMode.DRIVE
    private val codes = listOf("coolant_c", "battery_v")
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.5f * ctx.resources.displayMetrics.density }

    fun set(s: com.obdlogger.core.SeriesStore, mode: com.obdlogger.core.LiveMode) {
        store = s
        this.mode = mode
        invalidate()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val s = store ?: return
        val d = resources.displayMetrics.density
        val shown = codes.filter { it in s.columns }
        if (shown.isEmpty()) return
        val rowH = height.toFloat() / shown.size
        val end = s.lastTime() ?: return
        shown.forEachIndexed { k, code ->
            val top = rowH * k
            fill.color = p.s1
            c.drawRoundRect(4 * d, top + 4 * d, width - 4 * d, top + rowH - 4 * d, 14 * d, 14 * d, fill)
            val v = s.last(code)
            val norm = com.obdlogger.core.Norms.of(code, mode)
            val st = if (v.isNaN()) null else norm?.state(v)
            val name = paint(16f, p.t2)
            c.drawText(SensorNames.label(code), 20 * d, top + 30 * d, name)
            val big = paint(40f, p.t1, 500, mono = true)
            val vt = if (v.isNaN()) "—" else Num.fmt(code, v)
            c.drawText(vt, 20 * d, top + 30 * d + 44 * d, big)
            c.drawText(" " + SensorNames.unit(code), 20 * d + big.measureText(vt), top + 30 * d + 44 * d, paint(18f, p.t2))
            val out = st == com.obdlogger.core.NormState.LOW || st == com.obdlogger.core.NormState.HIGH
            val word = when {
                st == null || norm == null -> ""
                out -> norm.word(st)
                else -> "✓ в норме"
            }
            c.drawText(word, 20 * d, top + rowH - 18 * d, paint(16f, if (out) p.amb else p.acc, 600))
            // The last 5 minutes on the right, the norm as a band.
            val (t, vs) = s.series(code, end - 5 * 60_000)
            val x0 = 228 * d
            val x1 = width - 16 * d
            if (vs.size < 2 || x1 - x0 < 40 * d) return@forEachIndexed
            val vals = vs.filter { !it.isNaN() }
            if (vals.isEmpty()) return@forEachIndexed
            var lo = vals.min()
            var hi = vals.max()
            norm?.let { lo = minOf(lo, it.lo); hi = maxOf(hi, it.hi) }
            if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
            val pt = top + 16 * d
            val pb = top + rowH - 16 * d
            fun y(x: Double) = (pb - (pb - pt) * ((x - lo) / (hi - lo))).toFloat()
            norm?.let { fill.color = p.accZ; c.drawRect(x0, y(it.hi), x1, y(it.lo), fill) }
            val path = android.graphics.Path()
            var started = false
            for (i in t.indices) {
                if (vs[i].isNaN()) { started = false; continue }
                val px = x0 + (x1 - x0) * ((t[i] - (end - 5 * 60_000)).toFloat() / (5 * 60_000))
                if (!started) { path.moveTo(px, y(vs[i])); started = true } else path.lineTo(px, y(vs[i]))
            }
            line.color = p.t1
            c.drawPath(path, line)
        }
    }
}
