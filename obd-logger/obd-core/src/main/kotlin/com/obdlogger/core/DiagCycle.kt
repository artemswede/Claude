package com.obdlogger.core

/**
 * Rotating diagnostic overview: cycles through every sensor the car reports,
 * then cross-references interesting pairs. Each step lasts [STEP_SEC] seconds.
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

    fun step(elapsedSec: Int, available: List<String>): Step {
        val sensors = ORDER.filter { it in available } +
            available.filter { it !in ORDER && it != "speed_kmh" && it != "marker" && it != "run_time_s" }
        if (sensors.isEmpty()) return Step("rpm", "Собираю данные")

        val refs = CROSS_REFS.filter { it.a in available && it.b in available }
        val totalSteps = sensors.size + refs.size
        if (totalSteps == 0) return Step(sensors.first(), SensorNames.label(sensors.first()))

        val idx = (elapsedSec / STEP_SEC) % totalSteps
        return if (idx < sensors.size) {
            val code = sensors[idx]
            Step(code, SensorNames.label(code))
        } else {
            val ref = refs[idx - sensors.size]
            Step(ref.a, "${SensorNames.label(ref.a)} и ${SensorNames.label(ref.b)}", ref)
        }
    }
}
