package com.obdlogger.core

import kotlin.math.abs

/**
 * A short targeted check («Проверки»): idle, mixture under load, charging, warm-up.
 * Each is a few steps with an rpm corridor (or a coolant target for the warm-up);
 * the timer of a step runs only while the engine is inside its corridor, so checks
 * before and after a repair compare honestly.
 */
enum class CheckKind(
    val id: String,
    val title: String,
    /** One line for the menu: what it shows and how long. */
    val short: String,
    val steps: List<CheckTest.Step>,
    /** The car must stand still (a warm-up may be driven). */
    val parked: Boolean = true,
    /** Coolant needed to start: warm (≥ [CheckTest.WARM_C]), cold (< [CheckTest.COLD_C]) or any. */
    val needWarm: Boolean? = true,
) {
    IDLE("холостой", "Холостой ход", "2 мин стоя на прогретом: обороты, их стабильность, коррекции, расход воздуха", listOf(
        CheckTest.Step(1, "Прогретый холостой", 120, 450, 1300, "Не трогайте педаль. Потребители — как обычно."),
    )),
    MIXTURE("смесь", "Смесь под нагрузкой", "4 мин стоя: ХХ → 2500 об/мин → ХХ — подсос воздуха против нехватки топлива", listOf(
        CheckTest.Step(1, "Прогретый холостой", 120, 450, 1300, "Не трогайте педаль. Свет и кондиционер можно оставить как обычно."),
        CheckTest.Step(2, "Держите 2500 об/мин", 60, 2300, 2700, "Плавно, без перегазовок. Когда таймер дойдёт до нуля — отпустите педаль."),
        CheckTest.Step(3, "Холостой", 60, 450, 1300, "Отпустите педаль и ничего не трогайте до конца проверки."),
    )),
    CHARGE("зарядка", "Зарядка", "2 мин стоя: напряжение без нагрузки и с фарами, печкой и обогревом", listOf(
        CheckTest.Step(1, "Холостой, потребители выключены", 60, 450, 1300, "Выключите фары, печку, обогрев стекла и кондиционер."),
        CheckTest.Step(2, "Включите фары, печку и обогрев", 60, 450, 1300, "Дальний свет, вентилятор печки на максимум, обогрев заднего стекла."),
    ), needWarm = null),
    WARMUP("прогрев", "Прогрев", "от холодного пуска до 80 °C: скорость прогрева и термостат; можно ехать", listOf(
        CheckTest.Step(1, "Прогрев до ${CheckTest.WARM_TARGET_C.toInt()} °C", CheckTest.WARMUP_MAX_SEC, 300, 9000,
            "Заведите холодный мотор и прогревайте как обычно — можно ехать. Бортач засечёт время.", untilCoolant = CheckTest.WARM_TARGET_C),
    ), parked = false, needWarm = false);

    companion object {
        /** The check that answers a version, if one does; null — an ordinary trip says enough. */
        fun forFinding(kind: String): CheckKind? = when (kind) {
            "rich_idle", "rich_cruise", "lean_idle", "lean_cruise", "rear_o2" -> MIXTURE
            "dips", "low_rpm" -> IDLE
            "weak_charge", "voltage_dips" -> CHARGE
            "cold_engine", "overheat" -> WARMUP
            else -> if (kind.startsWith("lean") || kind.startsWith("rich")) MIXTURE else null
        }

        /** From a chat tag or a marker: «смесь», «зарядка», MIXTURE… */
        fun of(s: String): CheckKind? = entries.firstOrNull { it.id.equals(s.trim(), true) || it.name.equals(s.trim(), true) || it.title.equals(s.trim(), true) }
    }
}

class CheckTest(private val startMs: Long, val kind: CheckKind = CheckKind.MIXTURE) {
    class Step(
        val n: Int, val title: String, val durationSec: Int, val lo: Int, val hi: Int, val hint: String,
        /** A warm-up step ends when coolant reaches this, not by its timer ([durationSec] is the limit). */
        val untilCoolant: Double? = null,
    )

    enum class Phase { RUNNING, DONE, ABORTED }

    class State(
        val phase: Phase,
        val step: Step,
        /** Seconds left on the current step. */
        val leftSec: Int,
        /** Progress of each step, 0–1. */
        val progress: List<Double>,
        val rpm: Double?,
        /** Rpm is inside the step corridor (the timer runs). */
        val inCorridor: Boolean,
        /** «ниже», «выше» or null. */
        val off: String?,
        val totalLeftSec: Int,
        val abortReason: String? = null,
        /** CSV marker for the current row: «TEST1 смесь»… */
        val marker: String? = null,
        val kind: CheckKind = CheckKind.MIXTURE,
        val coolant: Double? = null,
    )

