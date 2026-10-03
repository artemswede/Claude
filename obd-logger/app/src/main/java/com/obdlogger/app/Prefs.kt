package com.obdlogger.app

import android.content.Context
import android.content.SharedPreferences

/** Saved choices shared by the screen, the service and the boot receiver. */
object Prefs {
    const val DEVICE = "device"
    const val VEHICLE = "vehicle"
    const val EXTENDED = "extended"
    const val AUTO = "auto"
    const val BOOT = "boot"
    const val MARKS = "marks"
    const val TRACE = "trace"
    const val KEEP_DAYS = "keep_days"

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun device(ctx: Context): String? = of(ctx).getString(DEVICE, null)
    fun vehicle(ctx: Context): String = of(ctx).getString(VEHICLE, null) ?: "Toyota Avensis 2005"
    fun extended(ctx: Context): Boolean = of(ctx).getBoolean(EXTENDED, true)
    fun auto(ctx: Context): Boolean = of(ctx).getBoolean(AUTO, false)
    /** Start waiting for the engine when the tablet is switched on. */
    fun boot(ctx: Context): Boolean = of(ctx).getBoolean(BOOT, true)
    /** Driver marks are optional: the analysis works without them. */
    fun marks(ctx: Context): Boolean = of(ctx).getBoolean(MARKS, false)
    /** Write the adapter log (_elm.log) for troubleshooting. */
    fun trace(ctx: Context): Boolean = of(ctx).getBoolean(TRACE, true)
    /** Keep recordings this many days; 0 = forever. */
    fun keepDays(ctx: Context): Int = of(ctx).getInt(KEEP_DAYS, 90)
}
