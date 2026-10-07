package com.obdlogger.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * «Бортач» design tokens (bt.css of the Claude Design mock-ups): colours of both
 * themes, IBM Plex fonts and the type scale for tablet, 1024×600 and phone.
 */
object Bt {
    class Palette(
        val bg: Int, val s1: Int, val s2: Int, val s3: Int, val line: Int, val line2: Int,
        val t1: Int, val t2: Int, val t3: Int,
        val acc: Int, val accInk: Int, val accT: Int, val accZ: Int,
        val amb: Int, val ambT: Int, val ambZ: Int,
        val red: Int, val redT: Int, val l0: Int, val chart: Int,
        val dark: Boolean,
    )

    private fun c(hex: String) = Color.parseColor(hex)
    private fun rgba(r: Int, g: Int, b: Int, a: Double) = Color.argb((a * 255).toInt(), r, g, b)

    /** Светлая тема «мануал»: обзор, версии, журнал, план, печать. */
    val LIGHT = Palette(
        bg = c("#ECE8DF"), s1 = c("#F6F3EC"), s2 = c("#E3DED3"), s3 = c("#D8D2C5"), line = c("#CFC8BA"), line2 = c("#B3AB9B"),
        t1 = c("#23211E"), t2 = c("#4C4840"), t3 = c("#645E54"),
        acc = c("#2F6B5E"), accInk = c("#F6F3EC"), accT = rgba(47, 107, 94, .13), accZ = rgba(47, 107, 94, .16),
        amb = c("#8F5A0E"), ambT = rgba(176, 116, 24, .16), ambZ = rgba(176, 116, 24, .22),
        red = c("#A3352B"), redT = rgba(163, 53, 43, .10), l0 = c("#A39C8F"), chart = c("#23211E"), dark = false,
    )

    /** Тёмная тема: запись в салоне, ожидание, ночь. */
    val DARK = Palette(
        bg = c("#161513"), s1 = c("#1E1D1A"), s2 = c("#272522"), s3 = c("#322F2B"), line = c("#36332E"), line2 = c("#4A463F"),
        t1 = c("#E6E1D6"), t2 = c("#B0AA9E"), t3 = c("#8E887D"),
        acc = c("#5E9C8B"), accInk = c("#10120F"), accT = rgba(94, 156, 139, .16), accZ = rgba(94, 156, 139, .18),
        amb = c("#C9973F"), ambT = rgba(201, 151, 63, .16), ambZ = rgba(201, 151, 63, .20),
        red = c("#D2705C"), redT = rgba(210, 112, 92, .14), l0 = c("#5C574F"), chart = c("#E6E1D6"), dark = true,
    )

    /** Type scale per screen class (sizes in sp, as in the mock-ups where 1 px = 1 dp). */
    class Scale(
        val lbl: Float, val hxl: Float, val hl: Float, val hm: Float, val hs: Float, val pl: Float, val p: Float,
        val cap: Float, val code: Float, val conf: Float, val evLabel: Float, val evValue: Float, val evPad: Int,
        val sbarH: Int, val sbarFont: Float, val brandFont: Float, val lamp: Int,
        val railW: Int, val riH: Int, val riFont: Float, val riIcon: Int,
        val btnH: Int, val btnFont: Float, val btnBigH: Int, val btnBigFont: Float,
        val endLbl: Float, val axis: Float, val botH: Int, val pad: Int, val gap: Int, val tileValue: Float,
        val phone: Boolean,
        /** Small landscape screens (1024×600, car head units): fewer secondary lines. */
        val compact: Boolean = false,
    )

