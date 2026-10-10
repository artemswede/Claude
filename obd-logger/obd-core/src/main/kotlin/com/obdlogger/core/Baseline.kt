package com.obdlogger.core

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * One trip's fingerprint: every sensor in every mode as median and p10…p90, plus the
 * weather of the trip (intake / ambient air). Small, cached next to the trip, so the
 * car's profile is built without re-reading CSVs.
 */
class TripProfile(
    val name: String,
    val label: String,
    val start: LocalDateTime?,
    val stats: Map<String, Map<DriveMode, Stat>>,
    /** Median intake air on the move — the trip's «weather». */
    val intakeAir: Double?,
) {
    class Stat(val median: Double, val p10: Double, val p90: Double, val n: Int)

    fun of(code: String, mode: DriveMode): Stat? = stats[code]?.get(mode)

    /** Text cache: a header line, then «code|MODE|median|p10|p90|n». */
    fun encode(): String = buildString {
        append("#v1|").append(name).append('|').append(label).append('|').append(start?.toString().orEmpty())
            .append('|').append(intakeAir?.toString().orEmpty()).append('\n')
        for ((code, byMode) in stats) for ((m, s) in byMode) {
            append(code).append('|').append(m.name).append('|').append(s.median).append('|').append(s.p10).append('|')
                .append(s.p90).append('|').append(s.n).append('\n')
        }
    }

    companion object {
        /** Fewer readings than this in a mode say nothing about the mode. */
        const val MIN_READINGS = 8

        fun of(d: TripDetail): TripProfile {
            val t = d.table
            val stats = LinkedHashMap<String, Map<DriveMode, Stat>>()
            for (code in Relations.codes(t)) {
                val v = d.series(code)
                val byMode = LinkedHashMap<DriveMode, Stat>()
                for (m in DriveMode.entries) {
                    val x = v.indices.filter { t.modes[it] == m }.mapNotNull { v[it] }.sorted()
                    if (x.size < MIN_READINGS) continue
                    byMode[m] = Stat(pct(x, 0.5), pct(x, 0.1), pct(x, 0.9), x.size)
                }
                if (byMode.isNotEmpty()) stats[code] = byMode
            }
            val air = stats["intake_air_c"]?.let { it[DriveMode.CRUISE] ?: it.values.firstOrNull() }?.median
            return TripProfile(d.summary.name, d.summary.label, d.summary.start, stats, air)
        }

        fun decode(text: String): TripProfile? = try {
            val lines = text.lines().filter { it.isNotBlank() }
            val h = lines.first().split('|')
            if (h[0] != "#v1") null
            else {
                val stats = LinkedHashMap<String, MutableMap<DriveMode, Stat>>()
                for (l in lines.drop(1)) {
                    val p = l.split('|')
                    stats.getOrPut(p[0]) { LinkedHashMap() }[DriveMode.valueOf(p[1])] =
                        Stat(p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toInt())
                }
                TripProfile(h[1], h[2], h[3].takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse), stats, h[4].toDoubleOrNull())
            }
        } catch (_: Exception) {
            null
        }

        fun pct(sorted: List<Double>, p: Double): Double = sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
    }
}

/** «Your normal» for one sensor in one mode, from the reference trips. */
class CarNorm(val code: String, val mode: DriveMode, val median: Double, val lo: Double, val hi: Double, val spread: Double, val trips: Int)

/** A change against this car's normal: how strong, since when, where it is heading, why it may be. */
class Drift(
    val code: String,
    val mode: DriveMode,
    val norm: CarNorm,
    /** Median of the recent trips' medians, and their range. */
    val now: Double,
    val nowLo: Double,
    val nowHi: Double,
    /** (now − normal) / spread. */
    val z: Double,
    val level: Severity,
    /** Of the recent trips, how many were off in the same direction. */
    val persistent: Int,
    val recent: Int,
    /** The trip it started with. */
    val since: String?,
    /** Change per trip over all trips (robust slope), and a forecast to the sensor's limit. */
    val slope: Double,
    val forecast: String?,
    /** Hotter weather in the recent trips explains part of a temperature rise. */
    val hot: Boolean,
    val causes: String,
    val text: String,
)

/**
 * The car's own profile and the changes against it — predictive: a coolant that was
 * always 97–98 °C and now sits at 100–101 °C is reported before it ever leaves the
 * generic norm. The profile comes from the reference trips (all but the newest
 * [RECENT]); the newest are compared with it.
 */
