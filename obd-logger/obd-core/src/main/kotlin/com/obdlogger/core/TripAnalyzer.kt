package com.obdlogger.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/** Operating modes a row is classified into; trims and rpm are only comparable within one mode. */
enum class DriveMode(val ru: String) {
    COLD("Прогрев"),
    WARM_IDLE("Холостой стоя (прогрет)"),
    ROLL_TO_STOP("Подкат к остановке"),
    CRUISE("Равномерное движение"),
    HEAVY("Сильный газ"),
}

/** Per-trip key figures, each measured in the operating mode where it means something. */
enum class Metric(val ru: String, val unit: String) {
    IDLE_RPM("Обороты холостого стоя, медиана", "об/мин"),
    ROLL_RPM("Обороты при подкате к остановке, медиана", "об/мин"),
    RPM_DIPS("Провалы оборотов на холостом (ниже обычного на 15 %)", "раз"),
    IDLE_MAF("Расход воздуха на холостом", "г/с"),
    IDLE_TRIM_B1("Коррекция топлива на холостом, банк 1 (LTFT+STFT)", "%"),
    IDLE_TRIM_B2("Коррекция топлива на холостом, банк 2 (LTFT+STFT)", "%"),
    CRUISE_TRIM_B1("Коррекция топлива в движении, банк 1", "%"),
    CRUISE_TRIM_B2("Коррекция топлива в движении, банк 2", "%"),
    IDLE_REAR_O2("Задняя лямбда на холостом", "В"),
    CRUISE_REAR_O2("Задняя лямбда в движении", "В"),
    CHARGE_V("Напряжение на работающем моторе, медиана", "В"),
    CHARGE_V_MIN("Напряжение на работающем моторе, минимум", "В"),
    COOLANT_MAX("Температура ОЖ, максимум", "°C"),
    INTAKE_AIR("Воздух на впуске, медиана", "°C"),
}

enum class Severity(val ru: String) { OK("норма"), WATCH("наблюдать"), WARN("внимание"), BAD("проблема") }

/** One line of «Почему Бортач так думает»: label, value, and whether the value is out of norm. */
data class Evidence(val label: String, val value: String, val deviating: Boolean = false, val emphasis: Boolean = false)

data class Finding(
    val severity: Severity,
    val title: String,
    val evidence: String,
    val advice: String,
    /** высокая / средняя / низкая */
    val confidence: String,
    /** Short headline for the main screen («Подсос воздуха на холостом»). */
    val headline: String = title,
    /** What it means for driving, calm wording. */
    val urgency: String? = null,
    /** 2–3 numbers from the log that support the version. */
    val why: List<Evidence> = emptyList(),
    /** Stable id for the knowledge base ([Hypotheses]): «air_leak», «dips»… */
    val kind: String = "",
    /** How far beyond the norm, in norm widths; orders findings of equal severity (worst first). */
    val score: Double = 0.0,
)

/**
 * Which car a trip belongs to. Trips of different cars are never compared: the
 * app is moved between cars. VIN when the ECU reports it, otherwise the protocol
 * plus the set of supported PIDs — different engines answer differently.
 */
data class CarId(val key: String, val name: String) {
    companion object {
        /** Same key the service computes at connect time and [of] reads back from the info file. */
        fun key(vin: String?, protocolLine: String, pidsLine: String): String? = when {
            vin != null && Regex("[A-HJ-NPR-Z0-9]{17}").matches(vin) -> "vin:$vin"
            pidsLine.isBlank() || pidsLine == "—" -> null
            else -> "ecu:" + (protocolLine.trim() + "|" + pidsLine.trim()).hashCode().toUInt().toString(16)
        }

        /** Protocol line as written in the info file: «ISO 9141-2 (#3)». */
        fun protocolLine(info: VehicleInfo) = "${info.protocol} (#${info.protocolNumber})"

        fun pidsLine(info: VehicleInfo) = info.supportedPids.joinToString(" ") { "%02X".format(it) }.ifEmpty { "—" }

        fun of(info: String?): CarId? {
            if (info == null) return null
            val owner = Regex("Автомобиль \\(со слов владельца\\): (.*)").find(info)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != "не указан" }
            val vin = Regex("VIN: ([A-HJ-NPR-Z0-9]{17})").find(info)?.groupValues?.get(1)
            val proto = Regex("Протокол OBD: ([^\\n]*)").find(info)?.groupValues?.get(1)?.trim().orEmpty()
            val pids = info.substringAfter("Поддерживаемые автомобилем PID режима 01:", "").trim().lineSequence().firstOrNull()?.trim().orEmpty()
            val key = key(vin, proto, pids) ?: return owner?.let { CarId("name:$it", it) }
            return CarId(key, owner ?: if (vin != null) "VIN …${vin.takeLast(6)}" else "Машина без названия")
        }
    }
}

/** Per-row series of a trip for the main-screen chart. */
class TripTrace(
    val minutes: DoubleArray,
    /** LTFT+STFT bank 1, NaN where unknown. */
    val trimB1: DoubleArray,
    val modes: Array<DriveMode?>,
    val start: LocalDateTime?,
    /** The sensors a version can be about ([Focus.CODES]), NaN where unknown. */
    val series: Map<String, DoubleArray> = emptyMap(),
) {
    fun of(code: String): DoubleArray? = series[code]?.takeIf { s -> s.any { !it.isNaN() } }
}

