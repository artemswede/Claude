package com.obdlogger.core

import kotlin.math.abs

/** Where the engine is right now — norms depend on it. */
enum class LiveMode(val ru: String) {
    OFF("мотор заглушен"),
    COLD("прогрев"),
    IDLE("холостой ход"),
    DRIVE("движение");

    companion object {
        /** From the latest readings: rpm, speed, coolant. */
        fun of(rpm: Double?, speed: Double?, coolant: Double?): LiveMode = when {
            rpm == null || rpm.isNaN() || rpm < 300 -> OFF
            coolant != null && !coolant.isNaN() && coolant < 70 -> COLD
            (speed == null || speed.isNaN() || speed < 1) && rpm < 1200 -> IDLE
            else -> DRIVE
        }

        fun of(mode: DriveMode): LiveMode = when (mode) {
            DriveMode.COLD -> COLD
            DriveMode.WARM_IDLE -> IDLE
            else -> DRIVE
        }
    }
}

enum class NormState { IN, LOW, HIGH, NONE }

/** A norm band for one sensor in one mode, with the words to describe a value against it. */
class Norm(val lo: Double, val hi: Double, private val lowWord: String = "ниже нормы", private val highWord: String = "выше нормы") {
    fun state(v: Double): NormState = when {
        v.isNaN() -> NormState.NONE
        v < lo -> NormState.LOW
        v > hi -> NormState.HIGH
        else -> NormState.IN
    }

    fun word(s: NormState): String = when (s) {
        NormState.LOW -> lowWord
        NormState.HIGH -> highWord
        NormState.IN -> "в норме"
        NormState.NONE -> ""
    }

    /** «норма 600–800», «норма ±10». */
    val text: String
        get() = if (lo == -hi) "норма ±${Values.format(hi)}" else "норма ${Values.format(lo)?.replace("-", "−")}–${Values.format(hi)?.replace("-", "−")}"

    /** How far outside, as a share of the band width (0 inside). */
    fun excess(v: Double): Double = when {
        v.isNaN() -> 0.0
        v < lo -> (lo - v) / (hi - lo)
        v > hi -> (v - hi) / (hi - lo)
        else -> 0.0
    }
}

/**
 * Typical norms of a petrol engine with OBD-II, per mode. Deliberately simple: they
 * decide colours and the «Внимание» ranking, not diagnoses. No norm → neutral.
 */
object Norms {
    fun of(code: String, mode: LiveMode): Norm? {
        if (mode == LiveMode.OFF) return if (code == "battery_v") Norm(12.2, 12.9, "разряжен", "выше обычного") else null
        return when {
            code.startsWith("trim_b") -> if (mode == LiveMode.COLD) null else Norm(-10.0, 10.0, "богато", "выше нормы")
            code.matches(Regex("ltft_b\\d_pct")) -> if (mode == LiveMode.COLD) null else Norm(-10.0, 10.0, "богато", "выше нормы")
            code.matches(Regex("stft_b\\d_pct")) -> if (mode == LiveMode.COLD) null else Norm(-10.0, 10.0, "богато", "выше нормы")
            code == "rpm" -> if (mode == LiveMode.IDLE) Norm(600.0, 850.0, "низкие", "высокие") else null
            code.matches(Regex("o2_b\\ds2_v")) -> when (mode) {
                LiveMode.IDLE, LiveMode.DRIVE -> Norm(0.45, 0.85, "«бедно»", "«богато»")
                else -> null
            }
            code == "coolant_c" -> if (mode == LiveMode.COLD) null else Norm(80.0, 100.0, "холодный", "горячо")
            code == "battery_v" -> Norm(13.5, 14.8, "слабая зарядка", "перезаряд")
            else -> null
        }
    }

    /** Front O2 is fine when it switches lean↔rich; [recent] are the last ~30 s of readings. */
    fun frontO2Switching(recent: DoubleArray): Boolean? {
        if (recent.size < 4) return null
        return recent.min() < 0.3 && recent.max() > 0.6
    }

    fun isFrontO2(code: String) = code.matches(Regex("o2_b\\ds1_v"))
}

