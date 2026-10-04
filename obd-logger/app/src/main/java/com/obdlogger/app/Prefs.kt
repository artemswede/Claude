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
    const val TILES = "tiles"
    const val SETUP_DONE = "setup_done"
    const val KILLED_AT = "killed_at"
    const val RECORDING_SINCE = "recording_since"

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
    /** Sensors on the record panel tiles, in order; empty = default set. */
    fun tiles(ctx: Context): List<String> = of(ctx).getString(TILES, null)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
    fun setTiles(ctx: Context, codes: List<String>) = of(ctx).edit().putString(TILES, codes.joinToString(",")).apply()
    /** The first-run wizard was finished (or skipped). */
    fun setupDone(ctx: Context): Boolean = of(ctx).getBoolean(SETUP_DONE, false)
    /** When the system last killed the service mid-recording; 0 = never / acknowledged. */
    fun killedAt(ctx: Context): Long = of(ctx).getLong(KILLED_AT, 0)

    /**
     * A trip was being written when the process died (the service clears this when it
     * saves a trip): remember when, so the main screen can say the system stopped it.
     */
    fun checkKilled(ctx: Context) {
        val since = of(ctx).getLong(RECORDING_SINCE, 0)
        if (since > 0) of(ctx).edit().putLong(KILLED_AT, System.currentTimeMillis()).remove(RECORDING_SINCE).apply()
    }
}
