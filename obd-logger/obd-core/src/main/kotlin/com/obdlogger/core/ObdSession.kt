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

/** A manufacturer mode 21 data block (Toyota "readDataByLocalIdentifier") that answered. */
data class ExtendedBlock(
    /** Short ECU tag for column names: fn (functional/default address), ecm, tcm. */
    val ecu: String,
    /** ATSH header to reach the ECU; null = protocol default. */
    val header: String?,
    val id: Int,
    val length: Int,
) {
    val ecuName get() = when (ecu) {
        "ecm" -> "ЭБУ двигателя"
        "tcm" -> "ЭБУ АКПП"
        else -> "ответ на общий адрес"
    }
}

data class VehicleInfo(
    val protocol: String,
    val protocolNumber: Int,
    val supportedPids: Set<Int>,
    val vin: String?,
    val obdStandard: String?,
    val dtcs: DtcSnapshot,
    /** Mode 09 04: ECU software calibration ID. */
    val calibrationId: String? = null,
)

/** Adapter setup and one-off vehicle queries on top of [ElmIo]. */
class ObdSession(private val elm: ElmIo, private val resetDelayMs: Long = 1_000) {
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

    /** The ELM's own default (functional) header for the detected protocol; null if not switchable. */
    val defaultHeader: String?
        get() = when (protocolNumber) {
            3 -> "686AF1"
            4, 5 -> "C133F1"
            6, 8 -> "7DF"
            7, 9 -> "18DB33F1"
            else -> null
        }

