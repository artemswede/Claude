package com.obdlogger.core

import java.math.BigDecimal
import java.math.RoundingMode

/** One CSV column. [name] already carries the unit so the CSV is readable on its own. */
data class Column(val name: String, val unit: String, val description: String)

/** Something requested from the adapter once per poll cycle; may produce several columns. */
class PollItem(
    val request: String,
    val columns: List<Column>,
    /** Polled only every few cycles (slowly changing values). */
    val slow: Boolean,
    /** True for ECU data; false for values the adapter measures itself (ATRV). */
    val fromEcu: Boolean,
    private val parser: (String) -> List<String?>,
) {
    fun parse(raw: String): List<String?> = parser(raw)
}

/** Standard SAE J1979 mode 01 PID with its decoding formula. */
class PidDef(
    val pid: Int,
    val minBytes: Int,
    val slow: Boolean,
    val columns: List<Column>,
    val decode: (IntArray) -> List<Any?>,
)

object Values {
    fun format(v: Any?): String? = when (v) {
        null -> null
        is Double -> if (v.isNaN() || v.isInfinite()) null
        else BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        is Number -> format(v.toDouble())
        else -> v.toString()
    }
}

object Pids {
    private val SENSORS = listOf("b1s1", "b1s2", "b1s3", "b1s4", "b2s1", "b2s2", "b2s3", "b2s4")

    private fun sensorRu(s: String) = "банк ${s[1]}, датчик ${s[3]}"

    private fun pct(a: Int) = a * 100.0 / 255
    private fun trim(a: Int) = (a - 128) * 100.0 / 128
    private fun word(d: IntArray, i: Int = 0) = d[i] * 256 + d[i + 1]

    private fun pid(
        pid: Int, name: String, unit: String, desc: String,
        slow: Boolean = false, minBytes: Int = 1, f: (IntArray) -> Any?,
    ) = PidDef(pid, minBytes, slow, listOf(Column(name, unit, desc))) { listOf(f(it)) }

    private fun fuelStatus(a: Int): String? = when (a) {
        0 -> null
        1 -> "OL"
        2 -> "CL"
        4 -> "OL_DRIVE"
        8 -> "OL_FAULT"
        16 -> "CL_FAULT"
        else -> "0x%02X".format(a)
    }

