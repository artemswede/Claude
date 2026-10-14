package com.obdlogger.app

import android.content.Context
import android.content.res.Configuration

/**
 * Size of the whole interface. Head units often report a high density for a small
 * 1024×600 panel (≈ 512×300 dp): Android then picks the phone layout and everything
 * is huge. On a short landscape screen the density is lowered so that the app gets
 * at least [MIN_HEIGHT_DP] dp of height; the owner can make it smaller or larger in
 * Settings («Размер интерфейса»).
 */
object UiScale {
    const val MIN_HEIGHT_DP = 400

    /** Factors offered in Settings; 1 = automatic. Bigger factor — bigger interface. */
    val LEVELS = listOf(0.9f to "Мельче", 1f to "Обычный", 1.12f to "Крупнее", 1.25f to "Ещё крупнее")

    fun factor(ctx: Context): Float {
        val p = Prefs.of(ctx)
        if (p.contains(Prefs.UI_SIZE)) return p.getFloat(Prefs.UI_SIZE, 1f)
        // Before: the factor divided the density, so «Крупнее» made everything smaller.
        // The saved choice is carried over so that the screen looks the same as before.
        val old = p.getFloat(Prefs.UI_SCALE, 1f)
        val f = LEVELS.minByOrNull { kotlin.math.abs(it.first - 1f / old) }!!.first
        p.edit().putFloat(Prefs.UI_SIZE, f).apply()
        return f
    }

    /** A context whose resources use the adjusted density; the original one if nothing changes. */
    fun wrap(base: Context): Context {
        val res = base.resources
        val cfg = res.configuration
        val density = res.displayMetrics.density
        val wPx = cfg.screenWidthDp * density
        val hPx = cfg.screenHeightDp * density
        var d = density
        if (wPx > hPx && hPx / d < MIN_HEIGHT_DP) d = hPx / MIN_HEIGHT_DP
        d *= factor(base)
        // Head units often ship with a large system font; sizes here are already set for the car,
        // and an extra 1.3× makes values run into labels. The owner's size is «Размер интерфейса».
        val font = minOf(cfg.fontScale, 1f)
        if (kotlin.math.abs(d - density) < 0.01f && font == cfg.fontScale) return base
        val c = Configuration(cfg)
        c.fontScale = font
        c.densityDpi = (d * 160).toInt()
        c.screenWidthDp = (wPx / d).toInt()
        c.screenHeightDp = (hPx / d).toInt()
        c.smallestScreenWidthDp = minOf(c.screenWidthDp, c.screenHeightDp)
        return base.createConfigurationContext(c)
    }

    /** «683×400 dp, плотность 1.5» — for Settings and crash reports. */
    fun describe(ctx: Context): String {
        val cfg = ctx.resources.configuration
        return "${cfg.screenWidthDp}×${cfg.screenHeightDp} dp, плотность ${"%.2f".format(ctx.resources.displayMetrics.density)}"
    }
}
