package com.obdlogger.core

import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full pipeline (adapter init → vehicle info → logging → report) against the
 * simulated car, as the app's demo mode runs it. Leaves sample output in
 * build/demo-sample for inspection.
 */
class DemoSessionTest {

    @Test
    fun simulatedDriveProducesMachineReadableCsvAndReport() {
        var now = 1_790_000_000_000L // fixed start so the sample is reproducible
        val clock = { now.also { now += 60 } } // ~120 ms per request, like K-line
        val elm = SimulatedElm(clock)
        val session = ObdSession(elm, resetDelayMs = 0)
        val adapter = session.initAdapter()
        assertTrue(session.connectEcu())
        val info = session.readVehicleInfo()
        assertEquals("DEMOSIMULATED0001", info.vin)
        assertEquals(listOf("P0171"), info.dtcs.stored)

        val rawPids = session.discoverRawPids(info.supportedPids)
        assertEquals(mapOf(0x01 to 4, 0x12 to 1, 0x13 to 1, 0x1C to 1), rawPids)
        val extended = session.discoverExtended()
        assertEquals(listOf(0x01 to 9, 0x03 to 3), extended.map { it.id to it.length })
        assertTrue(extended.all { it.ecu == "fn" && it.header == null })

        val dir = File("build/demo-sample").apply { mkdirs() }
        val csvFile = File(dir, "obd_demo.csv")
        val logger = csvFile.bufferedWriter().use { w ->
            val logger = DataLogger(Pids.pollItems(info.supportedPids, session.singleResponse, rawPids, extended), w,
                clock = clock, zone = ZoneId.of("Europe/Moscow"), defaultHeader = session.defaultHeader)
            logger.writeHeader()
            var cycle = 0
            while (now - logger.startMs < 12 * 60_000) {
                logger.cycle(elm, if (cycle == 150) "M1" else null)
                cycle++
            }
            logger
        }
        val report = SessionReport("Toyota Avensis 2005 1.8 (ДЕМО)", adapter, info)
            .render(logger, session.readDtcs(), now)
        File(dir, "obd_demo_info.txt").writeText(report)

        val lines = csvFile.readLines()
        val header = lines.first().split(",")
        assertTrue(lines.size > 300, "rows: ${lines.size}")
        // Machine-readable: same number of fields in every row, numbers with a dot.
        assertTrue(lines.all { it.split(",").size == header.size })
        assertTrue(lines.drop(1).all { Regex("^\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3},").containsMatchIn(it) })
        assertEquals(1, lines.count { it.endsWith(",M1") })
        // The demo car has learned +18 % for warm idle only.
        assertEquals(18.0, logger.stats.getValue("ltft_b1_pct").max, 0.8)
        assertTrue(logger.stats.getValue("coolant_c").max > 85)
        assertTrue(logger.stats.getValue("speed_kmh").max >= 85)
        assertTrue("P0171" in report && "M1 в " in report)
        assertTrue(listOf("pid01_12_b00", "m21_fn_01_b00", "m21_fn_01_b08", "m21_fn_03_b02").all { it in header })
        assertTrue("m21_fn_01_b00…b08 — Скрытый блок Toyota 21 01" in report)
        // raw bytes are filled: coolant byte of 21 01 tracks the decoded coolant
        val i = header.indexOf("m21_fn_01_b02")
        val lastCoolantByte = lines.last().split(",")[i].toInt()
        assertTrue(lastCoolantByte in 128..132, "coolant byte $lastCoolantByte")
    }
}