/**
 * Which sensor and which trip metric a version is about — so the main screen
 * charts what matters for this car right now, not always the mixture.
 */
object Focus {
    val CODES = listOf("trim_b1", "trim_b2", "rpm", "battery_v", "coolant_c", "o2_b1s2_v", "maf_gs")

    fun code(kind: String): String? = when (kind) {
        "air_leak", "lean_all", "rich_idle", "rich_cruise" -> "trim_b1"
        "dips", "low_rpm" -> "rpm"
        "weak_charge", "voltage_dips" -> "battery_v"
        "overheat", "cold_engine" -> "coolant_c"
        "rear_o2" -> "o2_b1s2_v"
        else -> null
    }

    fun metric(kind: String): Metric? = when (kind) {
        "air_leak", "rich_idle" -> Metric.IDLE_TRIM_B1
        "lean_all", "rich_cruise" -> Metric.CRUISE_TRIM_B1
        "dips" -> Metric.RPM_DIPS
        "low_rpm" -> Metric.IDLE_RPM
        "weak_charge" -> Metric.CHARGE_V
        "voltage_dips" -> Metric.CHARGE_V_MIN
        "overheat", "cold_engine" -> Metric.COOLANT_MAX
        "rear_o2" -> Metric.IDLE_REAR_O2
        else -> null
    }

    fun code(metric: Metric): String = when (metric) {
        Metric.IDLE_TRIM_B1, Metric.CRUISE_TRIM_B1 -> "trim_b1"
        Metric.IDLE_TRIM_B2, Metric.CRUISE_TRIM_B2 -> "trim_b2"
        Metric.IDLE_RPM, Metric.ROLL_RPM, Metric.RPM_DIPS -> "rpm"
        Metric.CHARGE_V, Metric.CHARGE_V_MIN -> "battery_v"
        Metric.COOLANT_MAX -> "coolant_c"
        Metric.IDLE_REAR_O2, Metric.CRUISE_REAR_O2 -> "o2_b1s2_v"
        Metric.IDLE_MAF -> "maf_gs"
        Metric.INTAKE_AIR -> "intake_air_c"
    }
}

/** A parsed CSV with every row classified by [DriveMode]; columns are read on demand. */
class TripTable(
    val header: List<String>,
    val rows: List<List<String>>,
    val times: List<LocalDateTime?>,
    /** Milliseconds since the first row. */
    val ms: List<Long>,
) {
    private val col = header.withIndex().associate { (i, h) -> h to i }
    var modes: List<DriveMode?> = List(rows.size) { null }
        internal set
    var closed: BooleanArray = BooleanArray(rows.size)
        internal set

    val start: LocalDateTime? get() = times.firstNotNullOfOrNull { it }

    fun has(name: String) = name in col || (name.startsWith("trim_b") && "stft_${name.removePrefix("trim_")}_pct" in col)

    fun raw(name: String): List<Double?> {
        if (name.startsWith("trim_b")) return totalTrim(name.removePrefix("trim_b").toInt())
        val i = col[name] ?: return List(rows.size) { null }
        return rows.map { it.getOrNull(i)?.toDoubleOrNull() }
    }

    /** Slow columns (polled every 5th cycle) are reused for up to 30 s. */
    fun carried(name: String): List<Double?> {
        val v = raw(name)
        var last: Double? = null
        var lastMs = 0L
        return v.indices.map { i ->
            val x = v[i]
            if (x != null) {
                last = x
                lastMs = ms[i]
                x
            } else if (last != null && ms[i] - lastMs <= 30_000L) last else null
        }
    }

    /** LTFT + STFT: what the ECU really adds. */
    fun totalTrim(bank: Int): List<Double?> {
        val st = raw("stft_b${bank}_pct")
        val lt = carried("ltft_b${bank}_pct")
        return st.indices.map { i -> st[i]?.let { s -> lt[i]?.let { s + it } } }
    }

    /** Numeric sensor columns (no time, marker or text), derived trims first. */
    val sensors: List<String> by lazy {
        val skip = setOf("time", "t_s", "marker", "fuel_system")
        val numeric = header.filter { it !in skip && raw(it).any { v -> v != null } }
        (1..2).map { "trim_b$it" }.filter { has(it) } + numeric
    }

    /** Markers with their row index: «M1», «RECONNECT», «TEST2». */
    val markers: List<Pair<Int, String>> by lazy {
        val i = col["marker"] ?: return@lazy emptyList()
        rows.withIndex().mapNotNull { (n, r) -> r.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { n to it } }
    }
}

class TripSummary(
    val name: String,
    val start: LocalDateTime?,
    val durationMin: Double,
    val rows: Int,
    val modeRows: Map<DriveMode, Int>,
    val metrics: Map<Metric, Double>,
    val dtcs: List<String>?,
    val findings: List<Finding>,
    val trace: TripTrace? = null,
    /** Seconds of warm idle standing — the mode most versions need. */
    val warmIdleSec: Double = 0.0,
    /** The car, from the trip's info file; null for very old recordings. */
    val car: CarId? = null,
) {
    /** The version to show first: highest severity, real findings only. */
    val top: Finding? get() = findings.firstOrNull { it.severity >= Severity.WARN }
    val label: String get() = start?.format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) ?: name
}

