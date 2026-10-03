package com.obdlogger.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted adapter: command -> reply text (without prompt). Unknown commands answer "?". */
private class FakeElm(private val replies: Map<String, String>, private val silent: Set<String> = emptySet()) : ElmIo {
    val sent = mutableListOf<String>()
    override fun command(cmd: String, timeoutMs: Long): String {
        sent.add(cmd)
        if (cmd in silent) throw ElmTimeoutException(cmd, "")
        return replies[cmd] ?: "?"
    }
}

/** K-line Toyota-like car: ISO 14230 (KWP2000), engine ECU only. */
private val kline = mapOf(
    "ATZ" to "ATZ\r\r\rELM327 v2.1\r\r",
    "ATE0" to "ATE0\rOK\r\r",
    "ATI" to "ELM327 v2.1\r\r",
    "ATRV" to "12.6V\r\r",
    "0100" to "SEARCHING...\rBUS INIT: ...OK\r41 00 BE 3E B8 11\r\r",
    "01001" to "41 00 BE 3E B8 11\r\r",
    "0120" to "41 20 80 00 00 00\r\r",
    "ATDPN" to "A5\r\r",
    "ATDP" to "AUTO, ISO 14230-4 (KWP FAST)\r\r",
    "0101" to "41 01 82 07 E5 00\r\r",
    "03" to "43 01 71 01 33 00 00\r\r",
    "07" to "43 00 00 00 00 00 00\r\r".replace("43", "47"),
    "0A" to "NO DATA\r\r",
    "0902" to "49 02 01 00 00 00 53\r49 02 02 42 31 42 52\r49 02 03 33 32 4A 34\r49 02 04 30 30 30 33\r49 02 05 33 33 33 34\r\r",
    "010C1" to "41 0C 1A F8\r\r",
    "010D1" to "41 0D 3C\r\r",
    "01051" to "41 05 5A\r\r",
    "01061" to "41 06 8A\r\r",
    "01071" to "41 07 70\r\r",
    "01141" to "41 14 B4 80\r\r",
    "01151" to "41 15 82 FF\r\r",
)

class ObdCoreTest {

    @Test
    fun parsesCanMultiFrameVin() {
        val raw = "014\r0: 49 02 01 4A 54 4D\r1: 42 45 33 32 56 30 30\r2: 30 31 32 33 34 35 36\r\r"
        assertEquals("JTMBE32V000123456", ObdSession.parseVin(raw))
    }

    @Test
    fun parsesKLineVinWithoutSpaces() {
        val raw = kline.getValue("0902").replace(" ", "")
        assertEquals("SB1BR32J400033334", ObdSession.parseVin(raw))
    }

    @Test
    fun decodesDtcs() {
        assertEquals(listOf("P0171", "P0133"), Dtc.parse("43 01 71 01 33 00 00", 0x43, isCan = false))
        assertEquals(listOf("P0300", "C0123", "U0100"), Dtc.parse("43 03 03 00 41 23 C1 00", 0x43, isCan = true))
        assertEquals(emptyList(), Dtc.parse("43 00", 0x43, isCan = true))
        assertEquals("B1A2F", Dtc.decode(0x9A, 0x2F))
    }

    @Test
    fun parsesPidDataAndErrors() {
        assertEquals(listOf(0x1A, 0xF8), ElmResponse.pidData("41 0C 1A F8", 0x0C)!!.map { it.toInt() and 0xFF })
        assertEquals(listOf(0x1A, 0xF8), ElmResponse.pidData("410C1AF8\r", 0x0C)!!.map { it.toInt() and 0xFF })
        assertNull(ElmResponse.pidData("NO DATA", 0x0C))
        assertEquals("NO DATA", ElmResponse.error("NO DATA\r\r"))
        assertEquals("BUS INIT: ...ERROR", ElmResponse.error("SEARCHING...\rBUS INIT: ...ERROR\r"))
    }

