package com.obdlogger.app.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.obdlogger.app.Prefs
import com.obdlogger.core.SensorNames

/**
 * One control bar for every chart page («Запись → Графики», a trip's charts, full
 * screen): [−] window [+], lanes per screen, overlay, back to «сейчас», full screen,
 * saved views. Everything also works by gestures on the lanes (see [LanesView]).
 */
class ChartBar(
    ctx: Context,
    private val sc: Bt.Scale,
    private val p: Bt.Palette,
    private val lanes: LanesView,
    /** Remembered separately per place: «record», «trip». */
    private val place: String,
    /** «⤢»: null in the full-screen view itself, which shows «⤡» to close instead. */
    private val onClose: (() -> Unit)? = null,
) : LinearLayout(ctx) {
    private val font = if (sc.phone) 14f else 15f
    private val window = ctx.text("", font, p.t1, 600, mono = true, maxLines = 1).apply { gravity = Gravity.CENTER; minWidth = dp(64) }.tap()
    private val per = ctx.text("", font, p.t1, 600, maxLines = 1).apply { gravity = Gravity.CENTER }
    private val overlayBtn = ctx.text("", font, p.acc, 600, maxLines = 1).apply { gravity = Gravity.CENTER }
    private val chips = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chipRow = HorizontalScrollView(ctx).apply { addView(chips); isHorizontalScrollBarEnabled = false }
    private val hint = ctx.text("Нажмите и держите дорожку — датчик ляжет на верхний график. Щипок — масштаб, свайп вбок — назад во времени, касание — значения в эту секунду.",
        sc.cap, p.t3, maxLines = 2)
    private var picking = false

    var perScreen: Int
        get() = Prefs.of(context).getInt("chart_lanes_$place", Prefs.of(context).getInt("chart_lanes", if (sc === Bt.TABLET) 4 else 3)).coerceIn(1, 4)
        set(v) {
            Prefs.of(context).edit().putInt("chart_lanes_$place", v).apply()
            lanes.perScreen = v
            update()
        }

    init {
        orientation = VERTICAL
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun btn(t: String, desc: String, onClick: () -> Unit) = ctx.text(t, if (sc.phone) 18f else 20f, p.t1, 600, maxLines = 1).apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            contentDescription = desc
            background = roundRect(p.s1, dp(10).toFloat(), dp(1), p.line2)
            setOnClickListener { onClick() }
        }.tap()
        addTo(row, btn("−", "Больше времени на экране") { zoom(+1) }, 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(row, window.apply { setOnClickListener { windowMenu(this) } }, dp(4), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(row, btn("+", "Меньше времени на экране") { zoom(-1) }, dp(4), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(row, per.apply {
            setPadding(dp(12), 0, dp(12), 0)
            background = roundRect(p.s1, dp(10).toFloat(), dp(1), p.line2)
            contentDescription = "Дорожек на экране"
            setOnClickListener { perScreen = perScreen % 4 + 1 }
        }.tap(), dp(10), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(row, overlayBtn.apply {
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { picking = !picking; update() }
        }.tap(), dp(6), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(row, btn("⟲", "К текущему моменту") { lanes.reset() }, dp(6), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(row, btn(if (onClose == null) "⤡" else "⤢", if (onClose == null) "Свернуть" else "На весь экран") { if (onClose == null) closeFull?.invoke() else full() },
            dp(6), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(row, ctx.text("Виды ▾", font, p.acc, 600, maxLines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { presets(this) }
        }.tap(), dp(4), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(HorizontalScrollView(ctx).apply { addView(row); isHorizontalScrollBarEnabled = false })
        addTo(this, chipRow, dp(4))
        addTo(this, hint, dp(2))
        lanes.perScreen = perScreen
        lanes.onChange = { update() }
        update()
    }

    /** Set by the full-screen dialog: «⤡» closes it. */
    var closeFull: (() -> Unit)? = null

    private fun zoom(dir: Int) {
        val now = lanes.windowMs
        lanes.windowMs = if (dir > 0) STEPS.firstOrNull { it > now + 500 } ?: now * 2 else STEPS.lastOrNull { it < now - 500 } ?: now / 2
        update()
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

    fun update() {
        window.text = label(lanes.windowMs)
        per.text = "▦ $perScreen"
        overlayBtn.text = if (picking) "⧉ готово" else "⧉ Наложить"
        overlayBtn.background = roundRect(if (picking) p.accT else p.s1, dp(10).toFloat(), dp(1), if (picking) p.acc else p.line2)
        hint.visibility = if (picking) View.VISIBLE else View.GONE
        chips.removeAllViews()
        val colors = listOf(p.chart, p.acc, p.amb, p.red)
        lanes.overlay.forEachIndexed { k, code ->
            addTo(chips, context.text("● ${SensorNames.label(code)}  ×", if (sc.phone) 13f else 14f, colors[k % colors.size], 600, maxLines = 1).apply {
                setPadding(dp(12), 0, dp(12), 0)
                background = roundRect(p.s1, dp(18).toFloat(), dp(1), p.line2)
                contentDescription = "Убрать ${SensorNames.label(code)} с наложения"
                setOnClickListener { lanes.removeOverlay(code) }
            }.tap(), 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
            (chips.getChildAt(chips.childCount - 1).layoutParams as LayoutParams).leftMargin = if (k > 0) dp(6) else 0
        }
        if (lanes.overlay.isNotEmpty()) addTo(chips, context.text("Разнести", if (sc.phone) 13f else 14f, p.acc, 600).apply {
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { lanes.clearOverlay() }
        }.tap(), 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        chipRow.visibility = if (lanes.overlay.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- saved views ----

    private class Preset(val name: String, val windowMs: Long, val per: Int, val codes: List<String>)

    private fun builtIn() = listOf(
        Preset("Смесь", 5 * 60_000L, 3, listOf("trim_b1", "maf_gs", "o2_b1s2_v", "rpm")),
        Preset("Зарядка", 5 * 60_000L, 3, listOf("battery_v", "rpm", "engine_load_pct")),
        Preset("Температуры", 15 * 60_000L, 3, listOf("coolant_c", "intake_air_c", "speed_kmh")),
        Preset("Холостой", 2 * 60_000L, 3, listOf("rpm", "maf_gs", "timing_deg", "trim_b1")),
    )

    private fun saved(): List<Preset> = Prefs.of(context).getString("chart_presets", null)?.lines()?.mapNotNull { l ->
        val f = l.split('|')
        if (f.size < 4) null else Preset(f[0], f[1].toLongOrNull() ?: return@mapNotNull null, f[2].toIntOrNull() ?: 3, f[3].split(',').filter { it.isNotBlank() })
    }.orEmpty()

    private fun store(list: List<Preset>) = Prefs.of(context).edit()
        .putString("chart_presets", list.joinToString("\n") { "${it.name.replace('|', ' ')}|${it.windowMs}|${it.per}|${it.codes.joinToString(",")}" }).apply()

    private fun apply(pr: Preset) {
        lanes.clearOverlay()
        pr.codes.filter { lanes.has(it) }.forEach { lanes.addOverlay(it) }
        lanes.windowMs = pr.windowMs
        perScreen = pr.per
        lanes.reset()
    }

    private fun presets(anchor: View) {
        val all = builtIn() + saved()
        val m = android.widget.PopupMenu(context, anchor)
        all.forEachIndexed { i, pr -> m.menu.add(0, i, i, pr.name + " · " + pr.codes.filter { lanes.has(it) }.joinToString(", ") { SensorNames.label(it) }) }
        m.menu.add(0, 1000, 1000, "Сохранить этот вид…")
        if (saved().isNotEmpty()) m.menu.add(0, 1001, 1001, "Удалить сохранённые виды")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1000 -> saveDialog()
                1001 -> store(emptyList())
                else -> all.getOrNull(item.itemId)?.let { apply(it) }
            }
            true
        }
        m.show()
    }

    private fun saveDialog() {
        val field = android.widget.EditText(context).apply { hint = "Название, например «Подсос»"; setSingleLine() }
        val box = android.widget.FrameLayout(context).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        android.app.AlertDialog.Builder(context)
            .setTitle("Сохранить вид графиков")
            .setMessage(if (lanes.overlay.isEmpty()) "Сохранятся масштаб и число дорожек. Чтобы сохранить и набор датчиков — сначала наложите их (⧉)." else
                "Наложение: " + lanes.overlay.joinToString(", ") { SensorNames.label(it) } + " · " + label(lanes.windowMs))
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = field.text.toString().trim().ifEmpty { "Вид ${saved().size + 1}" }
                store((saved().filter { it.name != name } + Preset(name, lanes.windowMs, perScreen, lanes.overlay.toList())).takeLast(8))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---- full screen ----

    private fun full() {
        val d = Dialog(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val big = LanesView(context, p)
        lanes.copyTo(big)
        lanes.mirror = big
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val bar = ChartBar(context, sc, p, big, place, onClose = null)
        bar.closeFull = { d.dismiss() }
        col.addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(big, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        d.setContentView(col)
        d.setOnDismissListener {
            lanes.mirror = null
            big.copyTo(lanes)
            lanes.onChange = { update() }
            update()
        }
        d.show()
    }

    companion object {
        val STEPS = listOf(30_000L, 60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 2 * 3600_000L)

        fun label(ms: Long): String = when {
            ms < 60_000 -> "${ms / 1000} с"
            ms < 3600_000 -> "${ms / 60_000} мин"
            ms % 3600_000 == 0L -> "${ms / 3600_000} ч"
            else -> "%.1f ч".format(ms / 3600_000.0)
        }
    }
}