/**
 * Turns a recorded CSV into a [TripSummary]: rows are classified by operating mode,
 * slow columns are carried forward for a short while, and simple diagnostic rules
 * produce [Finding]s. Works on any CSV written by [DataLogger], old ones included.
 */
object TripAnalyzer {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private const val WARM_C = 70.0

    /**
     * A dip: rpm below this car's own warm idle by 15 % (at least 100 rpm) — any engine,
     * any idle speed. Without a warm idle in the trip, a generic 560.
     */
    fun dipBelow(idleRpm: Double?): Double = idleRpm?.let { it - maxOf(100.0, it * 0.15) } ?: 560.0
    private const val MIN_SAMPLES = 5

    /** Parses a CSV and classifies every row by operating mode. Null if there is nothing to analyse. */
    fun table(csv: String): TripTable? {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return null
        val header = splitCsv(lines[0])
        val timeIdx = header.indexOf("time").takeIf { it >= 0 } ?: return null
        val rows = lines.drop(1).map { splitCsv(it) }
        val times = rows.map { r -> r.getOrNull(timeIdx)?.let { runCatching { LocalDateTime.parse(it, TIME) }.getOrNull() } }
        val first = times.firstNotNullOfOrNull { it }
        val ms = times.map { t -> t?.let { Duration.between(first ?: it, it).toMillis() } ?: 0L }
        val t = TripTable(header, rows, times, ms)

        val rpm = t.raw("rpm")
        val speed = t.raw("speed_kmh")
        val throttle = t.raw("throttle_pct")
        val load = t.raw("engine_load_pct")
        val coolant = t.carried("coolant_c")
        val running = rpm.map { it != null && it > 250 }

        // Closed-throttle position differs per car; take a low percentile of the trip.
        val thr = throttle.filterIndexed { i, x -> x != null && running[i] }.filterNotNull().sorted()
        val closedThr = if (thr.isEmpty()) 0.0 else thr[(thr.size * 0.05).toInt()] + 1.0
        t.closed = BooleanArray(rows.size) { i -> throttle[i]?.let { it <= closedThr } ?: false }

        t.modes = rows.indices.map { i ->
            val r = rpm[i] ?: return@map null
            if (!running[i]) return@map null
            val c = coolant[i]
            val s = speed[i] ?: 0.0
            when {
                (throttle[i] ?: 0.0) > 50 || (load[i] ?: 0.0) > 75 -> DriveMode.HEAVY
                c != null && c < WARM_C -> DriveMode.COLD
                c == null -> null
                s == 0.0 && t.closed[i] && r < 1100 -> DriveMode.WARM_IDLE
                s in 0.5..20.0 && t.closed[i] -> DriveMode.ROLL_TO_STOP
                s >= 40 && !t.closed[i] && (load[i] ?: 0.0) in 15.0..70.0 -> DriveMode.CRUISE
                else -> null
            }
        }
        return t
    }

    fun analyze(name: String, csv: String, info: String? = null): TripSummary? = table(csv)?.let { analyze(name, it, info) }

