package com.obdlogger.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A simulated car: which faults it has and how it talks. Used by the demo mode and
 * by tests that run the whole pipeline on cars with different problems.
 */
data class CarProfile(
    val name: String,
    /** ISO 15765 CAN 11-bit (true) or ISO 14230 K-line (false). */
    val can: Boolean = false,
    /** Long-term trim the ECU learned for warm idle, %. */
    val idleTrim: Double = 0.0,
    /** Long-term trim under load, %. */
    val loadTrim: Double = 0.0,
    val idleRpm: Double = 760.0,
    /** Rpm sags to ~470 when rolling to a stop. */
    val idleDips: Boolean = false,
    /** Charging voltage with the engine running. */
    val charge: Double = 14.2,
    val coolantMax: Double = 90.0,
    /** Warm-up speed, °C per scenario second. */
    val warmRate: Double = 0.25,
    val dtcs: List<String> = emptyList(),
    /** Toyota mode 21 blocks are answered. */
    val toyota: Boolean = true,
) {
    companion object {
        /** The demo car: lean idle like the real Avensis, plus a stored P0171. */
        val DEMO = CarProfile("Демо: подсос воздуха", idleTrim = 18.0, dtcs = listOf("P0171"))
        val HEALTHY = CarProfile("Исправная")
        val LEAN_IDLE = CarProfile("Подсос воздуха на холостом", idleTrim = 18.0)
        val LEAN_ALL = CarProfile("Бедно везде (насос)", idleTrim = 16.0, loadTrim = 15.0)
        val RICH_IDLE = CarProfile("Богато на холостом", idleTrim = -18.0)
        val IDLE_DIPS = CarProfile("Провалы холостого", idleDips = true)
        val WEAK_CHARGE = CarProfile("Слабый генератор", charge = 12.9)
        val OVERHEAT = CarProfile("Перегрев", coolantMax = 108.0)
        val THERMOSTAT_OPEN = CarProfile("Термостат открыт", coolantMax = 62.0, warmRate = 0.06)
        val CAN_HEALTHY = CarProfile("Исправная на CAN", can = true, toyota = false)
        val CAN_DTC = CarProfile("CAN с кодами", can = true, toyota = false, dtcs = listOf("P0300", "P0420"))
        val ALL = listOf(DEMO, HEALTHY, LEAN_IDLE, LEAN_ALL, RICH_IDLE, IDLE_DIPS, WEAK_CHARGE, OVERHEAT, THERMOSTAT_OPEN, CAN_HEALTHY, CAN_DTC)
    }
}

/**
 * Fake ELM327 + petrol car for the demo mode and tests: cold start, warm-up idle,
 * city cycles, highway, a parking idle, then city again. Faults and protocol come
 * from [profile].
 *
 * [timeScale] speeds up the driving scenario relative to [clock].
 */
