package com.obdlogger.core

/**
 * Mode 06: on-board monitoring test results.
 * One-shot read — what the ECU checked and whether it passed.
 */
data class Mode06Result(
    val mid: Int,
    val tid: Int,
    val value: Double,
    val min: Double,
    val max: Double,
    val passed: Boolean,
    val unitScaling: Int,
) {
    val midName: String get() = Mode06Names.midName(mid)
    val tidName: String get() = Mode06Names.tidName(mid, tid)
    val unitName: String get() = Mode06Units.name(unitScaling)
}

data class Mode06Snapshot(val results: List<Mode06Result>) {
    val passedCount get() = results.count { it.passed }
    val failedCount get() = results.count { !it.passed }
    val totalCount get() = results.size
}

object Mode06Units {
    private val units = mapOf(
        0x01 to Pair("", 1.0),
        0x02 to Pair("", 1.0),
        0x03 to Pair("", 1.0),
        0x04 to Pair("", 1.0),
        0x05 to Pair("", 0.001),
        0x06 to Pair("", 0.001),
        0x07 to Pair("об/мин", 0.25),
        0x08 to Pair("км/ч", 1.0),
        0x09 to Pair("°", 1.0),
        0x0A to Pair("В", 0.001),
        0x0B to Pair("с", 0.01),
        0x0C to Pair("мс", 1.0),
        0x0D to Pair("мс", 0.01),
        0x0E to Pair("мА", 0.01),
        0x0F to Pair("с", 0.01),
        0x10 to Pair("кПа", 0.01),
        0x11 to Pair("", 0.01),
        0x12 to Pair("%", 0.01),
        0x13 to Pair("В", 0.001),
        0x14 to Pair("с", 1.0),
        0x15 to Pair("мс", 1.0),
        0x16 to Pair("с", 1.0),
        0x17 to Pair("с", 0.001),
        0x18 to Pair("мс", 0.001),
        0x19 to Pair("кПа", 0.01),
        0x1A to Pair("кПа", 1.0),
        0x1B to Pair("кПа", 0.1),
        0x1C to Pair("мА", 0.01),
        0x1D to Pair("мА", 0.001),
        0x1E to Pair("мА", 0.00390625),
        0x1F to Pair("В", 0.001),
        0x20 to Pair("мм", 0.01),
        0x21 to Pair("Па", 0.01),
        0x22 to Pair("λ", 0.0000305),
        0x23 to Pair("λ", 0.000122),
        0x24 to Pair("λ", 0.001),
        0x25 to Pair("мг/такт", 0.01),
        0x26 to Pair("г/с", 0.01),
        0x27 to Pair("Па/с", 1.0),
        0x28 to Pair("кг/ч", 0.01),
        0x29 to Pair("", 1.0),
        0x2A to Pair("кПа", 0.005),
        0x2B to Pair("Па", 1.0),
        0x81 to Pair("мг/такт", 0.01),
        0x82 to Pair("г/с", 0.01),
    )

    fun scale(id: Int, raw: Int): Double {
        val (_, factor) = units[id] ?: return raw.toDouble()
        val v = if (id >= 0x80) raw.toShort().toDouble() else raw.toDouble()
        return v * factor
    }

    fun name(id: Int): String = units[id]?.first ?: ""
}

