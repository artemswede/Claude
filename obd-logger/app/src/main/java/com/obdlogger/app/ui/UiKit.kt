package com.obdlogger.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tab strip under the service line («Панель · Внимание · Графики»): text tabs with
 * an accent underline on the selected one, a bottom rule and an optional view on the right.
 */
class Tabs(ctx: Context, private val sc: Bt.Scale, private val p: Bt.Palette, titles: List<String>, private val onSelect: (Int) -> Unit) :
    LinearLayout(ctx) {
    private val items = ArrayList<TextView>()
    private val right = FrameLayout(ctx)
    var selected = 0
        private set
    private val rule = Paint().apply { color = p.line }
    private val under = Paint().apply { color = p.acc }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        setPadding(dp(if (sc.phone) 8 else 24), 0, dp(if (sc.phone) 8 else 16), 0)
        titles.forEachIndexed { i, t ->
            val tv = ctx.text(t, if (sc.phone) 15f else if (sc === Bt.TABLET) 19f else 16f, p.t2, 500).apply {
                gravity = Gravity.CENTER
                setPadding(dp(if (sc.phone) 12 else 22), 0, dp(if (sc.phone) 12 else 22), 0)
                setOnClickListener { select(i) }
            }
            items += tv
            addView(tv, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        addView(right, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        style()
    }

    fun setRight(v: View?) {
        right.removeAllViews()
        v?.let { right.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)) }
    }

    fun select(i: Int, notify: Boolean = true) {
        selected = i
        style()
        invalidate()
        if (notify) onSelect(i)
    }

    private fun style() {
        items.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == selected) p.t1 else p.t2)
            tv.typeface = Bt.sans(context, if (i == selected) 600 else 400)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val d = resources.displayMetrics.density
        canvas.drawRect(0f, height - d, width.toFloat(), height.toFloat(), rule)
        items.getOrNull(selected)?.let { canvas.drawRect(it.left.toFloat(), height - 3 * d, it.right.toFloat(), height.toFloat(), under) }
    }

    companion object {
        fun height(sc: Bt.Scale) = if (sc.phone) 52 else if (sc === Bt.TABLET) 62 else 50
    }
}

/** Pill segment control («1 мин · 5 мин · 15 мин»). */
class Segment(ctx: Context, private val sc: Bt.Scale, private val p: Bt.Palette, private val titles: List<String>, initial: Int, private val onSelect: (Int) -> Unit) :
    LinearLayout(ctx) {
    private val items = ArrayList<TextView>()
    var selected = initial
        private set

    init {
        orientation = HORIZONTAL
        background = roundRect(if (p.dark) p.s1 else p.s1, dp(12).toFloat())
        setPadding(dp(3), dp(3), dp(3), dp(3))
        titles.forEachIndexed { i, t ->
            val tv = ctx.text(t, if (sc.phone) 14f else 16f, p.t2, 500).apply {
                gravity = Gravity.CENTER
                setPadding(dp(if (sc.phone) 12 else 18), 0, dp(if (sc.phone) 12 else 18), 0)
                setOnClickListener { select(i) }
            }
            items += tv
            addView(tv, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (sc.phone) 44 else 48)))
        }
        style()
    }

    fun setTitles(t: List<String>) = t.forEachIndexed { i, s -> items.getOrNull(i)?.text = s }

    fun select(i: Int) {
        selected = i
        style()
        onSelect(i)
    }

    private fun style() = items.forEachIndexed { i, tv ->
        val on = i == selected
        tv.setTextColor(if (on) (if (p.dark) p.bg else p.accInk) else p.t2)
        tv.typeface = Bt.sans(context, if (on) 600 else 400)
        tv.background = if (on) roundRect(p.t1, dp(9).toFloat()) else null
    }
}

/** Status chip: «за нормой», «норма», «мало данных». */
fun Context.chip(text: String, fg: Int, bg: Int, sizeSp: Float = 15f): TextView = text(text, sizeSp.coerceAtLeast(13f), fg, 600).apply {
    setPadding(dp(12), dp(5), dp(12), dp(5))
    background = roundRect(bg, dp(999).toFloat())
}

/** Filled accent button or outlined secondary one. */
fun Context.button(title: String, sc: Bt.Scale, p: Bt.Palette, primary: Boolean = true, onClick: () -> Unit): TextView =
    text(title, sc.btnFont, if (primary) p.accInk else p.t1, 600).apply {
        gravity = Gravity.CENTER
        setPadding(dp(22), 0, dp(22), 0)
        background = if (primary) roundRect(p.acc, dp(12).toFloat()) else roundRect(p.s1, dp(12).toFloat(), dp(1), p.line2)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(sc.btnH))
        setOnClickListener { onClick() }
    }

/** Card surface: s1 with a radius, optional dashed or coloured border. */
fun card(v: View, p: Bt.Palette, padH: Int, padV: Int, border: Int = 0, dashed: Boolean = false): View {
    val d = v.resources.displayMetrics.density
    v.setPadding(padH, padV, padH, padV)
    v.background = if (border != 0) roundRect(p.s1, 14 * d, (1.5f * d).toInt().coerceAtLeast(1), border, if (dashed) 5 * d else 0f)
    else roundRect(p.s1, 14 * d)
    return v
}

fun hline(ctx: Context, color: Int): View = View(ctx).apply {
    setBackgroundColor(color)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1))
}