    val TABLET = Scale(
        lbl = 13f, hxl = 44f, hl = 34f, hm = 24f, hs = 20f, pl = 21f, p = 17f, cap = 14f, code = 13f, conf = 16f,
        evLabel = 19f, evValue = 25f, evPad = 11, sbarH = 56, sbarFont = 16f, brandFont = 19f, lamp = 16,
        railW = 100, riH = 88, riFont = 14f, riIcon = 28, btnH = 56, btnFont = 17f, btnBigH = 68, btnBigFont = 20f,
        endLbl = 16f, axis = 13f, botH = 120, pad = 28, gap = 36, tileValue = 64f, phone = false,
    )
    val S1024 = Scale(
        lbl = 12f, hxl = 32f, hl = 27f, hm = 21f, hs = 17f, pl = 17f, p = 15f, cap = 12f, code = 12f, conf = 14f,
        evLabel = 17f, evValue = 21f, evPad = 8, sbarH = 46, sbarFont = 15f, brandFont = 17f, lamp = 14,
        railW = 84, riH = 72, riFont = 12f, riIcon = 26, btnH = 52, btnFont = 16f, btnBigH = 60, btnBigFont = 18f,
        endLbl = 14f, axis = 12f, botH = 96, pad = 22, gap = 24, tileValue = 44f, phone = false, compact = true,
    )

    /**
     * Car head units: wide but short (e.g. 1024×600 at hdpi ≈ 683×400 dp). Landscape
     * layout with the side rail like a tablet, but everything a size smaller.
     */
    val WIDE = Scale(
        lbl = 13f, hxl = 24f, hl = 20f, hm = 17f, hs = 15f, pl = 15f, p = 14f, cap = 13f, code = 12f, conf = 13f,
        evLabel = 15f, evValue = 18f, evPad = 6, sbarH = 48, sbarFont = 14f, brandFont = 15f, lamp = 12,
        railW = 88, riH = 64, riFont = 13f, riIcon = 26, btnH = 48, btnFont = 15f, btnBigH = 52, btnBigFont = 16f,
        endLbl = 13f, axis = 12f, botH = 56, pad = 16, gap = 16, tileValue = 36f, phone = false, compact = true,
    )
    val PHONE = Scale(
        lbl = 12f, hxl = 28f, hl = 24f, hm = 20f, hs = 17f, pl = 17f, p = 15f, cap = 13f, code = 12f, conf = 14f,
        evLabel = 15f, evValue = 18f, evPad = 10, sbarH = 48, sbarFont = 13f, brandFont = 15f, lamp = 14,
        railW = 0, riH = 64, riFont = 12f, riIcon = 24, btnH = 52, btnFont = 16f, btnBigH = 56, btnBigFont = 17f,
        endLbl = 14f, axis = 12f, botH = 0, pad = 16, gap = 16, tileValue = 44f, phone = true,
    )

    fun scaleFor(ctx: Context): Scale {
        val cfg = ctx.resources.configuration
        val sw = cfg.smallestScreenWidthDp
        return when {
            sw >= 720 -> TABLET
            sw >= 480 -> S1024
            // A short landscape screen (head unit) is not a phone: keep the side rail.
            cfg.screenWidthDp >= 560 && cfg.screenWidthDp > cfg.screenHeightDp -> WIDE
            else -> PHONE
        }
    }

    // ---- fonts ----
    private val cache = HashMap<String, Typeface>()

    private fun font(ctx: Context, file: String): Typeface = cache.getOrPut(file) {
        try {
            Typeface.createFromAsset(ctx.assets, "fonts/$file")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }

    /** IBM Plex Sans: 400, 500, 600, 700. */
    fun sans(ctx: Context, weight: Int = 400): Typeface = font(ctx, when {
        weight >= 700 -> "IBMPlexSans-Bold.ttf"
        weight >= 600 -> "IBMPlexSans-SemiBold.ttf"
        weight >= 500 -> "IBMPlexSans-Medium.ttf"
        else -> "IBMPlexSans-Regular.ttf"
    })

    /** IBM Plex Mono for numbers: tabular figures. */
    fun mono(ctx: Context, weight: Int = 500): Typeface = font(ctx, when {
        weight >= 600 -> "IBMPlexMono-SemiBold.ttf"
        weight >= 500 -> "IBMPlexMono-Medium.ttf"
        else -> "IBMPlexMono-Regular.ttf"
    })
}

// ---- small view helpers shared by the screens ----

fun Context.dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
fun View.dp(v: Number): Int = context.dp(v)

/** Text in the brand fonts. [mono] for numbers. */
fun Context.text(
    s: CharSequence, size: Float, color: Int, weight: Int = 400, mono: Boolean = false,
    lineHeight: Float? = null, maxLines: Int = Int.MAX_VALUE,
): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    typeface = if (mono) Bt.mono(context, weight) else Bt.sans(context, weight)
    includeFontPadding = false
    lineHeight?.let { setLineSpacing(0f, it / size) }
    if (maxLines != Int.MAX_VALUE) {
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }
}