    fun analyze(name: String, t: TripTable, info: String? = null): TripSummary {
        val rows = t.rows
        val ms = t.ms
        val times = t.times
        val modes = t.modes
        fun raw(n: String) = t.raw(n)
        fun carried(n: String) = t.carried(n)
        fun closed(i: Int) = t.closed[i]
        val rpm = raw("rpm")
        val speed = raw("speed_kmh")
        val coolant = carried("coolant_c")

        fun inMode(values: List<Double?>, vararg m: DriveMode) =
            values.filterIndexed { i, v -> v != null && modes[i] in m }.filterNotNull()

        fun median(v: List<Double>): Double? =
            if (v.size < MIN_SAMPLES) null else v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

        fun totalTrim(bank: Int) = t.totalTrim(bank)

        val metrics = LinkedHashMap<Metric, Double>()
        fun put(m: Metric, v: Double?) {
            if (v != null) metrics[m] = v
        }
        put(Metric.IDLE_RPM, median(inMode(rpm, DriveMode.WARM_IDLE)))
        put(Metric.ROLL_RPM, median(inMode(rpm, DriveMode.ROLL_TO_STOP)))
        put(Metric.IDLE_MAF, median(inMode(raw("maf_gs"), DriveMode.WARM_IDLE)))
        val trim1 = totalTrim(1)
        val trim2 = totalTrim(2)
        put(Metric.IDLE_TRIM_B1, median(inMode(trim1, DriveMode.WARM_IDLE)))
        put(Metric.IDLE_TRIM_B2, median(inMode(trim2, DriveMode.WARM_IDLE)))
        put(Metric.CRUISE_TRIM_B1, median(inMode(trim1, DriveMode.CRUISE)))
        put(Metric.CRUISE_TRIM_B2, median(inMode(trim2, DriveMode.CRUISE)))
        put(Metric.IDLE_REAR_O2, median(inMode(raw("o2_b1s2_v"), DriveMode.WARM_IDLE)))
        put(Metric.CRUISE_REAR_O2, median(inMode(raw("o2_b1s2_v"), DriveMode.CRUISE)))
        val volts = raw("battery_v").filterIndexed { i, v -> v != null && (rpm[i] ?: 0.0) > 500 }.filterNotNull()
        put(Metric.CHARGE_V, median(volts))
        if (volts.size >= MIN_SAMPLES) put(Metric.CHARGE_V_MIN, volts.min())
        // Max coolant only says something about a trip where the engine got warm.
        val coolantPeak = raw("coolant_c").filterNotNull().maxOrNull()
        coolantPeak?.takeIf { it >= 75 }?.let { put(Metric.COOLANT_MAX, it) }
        put(Metric.INTAKE_AIR, median(raw("intake_air_c").filterNotNull()))

        // Dips: warm engine, closed throttle, (almost) standing, rpm well below this trip's idle — counted as events.
        val dipRpm = dipBelow(metrics[Metric.IDLE_RPM])
        var dips = 0
        var inDip = false
        for (i in rows.indices) {
            val r = rpm[i]
            val dip = r != null && r in 250.0..dipRpm && (coolant[i] ?: 0.0) >= WARM_C && closed(i) && (speed[i] ?: 0.0) <= 20
            if (dip && !inDip) dips++
            inDip = dip
        }
        if (metrics.containsKey(Metric.IDLE_RPM) || metrics.containsKey(Metric.ROLL_RPM)) put(Metric.RPM_DIPS, dips.toDouble())

        val dtcs = info?.let(::parseDtcs)
        val duration = (ms.lastOrNull() ?: 0L) / 60_000.0
        val modeRows = DriveMode.entries.associateWith { m -> modes.count { it == m } }
        val trace = TripTrace(
            DoubleArray(rows.size) { ms[it] / 60_000.0 },
            DoubleArray(rows.size) { trim1[it] ?: Double.NaN },
            modes.toTypedArray(),
            times.firstNotNullOfOrNull { it },
            Focus.CODES.filter { t.has(it) }.associateWith { c ->
                val v = if (c == "coolant_c" || c == "battery_v") t.carried(c) else t.raw(c)
                DoubleArray(rows.size) { v[it] ?: Double.NaN }
            },
        )
        // Rows are ~3 s apart on K-line; count time, not rows.
        var warmIdleMs = 0L
        for (i in 1 until rows.size) if (modes[i] == DriveMode.WARM_IDLE) warmIdleMs += (ms[i] - ms[i - 1]).coerceIn(0L, 10_000L)
        return TripSummary(name, times.firstNotNullOfOrNull { it }, duration, rows.size, modeRows, metrics, dtcs,
            findings(metrics, modeRows, dtcs, coolantPeak, duration), trace, warmIdleMs / 1000.0, CarId.of(info))
    }