    private val steps = kind.steps
    private val done = DoubleArray(steps.size)
    private var index = 0
    private var lastMs = startMs
    private var lastDataMs = startMs
    private var phase = Phase.RUNNING
    private var reason: String? = null
    private var coolant: Double? = null
    private var coolantStart: Double? = null
    var endMs: Long = 0
        private set

    /** Feed the latest readings; [rpm] null when the ECU did not answer this cycle. */
    fun update(nowMs: Long, rpm: Double?, speed: Double?, coolant: Double? = null): State {
        if (coolant != null) {
            this.coolant = coolant
            if (coolantStart == null) coolantStart = coolant
        }
        if (phase == Phase.RUNNING) {
            val dt = ((nowMs - lastMs) / 1000.0).coerceIn(0.0, 5.0)
            lastMs = nowMs
            if (rpm != null) lastDataMs = nowMs
            val st = steps[index]
            when {
                kind.parked && speed != null && speed > 3 -> abort(nowMs, "Машина тронулась — эта проверка идёт только на стоянке.")
                rpm != null && rpm < 300 -> abort(nowMs, "Двигатель заглох или заглушён.")
                nowMs - lastDataMs > 20_000 -> abort(nowMs, "Нет данных от ЭБУ больше 20 секунд.")
                st.untilCoolant != null -> {
                    done[index] += dt
                    val c = this.coolant
                    if ((c != null && c >= st.untilCoolant) || done[index] >= st.durationSec) finish(nowMs)
                }
                rpm != null -> {
                    if (rpm >= st.lo && rpm <= st.hi) done[index] += dt
                    if (done[index] >= st.durationSec) {
                        done[index] = st.durationSec.toDouble()
                        if (index == steps.lastIndex) finish(nowMs) else index++
                    }
                }
            }
        }
        return state(rpm)
    }

    private fun finish(nowMs: Long) {
        phase = Phase.DONE
        endMs = nowMs
    }

    fun stop(nowMs: Long) = abort(nowMs, "Проверка остановлена.")

    private fun abort(nowMs: Long, why: String) {
        if (phase != Phase.RUNNING) return
        phase = Phase.ABORTED
        reason = why
        endMs = nowMs
    }