/** One sensor in the «Внимание» ranking. */
class AttentionItem(
    val code: String,
    /** 0–100: share of time out of norm weighted by how far (DEVIATION) or jitter (JUMPS). */
    val score: Double?,
    val outSec: Double,
    val totalSec: Double,
    val state: NormState,
    val norm: Norm?,
    val last: Double,
    /** Short status under the gauge: «вне нормы 60 из 60 с», «нормы для ХХ нет». */
    val note: String,
)

enum class AttentionSort { DEVIATION, JUMPS }

/**
 * Ranks live sensors for the «Внимание» page: by how much of the recent window
 * they spent outside their norm, or by jitter. Raw/undecoded columns are skipped.
 */
object Attention {
    val INTERESTING = listOf(
        "trim_b1", "trim_b2", "o2_b1s2_v", "o2_b2s2_v", "rpm", "maf_gs", "o2_b1s1_v", "o2_b2s1_v",
        "coolant_c", "battery_v", "map_kpa", "timing_deg", "intake_air_c", "engine_load_pct", "throttle_pct",
    )

    fun rank(store: SeriesStore, windowMs: Long = 60_000, sort: AttentionSort = AttentionSort.DEVIATION): List<AttentionItem> {
        val end = store.lastTime() ?: return emptyList()
        val from = end - windowMs
        val mode = LiveMode.of(store.last("rpm"), store.last("speed_kmh"), store.last("coolant_c"))
        val stats = store.stats().associateBy { it.name }
        val items = INTERESTING.filter { it in store.columns }.mapNotNull { code ->
            val (t, v) = store.series(code, from)
            if (v.isEmpty()) return@mapNotNull null
            val total = ((t.last() - t.first()) / 1000.0).coerceAtLeast(1.0)
            val last = v.last()
            if (Norms.isFrontO2(code)) {
                val sw = Norms.frontO2Switching(v)
                val note = if (sw == false) "не переключается" else "ожидаемый коридор 0.1–0.9 В"
                return@mapNotNull AttentionItem(code, if (sw == false) 80.0 else 0.0, if (sw == false) total else 0.0, total,
                    if (sw == false) NormState.LOW else NormState.IN, null, last, note)
            }
            val norm = Norms.of(code, mode)
            if (norm == null) {
                val j = stats[code]?.jitter
                return@mapNotNull AttentionItem(code, null, 0.0, total, NormState.NONE, null, last,
                    "нормы для режима «${mode.ru}» нет" + (j?.let { " · скачки ${it.toInt()} %" } ?: ""))
            }
            // Time-weighted share out of norm, scaled up by how far.
            var out = 0.0
            var weighted = 0.0
            for (i in v.indices) {
                val dt = if (i == 0) 0.0 else ((t[i] - t[i - 1]) / 1000.0).coerceAtMost(10.0)
                if (norm.state(v[i]) != NormState.IN) {
                    out += dt
                    weighted += dt * (1 + norm.excess(v[i]))
                }
            }
            val score = (weighted / total * 100 / 2).coerceIn(0.0, 100.0).let { if (out > 0) maxOf(it, out / total * 100) else 0.0 }
            val state = norm.state(last)
            val note = when {
                out > 0 -> "вне нормы ${out.toInt()} из ${total.toInt()} с" + (if (state != NormState.IN) " · ${norm.word(state)}" else "")
                else -> "в норме · ${norm.text}"
            }
            AttentionItem(code, score, out, total, state, norm, last, note)
        }
        return when (sort) {
            AttentionSort.DEVIATION -> items.sortedWith(compareByDescending<AttentionItem> { it.score ?: -1.0 }.thenByDescending { stats[it.code]?.jitter ?: 0.0 })
            AttentionSort.JUMPS -> items.sortedByDescending { stats[it.code]?.jitter ?: -1.0 }
        }
    }

    /** Sensors whose last value is out of norm, for the badge «N нестабильных». */
    fun unstableCount(items: List<AttentionItem>) = items.count { (it.score ?: 0.0) > 5 }

    fun isFlat(v: DoubleArray) = v.isEmpty() || abs(v.max() - v.min()) < 1e-9
}
