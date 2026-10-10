package com.obdlogger.core

/**
 * Petrol or diesel: from PID 01 51 when the ECU reports it, otherwise from the sensors
 * (a diesel has no narrow-band O2 sensors and no fuel trims). Rules about mixture and
 * lambda make no sense for a diesel and are skipped there.
 */
enum class Engine(val ru: String) {
    PETROL("бензин"), DIESEL("дизель"), OTHER("другое топливо"), UNKNOWN("тип не известен");

    companion object {
        const val FUEL_LINE = "Тип топлива (PID 51):"

        /** SAE J1979 fuel type codes. */
        fun fuelName(code: Int): String = when (code) {
            1 -> "Бензин"
            4 -> "Дизель"
            2 -> "Метанол"
            3 -> "Этанол"
            5 -> "Пропан (LPG)"
            6 -> "Метан (CNG)"
            8 -> "Электро"
            19 -> "Дизель-гибрид"
            23 -> "Дизель двухтопливный"
            in 9..16 -> "Двухтопливный"
            in 17..22 -> "Гибрид"
            else -> "код $code"
        }

        fun of(info: String?, sensors: Collection<String>): Engine {
            val fuel = info?.let { Regex(Regex.escape(FUEL_LINE) + " *(.+)").find(it)?.groupValues?.get(1)?.trim()?.lowercase() }
            when {
                fuel == null -> {}
                fuel.startsWith("бензин") || fuel.startsWith("гибрид") -> return PETROL
                fuel.startsWith("дизель") -> return DIESEL
                else -> return OTHER
            }
            if (sensors.isEmpty()) return UNKNOWN
            val lambda = sensors.any { it.matches(Regex("o2_b\\ds\\d_v")) || it.matches(Regex("[sl]tft_b\\d_pct")) }
            return if (lambda) PETROL else if ("rpm" in sensors) DIESEL else UNKNOWN
        }
    }
}