    private fun progress(i: Int): Double {
        val st = steps[i]
        val target = st.untilCoolant ?: return done[i] / st.durationSec
        if (phase == Phase.DONE) return 1.0
        val c0 = coolantStart ?: return 0.0
        val c = coolant ?: return 0.0
        return ((c - c0) / (target - c0).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
    }

    private fun state(rpm: Double?): State {
        val st = steps[index]
        val warm = st.untilCoolant != null
        val inside = rpm != null && (warm || (rpm >= st.lo && rpm <= st.hi))
        val off = when {
            rpm == null || inside -> null
            rpm < st.lo -> "ниже"
            else -> "выше"
        }
        val left = (st.durationSec - done[index]).toInt().coerceAtLeast(0)
        val total = steps.indices.sumOf { (steps[it].durationSec - done[it]).toInt().coerceAtLeast(0) }
        return State(phase, st, left, steps.indices.map { progress(it) }, rpm, inside, off, total, reason,
            if (phase == Phase.RUNNING) "TEST${st.n} ${kind.id}" else null, kind, coolant)
    }

    companion object {
        /** The mixture check (the former «проверочный лог»). */
        val STEPS get() = CheckKind.MIXTURE.steps
        const val TOTAL_SEC = 240

        /** Coolant needed to start a warm check: the engine must be warm. */
        const val WARM_C = 75.0
        /** A warm-up check starts from a cold engine… */
        const val COLD_C = 50.0
        /** …and ends here. */
        const val WARM_TARGET_C = 80.0
        const val WARMUP_MAX_SEC = 25 * 60

        /** Why the [kind] check cannot start now, or null. */
        fun blocker(rpm: Double?, speed: Double?, coolant: Double?, kind: CheckKind = CheckKind.MIXTURE): String? = when {
            rpm == null || rpm < 300 -> if (kind == CheckKind.WARMUP) "Заведите холодный двигатель." else "Заведите двигатель."
            kind.parked && speed != null && speed > 0.5 -> "Остановитесь: проверка проводится на стоянке."
            kind.needWarm != null && coolant == null -> "Нет данных о температуре мотора — подождите немного."
            kind.needWarm == true && coolant!! < WARM_C -> "Мотор ещё холодный (${coolant.toInt()} °C). Нужно от ${WARM_C.toInt()} °C."
            kind.needWarm == false && coolant!! >= COLD_C -> "Мотор уже тёплый (${coolant.toInt()} °C). Проверка прогрева — с холодного пуска, ниже ${COLD_C.toInt()} °C."
            else -> null
        }
    }
}

/** Figures of one finished check log by step, for the result screen and for comparing «до / после». */
class CheckResult(
    val name: String,
    val start: java.time.LocalDateTime?,
    /** Median total trim B1 on warm idle (steps 1 and 3) and at 2500. */
    val idleTrim: Double?,
    val revTrim: Double?,
    val idleTrimB2: Double?,
    val idleRpm: Double?,
    val rearO2Idle: Double?,
    val rearO2Rev: Double?,
    val idleMaf: Double?,
    val kind: CheckKind = CheckKind.MIXTURE,
    /** Charging: median voltage without and with the loads on. */
    val voltIdle: Double? = null,
    val voltLoad: Double? = null,
    /** Warm-up: minutes to [CheckTest.WARM_TARGET_C] (null — did not get there), start and end coolant. */
    val warmMin: Double? = null,
    val coolantStart: Double? = null,
    val coolantEnd: Double? = null,
    /** Idle: rpm spread p10…p90 — how steady it is. */
    val idleRpmSpread: Double? = null,
) {
    /** The figures as one line (for the chat and the result screen). */
    fun summary(): String = listOfNotNull(
        idleRpm?.let { "обороты ХХ ${it.toInt()}" },
        idleRpmSpread?.let { "разброс оборотов ${it.toInt()}" },
        idleTrim?.let { "коррекция Б1 ХХ ${TripAnalyzer.pct(it)}" },
        revTrim?.let { "Б1 на 2500 ${TripAnalyzer.pct(it)}" },
        idleTrimB2?.let { "Б2 ХХ ${TripAnalyzer.pct(it)}" },
        rearO2Idle?.let { "задняя лямбда ХХ ${TripAnalyzer.fmt(it)} В" },
        idleMaf?.let { "ДМРВ ХХ ${TripAnalyzer.fmt(it)} г/с" },
        voltIdle?.let { "напряжение без нагрузки ${TripAnalyzer.fmt(it)} В" },
        voltLoad?.let { "с фарами, печкой, обогревом ${TripAnalyzer.fmt(it)} В" },
        if (kind == CheckKind.WARMUP) (warmMin?.let { "прогрев до ${CheckTest.WARM_TARGET_C.toInt()} °C за ${TripAnalyzer.fmt(it)} мин" }
            ?: "до ${CheckTest.WARM_TARGET_C.toInt()} °C не прогрелся") else null,
        coolantStart?.takeIf { kind == CheckKind.WARMUP }?.let { "ОЖ ${it.toInt()} → ${coolantEnd?.toInt() ?: "?"} °C" },
    ).joinToString(", ")

    companion object {
        /** From a check CSV whose rows carry TEST1…TEST3 markers. */
        fun of(name: String, csv: String): CheckResult? {
            val t = TripAnalyzer.table(csv) ?: return null
            val mi = t.header.indexOf("marker")
            val mk = t.rows.map { it.getOrNull(mi).orEmpty() }
            fun med(code: String, vararg steps: String): Double? {
                val v = t.raw(code).withIndex().filter { (i, x) -> x != null && steps.any { mk[i].contains(it) } }.map { it.value!! }.sorted()
                return if (v.size < 3) null else v[v.size / 2]
            }
            // Older check logs carry bare TEST1…TEST3: the mixture check.
            val kind = mk.firstNotNullOfOrNull { m -> Regex("TEST\\d (\\S+)").find(m)?.groupValues?.get(1)?.let(CheckKind::of) } ?: CheckKind.MIXTURE
            return when (kind) {
                CheckKind.MIXTURE -> CheckResult(name, t.start,
                    med("trim_b1", "TEST1", "TEST3"), med("trim_b1", "TEST2"), med("trim_b2", "TEST1", "TEST3"),
                    med("rpm", "TEST1", "TEST3"), med("o2_b1s2_v", "TEST1", "TEST3"), med("o2_b1s2_v", "TEST2"),
                    med("maf_gs", "TEST1", "TEST3"), kind)
                CheckKind.IDLE -> {
                    val r = t.raw("rpm").withIndex().filter { (i, x) -> x != null && mk[i].contains("TEST1") }.map { it.value!! }.sorted()
                    CheckResult(name, t.start, med("trim_b1", "TEST1"), null, med("trim_b2", "TEST1"), med("rpm", "TEST1"),
                        med("o2_b1s2_v", "TEST1"), null, med("maf_gs", "TEST1"), kind,
                        idleRpmSpread = if (r.size >= 10) TripProfile.pct(r, 0.9) - TripProfile.pct(r, 0.1) else null)
                }
                CheckKind.CHARGE -> CheckResult(name, t.start, null, null, null, med("rpm", "TEST1"), null, null, null, kind,
                    voltIdle = med("battery_v", "TEST1"), voltLoad = med("battery_v", "TEST2"))
                CheckKind.WARMUP -> {
                    val c = t.carried("coolant_c")
                    val rows = c.indices.filter { mk[it].contains("TEST1") && c[it] != null }
                    val first = rows.firstOrNull()
                    val hit = rows.firstOrNull { c[it]!! >= CheckTest.WARM_TARGET_C }
                    CheckResult(name, t.start, null, null, null, null, null, null, null, kind,
                        warmMin = if (first != null && hit != null) (t.ms[hit] - t.ms[first]) / 60_000.0 else null,
                        coolantStart = first?.let { c[it] }, coolantEnd = rows.lastOrNull()?.let { c[it] })
                }
            }
        }
    }
}

/** The answer of a finished check, first: text, «good», «better than before». */
object CheckVerdict {
    fun of(now: CheckResult, prev: CheckResult?): Triple<String, Boolean, Boolean> = when (now.kind) {
        CheckKind.MIXTURE, CheckKind.IDLE -> mixture(now, prev)
        CheckKind.CHARGE -> charge(now, prev)
        CheckKind.WARMUP -> warmup(now, prev)
    }

