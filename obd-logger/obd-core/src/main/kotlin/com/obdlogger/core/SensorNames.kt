package com.obdlogger.core

/**
 * Short Russian names for CSV column codes, for the UI. Works on any column name,
 * including ones from old recordings and undecoded raw bytes.
 */
object SensorNames {
    private val units: Map<String, String> =
        (Pids.ALL.flatMap { it.columns } + Pids.adapterVoltage.columns).associate { it.name to it.unit }

    private val fixed = mapOf(
        "battery_v" to "Напряжение сети",
        "fuel_system" to "Режим топливной системы",
        "engine_load_pct" to "Нагрузка двигателя",
        "coolant_c" to "Температура ОЖ",
        "fuel_pressure_kpa" to "Давление топлива",
        "map_kpa" to "Давление во впуске",
        "rpm" to "Обороты",
        "speed_kmh" to "Скорость",
        "timing_deg" to "Угол опережения",
        "intake_air_c" to "Температура на впуске",
        "maf_gs" to "Расход воздуха (ДМРВ)",
        "throttle_pct" to "Дроссель",
        "run_time_s" to "Время работы мотора",
        "dist_with_mil_km" to "Пробег с Check Engine",
        "fuel_rail_rel_kpa" to "Давление в рампе (отн.)",
        "fuel_rail_kpa" to "Давление в рампе",
        "egr_cmd_pct" to "EGR: задание",
        "egr_error_pct" to "EGR: ошибка положения",
        "evap_purge_pct" to "Продувка адсорбера",
        "fuel_level_pct" to "Уровень топлива",
        "warmups_since_clear" to "Прогревов после сброса",
        "dist_since_clear_km" to "Пробег после сброса",
        "baro_kpa" to "Атмосферное давление",
        "ecu_voltage_v" to "Напряжение ЭБУ",
        "abs_load_pct" to "Абсолютная нагрузка",
        "cmd_lambda" to "Заданная лямбда",
        "rel_throttle_pct" to "Дроссель (относит.)",
        "ambient_c" to "Температура снаружи",
        "throttle_b_pct" to "Дроссель, датчик B",
        "pedal_d_pct" to "Педаль газа D",
        "pedal_e_pct" to "Педаль газа E",
        "cmd_throttle_pct" to "Дроссель: задание",
        "oil_c" to "Температура масла",
        "fuel_rate_lh" to "Расход топлива",
        "marker" to "Метка",
    )

    private val trim = Regex("^(stft|ltft)_b(\\d)_pct$")
    private val o2 = Regex("^o2_b(\\d)s(\\d)_(v|trim_pct)$")
    private val wide = Regex("^wo2(v?)_b(\\d)s(\\d)_(lambda|v|volt|ma)$")
    private val cat = Regex("^cat_temp_b(\\d)s(\\d)_c$")
    private val rawPid = Regex("^pid01_([0-9A-F]{2})_b(\\d+)$")
    private val mode21 = Regex("^m21_(\\w+?)_([0-9A-F]{2})_b(\\d+)$")

    private fun place(sensor: String) = if (sensor == "1") "до кат." else "после кат."

    /** «Долг. коррекция Б1», «Лямбда Б1 до кат.», «Toyota 21 01 · байт 2»… */
    fun label(code: String): String {
        fixed[code]?.let { return it }
        trim.matchEntire(code)?.let { m ->
            val (kind, bank) = m.destructured
            return (if (kind == "stft") "Кратк. коррекция" else "Долг. коррекция") + " Б$bank"
        }
        o2.matchEntire(code)?.let { m ->
            val (bank, sensor, what) = m.destructured
            return (if (what == "v") "Лямбда" else "Коррекция по лямбде") + " Б$bank ${place(sensor)}"
        }
        wide.matchEntire(code)?.let { m ->
            val (_, bank, sensor, what) = m.destructured
            val w = when (what) {
                "lambda" -> "λ"
                "ma" -> "ток"
                else -> "напряжение"
            }
            return "ШП лямбда Б$bank ${place(sensor)}, $w"
        }
        cat.matchEntire(code)?.let { m ->
            val (bank, sensor) = m.destructured
            return "Температура катализатора Б$bank-$sensor"
        }
        rawPid.matchEntire(code)?.let { m ->
            val (pid, byte) = m.destructured
            val what = when (pid) {
                "01" -> "Статус Check Engine и мониторов"
                "13" -> "Наличие лямбда-зондов"
                "1C" -> "Стандарт OBD"
                "12" -> "Подача вторичного воздуха"
                else -> "PID 01 $pid"
            }
            return "$what · байт ${byte.toInt()}"
        }
        mode21.matchEntire(code)?.let { m ->
            val (_, id, byte) = m.destructured
            return "Toyota 21 $id · байт ${byte.toInt()}"
        }
        return code
    }

    fun unit(code: String): String = units[code] ?: if (rawPid.matches(code) || mode21.matches(code)) "" else ""

    /** «Обороты (rpm)» — name with the code for people who also read the CSV. */
    fun full(code: String): String = label(code).let { if (it == code) it else "$it ($code)" }
}