    private fun findings(
        m: Map<Metric, Double>, modeRows: Map<DriveMode, Int>, dtcs: List<String>?,
        coolantPeak: Double? = null, durationMin: Double = 0.0,
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        fun f(v: Double) = pct(v).removeSuffix(" %")

        if (!dtcs.isNullOrEmpty()) {
            out += Finding(Severity.BAD, "Коды неисправностей: ${dtcs.joinToString(", ")}",
                "ЭБУ сообщает сохранённые коды", "Расшифровать коды для этого двигателя", "высокая",
                headline = "Блок записал коды: ${dtcs.joinToString(", ")}", kind = "dtc", score = 100.0)
        }

        val idle = listOfNotNull(m[Metric.IDLE_TRIM_B1], m[Metric.IDLE_TRIM_B2])
        val cruise = listOfNotNull(m[Metric.CRUISE_TRIM_B1], m[Metric.CRUISE_TRIM_B2])
        val idleMax = idle.maxByOrNull { abs(it) }
        val cruiseMax = cruise.maxByOrNull { abs(it) }
        if (idleMax != null && idleMax > 10) {
            val onlyIdle = cruiseMax == null || abs(cruiseMax) < 8
            out += Finding(
                if (idleMax > 20) Severity.BAD else Severity.WARN,
                if (onlyIdle) "Бедная смесь только на холостом" else "Бедная смесь на всех режимах",
                "Коррекция на холостом ${idle.joinToString(" / ") { f(it) + " %" }}" +
                    (cruiseMax?.let { ", в движении ${cruise.joinToString(" / ") { f(it) + " %" }}" } ?: ""),
                if (onlyIdle) {
                    "Подсос воздуха мимо ДМРВ (шланги вентиляции картера и усилителя тормозов, патрубок EGR, прокладки впуска) или занижение ДМРВ. Проверка дымогенератором"
                } else {
                    "Недолив топлива: давление топлива, бензонасос/фильтр, форсунки; или ДМРВ занижает во всём диапазоне"
                },
                if (cruiseMax != null) "высокая" else "средняя",
                headline = if (onlyIdle) "Подсос воздуха на холостом" else "Бедная смесь на всех режимах",
                kind = if (onlyIdle) "air_leak" else "lean_all",
                score = (idleMax - 10) / 10,
                urgency = if (idleMax > 30) "Ехать можно, но не откладывайте проверку." else "Не критично для поездки. Проверьте в ближайшие дни.",
                why = buildList {
                    add(Evidence("ХХ: ЭБУ добавляет топливо", pct(idleMax), deviating = true))
                    if (cruiseMax != null) {
                        add(if (onlyIdle) Evidence("В движении — норма", pct(cruiseMax), emphasis = true)
                        else Evidence("В движении тоже бедно", pct(cruiseMax), deviating = true))
                    }
                    m[Metric.IDLE_REAR_O2]?.takeIf { it < 0.15 }?.let { add(Evidence("Задняя лямбда на ХХ: «бедно»", "${fmt(it)} В", deviating = true)) }
                },
            )
        } else if (idleMax != null && idleMax < -10) {
            out += Finding(if (idleMax < -20) Severity.BAD else Severity.WARN, "Богатая смесь на холостом",
                "Коррекция на холостом ${idle.joinToString(" / ") { f(it) + " %" }}",
                "Подтекающие форсунки, давление топлива, продувка адсорбера (EVAP), датчик температуры ОЖ", "средняя",
                kind = "rich_idle", score = (-idleMax - 10) / 10,
                urgency = "Не критично для поездки. Расход выше обычного — проверьте в ближайшие дни.",
                why = listOfNotNull(Evidence("ХХ: ЭБУ убирает топливо", pct(idleMax), deviating = true),
                    cruiseMax?.let { Evidence("В движении", pct(it), deviating = abs(it) > 10) }))
        }
        if (cruiseMax != null && cruiseMax < -10) {
            out += Finding(Severity.WARN, "Богатая смесь в движении", "Коррекция в движении ${cruise.joinToString(" / ") { f(it) + " %" }}",
                "ДМРВ завышает, давление топлива, форсунки", "средняя", kind = "rich_cruise", score = (-cruiseMax - 10) / 10)
        }

        val dips = m[Metric.RPM_DIPS] ?: 0.0
        val idleRpm = m[Metric.IDLE_RPM]
        val dipRpm = dipBelow(idleRpm).toInt()
        if (dips >= 2) {
            out += Finding(if (dips >= 5) Severity.BAD else Severity.WARN, "Провалы холостого хода",
                "${dips.toInt()} раз обороты опускались ниже $dipRpm (обычного холостого минус 15 %) при прогретом моторе и закрытом дросселе",
                "Загрязнение дросселя или регулятора холостого, подсос воздуха, нагрузка от генератора. Чистка дросселя, затем обучение холостого", "высокая",
                kind = "dips", score = dips / 2,
                urgency = "Мотор может заглохнуть на остановке. Проверьте в ближайшие дни.",
                why = listOfNotNull(
                    Evidence("Провалы ниже $dipRpm об/мин", "${dips.toInt()} раз", deviating = true),
                    idleRpm?.let { Evidence("Обороты холостого, медиана", "${it.toInt()}") },
                ))
        } else if (idleRpm != null && idleRpm < 620) {
            out += Finding(Severity.WATCH, "Низкие обороты холостого", "Медиана ${idleRpm.toInt()} об/мин",
                "Чистка дросселя, проверка подсоса воздуха", "средняя", kind = "low_rpm", score = (620 - idleRpm) / 60)
        }

        val rearIdle = m[Metric.IDLE_REAR_O2]
        val rearCruise = m[Metric.CRUISE_REAR_O2]
        if (rearIdle != null && rearIdle < 0.15 && (rearCruise == null || rearCruise > 0.45)) {
            out += Finding(Severity.WATCH, "Задняя лямбда на холостом показывает «бедно»",
                "${fmt(rearIdle)} В на холостом" + (rearCruise?.let { ", ${fmt(it)} В в движении" } ?: ""),
                "Подтверждает бедную смесь на холостом (если коррекции тоже высокие) либо подсос воздуха в выпуске", "средняя", kind = "rear_o2")
        } else if (rearCruise != null && rearCruise < 0.2 && (modeRows[DriveMode.CRUISE] ?: 0) >= 30) {
            out += Finding(Severity.WATCH, "Задняя лямбда почти всё время около нуля",
                "${fmt(rearCruise)} В при равномерном движении", "Подсос в выпуске перед датчиком, старый датчик или бедная смесь", "низкая", kind = "rear_o2")
        }

        val volts = m[Metric.CHARGE_V]
        val vMin = m[Metric.CHARGE_V_MIN]
        if (volts != null && volts < 13.4) {
            out += Finding(Severity.WARN, "Слабая зарядка", "Медиана ${fmt(volts)} В на работающем моторе",
                "Генератор, регулятор напряжения, ремень, клеммы", "средняя",
                kind = "weak_charge", score = (13.4 - volts) / 0.4,
                urgency = "Проверьте зарядку до дальней поездки.",
                why = listOfNotNull(Evidence("Напряжение на работающем моторе", "${fmt(volts)} В", deviating = true),
                    vMin?.let { Evidence("Минимум", "${fmt(it)} В", deviating = it < 12.8) }))
        } else if (vMin != null && vMin < 12.8) {
            out += Finding(Severity.WATCH, "Просадки напряжения", "Минимум ${fmt(vMin)} В на работающем моторе",
                "Ремень генератора, клеммы, аккумулятор; совпадает ли с провалами оборотов", "низкая",
                headline = "Просадка напряжения до ${fmt(vMin)} В", kind = "voltage_dips", score = (12.8 - vMin) / 0.4)
        }

        val coolantMax = m[Metric.COOLANT_MAX]
        if (coolantMax != null && coolantMax > 104) {
            out += Finding(Severity.BAD, "Перегрев", "Температура ОЖ до ${coolantMax.toInt()} °C",
                "Вентилятор радиатора, уровень ОЖ, термостат, помпа", "высокая",
                kind = "overheat", score = (coolantMax - 104) / 4,
                urgency = "Остановитесь, дайте мотору остыть и проверьте уровень жидкости.",
                why = listOf(Evidence("Температура ОЖ, максимум", "${coolantMax.toInt()} °C", deviating = true)))
        } else if (coolantMax == null && coolantPeak != null && coolantPeak < 75 && durationMin >= 15) {
            out += Finding(Severity.WARN, "Мотор не прогревается", "Максимум ${coolantPeak.toInt()} °C за ${durationMin.toInt()} мин",
                "Термостат открыт постоянно или датчик температуры", "средняя",
                headline = "Мотор не прогревается", kind = "cold_engine", score = (75 - coolantPeak) / 10,
                urgency = "Ехать можно. Расход и износ выше, печка греет хуже — проверьте термостат.",
                why = listOf(Evidence("Температура ОЖ, максимум", "${coolantPeak.toInt()} °C", deviating = true),
                    Evidence("Норма для прогретого мотора", "80–100 °C")))
        }

        if (out.none { it.severity >= Severity.WARN }) {
            out += Finding(Severity.OK, "Явных отклонений не найдено", "Ключевые показатели в норме", "—", "средняя")
        }
        // Worst first: severity, then how far beyond the norm — fix one, the next comes up.
        return out.sortedWith(compareByDescending<Finding> { it.severity }.thenByDescending { it.score })
    }

