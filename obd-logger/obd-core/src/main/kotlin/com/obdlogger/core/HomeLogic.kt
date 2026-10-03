package com.obdlogger.core

/**
 * What the main screen may claim, decided from data only — no Android here, so
 * every state is covered by tests.
 *
 * A conclusion about the car is shown only for the trip being recorded, and only
 * after enough diagnosis time. When nothing is recorded, the screen says so and
 * shows the last trip's conclusion as a past one.
 */
enum class HomeState {
    /** No trips recorded yet (fresh install). */
    NO_TRIPS,
    /** Recording is off and auto mode is off. */
    OFF,
    /** Auto mode is waiting for the engine. */
    WAIT,
    /** Auto mode cannot reach the adapter. */
    NO_CONNECTION,
    /** Recording, but not enough data for a conclusion yet. */
    COLLECTING,
    /** Recording: the ECU has stored codes. */
    DTC,
    /** Recording: there is a version of a fault. */
    VERSION,
    /** Recording, enough data, nothing found. */
    CALM,
}

object HomeLogic {
    /** Minimum trip time before any version is shown. */
    const val NEED_TRIP_MIN = 5.0

    /** Warm idle needed before «всё спокойно» can be claimed (idle is where most faults show). */
    const val NEED_IDLE_SEC = 120.0

    fun decide(
        current: TripSummary?,
        lastSaved: TripSummary?,
        recording: Boolean,
        auto: Boolean,
        linkFailed: Boolean,
    ): HomeState {
        if (!recording) {
            return when {
                auto && linkFailed -> HomeState.NO_CONNECTION
                auto -> HomeState.WAIT
                lastSaved == null -> HomeState.NO_TRIPS
                else -> HomeState.OFF
            }
        }
        val t = current ?: return HomeState.COLLECTING
        return when {
            !t.dtcs.isNullOrEmpty() -> HomeState.DTC
            t.durationMin < NEED_TRIP_MIN -> HomeState.COLLECTING
            t.top != null -> HomeState.VERSION
            t.warmIdleSec < NEED_IDLE_SEC -> HomeState.COLLECTING
            else -> HomeState.CALM
        }
    }

    /** Share of the needed diagnosis already collected, 0…1 (for the progress bar). */
    fun progress(t: TripSummary?): Double {
        if (t == null) return 0.0
        val time = (t.durationMin / NEED_TRIP_MIN).coerceAtMost(1.0)
        val idle = (t.warmIdleSec / NEED_IDLE_SEC).coerceAtMost(1.0)
        return minOf(time, idle)
    }

    /** A past trip is worth quoting only if it had enough data itself. */
    fun conclusive(t: TripSummary?): Boolean =
        t != null && t.durationMin >= NEED_TRIP_MIN && (t.top != null || t.warmIdleSec >= NEED_IDLE_SEC || !t.dtcs.isNullOrEmpty())
}