    val ALL: List<PidDef> = buildList {
        add(PidDef(0x03, 1, true, listOf(Column("fuel_system", "",
            "Режим топливной системы: CL — замкнутый контур (по лямбде), OL — разомкнутый (прогрев), " +
                "OL_DRIVE — разомкнутый из-за нагрузки, *_FAULT — из-за неисправности"))) { listOf(fuelStatus(it[0])) })
        add(pid(0x04, "engine_load_pct", "%", "Расчётная нагрузка двигателя") { pct(it[0]) })
        add(pid(0x05, "coolant_c", "°C", "Температура охлаждающей жидкости", slow = true) { it[0] - 40.0 })
        add(pid(0x06, "stft_b1_pct", "%", "Краткосрочная топливная коррекция, банк 1") { trim(it[0]) })
        add(pid(0x07, "ltft_b1_pct", "%", "Долгосрочная топливная коррекция, банк 1", slow = true) { trim(it[0]) })
        add(pid(0x08, "stft_b2_pct", "%", "Краткосрочная топливная коррекция, банк 2") { trim(it[0]) })
        add(pid(0x09, "ltft_b2_pct", "%", "Долгосрочная топливная коррекция, банк 2", slow = true) { trim(it[0]) })
        add(pid(0x0A, "fuel_pressure_kpa", "кПа", "Давление топлива (избыточное)", slow = true) { it[0] * 3.0 })
        add(pid(0x0B, "map_kpa", "кПа", "Абсолютное давление во впускном коллекторе") { it[0].toDouble() })
        add(pid(0x0C, "rpm", "об/мин", "Обороты двигателя", minBytes = 2) { word(it) / 4.0 })
        add(pid(0x0D, "speed_kmh", "км/ч", "Скорость автомобиля") { it[0].toDouble() })
        add(pid(0x0E, "timing_deg", "°", "Угол опережения зажигания до ВМТ") { it[0] / 2.0 - 64 })
        add(pid(0x0F, "intake_air_c", "°C", "Температура воздуха на впуске", slow = true) { it[0] - 40.0 })
        add(pid(0x10, "maf_gs", "г/с", "Массовый расход воздуха (ДМРВ)", minBytes = 2) { word(it) / 100.0 })
        add(pid(0x11, "throttle_pct", "%", "Положение дроссельной заслонки") { pct(it[0]) })
        SENSORS.forEachIndexed { i, s ->
            add(PidDef(0x14 + i, 2, false, listOf(
                Column("o2_${s}_v", "В", "Напряжение лямбда-зонда (${sensorRu(s)}); " + if (s[3] == '1') {
                    "до катализатора, исправный в замкнутом контуре переключается 0.1–0.9 В"
                } else {
                    "за катализатором, при живом катализаторе почти ровный ≈0.6–0.7 В"
                }),
                Column("o2_${s}_trim_pct", "%", "Коррекция топлива по лямбда-зонду (${sensorRu(s)})"),
            )) { d -> listOf(d[0] / 200.0, if (d[1] == 0xFF) null else trim(d[1])) })
        }
        add(pid(0x1F, "run_time_s", "с", "Время с момента запуска двигателя", slow = true, minBytes = 2) { word(it).toDouble() })
        add(pid(0x21, "dist_with_mil_km", "км", "Пробег с горящей лампой Check Engine", slow = true, minBytes = 2) { word(it).toDouble() })
        add(pid(0x22, "fuel_rail_rel_kpa", "кПа", "Давление в топливной рампе относительно впуска", minBytes = 2) { word(it) * 0.079 })
        add(pid(0x23, "fuel_rail_kpa", "кПа", "Давление в топливной рампе (дизель / непосредственный впрыск)", minBytes = 2) { word(it) * 10.0 })
        SENSORS.forEachIndexed { i, s ->
            add(PidDef(0x24 + i, 4, false, listOf(
                Column("wo2v_${s}_lambda", "λ", "Широкополосный датчик (${sensorRu(s)}): лямбда"),
                Column("wo2v_${s}_v", "В", "Широкополосный датчик (${sensorRu(s)}): напряжение"),
            )) { d -> listOf(word(d) * 2.0 / 65536, word(d, 2) * 8.0 / 65536) })
        }
        add(pid(0x2C, "egr_cmd_pct", "%", "Заданное открытие EGR", slow = true) { pct(it[0]) })
        add(pid(0x2D, "egr_error_pct", "%", "Ошибка положения EGR", slow = true) { trim(it[0]) })
        add(pid(0x2E, "evap_purge_pct", "%", "Продувка адсорбера (EVAP)", slow = true) { pct(it[0]) })
        add(pid(0x2F, "fuel_level_pct", "%", "Уровень топлива", slow = true) { pct(it[0]) })
        add(pid(0x30, "warmups_since_clear", "", "Прогревов после сброса ошибок", slow = true) { it[0].toDouble() })
        add(pid(0x31, "dist_since_clear_km", "км", "Пробег после сброса ошибок", slow = true, minBytes = 2) { word(it).toDouble() })
        add(pid(0x33, "baro_kpa", "кПа", "Атмосферное давление", slow = true) { it[0].toDouble() })
        SENSORS.forEachIndexed { i, s ->
            add(PidDef(0x34 + i, 4, false, listOf(
                Column("wo2_${s}_lambda", "λ", "Датчик состава смеси A/F (${sensorRu(s)}): лямбда (1.0 = стехиометрия)"),
                Column("wo2_${s}_ma", "мА", "Датчик состава смеси A/F (${sensorRu(s)}): ток"),
            )) { d -> listOf(word(d) * 2.0 / 65536, word(d, 2) / 256.0 - 128) })
        }
        listOf(0x3C to "b1s1", 0x3D to "b2s1", 0x3E to "b1s2", 0x3F to "b2s2").forEach { (p, s) ->
            add(pid(p, "cat_temp_${s}_c", "°C", "Температура катализатора (${sensorRu(s)})", slow = true, minBytes = 2) { word(it) / 10.0 - 40 })
        }
        add(pid(0x42, "ecu_voltage_v", "В", "Напряжение питания ЭБУ", slow = true, minBytes = 2) { word(it) / 1000.0 })
        add(pid(0x43, "abs_load_pct", "%", "Абсолютная нагрузка", minBytes = 2) { word(it) * 100.0 / 255 })
        add(pid(0x44, "cmd_lambda", "λ", "Заданная ЭБУ лямбда", minBytes = 2) { word(it) * 2.0 / 65536 })
        add(pid(0x45, "rel_throttle_pct", "%", "Относительное положение дросселя") { pct(it[0]) })
        add(pid(0x46, "ambient_c", "°C", "Температура окружающего воздуха", slow = true) { it[0] - 40.0 })
        add(pid(0x47, "throttle_b_pct", "%", "Абсолютное положение дросселя B (второй датчик)") { pct(it[0]) })
        add(pid(0x49, "pedal_d_pct", "%", "Положение педали газа D") { pct(it[0]) })
        add(pid(0x4A, "pedal_e_pct", "%", "Положение педали газа E") { pct(it[0]) })
        add(pid(0x4C, "cmd_throttle_pct", "%", "Заданное положение электронного дросселя") { pct(it[0]) })
        add(pid(0x5C, "oil_c", "°C", "Температура масла", slow = true) { it[0] - 40.0 })
        add(pid(0x5E, "fuel_rate_lh", "л/ч", "Расход топлива", minBytes = 2) { word(it) / 20.0 })
    }

    val adapterVoltage = PollItem(
        request = "ATRV",
        columns = listOf(Column("battery_v", "В", "Напряжение бортсети на разъёме OBD (измеряет адаптер); норма 12.4–12.7 В заглушен, 13.8–14.6 В заведён")),
        slow = true,
        fromEcu = false,
    ) { raw -> listOf(Regex("(\\d+(?:\\.\\d+)?)\\s*V", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)) }

    /** Poll list for the PIDs the car reported as supported. */
    fun pollItems(supported: Set<Int>, singleResponse: Boolean): List<PollItem> =
        listOf(adapterVoltage) + ALL.filter { it.pid in supported }.map { def ->
            val request = "01%02X".format(def.pid) + if (singleResponse) "1" else ""
            PollItem(request, def.columns, def.slow, fromEcu = true) { raw ->
                val data = ElmResponse.pidData(raw, def.pid)
                if (data == null || data.size < def.minBytes) {
                    List(def.columns.size) { null }
                } else {
                    def.decode(IntArray(data.size) { data.u(it) }).map(Values::format)
                }
            }
        }

    fun describe(pid: Int): String =
        ALL.firstOrNull { it.pid == pid }?.columns?.joinToString(", ") { it.name } ?: "не записывается"
}
