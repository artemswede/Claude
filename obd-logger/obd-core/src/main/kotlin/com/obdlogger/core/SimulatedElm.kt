package com.obdlogger.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fake ELM327 + petrol car on K-line for the demo mode and tests: cold start,
 * warm-up idle, city cycles and highway. The car has a built-in fault for the
 * analysis to find: lean mixture (LTFT ≈ +12.5 %, stored code P0171).
 *
 * [timeScale] speeds up the driving scenario relative to [clock].
 */
class SimulatedElm(
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeScale: Double = 1.0,
    private val latencyMs: Long = 0,
) : ElmIo {
    private val startMs = clock()
    private val rnd = Random(42)

    override fun command(cmd: String, timeoutMs: Long): String {
        if (latencyMs > 0) Thread.sleep(latencyMs)
        val c = cmd.uppercase().replace(" ", "")
        val reply = when {
            c == "ATZ" || c == "ATI" -> "ELM327 v1.5 (симуляция)"
            c == "ATRV" -> "%.1fV".format(java.util.Locale.ROOT, state().battery)
            c == "ATDPN" -> "A4"
            c == "ATDP" -> "AUTO, ISO 14230-4 (KWP 5BAUD)"
            c.startsWith("AT") -> "OK"
            c == "03" -> "43 01 71 00 00 00 00"
            c == "07" -> "47 00 00 00 00 00 00"
            c == "0A" -> "NO DATA"
            c == "0902" -> vinFrames()
            c.startsWith("01") && c.length >= 4 -> pid(c.substring(2, 4).toInt(16))
            c.startsWith("21") && c.length == 4 -> toyota(c.substring(2, 4).toInt(16))
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
        else -> speedAt(90 + (s - 700) % 310)
    }

    private fun state(): State {
        val s = (clock() - startMs) / 1000.0 * timeScale
        val speed = max(0.0, speedAt(s))
        val dv = speedAt(s + 1) - speedAt(s)
        val coolant = min(90.0, 15 + s * 0.25) + noise(0.3)
        val idle = if (coolant < 60) 1150 - (coolant - 15) * 7 else 760.0
        val rpmPerKmh = when {
            speed < 20 -> 110.0
            speed < 35 -> 65.0
            speed < 55 -> 45.0
            speed < 75 -> 35.0
            else -> 28.0
        }
        val rpm = max(idle, speed * rpmPerKmh) + noise(12.0)
        val heavyAccel = dv > 3
        val fuelCut = dv < -3 && speed > 15
        val load = when {
            heavyAccel -> 72.0
            fuelCut -> 8.0
            speed < 1 -> 24.0
            else -> 28 + speed * 0.2
        } + noise(1.5)
        val throttle = when {
            heavyAccel -> 45.0
            fuelCut || speed < 1 -> 14.5
            else -> 15 + speed * 0.1
        } + noise(0.3)
        return State(
            s = s, speed = speed, rpm = rpm, coolant = coolant, load = load, throttle = throttle,
            maf = rpm * load / 100 * 0.0133 + noise(0.05),
            timing = (if (heavyAccel) 14.0 else if (speed < 1) 8.0 else 24.0) + noise(1.0),
            iat = 19 + (coolant - 15) * 0.1 + noise(0.2),
            closedLoop = coolant >= 40 && !heavyAccel && !fuelCut,
            heavyAccel = heavyAccel, fuelCut = fuelCut,
            battery = (if (s < 2) 12.6 else 14.2) + noise(0.05),
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
            0x01 -> reply(p, 0x81, 0x07, 0x65, 0x00) // MIL on, 1 code
            0x03 -> reply(p, if (st.heavyAccel) 4 else if (st.closedLoop) 2 else 1, 0)
            0x04 -> reply(p, pct(st.load))
            0x05 -> reply(p, (st.coolant + 40).roundToInt())
            0x06 -> reply(p, trim(if (st.closedLoop) 2 + noise(3.0) else 0.0))
            0x07 -> reply(p, trim(12.5)) // the built-in fault: lean mixture
            0x0C -> (st.rpm * 4).roundToInt().let { reply(p, it shr 8, it) }
            0x0D -> reply(p, st.speed.roundToInt())
            0x0E -> reply(p, ((st.timing + 64) * 2).roundToInt())
            0x0F -> reply(p, (st.iat + 40).roundToInt())
            0x10 -> (st.maf * 100).roundToInt().let { reply(p, it shr 8, it) }
            0x11 -> reply(p, pct(st.throttle))
            0x12 -> reply(p, 0x04) // secondary air status: no formula in the app → logged raw
            0x13 -> reply(p, 0x03)
            0x14 -> reply(p, volts(frontO2(st)), trim(0.0))
            0x15 -> reply(p, volts(if (st.coolant < 40) 0.1 else 0.66 + noise(0.02)), 0xFF)
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

    /** Narrowband sensor: switches ~1 Hz in closed loop, flat when cold, ~0 on fuel cut. */
    private fun frontO2(st: State) = when {
        st.coolant < 40 -> 0.1
        st.fuelCut -> 0.05
        st.heavyAccel -> 0.85
        else -> 0.45 + 0.4 * sin(2 * PI * 1.1 * st.s) + noise(0.03)
    }

    private fun vinFrames(): String {
        val vin = "DEMOSIMULATED0001".map { it.code }
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