    /** «+21.9 %» / «−3.1 %» with a real minus sign. */
    fun pct(v: Double): String = (if (v < 0) "−" else "+") + String.format(Locale.ROOT, "%.1f", abs(v)) + " %"

    private fun parseDtcs(info: String): List<String>? {
        val section = info.substringAfter("В конце сессии", "").ifEmpty { info }
        val line = Regex("Сохранённые \\(режим 03\\):\\s*(.*)").find(section)?.groupValues?.get(1)?.trim() ?: return null
        if (line.startsWith("нет")) return emptyList()
        if (line.startsWith("не поддерж")) return null
        return Regex("[PCBU][0-9A-F]{4}").findAll(line).map { it.value }.toList()
    }

    /** CSV line → fields; only the marker column is ever quoted. */
    fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    fun fmt(v: Double): String = Values.format(BigRound.round(v)) ?: "—"
}

private object BigRound {
    fun round(v: Double): Double = if (abs(v) >= 100) Math.round(v).toDouble() else Math.round(v * 100) / 100.0
}

/** Comparison table across trips, trend per metric and a naive forecast towards known limits. */
object TripComparison {
    /** Limit, direction (+1 = bad when rising) and what happens there. */
    private val LIMITS = mapOf(
        Metric.IDLE_TRIM_B1 to Triple(25.0, +1, "около этого порога ЭБУ обычно записывает P0171"),
        Metric.IDLE_TRIM_B2 to Triple(25.0, +1, "около этого порога ЭБУ обычно записывает P0174"),
        Metric.IDLE_RPM to Triple(Double.NaN, -1, "холостой начнёт проваливаться, риск заглохания"),
        Metric.CHARGE_V to Triple(13.2, -1, "аккумулятор перестанет заряжаться"),
        Metric.COOLANT_MAX to Triple(105.0, +1, "перегрев"),
    )

    enum class Tone { BAD, WARN, OK, NEUTRAL }

    /** One row of the «Сравнение» table. */
    class Row(
        val metric: Metric, val title: String, val code: String, val unit: String,
        val values: List<Double?>, val arrow: String, val verdict: String, val tone: Tone,
        /** How problematic now: beyond the norm in norm widths (+ a bit for getting worse); rows are sorted by it. */
        val problem: Double = 0.0,
        /** Norm band of the metric, for charts; null side = no limit there. */
        val normLo: Double? = null,
        val normHi: Double? = null,
    )

    class Forecast(
        val metric: Metric,
        /** «Если темп сохранится, через ~3–4 поездки …» */
        val text: String,
        val confidence: String,
        val confidenceWhy: String,
        val series: List<Double>,
        val limit: Double,
        val limitLabel: String,
    )

    class Table(val trips: List<TripSummary>, val rows: List<Row>, val forecast: Forecast?)

    private class Spec(val title: String, val code: String, val lo: Double?, val hi: Double?, val bad: Int)

    private val SPECS = linkedMapOf(
        Metric.IDLE_TRIM_B1 to Spec("Коррекция Б1", "ltft+stft_b1", -10.0, 10.0, +1),
        Metric.IDLE_TRIM_B2 to Spec("Коррекция Б2", "ltft+stft_b2", -10.0, 10.0, +1),
        Metric.CRUISE_TRIM_B1 to Spec("Коррекция в движении", "", -10.0, 10.0, +1),
        Metric.IDLE_RPM to Spec("Обороты ХХ", "rpm", 600.0, 850.0, -1),
        Metric.RPM_DIPS to Spec("Провалы оборотов", "", null, 1.0, +1),
        Metric.IDLE_REAR_O2 to Spec("Лямбда Б1 после кат.", "o2_b1s2", 0.45, null, -1),
        Metric.CHARGE_V to Spec("Напряжение, мотор работает", "battery_v", 13.5, 14.8, -1),
        Metric.COOLANT_MAX to Spec("Температура ОЖ, максимум", "coolant_c", null, 104.0, +1),
    )

