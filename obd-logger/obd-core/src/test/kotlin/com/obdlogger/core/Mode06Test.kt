package com.obdlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class Mode06Test {
    @Test fun canParseSingleRecord() {
        // 46 MID=01 TID=80 Unit=07(RPM*0.25) TV=00C8(200) Min=0000 Max=FFFF
        val raw = "46 01 80 07 00 C8 00 00 FF FF"
        val results = Mode06Parser.parse(raw, 0x01, isCan = true)
        assertEquals(1, results.size)
        val r = results[0]
        assertEquals(0x01, r.mid)
        assertEquals(0x80, r.tid)
        assertEquals(0x07, r.unitScaling)
        assertEquals(50.0, r.value, 0.01) // 200 * 0.25
        assertTrue(r.passed)
    }

    @Test fun canParseMultipleRecords() {
        // Two records in one CAN message
        val raw = "46 01 80 07 00 C8 00 00 FF FF 01 81 0A 03 E8 00 01 07 D0"
        val results = Mode06Parser.parse(raw, 0x01, isCan = true)
        assertEquals(2, results.size)
        assertEquals(0x80, results[0].tid)
        assertEquals(0x81, results[1].tid)
    }

    @Test fun nonCanParse() {
        // 46 MID=01 TID=05 TV=0100(256) Min=0050(80) Max=0200(512)  00 00
        val raw = "46 01 05 01 00 00 50 02 00 00 00"
        val results = Mode06Parser.parse(raw, 0x01, isCan = false)
        assertEquals(1, results.size)
        val r = results[0]
        assertEquals(0x01, r.mid)
        assertEquals(0x05, r.tid)
        assertEquals(256.0, r.value, 0.01)
        assertEquals(80.0, r.min, 0.01)
        assertEquals(512.0, r.max, 0.01)
        assertTrue(r.passed) // 256 in 80..512
    }

    @Test fun failedTest() {
        // TV=0300(768) > Max=0200(512)
        val raw = "46 01 05 03 00 00 50 02 00 00 00"
        val results = Mode06Parser.parse(raw, 0x01, isCan = false)
        assertEquals(1, results.size)
        assertFalse(results[0].passed)
    }

    @Test fun supportedMids() {
        // Bitmap: 80000000 → only MID 01 supported
        val raw = "46 00 80 00 00 00"
        val mids = Mode06Parser.supportedMids(0x00, raw)
        assertTrue(0x01 in mids)
        assertFalse(0x02 in mids)
    }

    @Test fun supportedMidsMultiple() {
        // Bitmap: C0000000 → MIDs 01 and 02 supported
        val raw = "46 00 C0 00 00 00"
        val mids = Mode06Parser.supportedMids(0x00, raw)
        assertTrue(0x01 in mids)
        assertTrue(0x02 in mids)
        assertFalse(0x03 in mids)
    }

    @Test fun midNames() {
        assertEquals("Кислородный датчик B1S1", Mode06Names.midName(0x01))
        assertEquals("Катализатор B1", Mode06Names.midName(0x21))
        assertEquals("Пропуски зажигания, общие", Mode06Names.midName(0xA1))
        assertTrue(Mode06Names.midName(0xFF).startsWith("Монитор"))
    }

    @Test fun emptyResponse() {
        val results = Mode06Parser.parse("NO DATA", 0x01, isCan = true)
        assertTrue(results.isEmpty())
    }

    @Test fun zeroLimitsAlwaysPass() {
        // Min=0, Max=0 → always pass regardless of value
        val raw = "46 01 05 00 FF 00 00 00 00 00 00"
        val results = Mode06Parser.parse(raw, 0x01, isCan = false)
        assertEquals(1, results.size)
        assertTrue(results[0].passed)
    }

    @Test fun unitScaling() {
        assertEquals("об/мин", Mode06Units.name(0x07))
        assertEquals("В", Mode06Units.name(0x0A))
        assertEquals("", Mode06Units.name(0xFF)) // unknown
        assertEquals(50.0, Mode06Units.scale(0x07, 200), 0.01) // 200 * 0.25
        assertEquals(1.0, Mode06Units.scale(0x0A, 1000), 0.01) // 1000 * 0.001
    }

    @Test fun snapshotCounts() {
        val results = listOf(
            Mode06Result(1, 1, 50.0, 0.0, 100.0, true, 0x07),
            Mode06Result(1, 2, 200.0, 0.0, 100.0, false, 0x07),
            Mode06Result(2, 1, 50.0, 0.0, 100.0, true, 0x07),
        )
        val snap = Mode06Snapshot(results)
        assertEquals(3, snap.totalCount)
        assertEquals(2, snap.passedCount)
        assertEquals(1, snap.failedCount)
    }
}