object Baseline {
    const val RECENT = 3
    const val MIN_TRIPS = 5

    /** Smallest change that means something, per sensor — no alarms on a perfectly steady sensor. */
    fun step(code: String): Double = when {
        code == "coolant_c" || code == "oil_c" -> 1.0
        code == "intake_air_c" || code == "ambient_c" -> 3.0
        code == "rpm" -> 30.0
        code.startsWith("trim_b") || code.matches(Regex("[ls]tft_b\\d_pct")) -> 2.0
        code == "battery_v" || code == "ecu_voltage_v" -> 0.1
        code == "maf_gs" -> 0.3
        code.matches(Regex("o2_b\\ds\\d_v")) -> 0.05
        code.endsWith("_trim_pct") -> 2.0
        code == "engine_load_pct" || code == "abs_load_pct" -> 3.0
        code == "throttle_pct" || code.startsWith("rel_throttle") || code.startsWith("pedal") -> 2.0
        code == "timing_deg" -> 1.5
        code == "map_kpa" -> 2.0
        code == "speed_kmh" -> 3.0
        code.endsWith("_c") -> 2.0
        code.endsWith("_kpa") -> 2.0
        else -> 0.0
    }

    /** Sensors whose level, not the driver, says something about the car. */
    private fun meaningful(code: String) = code !in setOf("speed_kmh", "throttle_pct", "rel_throttle_pct", "pedal_d_pct", "pedal_e_pct", "cmd_throttle_pct", "run_time_s") &&
        !code.matches(Regex("stft_b\\d_pct")) && !code.matches(Regex("o2_b\\ds1_v")) && !code.endsWith("_trim_pct")

    /** Modes where a sensor's level is comparable from trip to trip. */
    private fun comparable(code: String, mode: DriveMode): Boolean = when (mode) {
        DriveMode.COLD -> code == "rpm" || code == "battery_v"
        DriveMode.HEAVY -> false
        DriveMode.ROLL_TO_STOP -> code == "rpm"
        else -> true
    }

    /** «Изучаю: [have] из [MIN_TRIPS + RECENT]» while there are too few trips. */
    fun learning(profiles: List<TripProfile>): Pair<Int, Int> = profiles.size to MIN_TRIPS + RECENT

    fun ready(profiles: List<TripProfile>) = profiles.size >= MIN_TRIPS + RECENT

    private fun median(x: List<Double>): Double = x.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** This car's normal per sensor and mode, from the reference trips (oldest first in [profiles]). */
    fun norms(profiles: List<TripProfile>): List<CarNorm> {
        val ref = profiles.sortedBy { it.start }.dropLast(RECENT)
        val keys = ref.flatMap { p -> p.stats.flatMap { (c, m) -> m.keys.map { c to it } } }.distinct()
        return keys.mapNotNull { (code, mode) ->
            val v = ref.mapNotNull { it.of(code, mode)?.median }
            if (v.size < MIN_TRIPS) return@mapNotNull null
            val m = median(v)
            val mad = median(v.map { abs(it - m) }) * 1.4826
            val s = max(mad, step(code)).takeIf { it > 0 } ?: max(abs(m) * 0.05, 1e-3)
            val sorted = v.sorted()
            CarNorm(code, mode, m, TripProfile.pct(sorted, 0.1), TripProfile.pct(sorted, 0.9), s, v.size)
        }
    }

    fun norm(norms: List<CarNorm>, code: String, mode: DriveMode): CarNorm? = norms.firstOrNull { it.code == code && it.mode == mode }

    /** Robust slope (median of pairwise slopes) of [v] against its index. */
    fun slope(v: List<Double>): Double {
        val s = ArrayList<Double>()
        for (i in v.indices) for (j in i + 1 until v.size) s += (v[j] - v[i]) / (j - i)
        return if (s.isEmpty()) 0.0 else median(s)
    }

    /** A limit worth forecasting, for a sensor moving in [up] direction; null if none. */
    private fun limit(code: String, mode: DriveMode, norm: CarNorm, up: Boolean): Double? = when {
        code == "coolant_c" && up && mode != DriveMode.COLD -> 105.0
        code == "battery_v" && !up && mode != DriveMode.COLD -> 13.2
        code.startsWith("trim_b") || code.matches(Regex("ltft_b\\d_pct")) -> if (up) 25.0 else -25.0
        code == "rpm" && mode == DriveMode.WARM_IDLE && !up -> norm.median - max(100.0, norm.median * 0.15)
        else -> null
    }

