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
    const val RECORDING_FILE = "recording_file"
    /** Boot of the device the trip started in, and its deep-sleep total at the last sync (see [Recovery]). */
    const val RECORDING_BOOT = "recording_boot"
    const val RECORDING_SLEEP = "recording_sleep"
    /** Set by the shutdown broadcast: the device is going off together with the car. */
    const val RECORDING_SHUTDOWN = "recording_shutdown"
    const val SEEN_TRIP = "seen_trip"
    const val UI_SCALE = "ui_scale"

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun device(ctx: Context): String? = of(ctx).getString(DEVICE, null)
    const val CURRENT_CAR = "current_car"

    /**
     * Name of the car connected last («Toyota Avensis 2005 · 2.0 D-4»), set by the
     * owner per car: the tablet moves between cars and each keeps its own name.
     * Empty when not named yet.
     */
    fun vehicle(ctx: Context): String = currentCar(ctx)?.let { carName(ctx, it) } ?: of(ctx).getString(VEHICLE, null).orEmpty()
    fun setVehicle(ctx: Context, name: String) {
        val car = currentCar(ctx)
        if (car != null) setCarName(ctx, car, name) else of(ctx).edit().putString(VEHICLE, name).apply()
    }
    /** Key of the car the adapter last talked to (see CarId). */
    fun currentCar(ctx: Context): String? = of(ctx).getString(CURRENT_CAR, null)
    fun setCurrentCar(ctx: Context, key: String) = of(ctx).edit().putString(CURRENT_CAR, key).apply()
    fun carName(ctx: Context, key: String): String? = of(ctx).getString("car_name_$key", null)
    fun setCarName(ctx: Context, key: String, name: String) = of(ctx).edit().putString("car_name_$key", name).apply()
    fun extended(ctx: Context): Boolean = of(ctx).getBoolean(EXTENDED, true)
    /** On by default: Бортач is meant to record every trip by itself once an adapter is chosen. */
    fun auto(ctx: Context): Boolean = of(ctx).getBoolean(AUTO, true)
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
    /** The last trip whose result the owner has opened (the main screen stops offering it). */
    fun seenTrip(ctx: Context): String? = of(ctx).getString(SEEN_TRIP, null)
    fun setSeenTrip(ctx: Context, name: String) = of(ctx).edit().putString(SEEN_TRIP, name).apply()
}