object Mode06Names {
    private val mids = mapOf(
        0x01 to "Кислородный датчик B1S1",
        0x02 to "Кислородный датчик B1S2",
        0x03 to "Кислородный датчик B2S1",
        0x04 to "Кислородный датчик B2S2",
        0x05 to "Кислородный датчик B1S3",
        0x06 to "Кислородный датчик B1S4",
        0x07 to "Кислородный датчик B2S3",
        0x08 to "Кислородный датчик B2S4",
        0x21 to "Катализатор B1",
        0x22 to "Катализатор B2",
        0x31 to "Подогреваемый катализатор B1",
        0x32 to "Подогреваемый катализатор B2",
        0x33 to "Подогреваемый катализатор B3",
        0x34 to "Подогреваемый катализатор B4",
        0x41 to "Система EVAP",
        0x51 to "Вторичный воздух B1",
        0x52 to "Вторичный воздух B2",
        0x61 to "Подогрев лямбда-зонда B1S1",
        0x62 to "Подогрев лямбда-зонда B1S2",
        0x63 to "Подогрев лямбда-зонда B2S1",
        0x64 to "Подогрев лямбда-зонда B2S2",
        0x71 to "EGR/VVT B1",
        0x72 to "EGR/VVT B2",
        0x81 to "Подогрев лямбда-зонда B1S1 (чувствительность)",
        0x82 to "Подогрев лямбда-зонда B1S2 (чувствительность)",
        0x83 to "Подогрев лямбда-зонда B2S1 (чувствительность)",
        0x84 to "Подогрев лямбда-зонда B2S2 (чувствительность)",
        0xA1 to "Пропуски зажигания, общие",
        0xA2 to "Пропуски, цилиндр 1",
        0xA3 to "Пропуски, цилиндр 2",
        0xA4 to "Пропуски, цилиндр 3",
        0xA5 to "Пропуски, цилиндр 4",
        0xA6 to "Пропуски, цилиндр 5",
        0xA7 to "Пропуски, цилиндр 6",
        0xA8 to "Пропуски, цилиндр 7",
        0xA9 to "Пропуски, цилиндр 8",
    )

    fun midName(mid: Int): String = mids[mid] ?: "Монитор %02X".format(mid)

    fun tidName(mid: Int, tid: Int): String = "Тест %02X".format(tid)
}

object Mode06Parser {
    fun parse(raw: String, mid: Int, isCan: Boolean): List<Mode06Result> {
        val results = mutableListOf<Mode06Result>()
        for (msg in ElmResponse.messages(raw)) {
            if (msg.size < 2 || msg.u(0) != 0x46) continue
            if (isCan) {
                // CAN record: MID(1) TID(1) UnitScaling(1) TestValue(2) MinLimit(2) MaxLimit(2) = 8 bytes per record
                var pos = 1
                while (pos + 9 <= msg.size) {
                    val m = msg.u(pos)
                    val t = msg.u(pos + 1)
                    val unit = msg.u(pos + 2)
                    val tv = (msg.u(pos + 3) shl 8) or msg.u(pos + 4)
                    val mn = (msg.u(pos + 5) shl 8) or msg.u(pos + 6)
                    val mx = (msg.u(pos + 7) shl 8) or msg.u(pos + 8)
                    val value = Mode06Units.scale(unit, tv)
                    val minVal = Mode06Units.scale(unit, mn)
                    val maxVal = Mode06Units.scale(unit, mx)
                    val passed = if (mn == 0 && mx == 0) true else value in minVal..maxVal
                    results += Mode06Result(m, t, value, minVal, maxVal, passed, unit)
                    pos += 9
                }
            } else {
                // Non-CAN: 46 MID TID TV(2) Min(2) Max(2) [00 00]
                if (msg.size < 9) continue
                val m = msg.u(1)
                val t = msg.u(2)
                val tv = (msg.u(3) shl 8) or msg.u(4)
                val mn = (msg.u(5) shl 8) or msg.u(6)
                val mx = (msg.u(7) shl 8) or msg.u(8)
                val passed = if (mn == 0 && mx == 0) true else tv in mn..mx
                results += Mode06Result(m, t, tv.toDouble(), mn.toDouble(), mx.toDouble(), passed, 0)
            }
        }
        return results
    }

    fun supportedMids(baseMid: Int, raw: String): Set<Int> {
        val result = mutableSetOf<Int>()
        for (msg in ElmResponse.messages(raw)) {
            if (msg.size < 6 || msg.u(0) != 0x46 || msg.u(1) != baseMid) continue
            val bits = (msg.u(2).toLong() shl 24) or (msg.u(3).toLong() shl 16) or
                    (msg.u(4).toLong() shl 8) or msg.u(5).toLong()
            for (i in 0 until 32) {
                if (bits and (1L shl (31 - i)) != 0L) result.add(baseMid + i + 1)
            }
        }
        return result
    }
}