    /**
     * Ordering key of a row: the latest value's distance outside the norm, in norm
     * widths, plus a small bonus when it moved the bad way. In-norm rows score ≤ 0.1,
     * rows without enough data go last.
     */
    private fun problem(spec: Spec, metric: Metric, v: List<Double>): Double {
        if (v.isEmpty()) return -1.0
        val last = v.last()
        val width = when {
            spec.lo != null && spec.hi != null -> spec.hi - spec.lo
            metric == Metric.RPM_DIPS -> 2.0
            else -> 0.3 * abs(spec.lo ?: spec.hi ?: 1.0)
        }.coerceAtLeast(1e-6)
        val beyond = when {
            spec.lo != null && last < spec.lo -> (spec.lo - last) / width
            spec.hi != null && last > spec.hi -> (last - spec.hi) / width
            else -> 0.0
        }
        val worse = if (v.size >= 2) {
            val d = last - v[v.size - 2]
            val bad = if (spec.lo != null && spec.hi != null) Math.signum(last) * d else spec.bad * d
            if (bad > 0) 0.1 else 0.0
        } else 0.0
        return (if (beyond > 0) 1 + beyond else 0.0) + worse - (if (v.size < 2) 0.5 else 0.0)
    }

    private fun out(spec: Spec, v: Double) = (spec.lo != null && v < spec.lo) || (spec.hi != null && v > spec.hi)

    /** Structured comparison of the last [count] trips for the trips screen and the overview. */
    fun table(trips: List<TripSummary>, count: Int = 3): Table {
        val shown = trips.sortedBy { it.start }.takeLast(count)
        val dates = java.time.format.DateTimeFormatter.ofPattern("dd.MM")
        val rows = SPECS.mapNotNull { (metric, spec) ->
            val values = shown.map { it.metrics[metric] }
            if (values.all { it == null }) return@mapNotNull null
            val v = values.filterNotNull()
            val last = v.last()
            val isOut = out(spec, last)
            val (arrow, verdict, tone) = when {
                metric == Metric.RPM_DIPS -> when {
                    last == 0.0 && v.any { it > 0 } -> Triple("↘", "ушли", Tone.OK)
                    v.all { it == 0.0 } -> Triple("→", "нет", Tone.OK)
                    v.size >= 2 && last > v[v.size - 2] -> Triple("↗", "чаще", Tone.WARN)
                    else -> Triple("↘", "есть, реже", Tone.WARN)
                }
                v.size < 2 -> Triple("·", "мало данных", Tone.NEUTRAL)
                else -> {
                    val d = last - v.first()
                    val scale = maxOf(abs(last), abs(v.first()), 1.0)
                    val steady = v.takeLast(3).zipWithNext().all { (a, b) -> (b - a) * Math.signum(d) >= 0 }
                    val outSince = run {
                        var k = values.size - 1
                        while (k > 0 && values[k - 1]?.let { out(spec, it) } == true) k--
                        k
                    }
                    val since = if (outSince == 0) "уже за порогом" else "за порогом с ${shown[outSince].start?.format(dates) ?: "?"}"
                    val badDir = (if (spec.lo != null && spec.hi != null) Math.signum(last) else spec.bad.toDouble()) * Math.signum(d) > 0
                    when {
                        abs(d) / scale < 0.05 -> if (isOut) Triple("→", "за нормой", Tone.WARN) else Triple("→", "стабильно", Tone.OK)
                        !steady -> Triple("↕", "колеблется — тренда нет", if (isOut) Tone.WARN else Tone.NEUTRAL)
                        badDir -> Triple(if (d > 0) "↗" else "↘", if (isOut) "${if (d > 0) "растёт" else "падает"} · $since" else if (d > 0) "растёт" else "падает", if (isOut) Tone.WARN else Tone.NEUTRAL)
                        else -> Triple(if (d > 0) "↗" else "↘", if (isOut) "улучшается, но за нормой" else "улучшается", if (isOut) Tone.WARN else Tone.OK)
                    }
                }
            }
            Row(metric, spec.title, spec.code, metric.unit, values, arrow, verdict, tone, problem(spec, metric, v), spec.lo, spec.hi)
        }.sortedByDescending { it.problem }
        return Table(shown, rows, forecast(trips.sortedBy { it.start }))
    }

    private fun forecast(sorted: List<TripSummary>): Forecast? {
        for ((metric, limit) in LIMITS) {
            val series = sorted.mapNotNull { it.metrics[metric] }.takeLast(6)
            if (series.size < 3) continue
            val (thr0, dir, what) = limit
            val thr = if (thr0.isNaN()) TripAnalyzer.dipBelow(series.take(maxOf(1, series.size - 3)).sorted().let { it[it.size / 2] }) else thr0
            val slope = slope(series)
            val last = series.last()
            if ((last - thr) * dir >= 0 || slope * dir <= 1e-6) continue
            val n = ceil((thr - last) / slope).toInt()
            if (n > 20) continue
            val steady = series.takeLast(3).zipWithNext().all { (a, b) -> (b - a) * dir >= 0 }
            val conf = when {
                series.size >= 6 && steady -> "средняя"
                else -> "низкая"
            }
            val why = "${series.size} ${trips(series.size).let { if (it == "поездку") "поездка" else it }}" +
                (if (!steady) ", тренд неровный" else "")
            val name = when (metric) {
                Metric.IDLE_TRIM_B1 -> "коррекция Б1 на холостом"
                Metric.IDLE_TRIM_B2 -> "коррекция Б2 на холостом"
                Metric.IDLE_RPM -> "обороты холостого"
                Metric.CHARGE_V -> "напряжение зарядки"
                else -> metric.ru.lowercase()
            }
            val unit = if (metric.unit == "%") " %" else " ${metric.unit}"
            val text = "Если темп сохранится, через ~$n–${n + 1} ${trips(n + 1)} $name дойдёт до ~${TripAnalyzer.fmt(thr)}$unit — $what."
            return Forecast(metric, text, conf, why, series, thr, "≈ порог")
        }
        return null
    }