    fun initAdapter(): AdapterInfo {
        try {
            elm.command("ATZ", 5_000)
        } catch (_: ElmTimeoutException) {
            // some clones answer ATZ without a prompt; the next commands tell if it is alive
        }
        // The reset banner can arrive after the prompt; let it pass so it is not taken as the ATE0 reply.
        if (resetDelayMs > 0) Thread.sleep(resetDelayMs)
        for (attempt in 1..3) {
            val ok = try {
                elm.command("ATE0").contains("OK")
            } catch (_: ElmTimeoutException) {
                false
            }
            if (ok) break
        }
        // Clones reject some of these with "?"; none of them is essential.
        for (cmd in listOf("ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) {
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
        protocolNumber = query("ATDPN")?.let { ElmResponse.lines(it).lastOrNull() }?.removePrefix("A")?.toIntOrNull(16) ?: 0
        protocolName = query("ATDP")?.let { ElmResponse.lines(it).lastOrNull() }?.removePrefix("AUTO, ") ?: "?"
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

    /**
     * Mode 02 frame 0: the sensor values the ECU stored at the moment it set a code
     * (the «freeze frame»). Null if the ECU keeps none (no codes, or not supported).
     */
    fun readFreezeFrame(supported: Set<Int> = readSupportedPids()): FreezeFrame? {
        fun frame(pid: Int): ByteArray? = query("02%02X00".format(pid), 3_000)?.let { raw ->
            ElmResponse.messages(raw).firstOrNull { it.size > 3 && it.u(0) == 0x42 && it.u(1) == pid }?.let { it.copyOfRange(3, it.size) }
        }
        val cause = frame(0x02)?.takeIf { it.size >= 2 && (it.u(0) != 0 || it.u(1) != 0) }?.let { Dtc.decode(it.u(0), it.u(1)) }
        val values = ArrayList<FreezeFrame.Value>()
        for (def in Pids.ALL) {
            if (def.pid !in supported || def.pid == 0x03) continue
            val d = frame(def.pid) ?: continue
            if (d.size < def.minBytes) continue
            val decoded = def.decode(IntArray(d.size) { d.u(it) })
            def.columns.forEachIndexed { i, col ->
                val v = decoded.getOrNull(i)
                if (v is Number) values += FreezeFrame.Value(col.name, col.unit, col.description, v.toDouble())
            }
        }
        if (cause == null && values.isEmpty()) return null
        return FreezeFrame(cause, values)
    }

    /**
     * Mode 04: clears stored and pending codes, the freeze frame and the readiness
     * monitors, and turns the MIL off. True if the ECU confirmed (44).
     */
    fun clearDtcs(): Boolean {
        val raw = query("04", 10_000) ?: return false
        // The positive reply is the single byte 44, which the message parser skips as too short.
        return ElmResponse.lines(raw).any { line -> ElmResponse.parseHex(line)?.let { it.isNotEmpty() && it.u(0) == 0x44 } == true }
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
            calibrationId = query("0904", 5_000)?.let(::parseCalibrationId),
        )
    }

    /** Data length of each supported mode 01 PID the app cannot decode, so it is logged raw. */
    fun discoverRawPids(supported: Set<Int>): Map<Int, Int> =
        Pids.undecoded(supported).associateWith { pid ->
            query("01%02X".format(pid), 3_000)?.let { ElmResponse.pidData(it, pid)?.size } ?: 0
        }.filterValues { it > 0 }

    /**
     * Finds Toyota mode 21 data blocks by asking every local identifier 01..FF,
     * first at the default address, then at the engine (and on CAN the gearbox)
     * ECU's physical address. Restores the default header afterwards.
     */
    fun discoverExtended(progress: (String) -> Unit = {}): List<ExtendedBlock> {
        val default = defaultHeader ?: return emptyList()
        val targets = buildList {
            add("fn" to default)
            when (protocolNumber) {
                4, 5 -> add("ecm" to "8110F1")
                6, 8 -> {
                    add("ecm" to "7E0")
                    add("tcm" to "7E1")
                }
                7, 9 -> {
                    add("ecm" to "18DA10F1")
                    add("tcm" to "18DA18F1")
                }
            }
        }
        val found = mutableListOf<ExtendedBlock>()
        try {
            for ((ecu, header) in targets) {
                // The engine ECU usually is what answered at the default address.
                if (ecu == "ecm" && found.any { it.ecu == "fn" }) continue
                if (query("ATSH$header")?.contains("OK") != true) continue
                progress("Проверка режима 21 ($ecu)…")
                if (!speaksMode21()) continue
                var timeouts = 0
                for (id in 0x01..0xFF) {
                    if (id % 16 == 1) progress("Поиск скрытых параметров Toyota ($ecu): 21 %02X из FF, найдено ${found.size}".format(id))
                    val raw = try {
                        elm.command("21%02X".format(id), 1_500).also { timeouts = 0 }
                    } catch (_: ElmTimeoutException) {
                        if (++timeouts >= 5) break
                        continue
                    }
                    val msg = ElmResponse.messages(raw).firstOrNull { it.size > 2 && it.u(0) == 0x61 && it.u(1) == id } ?: continue
                    found.add(ExtendedBlock(ecu, header.takeIf { ecu != "fn" }, id, msg.size - 2))
                }
            }
        } finally {
            query("ATSH$default")
        }
        return found
    }

    /** True if the ECU answers mode 21 at all (data, or a "not this ID" negative reply). */
    private fun speaksMode21(): Boolean = listOf(0x01, 0x81, 0x03).any { id ->
        val raw = query("21%02X".format(id), 1_500) ?: return@any false
        ElmResponse.messages(raw).any {
            it.u(0) == 0x61 || (it.size >= 3 && it.u(0) == 0x7F && it.u(1) == 0x21 && it.u(2) != 0x11)
        }
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

        fun parseCalibrationId(raw: String): String? {
            val msgs = ElmResponse.messages(raw).filter { it.size > 3 && it.u(0) == 0x49 && it.u(1) == 0x04 }
            if (msgs.isEmpty()) return null
            val bytes = if (msgs.size > 1) msgs.sortedBy { it.u(2) }.flatMap { it.drop(3) } else msgs[0].drop(3)
            return bytes.map { it.toInt() and 0xFF }.filter { it in 0x21..0x7E }.map { it.toChar() }
                .joinToString("").ifEmpty { null }
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
