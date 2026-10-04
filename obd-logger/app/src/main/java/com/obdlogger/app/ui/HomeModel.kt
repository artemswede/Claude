package com.obdlogger.app.ui

import android.content.Context
import com.obdlogger.app.Lamp
import com.obdlogger.app.LoggerState
import com.obdlogger.app.SessionFiles
import com.obdlogger.core.Focus
import com.obdlogger.core.HomeLogic
import com.obdlogger.core.HomeState
import com.obdlogger.core.Metric
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.TripComparison
import com.obdlogger.core.TripSummary
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class Trend(
    val title: String,
    val values: List<Double>,
    val valuesText: String,
    val calm: Boolean,
    /** Norm band of the metric for the sparkline; null side = no limit there. */
    val normLo: Double? = null,
    val normHi: Double? = null,
)

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
    /** The last saved trip's result was not opened yet: offered on the main screen until it is. */
    val unseen: Boolean = false,
    /**
     * Sensor on the main chart: the one the version is about, otherwise the one that
     * is worst across this car's trips — not always the mixture.
     */
    val focus: String = "trim_b1",
) {
    companion object {
        private val DATE = DateTimeFormatter.ofPattern("dd.MM")

        /** Heavy: reads CSV files. Call off the main thread. */
        fun build(ctx: Context, s: LoggerState.Snapshot): HomeModel {
            val saved = SessionFiles.tripCsvs(ctx).takeLast(30).mapNotNull { TripCache.item(it)?.summary }.filter { it.rows >= 10 }
            val current = s.currentCsv?.let(::File)?.takeIf { s.recording && it.exists() }
                ?.let { f ->
                    val info = SessionFiles.infoOf(f).takeIf { it.exists() }?.readText()
                    runCatching { TripAnalyzer.analyze(f.nameWithoutExtension, f.readText(), info) }.getOrNull()
                }
            return from(saved.filter { it.name != current?.name }, current, s, com.obdlogger.app.Prefs.seenTrip(ctx))
        }

        /** Pure part of [build]: decides the state from already analysed trips. */
        fun from(allSaved: List<TripSummary>, current: TripSummary?, s: LoggerState.Snapshot, seenTrip: String? = null): HomeModel {
            // Only this car's trips: the tablet is moved between cars, their data must not mix.
            val car = (current ?: allSaved.maxByOrNull { it.start ?: java.time.LocalDateTime.MIN })?.car?.key
            val saved = allSaved.filter { it.car?.key == car }.sortedBy { it.start }.takeLast(8)
            val live = current != null
            val trip = current ?: saved.lastOrNull()

            val lastSaved = saved.lastOrNull()
            val state = HomeLogic.decide(current, lastSaved, s.recording, s.auto, s.link == Lamp.FAIL)
            val past = saved.lastOrNull { HomeLogic.conclusive(it) }

            val history = (saved + listOfNotNull(current)).distinctBy { it.name }
            // What to chart and trend: the version's own sensor and metric, otherwise the
            // row that is worst in this car's comparison (fix one problem, the next one shows).
            val top = (trip?.top ?: past?.top)
            val worstRow = TripComparison.table(history).rows.firstOrNull { it.problem > 0.5 }
            val metric = top?.kind?.let(Focus::metric) ?: worstRow?.metric ?: Metric.IDLE_TRIM_B1
            val focus = (top?.kind?.let(Focus::code) ?: worstRow?.metric?.let(Focus::code) ?: "rpm")
                .takeIf { c -> trip?.trace?.of(c) != null } ?: listOf("trim_b1", "rpm").firstOrNull { trip?.trace?.of(it) != null } ?: "trim_b1"
            return HomeModel(state, trip, live, past, trend(history, metric), lastTrip(trip, live), !live && trip != null && trip.name != seenTrip, focus)
        }

        /** Sensor to chart for a trip: the one its version is about, else the mixture, else rpm. */
        fun focusOf(t: TripSummary): String {
            val tr = t.trace ?: return "trim_b1"
            return (t.top?.kind?.let(Focus::code))?.takeIf { tr.of(it) != null }
                ?: listOf("trim_b1", "rpm").firstOrNull { tr.of(it) != null } ?: "trim_b1"
        }

        /** Short names for the trend line. */
        private fun name(m: Metric) = when (m) {
            Metric.IDLE_TRIM_B1 -> "коррекция Б1 на ХХ"
            Metric.IDLE_TRIM_B2 -> "коррекция Б2 на ХХ"
            Metric.CRUISE_TRIM_B1, Metric.CRUISE_TRIM_B2 -> "коррекция в движении"
            Metric.IDLE_RPM -> "обороты ХХ"
            Metric.RPM_DIPS -> "провалы оборотов"
            Metric.CHARGE_V -> "напряжение зарядки"
            Metric.CHARGE_V_MIN -> "минимум напряжения"
            Metric.COOLANT_MAX -> "температура ОЖ"
            Metric.IDLE_REAR_O2 -> "задняя лямбда на ХХ"
            else -> m.ru.lowercase()
        }

        private fun band(m: Metric): Pair<Double?, Double?> = when (m) {
            Metric.IDLE_TRIM_B1, Metric.IDLE_TRIM_B2, Metric.CRUISE_TRIM_B1, Metric.CRUISE_TRIM_B2 -> -10.0 to 10.0
            Metric.IDLE_RPM -> 600.0 to 850.0
            Metric.RPM_DIPS -> 0.0 to 1.0
            Metric.CHARGE_V -> 13.5 to 14.8
            Metric.CHARGE_V_MIN -> 12.8 to null
            Metric.COOLANT_MAX -> 80.0 to 104.0
            Metric.IDLE_REAR_O2 -> 0.45 to 0.85
            else -> null to null
        }

        private fun fmt(m: Metric, v: Double) = when (m.unit) {
            "%" -> TripAnalyzer.pct(v).removeSuffix(" %")
            "об/мин", "раз", "°C" -> "${v.toInt()}"
            else -> TripAnalyzer.fmt(v)
        }

        private fun trend(trips: List<TripSummary>, metric: Metric): Trend? {
            val with = trips.filter { it.metrics.containsKey(metric) }.takeLast(3)
            if (with.size < 2) return null
            val v = with.map { it.metrics.getValue(metric) }
            val (lo, hi) = band(metric)
            val d = v.last() - v.first()
            val scale = maxOf(kotlin.math.abs(v.first()), kotlin.math.abs(v.last()), 1.0)
            val unit = if (metric.unit == "раз") "" else " ${metric.unit}"
            val text = v.joinToString(" → ") { fmt(metric, it) } + unit
            val inNorm = v.all { (lo == null || it >= lo) && (hi == null || it <= hi) }
            val n = "За ${with.size} ${trips(with.size)}: ${name(metric)}"
            val title = when {
                inNorm && kotlin.math.abs(d) / scale < 0.15 -> "За ${with.size} ${trips(with.size)}: без заметного ухудшения"
                kotlin.math.abs(d) / scale < 0.05 -> "$n без изменений"
                d > 0 -> "$n растёт"
                else -> "$n снижается"
            }
            return Trend(title, v, text, inNorm, lo, hi)
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
