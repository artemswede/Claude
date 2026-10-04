package com.obdlogger.app

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Automated UX checks on a rendered screen, for a tablet used in a car:
 * - touch targets smaller than 44 dp (fingers, bumps);
 * - text that is cut off (ellipsized, wider or taller than its view);
 * - type smaller than 12 sp (read at arm's length);
 * - text contrast below 4.5 : 1 (3 : 1 for large text) against its background;
 * - visible views that stick out of the screen.
 * Content inside scroll views is only checked for clipping, not for being off screen.
 */
object UxAudit {
    fun check(root: View): List<String> {
        val out = ArrayList<String>()
        val d = root.resources.displayMetrics.density
        val sd = root.resources.displayMetrics.scaledDensityCompat()
        val screen = Rect(0, 0, root.width, root.height)
        fun name(v: View): String = when (v) {
            is TextView -> "«${v.text.toString().replace('\n', ' ').take(40)}»"
            else -> v.contentDescription?.let { "«$it»" } ?: v.javaClass.simpleName
        }
        fun walk(v: View, inScroll: Boolean) {
            if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            val r = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            if (!inScroll && (r.right > screen.right + 1 || r.left < -1) && v !is ViewGroup) out += "за краем экрана: ${name(v)} (x ${r.left}…${r.right})"
            if (v.isClickable && v.hasOnClickListeners() && (v.width < 44 * d || v.height < 44 * d)) {
                out += "мелкая цель нажатия ${(v.width / d).toInt()}×${(v.height / d).toInt()} dp: ${name(v)}"
            }
            if (v is TextView && v.text.isNotBlank()) {
                val sp = v.textSize / sd
                if (sp < 12f) out += "мелкий шрифт ${"%.1f".format(sp)} sp: ${name(v)}"
                val l = v.layout
                if (l != null) {
                    val ell = (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
                    val tooTall = l.height > v.height - v.paddingTop - v.paddingBottom + 2
                    val tooWide = (0 until l.lineCount).any { l.getLineWidth(it) > v.width - v.paddingLeft - v.paddingRight + 2 }
                    if (ell) out += "текст обрезан многоточием: ${name(v)}"
                    else if (tooTall || tooWide) out += "текст не помещается: ${name(v)}"
                }
                val bg = background(v)
                if (bg != null) {
                    val ratio = contrast(v.currentTextColor, bg)
                    val large = sp >= 18f || (sp >= 14f && v.typeface?.isBold == true)
                    if (ratio < if (large) 3.0 else 4.5) out += "низкий контраст ${"%.1f".format(ratio)}:1: ${name(v)}"
                }
            }
            if (v is ViewGroup) {
                val scroll = inScroll || v is ScrollView || v is HorizontalScrollView
                for (i in 0 until v.childCount) walk(v.getChildAt(i), scroll)
            }
        }
        walk(root, false)
        return out.distinct()
    }

    /** Nearest solid background up the tree. */
    private fun background(v: View): Int? {
        var p: View? = v
        while (p != null) {
            when (val b = p.background) {
                is ColorDrawable -> if (Color.alpha(b.color) > 200) return b.color
                is GradientDrawable -> b.color?.defaultColor?.let { if (Color.alpha(it) > 200) return it }
            }
            p = p.parent as? View
        }
        return null
    }

    private fun lum(c: Int): Double {
        fun ch(x: Int): Double {
            val s = x / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    fun contrast(a: Int, b: Int): Double {
        val la = lum(a)
        val lb = lum(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Suppress("DEPRECATION")
    private fun android.util.DisplayMetrics.scaledDensityCompat() = scaledDensity
}