    fun render(trips: List<TripSummary>): String = buildString {
        if (trips.isEmpty()) {
            append("Поездок для сравнения пока нет.")
            return@buildString
        }
        val sorted = trips.sortedBy { it.start }
        val latest = sorted.last()

        appendLine("ПОСЛЕДНЯЯ ПОЕЗДКА ${latest.label} — ${"%.0f".format(latest.durationMin)} мин, строк ${latest.rows}")
        for (f in latest.findings) {
            appendLine("[${f.severity.ru.uppercase()}] ${f.title} (уверенность: ${f.confidence})")
            appendLine("    Признак: ${f.evidence}")
            if (f.advice != "—") appendLine("    Проверить: ${f.advice}")
        }
        appendLine()

        val shown = sorted.takeLast(6)
        val nameW = Metric.entries.maxOf { it.ru.length + it.unit.length + 3 }
        val colW = 13
        appendLine("СРАВНЕНИЕ ПОЕЗДОК (значения в своём режиме работы)")
        append("".padEnd(nameW))
        shown.forEach { append(it.label.padStart(colW)) }
        appendLine("   тренд")
        for (metric in Metric.entries) {
            val values = shown.map { it.metrics[metric] }
            if (values.all { it == null }) continue
            append("${metric.ru}, ${metric.unit}".padEnd(nameW))
            values.forEach { append((it?.let(TripAnalyzer::fmt) ?: "—").padStart(colW)) }
            appendLine("   " + trendArrow(values))
        }
        append("Строк на холостом / в движении".padEnd(nameW))
        shown.forEach { append("${it.modeRows[DriveMode.WARM_IDLE]}/${it.modeRows[DriveMode.CRUISE]}".padStart(colW)) }
        appendLine()
        appendLine()

        appendLine("ПРОГНОЗ (наивная линейная экстраполяция по поездкам; условия поездок разные — ориентир, не диагноз)")
        var any = false
        for ((metric, limit) in LIMITS) {
            val series = sorted.mapNotNull { it.metrics[metric] }
            if (series.size < 3) continue
            any = true
            val slope = slope(series.takeLast(6))
            val last = series.last()
            val (thr0, dir, what) = limit
            val thr = if (thr0.isNaN()) TripAnalyzer.dipBelow(series.take(maxOf(1, series.size - 3)).sorted().let { it[it.size / 2] }) else thr0
            val toward = slope * dir > 0
            // A trend only counts if the last three trips all moved the same way.
            val recent = series.takeLast(3)
            val steady = recent.zipWithNext().all { (a, b) -> (b - a) * dir >= 0 }
            val line = when {
                (last - thr) * dir >= 0 -> "уже за порогом ${TripAnalyzer.fmt(thr)} ${metric.unit}: $what"
                !toward || abs(slope) < 1e-6 -> "стабильно или улучшается (сейчас ${TripAnalyzer.fmt(last)} ${metric.unit})"
                !steady -> "колеблется от поездки к поездке, устойчивого тренда нет (сейчас ${TripAnalyzer.fmt(last)} ${metric.unit})"
                else -> {
                    val n = ceil((thr - last) / slope).toInt()
                    "ухудшается на ${TripAnalyzer.fmt(abs(slope))} ${metric.unit} за поездку; при таком темпе через ~$n ${trips(n)} — $what"
                }
            }
            appendLine("• ${metric.ru}: $line")
        }
        if (!any) appendLine("Нужно минимум 3 поездки с данными на холостом, чтобы оценить тренд.")
    }

    private fun trips(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "поездку"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "поездки"
        else -> "поездок"
    }

    private fun trendArrow(values: List<Double?>): String {
        val v = values.filterNotNull()
        if (v.size < 2) return ""
        val d = v.last() - v[v.size - 2]
        val scale = maxOf(abs(v.last()), 1.0)
        return when {
            abs(d) / scale < 0.03 -> "→"
            d > 0 -> "↑ " + TripAnalyzer.fmt(d)
            else -> "↓ " + TripAnalyzer.fmt(d)
        }
    }

    /** Least-squares slope per trip index. */
    fun slope(v: List<Double>): Double {
        val n = v.size
        if (n < 2) return 0.0
        val mx = (n - 1) / 2.0
        val my = v.average()
        var num = 0.0
        var den = 0.0
        v.forEachIndexed { i, y ->
            num += (i - mx) * (y - my)
            den += (i - mx) * (i - mx)
        }
        return num / den
    }
}
