package com.obdlogger.app.ui

import android.content.Context
import com.obdlogger.app.Lamp
import com.obdlogger.app.LoggerState
import com.obdlogger.app.SessionFiles
import com.obdlogger.core.HomeLogic
import com.obdlogger.core.HomeState
import com.obdlogger.core.Metric
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.TripSummary
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class Trend(val title: String, val values: List<Double>, val valuesText: String, val calm: Boolean)

/**
 * What the main screen shows. The trip being recorded is analysed live; when the
 * car is off, the last saved trip is shown (dimmed, with its date).
 */
class HomeModel(
    val state: HomeState,
    /** Trip shown on the chart: the current one while recording, otherwise the last saved. */
    val trip: TripSummary?,
    val live: Boolean,
    /** Last saved trip with enough data — quoted as a past conclusion when nothing is recorded. */
    val past: TripSummary?,
    val trend: Trend?,
    val lastTripText: String,
) {
    companion object {
        private val DATE = DateTimeFormatter.ofPattern("dd.MM")

        /** Heavy: reads CSV files. Call off the main thread. */
        fun build(ctx: Context, s: LoggerState.Snapshot): HomeModel {
            val saved = SessionFiles.tripCsvs(ctx).takeLast(8).mapNotNull { SessionFiles.analyze(it) }.filter { it.rows >= 10 }
            val current = s.currentCsv?.let(::File)?.takeIf { s.recording && it.exists() }
                ?.let { f -> runCatching { TripAnalyzer.analyze(f.nameWithoutExtension, f.readText()) }.getOrNull() }
            return from(saved, current, s)
        }

        /** Pure part of [build]: decides the state from already analysed trips. */
        fun from(saved: List<TripSummary>, current: TripSummary?, s: LoggerState.Snapshot): HomeModel {
            val live = current != null
            val trip = current ?: saved.lastOrNull()

            val lastSaved = saved.lastOrNull()
            val state = HomeLogic.decide(current, lastSaved, s.recording, s.auto, s.link == Lamp.FAIL)
            val past = saved.lastOrNull { HomeLogic.conclusive(it) }

            val history = (saved + listOfNotNull(current)).distinctBy { it.name }
            return HomeModel(state, trip, live, past, trend(history), lastTrip(trip, live))
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