    private fun mixture(now: CheckResult, prev: CheckResult?): Triple<String, Boolean, Boolean> {
        val idle = now.idleTrim
        val was = prev?.idleTrim
        val spread = now.idleRpmSpread
        val steady = spread == null || spread <= 120
        val good = idle != null && abs(idle) <= 10 && (now.rearO2Idle ?: 1.0) >= 0.45 && steady
        val better = idle != null && was != null && abs(idle) < abs(was) - 3
        val text = when {
            idle == null && now.kind == CheckKind.IDLE && now.idleRpm != null ->
                if (steady) "Холостой ровный: ${now.idleRpm.toInt()} об/мин, разброс ${spread?.toInt() ?: "—"}." else "Холостой неровный: разброс оборотов ${spread!!.toInt()} об/мин."
            idle == null -> "На холостом не хватило данных о коррекции."
            !steady -> "Холостой неровный: разброс оборотов ${spread!!.toInt()} об/мин; коррекция ${TripAnalyzer.pct(idle)}."
            good && was != null && abs(was) > 10 -> "Ремонт помог: коррекция на ХХ ${TripAnalyzer.pct(was)} → ${TripAnalyzer.pct(idle)}, в норме."
            good -> "Смесь в порядке: коррекция на ХХ ${TripAnalyzer.pct(idle)}, задняя лямбда не падает."
            better -> "Стало лучше (${TripAnalyzer.pct(was!!)} → ${TripAnalyzer.pct(idle)}), но коррекция на ХХ ещё за нормой."
            was != null -> "Без улучшения: коррекция на ХХ ${TripAnalyzer.pct(was)} → ${TripAnalyzer.pct(idle)}, за нормой."
            else -> "Коррекция на ХХ ${TripAnalyzer.pct(idle)} — за нормой."
        }
        return Triple(text, good || (idle == null && now.kind == CheckKind.IDLE && steady && now.idleRpm != null), better)
    }

    private fun charge(now: CheckResult, prev: CheckResult?): Triple<String, Boolean, Boolean> {
        val a = now.voltIdle
        val b = now.voltLoad
        if (a == null || b == null) return Triple("Не хватило данных о напряжении.", false, false)
        val drop = a - b
        val good = a in 13.5..14.8 && b >= 13.2 && drop <= 0.5
        val better = prev?.voltLoad?.let { b > it + 0.2 } == true
        val f = TripAnalyzer::fmt
        val text = when {
            good -> "Зарядка в порядке: ${f(a)} В, с нагрузкой ${f(b)} В."
            a > 14.8 -> "Напряжение высокое (${f(a)} В) — проверьте регулятор генератора."
            a < 13.5 -> "Зарядка слабая уже без нагрузки: ${f(a)} В — генератор, ремень, клеммы."
            else -> "Под нагрузкой напряжение падает до ${f(b)} В (−${f(drop)}) — генератор не держит нагрузку: щётки, диоды, ремень, масса."
        }
        return Triple(text, good, better)
    }

    private fun warmup(now: CheckResult, prev: CheckResult?): Triple<String, Boolean, Boolean> {
        val m = now.warmMin
        val target = CheckTest.WARM_TARGET_C.toInt()
        val better = m != null && prev?.warmMin?.let { m < it - 2 } == true
        return when {
            m == null -> Triple("За ${CheckTest.WARMUP_MAX_SEC / 60} мин ОЖ дошла только до ${now.coolantEnd?.toInt() ?: "?"} °C — термостат, вероятно, открыт постоянно (или датчик ОЖ).", false, false)
            m > 15 -> Triple("Прогрев до $target °C медленный: ${TripAnalyzer.fmt(m)} мин. Проверьте термостат.", false, better)
            else -> Triple("Прогрев нормальный: до $target °C за ${TripAnalyzer.fmt(m)} мин.", true, better)
        }
    }
}