    private val CAUSES_UP = mapOf(
        "coolant_c" to "термостат открывается не полностью, вентилятор радиатора или его включение, уровень ОЖ, помпа, забитый радиатор",
        "oil_c" to "нагрузка, уровень и состояние масла, охлаждение",
        "battery_v" to "регулятор напряжения генератора (перезаряд)",
        "trim" to "бедная смесь: подсос воздуха, ДМРВ занижает, давление топлива, форсунки",
        "rpm" to "холостой выше обычного: подсос после дросселя, регулятор ХХ, датчик температуры ОЖ",
        "maf_gs" to "мотору нужно больше воздуха на том же режиме: нагрузка (генератор, кондиционер), трение, ДМРВ",
        "engine_load_pct" to "больше нагрузка на том же режиме: потребители, трение, подсос",
        "map_kpa" to "выше давление во впуске: подсос, клапан EGR, фазы ГРМ",
        "intake_air_c" to "жара или подогрев воздуха от мотора; если в мороз — датчик",
        "timing_deg" to "ЭБУ ставит больше опережения — обычно не проблема",
        "o2_s2" to "задняя лямбда «богаче» обычного: смесь, катализатор",
    )
    private val CAUSES_DOWN = mapOf(
        "coolant_c" to "термостат постоянно открыт или датчик температуры ОЖ",
        "oil_c" to "охлаждение масла, датчик",
        "battery_v" to "зарядка слабеет: генератор (щётки, диодный мост), ремень, клеммы и масса, аккумулятор",
        "trim" to "богатая смесь: форсунки льют, давление топлива выше, ДМРВ завышает, лямбда",
        "rpm" to "холостой ниже обычного: грязный дроссель или регулятор ХХ, подсос, пропуски",
        "maf_gs" to "ДМРВ показывает меньше: загрязнение ДМРВ или подсос после него",
        "engine_load_pct" to "нагрузка ниже на том же режиме: ДМРВ занижает",
        "map_kpa" to "ниже давление во впуске: обычно не проблема, проверьте датчик",
        "intake_air_c" to "холоднее, чем обычно, — погода",
        "timing_deg" to "ЭБУ убирает опережение — детонация: топливо, нагар, датчик детонации",
        "o2_s2" to "задняя лямбда «беднее» обычного: подсос, смесь, сама лямбда или её проводка",
    )

    fun causes(code: String, up: Boolean): String {
        val key = when {
            code.startsWith("trim_b") || code.matches(Regex("ltft_b\\d_pct")) -> "trim"
            code.matches(Regex("o2_b\\ds2_v")) -> "o2_s2"
            else -> code
        }
        return (if (up) CAUSES_UP else CAUSES_DOWN)[key] ?: "изменилась работа узла — посмотрите график по поездкам"
    }

    /** Numbers as a person reads them: by the sensor's step. */
    fun fmt(code: String, v: Double): String {
        val s = step(code)
        return when {
            s >= 1 -> round(v).toInt().toString()
            s >= 0.1 -> String.format(Locale.ROOT, "%.1f", v)
            else -> String.format(Locale.ROOT, "%.2f", v)
        }.replace("-", "−")
    }

    private fun range(code: String, lo: Double, hi: Double): String =
        if (fmt(code, lo) == fmt(code, hi)) fmt(code, lo) else "${fmt(code, lo)}–${fmt(code, hi)}"

    private fun trips(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "поездку"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "поездки"
        else -> "поездок"
    }

