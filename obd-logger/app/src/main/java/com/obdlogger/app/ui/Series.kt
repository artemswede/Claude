package com.obdlogger.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Series of a chart: six colours that stay apart for colour-blind eyes and in the sun, and a
 * second sign — the marker shape (circle, square, triangle, diamond, triangle down, hexagon).
 * The 7th sensor and on: no colour, text colour, «только дорожка».
 */
object Series {
    const val MAX = 6

    fun color(p: Bt.Palette, k: Int): Int = p.series.getOrNull(k) ?: p.t1

    /** The marker of series [k] centred at ([x], [y]) with radius [r]. */
    fun shape(c: Canvas, k: Int, x: Float, y: Float, r: Float, paint: Paint) {
        val path = Path()
        when (k % MAX) {
            0 -> { c.drawCircle(x, y, r, paint); return }
            1 -> { c.drawRect(x - r * 0.85f, y - r * 0.85f, x + r * 0.85f, y + r * 0.85f, paint); return }
            2 -> { path.moveTo(x, y - r); path.lineTo(x + r, y + r * 0.8f); path.lineTo(x - r, y + r * 0.8f) }
            3 -> { path.moveTo(x, y - r); path.lineTo(x + r, y); path.lineTo(x, y + r); path.lineTo(x - r, y) }
            4 -> { path.moveTo(x - r, y - r * 0.8f); path.lineTo(x + r, y - r * 0.8f); path.lineTo(x, y + r) }
            else -> for (i in 0 until 6) {
                val a = Math.PI / 3 * i - Math.PI / 2
                val px = x + r * Math.cos(a).toFloat()
                val py = y + r * Math.sin(a).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
        }
        path.close()
        c.drawPath(path, paint)
    }

    /** The same marker as text, for chips and answers. */
    fun glyph(k: Int): String = listOf("●", "■", "▲", "◆", "▼", "⬢")[k % MAX]
}