class SimulatedElm(
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeScale: Double = 1.0,
    private val latencyMs: Long = 0,
    private val profile: CarProfile = CarProfile.DEMO,
) : ElmIo {
    private val startMs = clock()
    private val rnd = Random(42)

    override fun command(cmd: String, timeoutMs: Long): String {
        if (latencyMs > 0) Thread.sleep(latencyMs)
        val c = cmd.uppercase().replace(" ", "")
        val reply = when {
            c == "ATZ" || c == "ATI" -> "ELM327 v1.5 (симуляция)"
            c == "ATRV" -> "%.1fV".format(java.util.Locale.ROOT, state().battery)
            c == "ATDPN" -> if (profile.can) "A6" else "A4"
            c == "ATDP" -> if (profile.can) "AUTO, ISO 15765-4 (CAN 11/500)" else "AUTO, ISO 14230-4 (KWP 5BAUD)"
            c.startsWith("AT") -> "OK"
            c == "03" -> dtcReply(0x43, profile.dtcs)
            c == "07" -> dtcReply(0x47, emptyList())
            c == "0A" -> "NO DATA"
            c == "0902" -> vinFrames()
            c.startsWith("01") && c.length >= 4 -> pid(c.substring(2, 4).toInt(16))
            c.startsWith("21") && c.length == 4 -> if (profile.toyota) toyota(c.substring(2, 4).toInt(16)) else "7F 21 11"
            else -> "?"
        }
        return "$reply\r\r"
    }

    private class State(
        val s: Double, val speed: Double, val rpm: Double, val coolant: Double, val load: Double,
        val throttle: Double, val maf: Double, val timing: Double, val iat: Double,
        val closedLoop: Boolean, val heavyAccel: Boolean, val fuelCut: Boolean, val battery: Double,
    )

    /** Driving scenario, km/h at scenario second [s]. */
    private fun speedAt(s: Double): Double = when {
        s < 90 -> 0.0 // cold start, warm-up idle
        s in 700.0..820.0 -> 0.0 // parked with the engine running (warm idle)
        s < 400 -> { // city: 60 s cycles of accelerate / cruise / brake / stop
            val t = (s - 90) % 60
            when {
                t < 10 -> t * 5
                t < 35 -> 50.0
                t < 45 -> 50 - (t - 35) * 5
                else -> 0.0
            }
        }
        s < 700 -> min(90.0, (s - 400) * 4.5) + 3 * sin(s / 7) // highway
        else -> speedAt(90 + (s - 820) % 310)
    }

    private fun state(): State {
        val s = (clock() - startMs) / 1000.0 * timeScale
        val speed = max(0.0, speedAt(s))
        val dv = speedAt(s + 1) - speedAt(s)
        val coolant = min(profile.coolantMax, 15 + s * profile.warmRate) + noise(0.3)
        val idle = if (coolant < 60) 1150 - (coolant - 15) * 7 else profile.idleRpm
        val rpmPerKmh = when {
            speed < 20 -> 110.0
            speed < 35 -> 65.0
            speed < 55 -> 45.0
            speed < 75 -> 35.0
            else -> 28.0
        }
        val heavyAccel = dv > 3
        val fuelCut = dv < -3 && speed > 15
        val sag = profile.idleDips && coolant >= 70 && dv < 0 && speed in 0.5..20.0
        val rpm = (if (sag) 470.0 else max(idle, speed * rpmPerKmh)) + noise(12.0)
        val load = when {
            heavyAccel -> 72.0
            fuelCut -> 8.0
            speed < 1 -> 24.0
            else -> 28 + speed * 0.2
        } + noise(1.5)
        val throttle = when {
            heavyAccel -> 45.0
            fuelCut || speed < 1 || dv < -0.5 -> 14.5
            else -> 15 + speed * 0.1
        } + noise(0.3)
        return State(
            s = s, speed = speed, rpm = rpm, coolant = coolant, load = load, throttle = throttle,
            maf = rpm * load / 100 * 0.0133 + noise(0.05),
            timing = (if (heavyAccel) 14.0 else if (speed < 1) 8.0 else 24.0) + noise(1.0),
            iat = 19 + (coolant - 15) * 0.1 + noise(0.2),
            closedLoop = coolant >= 40 && !heavyAccel && !fuelCut,
            heavyAccel = heavyAccel, fuelCut = fuelCut,
            battery = (if (s < 2) 12.6 else profile.charge) + noise(0.05),
        )
    }

    private val supported: Set<Int> = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15,
        0x1C, 0x1F, 0x20, 0x21, 0x2E, 0x2F, 0x30, 0x31, 0x33,
    )

    private fun pid(p: Int): String {
        if (p % 0x20 == 0) {
            if (p != 0 && p !in supported) return "NO DATA"
            var mask = 0L
            for (i in 0 until 32) if (p + i + 1 in supported) mask = mask or (1L shl (31 - i))
            return reply(p, (mask shr 24).toInt(), (mask shr 16).toInt(), (mask shr 8).toInt(), mask.toInt())
        }
        if (p !in supported) return "NO DATA"
        val st = state()
        return when (p) {
            0x01 -> reply(p, if (profile.dtcs.isEmpty()) 0x00 else 0x80 or profile.dtcs.size, 0x07, 0x65, 0x00)
            0x03 -> reply(p, if (st.heavyAccel) 4 else if (st.closedLoop) 2 else 1, 0)
            0x04 -> reply(p, pct(st.load))
            0x05 -> reply(p, (st.coolant + 40).roundToInt())
            0x06 -> reply(p, trim(if (st.closedLoop) noise(2.5) else 0.0))
            0x07 -> reply(p, trim(if (st.closedLoop) ltft(st) else 0.0))
            0x0C -> (st.rpm * 4).roundToInt().let { reply(p, it shr 8, it) }
            0x0D -> reply(p, st.speed.roundToInt())
            0x0E -> reply(p, ((st.timing + 64) * 2).roundToInt())
            0x0F -> reply(p, (st.iat + 40).roundToInt())
            0x10 -> (st.maf * 100).roundToInt().let { reply(p, it shr 8, it) }
            0x11 -> reply(p, pct(st.throttle))
            0x12 -> reply(p, 0x04) // secondary air status: no formula in the app → logged raw
            0x13 -> reply(p, 0x03)
            0x14 -> reply(p, volts(frontO2(st)), trim(0.0))
            0x15 -> reply(p, volts(rearO2(st)), 0xFF)
            0x1C -> reply(p, 6)
            0x1F -> st.s.roundToInt().let { reply(p, it shr 8, it) }
            0x21 -> reply(p, 0, 37)
            0x2E -> reply(p, pct(if (st.closedLoop && st.coolant > 70) 20.0 else 0.0))
            0x2F -> reply(p, pct(55 - st.s / 600))
            0x30 -> reply(p, 12)
            0x31 -> reply(p, 0x05, 0xF0)
            0x33 -> reply(p, 100)
            else -> "NO DATA"
        }
    }

    /**
     * Toyota mode 21 blocks with made-up layouts, so the demo shows raw columns:
     * 21 01 — rpm/4 (2 bytes), coolant+40, VVT angle+64, misfire counters x4, idle valve %;
     * 21 03 — gearbox oil temperature+40, gear, lock-up flag. Other IDs: negative reply.
     */
    private fun toyota(id: Int): String {
        val st = state()
        return when (id) {
            0x01 -> {
                val r = (st.rpm * 4).roundToInt()
                val vvt = if (st.speed > 1) 20.0 + noise(1.0) else 0.0
                val misfire3 = if (st.coolant < 40) (st.s / 10).toInt() % 6 else 0
                "61 01 " + listOf(r shr 8, r and 0xFF, (st.coolant + 40).roundToInt(), (vvt + 64).roundToInt(),
                    0, 0, misfire3, 0, pct(if (st.speed < 1) 30.0 else 0.0)).joinToString(" ") { "%02X".format(it and 0xFF) }
            }
            0x03 -> {
                val gear = when {
                    st.speed < 1 -> 0
                    st.speed < 20 -> 1
                    st.speed < 35 -> 2
                    st.speed < 55 -> 3
                    else -> 4
                }
                "61 03 " + listOf((st.coolant * 0.8 + 40).roundToInt(), gear, if (st.speed > 60) 1 else 0)
                    .joinToString(" ") { "%02X".format(it and 0xFF) }
            }
            else -> "7F 21 12"
        }
    }

    /** Toyota learns trims per load cell: idle cell vs. the rest. */
    private fun ltft(st: State) = if (st.speed < 1 && st.coolant >= 70) profile.idleTrim else profile.loadTrim

    /** Behind a good catalyst: steady ~0.66 V; a lean idle leaves extra oxygen (≈0.06 V). */
    private fun rearO2(st: State) = when {
        st.coolant < 40 -> 0.1
        st.speed < 1 && profile.idleTrim > 10 -> 0.06 + noise(0.01)
        st.speed < 1 && profile.idleTrim < -10 -> 0.8 + noise(0.01)
        else -> 0.66 + noise(0.02)
    }

    private fun dtcReply(mode: Int, codes: List<String>): String {
        val bytes = codes.flatMap { code ->
            val hi = "PCBU".indexOf(code[0]) shl 6 or (code[1].digitToInt() shl 4) or code[2].digitToInt(16)
            listOf(hi, code.substring(3).toInt(16))
        }
        fun hex(b: List<Int>) = b.joinToString(" ") { "%02X".format(it) }
        return if (profile.can) {
            hex(listOf(mode, codes.size) + bytes)
        } else {
            // K-line: 3 codes per frame, padded with zeros.
            val frames = bytes.chunked(6).ifEmpty { listOf(emptyList()) }
            frames.joinToString("\r") { f -> hex(listOf(mode) + f + List(6 - f.size) { 0 }) }
        }
    }

    /** Narrowband sensor: switches ~1 Hz in closed loop, flat when cold, ~0 on fuel cut. */
    private fun frontO2(st: State) = when {
        st.coolant < 40 -> 0.1
        st.fuelCut -> 0.05
        st.heavyAccel -> 0.85
        else -> 0.45 + 0.4 * sin(2 * PI * 1.1 * st.s) + noise(0.03)
    }

    private fun vinFrames(): String {
        val vin = "DEMOSIMULATED0001".map { it.code }
        if (profile.can) {
            // ISO 15765 multi-frame: byte count line, then indexed frames.
            val all = listOf(0x49, 0x02, 0x01) + vin
            val frames = listOf(all.take(6)) + all.drop(6).chunked(7)
            return "014\r" + frames.mapIndexed { i, b -> "$i: " + b.joinToString(" ") { "%02X".format(it) } }.joinToString("\r")
        }
        val frames = mutableListOf(listOf(0, 0, 0, vin[0]))
        frames += vin.drop(1).chunked(4)
        return frames.mapIndexed { i, b -> reply49(i + 1, b) }.joinToString("\r")
    }

    private fun reply49(seq: Int, bytes: List<Int>) = (listOf(0x49, 0x02, seq) + bytes).joinToString(" ") { "%02X".format(it) }

    private fun reply(p: Int, vararg bytes: Int) =
        (listOf(0x41, p) + bytes.toList()).joinToString(" ") { "%02X".format(it and 0xFF) }

    private fun pct(v: Double) = (v.coerceIn(0.0, 100.0) * 255 / 100).roundToInt()
    private fun trim(v: Double) = (v * 128 / 100 + 128).roundToInt().coerceIn(0, 255)
    private fun volts(v: Double) = (v.coerceIn(0.0, 1.275) * 200).roundToInt()
    private fun noise(sigma: Double) = rnd.nextGaussian() * sigma
}
