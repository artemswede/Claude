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
    RPM_DIPS("Провалы оборотов ниже 560 на холостом", "раз"),
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
)

/** Per-row series of a trip for the main-screen chart. */
class TripTrace(
    val minutes: DoubleArray,
    /** LTFT+STFT bank 1, NaN where unknown. */
    val trimB1: DoubleArray,
    val modes: Array<DriveMode?>,
    val start: LocalDateTime?,
)

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

    /** Slow columns (polled every 5th cycle) are reused for this long. */
    private const val CARRY_MS = 30_000L
    private const val WARM_C = 70.0
    private const val DIP_RPM = 560.0
    private const val MIN_SAMPLES = 5

    fun analyze(name: String, csv: String, info: String? = null): TripSummary? {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return null
        val header = splitCsv(lines[0])
        val col = header.withIndex().associate { (i, h) -> h to i }
        val timeIdx = col["time"] ?: return null
        val rows = lines.drop(1).map { splitCsv(it) }
        val times = rows.map { r -> r.getOrNull(timeIdx)?.let { runCatching { LocalDateTime.parse(it, TIME) }.getOrNull() } }
        val ms = times.map { t -> t?.let { Duration.between(times.firstNotNullOfOrNull { x -> x } ?: it, it).toMillis() } ?: 0L }

        fun raw(name: String): List<Double?> {
            val i = col[name] ?: return List(rows.size) { null }
            return rows.map { it.getOrNull(i)?.toDoubleOrNull() }
        }

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
                } else if (last != null && ms[i] - lastMs <= CARRY_MS) last else null
            }
        }

        val rpm = raw("rpm")
        val speed = raw("speed_kmh")
        val throttle = raw("throttle_pct")
        val load = raw("engine_load_pct")
        val coolant = carried("coolant_c")
        val running = rpm.map { it != null && it > 250 }

        // Closed-throttle position differs per car; take a low percentile of the trip.
        val thr = throttle.filterIndexed { i, t -> t != null && running[i] }.filterNotNull().sorted()
        val closedThr = if (thr.isEmpty()) 0.0 else thr[(thr.size * 0.05).toInt()] + 1.0
        fun closed(i: Int) = throttle[i]?.let { it <= closedThr } ?: false

        val modes = rows.indices.map { i ->
            val r = rpm[i] ?: return@map null
            if (!running[i]) return@map null
            val c = coolant[i]
            val s = speed[i] ?: 0.0
            when {
                (throttle[i] ?: 0.0) > 50 || (load[i] ?: 0.0) > 75 -> DriveMode.HEAVY
                c != null && c < WARM_C -> DriveMode.COLD
                c == null -> null
                s == 0.0 && closed(i) && r < 1100 -> DriveMode.WARM_IDLE
                s in 0.5..20.0 && closed(i) -> DriveMode.ROLL_TO_STOP
                s >= 40 && !closed(i) && (load[i] ?: 0.0) in 15.0..70.0 -> DriveMode.CRUISE
                else -> null
            }
        }

        fun inMode(values: List<Double?>, vararg m: DriveMode) =
            values.filterIndexed { i, v -> v != null && modes[i] in m }.filterNotNull()

        fun median(v: List<Double>): Double? =
            if (v.size < MIN_SAMPLES) null else v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

        fun totalTrim(bank: Int): List<Double?> {
            val st = raw("stft_b${bank}_pct")
            val lt = carried("ltft_b${bank}_pct")
            return st.indices.map { i -> st[i]?.let { s -> lt[i]?.let { s + it } } }
        }

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

        // Dips: warm engine, closed throttle, (almost) standing, rpm below 560 — counted as events.
        var dips = 0
        var inDip = false
        for (i in rows.indices) {
            val r = rpm[i]
            val dip = r != null && r in 250.0..DIP_RPM && (coolant[i] ?: 0.0) >= WARM_C && closed(i) && (speed[i] ?: 0.0) <= 20
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
        )
        // Rows are ~3 s apart on K-line; count time, not rows.
        var warmIdleMs = 0L
        for (i in 1 until rows.size) if (modes[i] == DriveMode.WARM_IDLE) warmIdleMs += (ms[i] - ms[i - 1]).coerceIn(0L, 10_000L)
        return TripSummary(name, times.firstNotNullOfOrNull { it }, duration, rows.size, modeRows, metrics, dtcs,
            findings(metrics, modeRows, dtcs, coolantPeak, duration), trace, warmIdleMs / 1000.0)
    }

    private fun findings(
        m: Map<Metric, Double>, modeRows: Map<DriveMode, Int>, dtcs: List<String>?,
        coolantPeak: Double? = null, durationMin: Double = 0.0,
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        fun f(v: Double) = String.format(Locale.ROOT, "%+.1f", v)

        if (!dtcs.isNullOrEmpty()) {
            out += Finding(Severity.BAD, "Коды неисправностей: ${dtcs.joinToString(", ")}",
                "ЭБУ сообщает сохранённые коды", "Расшифровать коды для этого двигателя", "высокая",
                headline = "Блок записал коды: ${dtcs.joinToString(", ")}")
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
                urgency = "Не критично для поездки. Расход выше обычного — проверьте в ближайшие дни.",
                why = listOfNotNull(Evidence("ХХ: ЭБУ убирает топливо", pct(idleMax), deviating = true),
                    cruiseMax?.let { Evidence("В движении", pct(it), deviating = abs(it) > 10) }))
        }
        if (cruiseMax != null && cruiseMax < -10) {
            out += Finding(Severity.WARN, "Богатая смесь в движении", "Коррекция в движении ${cruise.joinToString(" / ") { f(it) + " %" }}",
                "ДМРВ завышает, давление топлива, форсунки", "средняя")
        }

        val dips = m[Metric.RPM_DIPS] ?: 0.0
        val idleRpm = m[Metric.IDLE_RPM]
        if (dips >= 2) {
            out += Finding(if (dips >= 5) Severity.BAD else Severity.WARN, "Провалы холостого хода",
                "${dips.toInt()} раз обороты опускались ниже ${DIP_RPM.toInt()} при прогретом моторе и закрытом дросселе",
                "Нагар на дросселе и в EGR (для D-4 типично), подсос воздуха, слабый аккумулятор. Чистка дросселя/EGR, затем обучение холостого", "высокая",
                urgency = "Мотор может заглохнуть на остановке. Проверьте в ближайшие дни.",
                why = listOfNotNull(
                    Evidence("Провалы ниже ${DIP_RPM.toInt()} об/мин", "${dips.toInt()} раз", deviating = true),
                    idleRpm?.let { Evidence("Обороты холостого, медиана", "${it.toInt()}") },
                ))
        } else if (idleRpm != null && idleRpm < 620) {
            out += Finding(Severity.WATCH, "Низкие обороты холостого", "Медиана ${idleRpm.toInt()} об/мин",
                "Чистка дросселя, проверка подсоса воздуха", "средняя")
        }

        val rearIdle = m[Metric.IDLE_REAR_O2]
        val rearCruise = m[Metric.CRUISE_REAR_O2]
        if (rearIdle != null && rearIdle < 0.15 && (rearCruise == null || rearCruise > 0.45)) {
            out += Finding(Severity.WATCH, "Задняя лямбда на холостом показывает «бедно»",
                "${fmt(rearIdle)} В на холостом" + (rearCruise?.let { ", ${fmt(it)} В в движении" } ?: ""),
                "Подтверждает бедную смесь на холостом (если коррекции тоже высокие) либо подсос воздуха в выпуске", "средняя")
        } else if (rearCruise != null && rearCruise < 0.2 && (modeRows[DriveMode.CRUISE] ?: 0) >= 30) {
            out += Finding(Severity.WATCH, "Задняя лямбда почти всё время около нуля",
                "${fmt(rearCruise)} В при равномерном движении", "Подсос в выпуске перед датчиком, старый датчик или бедная смесь", "низкая")
        }

        val volts = m[Metric.CHARGE_V]
        val vMin = m[Metric.CHARGE_V_MIN]
        if (volts != null && volts < 13.4) {
            out += Finding(Severity.WARN, "Слабая зарядка", "Медиана ${fmt(volts)} В на работающем моторе",
                "Генератор, регулятор напряжения, ремень, клеммы", "средняя",
                urgency = "Проверьте зарядку до дальней поездки.",
                why = listOfNotNull(Evidence("Напряжение на работающем моторе", "${fmt(volts)} В", deviating = true),
                    vMin?.let { Evidence("Минимум", "${fmt(it)} В", deviating = it < 12.8) }))
        } else if (vMin != null && vMin < 12.8) {
            out += Finding(Severity.WATCH, "Просадки напряжения", "Минимум ${fmt(vMin)} В на работающем моторе",
                "Ремень генератора, клеммы, аккумулятор; совпадает ли с провалами оборотов", "низкая")
        }

        val coolantMax = m[Metric.COOLANT_MAX]
        if (coolantMax != null && coolantMax > 104) {
            out += Finding(Severity.BAD, "Перегрев", "Температура ОЖ до ${coolantMax.toInt()} °C",
                "Вентилятор радиатора, уровень ОЖ, термостат, помпа", "высокая",
                urgency = "Остановитесь, дайте мотору остыть и проверьте уровень жидкости.",
                why = listOf(Evidence("Температура ОЖ, максимум", "${coolantMax.toInt()} °C", deviating = true)))
        } else if (coolantMax == null && coolantPeak != null && coolantPeak < 75 && durationMin >= 15) {
            out += Finding(Severity.WARN, "Мотор не прогревается", "Максимум ${coolantPeak.toInt()} °C за ${durationMin.toInt()} мин",
                "Термостат открыт постоянно или датчик температуры", "средняя",
                headline = "Мотор не прогревается",
                urgency = "Ехать можно. Расход и износ выше, печка греет хуже — проверьте термостат.",
                why = listOf(Evidence("Температура ОЖ, максимум", "${coolantPeak.toInt()} °C", deviating = true),
                    Evidence("Норма для прогретого мотора", "80–100 °C")))
        }

        if (out.none { it.severity >= Severity.WARN }) {
            out += Finding(Severity.OK, "Явных отклонений не найдено", "Ключевые показатели в норме", "—", "средняя")
        }
        return out.sortedByDescending { it.severity }
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
        Metric.IDLE_TRIM_B1 to Triple(25.0, +1, "ЭБУ, вероятно, запишет ошибку бедной смеси P0171"),
        Metric.IDLE_TRIM_B2 to Triple(25.0, +1, "ЭБУ, вероятно, запишет ошибку бедной смеси P0174"),
        Metric.IDLE_RPM to Triple(560.0, -1, "холостой начнёт проваливаться, риск заглохания"),
        Metric.CHARGE_V to Triple(13.2, -1, "аккумулятор перестанет заряжаться"),
        Metric.COOLANT_MAX to Triple(105.0, +1, "перегрев"),
    )

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
            val (thr, dir, what) = limit
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
