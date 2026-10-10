package com.obdlogger.app

import android.content.Context
import com.obdlogger.app.ui.TripsModel
import com.obdlogger.core.Baseline
import com.obdlogger.core.CarNorm
import com.obdlogger.core.Drift
import com.obdlogger.core.Norms
import com.obdlogger.core.TripProfile
import java.time.LocalDateTime

/**
 * «Как обычно у вашей машины», kept for the whole app: this car's trip fingerprints,
 * its normal and the changes against it. Rebuilt off the main thread when trips are
 * added or the profile is reset; the live tiles take their bands from it ([Norms.personal]).
 */
object CarProfile {
    class State(
        val carKey: String?,
        val profiles: List<TripProfile>,
        /** Reset moment and what was done then («замена термостата»), null if never reset. */
        val since: LocalDateTime?,
        val sinceNote: String?,
    ) {
        val ready get() = Baseline.ready(profiles)
        val learning get() = Baseline.learning(profiles)
        val drifts: List<Drift> by lazy { Baseline.drifts(profiles) }
        val norms: List<CarNorm> by lazy { Baseline.norms(profiles) }
        /** What identifies this state: rebuilt only when it changes. */
        val key get() = listOf(carKey, since, profiles.size, profiles.lastOrNull()?.name)
    }

    @Volatile
    var state: State? = null
        private set

    /** Heavy the first time (each trip's CSV is read once, then `_profile.txt`). */
    @Synchronized
    fun refresh(ctx: Context, carKey: String? = Prefs.currentCar(ctx)): State {
        val m = TripsModel.build(ctx, carKey)
        return of(ctx, m)
    }

    @Synchronized
    fun of(ctx: Context, m: TripsModel): State {
        val key = m.car?.key
        val since = key?.let { Prefs.profileSince(ctx, it) }
        val s = State(key, m.profiles(since), since, key?.let { Prefs.profileNote(ctx, it) })
        state = s
        Norms.personal = Baseline.liveNorms(s.profiles)
        return s
    }

    /** Puts a ready state in place (tests, screenshots). */
    fun use(st: State?) {
        state = st
        Norms.personal = st?.let { Baseline.liveNorms(it.profiles) }.orEmpty()
    }

    /** «Обнулить профиль» after a repair: from now on the car learns its new normal. */
    fun reset(ctx: Context, carKey: String, note: String?) {
        Prefs.setProfileSince(ctx, carKey, LocalDateTime.now(), note)
        state = null
        Norms.personal = emptyMap()
    }
}
