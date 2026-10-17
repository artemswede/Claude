package com.obdlogger.core

/**
 * Rotating diagnostic overview: cycles through every sensor the car reports,
 * then cross-references interesting pairs. Each step lasts [STEP_SEC] seconds.
 * Mode-aware: at idle rotates individual sensors with diagnostic labels,
 * while driving shows a generic message, during warmup — warmup message.
 */
object DiagCycle {
    const val STEP_SEC = 50

    private val ORDER = listOf(
        "rpm", "coolant_c", "battery_v", "trim_b1", "trim_b2",
        "o2_b1s1_v", "o2_b1s2_v", "o2_b2s1_v", "o2_b2s2_v",
        "maf_gs", "map_kpa", "timing_deg", "engine_load_pct",
        "throttle_pct", "intake_air_c", "fuel_rate_lh",
        "stft_b1_pct", "ltft_b1_pct", "stft_b2_pct", "ltft_b2_pct",
        "fuel_rail_kpa", "fuel_rail_rel_kpa", "fuel_pressure_kpa",
        "egr_cmd_pct", "egr_error_pct", "evap_purge_pct",
        "oil_c", "ambient_c", "baro_kpa",
        "abs_load_pct", "cmd_throttle_pct", "rel_throttle_pct",
        "pedal_d_pct", "pedal_e_pct", "throttle_b_pct",
        "fuel_level_pct", "cmd_lambda",
    )

    /** Sensors that only make diagnostic sense at idle. */
    private val IDLE_ONLY = setOf(
        "rpm", "throttle_pct", "cmd_throttle_pct", "rel_throttle_pct",
        "pedal_d_pct", "pedal_e_pct", "throttle_b_pct",
    )

    /** What the diagnostic check actually looks for per sensor at idle. */
    private val IDLE_DETAIL = mapOf(
        "rpm" to "стабильность оборотов",
        "coolant_c" to "скорость прогрева",
        "battery_v" to "напряжение зарядки",
        "trim_b1" to "отклонение коррекции Б1",
        "trim_b2" to "отклонение коррекции Б2",
        "o2_b1s1_v" to "работу передней лямбды",
        "o2_b1s2_v" to "работу задней лямбды",
        "o2_b2s1_v" to "работу лямбды Б2 до кат.",
        "o2_b2s2_v" to "работу лямбды Б2 после кат.",
        "maf_gs" to "расход воздуха на ХХ",
        "map_kpa" to "разрежение во впуске",
        "timing_deg" to "стабильность зажигания",
        "engine_load_pct" to "нагрузку на ХХ",
        "throttle_pct" to "положение дросселя",
        "stft_b1_pct" to "краткосрочную коррекцию Б1",
        "ltft_b1_pct" to "долгосрочную коррекцию Б1",
        "stft_b2_pct" to "краткосрочную коррекцию Б2",
        "ltft_b2_pct" to "долгосрочную коррекцию Б2",
        "egr_cmd_pct" to "работу EGR",
        "egr_error_pct" to "ошибку EGR",
        "evap_purge_pct" to "продувку адсорбера",
        "fuel_rate_lh" to "расход топлива на ХХ",
    )

    /** Sensors meaningful while driving — focus rotates among these. */
    private val DRIVE_FOCUS = listOf(
        "trim_b1", "trim_b2", "battery_v",
        "o2_b1s1_v", "o2_b1s2_v",
        "engine_load_pct", "maf_gs",
    )

    /** Cross-refs that make sense while driving. */
    private val DRIVE_CROSS_REFS = listOf(
        CrossRef("engine_load_pct", "trim_b1", "нагрузку и коррекцию"),
        CrossRef("rpm", "battery_v", "обороты и напряжение"),
        CrossRef("rpm", "maf_gs", "обороты и расход воздуха"),
    )

    class CrossRef(val a: String, val b: String, val label: String)

    private val CROSS_REFS = listOf(
        CrossRef("rpm", "trim_b1", "обороты и коррекцию смеси"),
        CrossRef("rpm", "battery_v", "обороты и напряжение"),
        CrossRef("rpm", "maf_gs", "обороты и расход воздуха"),
        CrossRef("coolant_c", "trim_b1", "прогрев и коррекцию"),
        CrossRef("coolant_c", "rpm", "прогрев и обороты"),
        CrossRef("battery_v", "rpm", "зарядку и обороты"),
        CrossRef("throttle_pct", "rpm", "дроссель и обороты"),
        CrossRef("map_kpa", "rpm", "давление впуска и обороты"),
        CrossRef("timing_deg", "rpm", "зажигание и обороты"),
        CrossRef("o2_b1s1_v", "o2_b1s2_v", "лямбды до и после катализатора"),
        CrossRef("maf_gs", "throttle_pct", "расход воздуха и дроссель"),
        CrossRef("engine_load_pct", "trim_b1", "нагрузку и коррекцию"),
    )

    class Step(
        val focus: String,
        val label: String,
        val crossRef: CrossRef? = null,
    )

    fun step(elapsedSec: Int, available: List<String>, mode: LiveMode = LiveMode.IDLE): Step {
        if (mode == LiveMode.OFF) return Step("rpm", "")

        if (mode == LiveMode.COLD) {
            val focus = listOf("coolant_c", "rpm", "battery_v").firstOrNull { it in available } ?: "rpm"
            return Step(focus, "Прогрев — записываю данные")
        }

        if (mode == LiveMode.DRIVE) return driveStep(elapsedSec, available)

        return idleStep(elapsedSec, available)
    }

    private fun idleStep(elapsedSec: Int, available: List<String>): Step {
        val sensors = ORDER.filter { it in available } +
            available.filter { it !in ORDER && it != "speed_kmh" && it != "marker" && it != "run_time_s" }
        if (sensors.isEmpty()) return Step("rpm", "Собираю данные")

        val refs = CROSS_REFS.filter { it.a in available && it.b in available }
        val totalSteps = sensors.size + refs.size
        if (totalSteps == 0) return Step(sensors.first(), SensorNames.label(sensors.first()))

        val idx = (elapsedSec / STEP_SEC) % totalSteps
        return if (idx < sensors.size) {
            val code = sensors[idx]
            val detail = IDLE_DETAIL[code]
            val label = if (detail != null) detail else SensorNames.label(code)
            Step(code, label)
        } else {
            val ref = refs[idx - sensors.size]
            Step(ref.a, "${SensorNames.label(ref.a)} и ${SensorNames.label(ref.b)}", ref)
        }
    }

    private fun driveStep(elapsedSec: Int, available: List<String>): Step {
        val driveFocus = DRIVE_FOCUS.filter { it in available }
        val driveRefs = DRIVE_CROSS_REFS.filter { it.a in available && it.b in available }

        if (driveFocus.isEmpty() && driveRefs.isEmpty()) {
            val fallback = listOf("trim_b1", "rpm").firstOrNull { it in available } ?: "rpm"
            return Step(fallback, "Записываю данные движения")
        }

        val total = driveFocus.size + driveRefs.size
        val idx = (elapsedSec / STEP_SEC) % total

        return if (idx < driveFocus.size) {
            Step(driveFocus[idx], "Записываю данные движения")
        } else {
            val ref = driveRefs[idx - driveFocus.size]
            Step(ref.a, ref.label, ref)
        }
    }
}