/** Small caps label («ЕСТЬ ВЕРСИЯ»): 600, tracking .09em, t3. */
fun Context.label(s: String, sc: Bt.Scale, p: Bt.Palette, color: Int = p.t3): TextView =
    text(s.uppercase(), sc.lbl, color, 600).apply { letterSpacing = 0.09f }

fun roundRect(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0, dash: Float = 0f): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) {
            if (dash > 0) setStroke(stroke, strokeColor, dash, dash * 0.6f) else setStroke(stroke, strokeColor)
        }
    }

fun Context.icon(res: Int, color: Int, sizeDp: Int): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Drawable.tinted(color: Int): Drawable = mutate().apply { setTint(color) }

fun row(ctx: Context, gap: Int = 0, gravity: Int = Gravity.CENTER_VERTICAL, vararg children: View): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        children.forEachIndexed { i, v ->
            val lp = (v.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.leftMargin = gap
            addView(v, lp)
        }
    }

/** At least 48 dp tall to hit with a finger in a moving car; text stays vertically centred. */
fun <T : View> T.tap(): T = apply {
    minimumHeight = dp(48)
    if (this is TextView) {
        minHeight = dp(48)
        gravity = (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) or Gravity.CENTER_VERTICAL
    }
}

/** Flexible gap in a horizontal row; zero height so it never stretches the row. */
/** coerceIn that never throws: on a view too small for both margins (head units) it takes the middle. */
fun Float.clamp(lo: Float, hi: Float): Float = if (lo > hi) (lo + hi) / 2 else coerceIn(lo, hi)

fun spacer(ctx: Context): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }

fun column(ctx: Context, gap: Int = 0, vararg children: View): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        children.forEachIndexed { i, v -> addTo(this, v, if (i > 0) gap else 0) }
    }

fun addTo(parent: LinearLayout, v: View, top: Int = 0, weight: Float = 0f, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) {
    val vertical = parent.orientation == LinearLayout.VERTICAL
    // Views that set their own size (dividers, dots, progress bars) keep it.
    val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: if (vertical) {
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    } else {
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    if (weight > 0) {
        lp.weight = weight
        if (vertical) lp.height = 0 else lp.width = 0
    }
    if (vertical) lp.topMargin = top else lp.leftMargin = top
    parent.addView(v, lp)
}

/** Three-segment confidence: bars + word, no colour (confidence is not a fault status). */
class Confidence(ctx: Context, level: String, sc: Bt.Scale, p: Bt.Palette, suffix: String = "") : LinearLayout(ctx) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val n = when (level) {
            "высокая" -> 3
            "средняя" -> 2
            else -> 1
        }
        val bars = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        val bw = if (sc === Bt.TABLET) 8 else 6
        val bh = if (sc === Bt.TABLET) 22 else 16
        for (i in 0 until 3) {
            val v = View(ctx)
            v.background = roundRect(if (i < n) p.t1 else p.s3, ctx.dp(3).toFloat())
            bars.addView(v, LayoutParams(ctx.dp(bw), ctx.dp(bh)).apply { if (i > 0) leftMargin = ctx.dp(4) })
        }
        addView(bars)
        addView(ctx.text("уверенность $level$suffix", sc.conf, p.t2), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(12) })
    }
}
