package com.obdlogger.app.ui

import android.content.Context
import com.obdlogger.app.Lamp
import com.obdlogger.app.LoggerState
import com.obdlogger.app.SessionFiles
import com.obdlogger.core.Metric
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.TripSummary
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The six states of the main screen (Д1–Д6 in the mock-ups). */
enum class HomeState { VERSION, CALM, DTC, NODATA, WAIT, NOCONN }

class Trend(val title: String, val values: List<Double>, val valuesText: String, val calm: Boolean)

/**
 * What the main screen shows. The trip being recorded is analysed live; when the
 * car is off, the last saved trip is shown (dimmed, with its date).
 */
class HomeModel(
    val state: HomeState,
    /** Trip the conclusion is about: current one while recording, otherwise the last saved. */
    val trip: TripSummary?,
    val live: Boolean,
    val trend: Trend?,
    val lastTripText: String,
    /** Warm idle collected so far, for the «мало данных» state. */
    val warmIdleSec: Double,
) {
    companion object {
        /** Warm idle needed before the idle-based versions can be trusted. */
        const val NEED_IDLE_SEC = 120.0

        private val DATE = DateTimeFormatter.ofPattern("dd.MM")

        /** Heavy: reads CSV files. Call off the main thread. */
        fun build(ctx: Context, s: LoggerState.Snapshot): HomeModel {
            val saved = SessionFiles.tripCsvs(ctx).takeLast(8).mapNotNull { SessionFiles.analyze(it) }.filter { it.rows >= 10 }
            val current = s.currentCsv?.let(::File)?.takeIf { s.recording && it.exists() }
                ?.let { f -> runCatching { TripAnalyzer.analyze(f.nameWithoutExtension, f.readText()) }.getOrNull() }
            val live = current != null
            val trip = current ?: saved.lastOrNull()

            val state = when {
                s.auto && !s.recording && s.link == Lamp.FAIL -> HomeState.NOCONN
                s.auto && !s.recording -> HomeState.WAIT
                trip == null -> HomeState.NODATA
                !trip.dtcs.isNullOrEmpty() -> HomeState.DTC
                trip.top != null -> HomeState.VERSION
                trip.warmIdleSec < NEED_IDLE_SEC -> HomeState.NODATA
                else -> HomeState.CALM
            }

            val history = (saved + listOfNotNull(current)).distinctBy { it.name }
            return HomeModel(state, trip, live, trend(history), lastTrip(trip, live), trip?.warmIdleSec ?: 0.0)
        }

        private fun trend(trips: List<TripSummary>): Trend? {
            val withIdle = trips.filter { it.metrics.containsKey(Metric.IDLE_TRIM_B1) }.takeLast(3)
            if (withIdle.size < 2) return null
            val v = withIdle.map { it.metrics.getValue(Metric.IDLE_TRIM_B1) }
            val d = v.last() - v.first()
            val text = v.joinToString(" → ") { TripAnalyzer.pct(it).removeSuffix(" %") } + " %"
            val inNorm = v.all { kotlin.math.abs(it) <= 10 }
            val title = when {
                inNorm && kotlin.math.abs(d) < 5 -> "За ${withIdle.size} ${trips(withIdle.size)}: без заметного ухудшения"
                d > 3 -> "За ${withIdle.size} ${trips(withIdle.size)}: коррекция на ХХ растёт"
                d < -3 -> "За ${withIdle.size} ${trips(withIdle.size)}: коррекция на ХХ снижается"
                else -> "За ${withIdle.size} ${trips(withIdle.size)}: коррекция на ХХ без изменений"
            }
            return Trend(title, v, text, inNorm)
        }

        private fun trips(n: Int) = if (n in 2..4) "поездки" else "поездок"

        private fun lastTrip(t: TripSummary?, live: Boolean): String {
            if (t == null) return "поездок ещё нет"
            val warm = t.metrics[Metric.COOLANT_MAX]?.let { "мотор прогрет до ${it.toInt()} °C" } ?: "прогрев"
            val dur = "${t.durationMin.toInt()} мин"
            val date = t.start?.takeIf { !live && it.toLocalDate() != LocalDateTime.now().toLocalDate() }?.format(DATE)
            return listOfNotNull(if (live) "идёт" else null, date, dur, warm).joinToString(" · ").replaceFirst("идёт · ", "идёт: ")
        }

        fun tripRange(t: TripSummary): String {
            val start = t.start ?: return t.name
            val end = start.plusSeconds((t.durationMin * 60).toLong())
            val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
            return "${start.format(DATE)} · ${start.format(hm)}–${end.format(hm)}"
        }
    }
}
