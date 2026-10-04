package com.obdlogger.core

/**
 * «Проверочный лог»: a fixed test on a parked, warm engine so that logs before and
 * after a repair compare honestly. Warm idle 2:00 → hold 2500 rpm 1:00 → idle 1:00.
 * The timer of a step runs only while rpm is inside its corridor.
 *
 * Pure state machine: feed it live readings with [update]; the app shows [State].
 */
class CheckTest(private val startMs: Long) {
    class Step(val n: Int, val title: String, val durationSec: Int, val lo: Int, val hi: Int, val hint: String)

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
        /** CSV marker for the current row: TEST1…TEST3. */
        val marker: String? = null,
    )

    private val done = DoubleArray(STEPS.size)
    private var index = 0
    private var lastMs = startMs
    private var lastDataMs = startMs
    private var phase = Phase.RUNNING
    private var reason: String? = null
    var endMs: Long = 0
        private set

    /** Feed the latest readings; [rpm] null when the ECU did not answer this cycle. */
    fun update(nowMs: Long, rpm: Double?, speed: Double?): State {
        if (phase == Phase.RUNNING) {
            val dt = ((nowMs - lastMs) / 1000.0).coerceIn(0.0, 5.0)
            lastMs = nowMs
            if (rpm != null) lastDataMs = nowMs
            when {
                speed != null && speed > 3 -> abort(nowMs, "Машина тронулась — тест идёт только на стоянке.")
                rpm != null && rpm < 300 -> abort(nowMs, "Двигатель заглох или заглушён.")
                nowMs - lastDataMs > 20_000 -> abort(nowMs, "Нет данных от ЭБУ больше 20 секунд.")
                rpm != null -> {
                    val st = STEPS[index]
                    if (rpm >= st.lo && rpm <= st.hi) done[index] += dt
                    if (done[index] >= st.durationSec) {
                        done[index] = st.durationSec.toDouble()
                        if (index == STEPS.lastIndex) {
                            phase = Phase.DONE
                            endMs = nowMs
                        } else index++
                    }
                }
            }
        }
        return state(rpm)
    }

    fun stop(nowMs: Long) = abort(nowMs, "Тест остановлен.")

    private fun abort(nowMs: Long, why: String) {
        if (phase != Phase.RUNNING) return
        phase = Phase.ABORTED
        reason = why
        endMs = nowMs
    }

    private fun state(rpm: Double?): State {
        val st = STEPS[index]
        val inside = rpm != null && rpm >= st.lo && rpm <= st.hi
        val off = when {
            rpm == null || inside -> null
            rpm < st.lo -> "ниже"
            else -> "выше"
        }
        val left = (st.durationSec - done[index]).toInt().coerceAtLeast(0)
        val total = STEPS.indices.sumOf { (STEPS[it].durationSec - done[it]).toInt().coerceAtLeast(0) }
        return State(phase, st, left, STEPS.indices.map { done[it] / STEPS[it].durationSec }, rpm, inside, off, total, reason,
            if (phase == Phase.RUNNING) "TEST${st.n}" else null)
    }

    companion object {
        val STEPS = listOf(
            Step(1, "Прогретый холостой", 120, 550, 1100, "Не трогайте педаль. Свет и кондиционер можно оставить как обычно."),
            Step(2, "Держите 2500 об/мин", 60, 2300, 2700, "Плавно, без перегазовок. Когда таймер дойдёт до нуля — отпустите педаль."),
            Step(3, "Холостой", 60, 550, 1100, "Отпустите педаль и ничего не трогайте до конца теста."),
        )
        const val TOTAL_SEC = 240

        /** Coolant needed to start: the engine must be warm. */
        const val WARM_C = 75.0

        /** Why the test cannot start now, or null. */
        fun blocker(rpm: Double?, speed: Double?, coolant: Double?): String? = when {
            rpm == null || rpm < 300 -> "Заведите двигатель."
            speed != null && speed > 0.5 -> "Остановитесь: тест проводится на стоянке."
            coolant == null -> "Нет данных о температуре мотора — подождите немного."
            coolant < WARM_C -> "Мотор ещё холодный (${coolant.toInt()} °C). Нужно от ${WARM_C.toInt()} °C."
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
) {
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
            return CheckResult(name, t.start,
                med("trim_b1", "TEST1", "TEST3"), med("trim_b1", "TEST2"), med("trim_b2", "TEST1", "TEST3"),
                med("rpm", "TEST1", "TEST3"), med("o2_b1s2_v", "TEST1", "TEST3"), med("o2_b1s2_v", "TEST2"),
                med("maf_gs", "TEST1", "TEST3"))
        }
    }
}