    @Test
    fun sessionDiscoversKLineCar() {
        val elm = FakeElm(kline)
        val session = ObdSession(elm, resetDelayMs = 0)
        assertEquals("ELM327 v2.1", session.initAdapter().id)
        assertTrue(session.connectEcu())
        assertEquals(5, session.protocolNumber)
        assertEquals("ISO 14230-4 (KWP FAST)", session.protocolName)
        assertTrue(session.singleResponse)

        val info = session.readVehicleInfo()
        // BE 3E B8 11 -> 01 03-07 0B-0F 11 13-15 1C 20, then 0120 adds 21
        assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x14, 0x15, 0x1C, 0x20, 0x21),
            info.supportedPids)
        assertEquals(true, info.dtcs.milOn)
        assertEquals(2, info.dtcs.reportedCount)
        assertEquals(listOf("P0171", "P0133"), info.dtcs.stored)
        assertEquals(emptyList(), info.dtcs.pending)
        assertNull(info.dtcs.permanent)
    }

    /** Real log: the clone answered ATZ late, so ATE0 was lost and every reply carried the echo. */
    @Test
    fun echoingCloneOnIso9141() {
        assertEquals("A3", ElmConnection.stripEcho("ATDPN", "ATDPN\rA3\r\r").trim())
        assertEquals("4100BFBFB991", ElmConnection.stripEcho("01001", "01001\r4100BFBFB991\r").trim())
        assertEquals("NO DATA", ElmConnection.stripEcho("0902", "NO DATA").trim())

        val echoing = kline.mapValues { (cmd, reply) -> "$cmd\r$reply" } +
            ("ATDPN" to "ATDPN\rA3\r") + ("ATDP" to "ATDP\rAUTO, ISO 9141-2\r")
        val session = ObdSession(FakeElm(echoing), resetDelayMs = 0)
        session.initAdapter()
        assertTrue(session.connectEcu())
        assertEquals(3, session.protocolNumber)
        assertEquals("ISO 9141-2", session.protocolName)
        assertEquals("686AF1", session.defaultHeader)
    }

    @Test
    fun ecuNotAnsweringIsReported() {
        val session = ObdSession(FakeElm(kline + ("0100" to "SEARCHING...\rUNABLE TO CONNECT\r")), resetDelayMs = 0)
        session.initAdapter()
        assertTrue(!session.connectEcu())
        assertEquals("UNABLE TO CONNECT", session.lastError)
    }

    @Test
    fun loggerWritesRowsAndPollsSlowItemsEveryFifthCycle() {
        val supported = setOf(0x05, 0x06, 0x07, 0x0C, 0x0D, 0x14, 0x15)
        val items = Pids.pollItems(supported, singleResponse = true)
        val out = StringWriter()
        var now = 1_700_000_000_000L
        val logger = DataLogger(items, out, clock = { now.also { now += 250 } }, zone = ZoneOffset.UTC)
        logger.writeHeader()
        val elm = FakeElm(kline)
        repeat(6) { logger.cycle(elm, if (it == 1) "M1" else null) }

        val lines = out.toString().trim().lines()
        assertEquals(
            "time,t_s,battery_v,coolant_c,stft_b1_pct,ltft_b1_pct,rpm,speed_kmh,o2_b1s1_v,o2_b1s1_trim_pct,o2_b1s2_v,o2_b1s2_trim_pct,marker",
            lines[0],
        )
        assertEquals(7, lines.size)
        // cycle 0 polls everything
        assertTrue(lines[1].endsWith(",12.6,50,7.813,-12.5,1726,60,0.9,0,0.65,,"), lines[1])
        // cycle 1: slow columns empty, marker set
        assertTrue(lines[2].endsWith(",,,7.813,,1726,60,0.9,0,0.65,,M1"), lines[2])
        assertEquals(6, elm.sent.count { it == "010C1" })
        assertEquals(2, elm.sent.count { it == "01051" })
        assertEquals(listOf("M1"), logger.markers.map { it.first })
        assertEquals(1726.0, logger.stats.getValue("rpm").mean)

        val report = SessionReport("Toyota Avensis 2005", AdapterInfo("ELM327 v2.1", "12.6V"),
            ObdSession(FakeElm(kline), resetDelayMs = 0).run { initAdapter(); connectEcu(); readVehicleInfo() })
            .render(logger, null, now)
        assertTrue("P0171, P0133" in report)
        assertTrue("rpm: 1726 / 1726 / 1726 (6)" in report)
        assertTrue("o2_b1s2_trim_pct" in report)
    }

    @Test
    fun noRowWhenEcuSilentButAdapterAnswers() {
        val items = Pids.pollItems(setOf(0x0C), singleResponse = false)
        val out = StringWriter()
        val logger = DataLogger(items, out)
        val result = logger.cycle(FakeElm(mapOf("ATRV" to "12.1V", "010C" to "NO DATA")), null)
        assertTrue(!result.wroteRow)
        assertEquals("NO DATA", result.error)
        assertEquals(0, logger.rows)
    }

    @Test
    fun repeatedTimeoutsMeanLostAdapter() {
        val items = Pids.pollItems(setOf(0x04, 0x0B, 0x0C, 0x0D, 0x0E, 0x11), singleResponse = false)
        val logger = DataLogger(items, StringWriter())
        val elm = FakeElm(emptyMap(), silent = items.map { it.request }.toSet())
        assertFailsWith<IOException> { logger.cycle(elm, null) }
    }

    @Test
    fun connectionReadsUntilPromptAndTimesOut() {
        val input = ScriptedInput()
        val output = object : OutputStream() {
            val buf = ByteArrayOutputStream()
            override fun write(b: Int) {
                buf.write(b)
                if (b == '\r'.code) {
                    val cmd = buf.toString(Charsets.US_ASCII).trim()
                    buf.reset()
                    if (cmd == "010C") input.feed("41 0C 0F A0\r\r>")
                    if (cmd == "ATRV") input.feed("12.") // no prompt
                }
            }
        }
        val elm = ElmConnection(input, output)
        assertEquals(listOf(0x0F, 0xA0), ElmResponse.pidData(elm.command("010C"), 0x0C)!!.map { it.toInt() and 0xFF })
        val e = assertFailsWith<ElmTimeoutException> { elm.command("ATRV", 50) }
        assertEquals("12.", e.partial)
    }

    private class ScriptedInput : InputStream() {
        private val pending = ArrayDeque<Int>()
        fun feed(s: String) = s.forEach { pending.addLast(it.code) }
        override fun available() = pending.size
        override fun read(): Int = pending.removeFirstOrNull() ?: -1
    }
}