    /** Changes against this car's normal, strongest first; empty while learning. */
    fun drifts(profiles: List<TripProfile>): List<Drift> {
        if (!ready(profiles)) return emptyList()
        val sorted = profiles.sortedBy { it.start }
        val ref = sorted.dropLast(RECENT)
        val recent = sorted.takeLast(RECENT)
        val refAir = ref.mapNotNull { it.intakeAir }.takeIf { it.size >= 3 }?.let(::median)
        val recentAir = recent.mapNotNull { it.intakeAir }.takeIf { it.isNotEmpty() }?.let(::median)
        val out = ArrayList<Drift>()
        for (norm in norms(sorted)) {
            val code = norm.code
            val mode = norm.mode
            if (!meaningful(code) || !comparable(code, mode)) continue
            val rv = recent.mapNotNull { it.of(code, mode)?.median }
            if (rv.isEmpty()) continue
            val now = median(rv)
            val z = (now - norm.median) / norm.spread
            val up = z > 0
            val persistent = rv.count { v -> ((v - norm.median) / norm.spread).let { if (up) it >= 2 else it <= -2 } }
            val last = rv.last()
            val zLast = (last - norm.median) / norm.spread
            // A temperature rise in hotter weather is partly the weather.
            val hot = up && (code == "coolant_c" || code == "intake_air_c" || code == "oil_c") &&
                refAir != null && recentAir != null && recentAir - refAir >= 8
            var level = when {
                abs(z) >= 3 && persistent >= 2 -> Severity.WARN
                abs(z) >= 2 && persistent >= 2 -> Severity.WATCH
                abs(zLast) >= 5 -> Severity.WATCH
                else -> null
            } ?: continue
            if (hot) level = if (level == Severity.WARN) Severity.WATCH else continue
            // Since when: the earliest trip from which every trip is off in this direction.
            val all = sorted.mapNotNull { p -> p.of(code, mode)?.median?.let { p.label to it } }
            var sinceIdx = all.size
            for (i in all.indices.reversed()) {
                val zi = (all[i].second - norm.median) / norm.spread
                if (if (up) zi >= 2 else zi <= -2) sinceIdx = i else break
            }
            val since = all.getOrNull(sinceIdx)?.first
            val sl = slope(all.map { it.second })
            val lim = limit(code, mode, norm, up)
            val forecast = if (lim != null && sl != 0.0 && (lim - now) / sl > 0) {
                val n = ((lim - now) / sl).let { kotlin.math.ceil(it).toInt() }
                if (n in 1..30) "при таком темпе дойдёт до ${fmt(code, lim)} ${SensorNames.unit(code)} примерно через $n ${trips(n)}" else null
            } else null
            val unit = SensorNames.unit(code).let { if (it.isEmpty()) "" else " $it" }
            val delta = now - norm.median
            val text = buildString {
                append("${SensorNames.label(code)} · ${mode.ru.lowercase()}: обычно у вас ${range(code, norm.lo, norm.hi)}$unit, ")
                append(if (rv.size == 1) "в последней поездке ${fmt(code, last)}$unit" else "последние ${rv.size} ${trips(rv.size)} ${range(code, rv.min(), rv.max())}$unit")
                append(" (${if (delta > 0) "+" else ""}${fmt(code, delta)}).")
                since?.let { if (sinceIdx < all.size - 1) append(" Началось с $it.") }
                if (hot) append(" Часть роста — жара: воздух на впуске теплее обычного.")
                forecast?.let { append(" ${it.replaceFirstChar { c -> c.uppercase() }}.") }
            }
            out += Drift(code, mode, norm, now, rv.min(), rv.max(), z, level, persistent, rv.size, since, sl, forecast, hot, causes(code, up), text)
        }
        // One line per sensor: the mode where it moved most.
        return out.groupBy { it.code }.values.map { g -> g.maxByOrNull { abs(it.z) }!! }
            .sortedWith(compareByDescending<Drift> { it.level }.thenByDescending { abs(it.z) })
    }

    /** For the AI: the car's normal and what changed, as text. */
    fun render(profiles: List<TripProfile>): String = buildString {
        val (have, need) = learning(profiles)
        if (!ready(profiles)) {
            appendLine("ОБЫЧНО У ЭТОЙ МАШИНЫ: профиль ещё учится ($have из $need поездок) — сравнивай с общими нормами осторожно.")
            return@buildString
        }
        appendLine("ОБЫЧНО У ЭТОЙ МАШИНЫ (медианы по ${have - RECENT} опорным поездкам; «сейчас» — последние $RECENT):")
        for (n in norms(profiles).filter { meaningful(it.code) && comparable(it.code, it.mode) }) {
            appendLine("  ${n.code} [${n.mode.name}]: обычно ${range(n.code, n.lo, n.hi)} (медиана ${fmt(n.code, n.median)})")
        }
        val d = drifts(profiles)
        appendLine(if (d.isEmpty()) "ЧТО ИЗМЕНИЛОСЬ: ничего заметного — всё как обычно у этой машины." else "ЧТО ИЗМЕНИЛОСЬ (сильнее — выше):")
        d.forEach { appendLine("  [${it.level.ru}] ${it.text} Возможные причины: ${it.causes}.") }
    }
}
