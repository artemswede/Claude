package com.obdlogger.app

import android.content.Context
import android.content.SharedPreferences

/** Saved choices shared by the screen, the service and the boot receiver. */
object Prefs {
    const val DEVICE = "device"
    const val VEHICLE = "vehicle"
    const val EXTENDED = "extended"
    const val AUTO = "auto"

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun device(ctx: Context): String? = of(ctx).getString(DEVICE, null)
    fun vehicle(ctx: Context): String = of(ctx).getString(VEHICLE, null) ?: "Toyota Avensis 2005"
    fun extended(ctx: Context): Boolean = of(ctx).getBoolean(EXTENDED, true)
    fun auto(ctx: Context): Boolean = of(ctx).getBoolean(AUTO, false)
}
