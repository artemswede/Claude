package com.obdlogger.core

import java.io.IOException

data class AdapterInfo(val id: String, val voltage: String?)

data class DtcSnapshot(
    val milOn: Boolean?,
    val reportedCount: Int?,
    /** Mode 03; null if the request failed. */
    val stored: List<String>?,
    /** Mode 07: detected in the current/last drive cycle, not yet confirmed. */
    val pending: List<String>?,
    /** Mode 0A: cannot be cleared by a scanner (usually unsupported before ~2010). */
    val permanent: List<String>?,
)

data class VehicleInfo(
    val protocol: String,
    val protocolNumber: Int,
    val supportedPids: Set<Int>,
    val vin: String?,
    val obdStandard: String?,
    val dtcs: DtcSnapshot,
)

/** Adapter setup and one-off vehicle queries on top of [ElmIo]. */
class ObdSession(private val elm: ElmIo) {
    var protocolNumber = 0
        private set
    var protocolName = ""
        private set
    /** The adapter understands the "return after first reply" digit (`010C1`), which saves a timeout per request. */
    var singleResponse = false
        private set
    /** Why [connectEcu] failed last time. */
    var lastError: String? = null
        private set

    val isCan get() = protocolNumber in 6..9

    fun initAdapter(): AdapterInfo {
        try {
            elm.command("ATZ", 5_000)
        } catch (_: ElmTimeoutException) {
            // some clones answer ATZ without a prompt; the next commands tell if it is alive
        }
        // Clones reject some of these with "?"; none of them is essential.
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) {
            try {
                elm.command(cmd)
            } catch (_: ElmTimeoutException) {
            }
        }
        val id = try {
            ElmResponse.lines(elm.command("ATI")).lastOrNull().orEmpty()
        } catch (e: ElmTimeoutException) {
            throw IOException("Адаптер не отвечает на команды", e)
        }
        val voltage = try {
            ElmResponse.lines(elm.command("ATRV")).lastOrNull()
        } catch (_: ElmTimeoutException) {
            null
        }
        return AdapterInfo(id, voltage)
    }

    /** Opens the OBD session with the car. False if the ECU does not answer (ignition off?). */
    fun connectEcu(): Boolean {
        // The first request triggers protocol search; slow K-line init can take ~10 s.
        val raw = try {
            elm.command("0100", 20_000)
        } catch (_: ElmTimeoutException) {
            lastError = "нет ответа"
            return false
        }
        if (ElmResponse.pidData(raw, 0x00) == null) {
            lastError = ElmResponse.error(raw)
            return false
        }
        protocolNumber = query("ATDPN")?.removePrefix("A")?.toIntOrNull(16) ?: 0
        protocolName = query("ATDP")?.removePrefix("AUTO, ") ?: "?"
        singleResponse = query("01001")?.let { ElmResponse.pidData(it, 0x00) } != null
        lastError = null
        return true
    }

    fun readSupportedPids(): Set<Int> {
        val result = sortedSetOf<Int>()
        var base = 0
        while (base <= 0xC0) {
            val raw = try {
                elm.command("01%02X".format(base), 5_000)
            } catch (_: ElmTimeoutException) {
                break
            }
            // Several ECUs (engine, gearbox) may answer; take the union.
            val masks = ElmResponse.messages(raw)
                .filter { it.size >= 6 && it.u(0) == 0x41 && it.u(1) == base }
            if (masks.isEmpty()) break
            for (m in masks) {
                val bits = (m.u(2).toLong() shl 24) or (m.u(3).toLong() shl 16) or (m.u(4).toLong() shl 8) or m.u(5).toLong()
                for (i in 0 until 32) {
                    if (bits and (1L shl (31 - i)) != 0L) result.add(base + i + 1)
                }
            }
            if (base + 0x20 !in result) break
            base += 0x20
        }
        return result
    }

    fun readDtcs(): DtcSnapshot {
        val status = query("0101", 5_000)?.let { ElmResponse.pidData(it, 0x01) }
        return DtcSnapshot(
            milOn = status?.let { it.u(0) and 0x80 != 0 },
            reportedCount = status?.let { it.u(0) and 0x7F },
            stored = dtcs("03", 0x43),
            pending = dtcs("07", 0x47),
            permanent = dtcs("0A", 0x4A),
        )
    }

    fun readVehicleInfo(): VehicleInfo {
        val supported = readSupportedPids()
        val obd = if (0x1C in supported) {
            query("011C")?.let { ElmResponse.pidData(it, 0x1C) }?.let { obdStandardName(it.u(0)) }
        } else null
        return VehicleInfo(
            protocol = protocolName,
            protocolNumber = protocolNumber,
            supportedPids = supported,
            vin = query("0902", 5_000)?.let(::parseVin),
            obdStandard = obd,
            dtcs = readDtcs(),
        )
    }

    private fun dtcs(mode: String, responseMode: Int): List<String>? {
        val raw = query(mode, 5_000) ?: return null
        val codes = Dtc.parse(raw, responseMode, isCan)
        if (codes.isEmpty() && ElmResponse.messages(raw).none { it.u(0) == responseMode }) return null
        return codes
    }

    private fun query(cmd: String, timeoutMs: Long = ElmIo.DEFAULT_TIMEOUT_MS): String? = try {
        elm.command(cmd, timeoutMs).trim().ifEmpty { null }
    } catch (_: ElmTimeoutException) {
        null
    }

    companion object {
        fun parseVin(raw: String): String? {
            val msgs = ElmResponse.messages(raw).filter { it.size > 3 && it.u(0) == 0x49 && it.u(1) == 0x02 }
            if (msgs.isEmpty()) return null
            // K-line: several "49 02 <seq> b b b b" frames; CAN: one joined message "49 02 01 <17 bytes>".
            val bytes = if (msgs.size > 1) msgs.sortedBy { it.u(2) }.flatMap { it.drop(3) } else msgs[0].drop(3)
            val text = bytes.map { it.toInt() and 0xFF }.filter { it in 0x21..0x7E }.map { it.toChar() }.joinToString("")
            return text.takeLast(17).ifEmpty { null }
        }

        fun obdStandardName(code: Int): String = when (code) {
            1 -> "OBD-II (CARB)"
            2 -> "OBD (EPA)"
            3 -> "OBD и OBD-II"
            6 -> "EOBD (Европа)"
            7 -> "EOBD и OBD-II"
            9 -> "EOBD, OBD и OBD-II"
            13 -> "JOBD (Япония)"
            else -> "код $code"
        }
    }
}
