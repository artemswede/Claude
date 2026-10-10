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
    /** The owner's DeepSeek key and model for the AI chat. */
    const val AI_KEY = "ai_key"
    const val AI_MODEL = "ai_model"
    /** Model id picked from the server's list for this key. */
    const val AI_MODEL_ID = "ai_model_id"
    /** OpenRouter model (one of [AiChat.OR_MODELS]). */
    const val AI_OR_MODEL = "ai_or_model"
    /** How many newest trips go to the AI as raw rows. */
    const val AI_RAW = "ai_raw"
    /** «Свой сервер» (bortach-proxy on Vercel): its address and token; when set, questions go there. */
    const val AI_PROXY_URL = "ai_proxy_url"
    const val AI_PROXY_TOKEN = "ai_proxy_token"
    const val PANEL_SORT = "panel_sort"
    const val PANEL_HIDDEN = "panel_hidden"

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun device(ctx: Context): String? = of(ctx).getString(DEVICE, null)
    const val CURRENT_CAR = "current_car"

    /**
     * Name of the car connected last («марка модель год · двигатель»), set by the
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
    /** Panel order: [com.obdlogger.core.PanelSort] name. */
    fun panelSort(ctx: Context): com.obdlogger.core.PanelSort =
        of(ctx).getString(PANEL_SORT, null)?.let { n -> com.obdlogger.core.PanelSort.entries.firstOrNull { it.name == n } } ?: com.obdlogger.core.PanelSort.PROBLEM
    fun setPanelSort(ctx: Context, s: com.obdlogger.core.PanelSort) = of(ctx).edit().putString(PANEL_SORT, s.name).apply()
    /** Sensors switched off on the panel; null = never chosen (raw bytes off). */
    fun panelHidden(ctx: Context): Set<String>? = of(ctx).getString(PANEL_HIDDEN, null)?.split(",")?.filter { it.isNotBlank() }?.toSet()
    fun setPanelHidden(ctx: Context, codes: Set<String>) = of(ctx).edit().putString(PANEL_HIDDEN, codes.joinToString(",")).apply()
    /** The first-run wizard was finished (or skipped). */
    fun setupDone(ctx: Context): Boolean = of(ctx).getBoolean(SETUP_DONE, false)
    /** When the system last killed the service mid-recording; 0 = never / acknowledged. */
    fun killedAt(ctx: Context): Long = of(ctx).getLong(KILLED_AT, 0)
    /** The last trip whose result the owner has opened (the main screen stops offering it). */
    /** «Обнулить профиль»: trips before this moment no longer count as the car's normal. */
    fun profileSince(ctx: Context, carKey: String): java.time.LocalDateTime? =
        of(ctx).getString("profile_since_$carKey", null)?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
    fun setProfileSince(ctx: Context, carKey: String, at: java.time.LocalDateTime?, note: String? = null) =
        of(ctx).edit().apply {
            if (at == null) remove("profile_since_$carKey") else putString("profile_since_$carKey", at.toString())
            if (note.isNullOrBlank()) remove("profile_note_$carKey") else putString("profile_note_$carKey", note.trim())
        }.apply()
    fun profileNote(ctx: Context, carKey: String): String? = of(ctx).getString("profile_note_$carKey", null)

    fun seenTrip(ctx: Context): String? = of(ctx).getString(SEEN_TRIP, null)
    fun setSeenTrip(ctx: Context, name: String) = of(ctx).edit().putString(SEEN_TRIP, name).apply()
}
