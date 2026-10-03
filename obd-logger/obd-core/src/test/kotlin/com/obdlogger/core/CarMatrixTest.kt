package com.obdlogger.core

import java.io.StringWriter
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whole pipeline on simulated cars with different faults and protocols:
 * adapter init → protocol → PID discovery → 20 min of logging → trip analysis →
 * what the main screen may claim. Each car must get its own diagnosis, and a
 * healthy car must not get any.
 */
class CarMatrixTest {
    private class Run(val session: ObdSession, val info: VehicleInfo, val trip: TripSummary, val csv: String)

    private fun drive(profile: CarProfile, minutes: Int = 20): Run {
        var now = 1_790_000_000_000L
        val clock = { now.also { now += 60 } }
        val elm = SimulatedElm(clock, profile = profile)
        val session = ObdSession(elm, resetDelayMs = 0)
        session.initAdapter()
        assertTrue(session.connectEcu(), "${profile.name}: ECU must answer")
        val info = session.readVehicleInfo()
        val raw = session.discoverRawPids(info.supportedPids)
        val ext = session.discoverExtended()
        val out = StringWriter()
        val logger = DataLogger(Pids.pollItems(info.supportedPids, session.singleResponse, raw, ext), out,
            clock = clock, zone = ZoneOffset.UTC, defaultHeader = session.defaultHeader)
        logger.writeHeader()
        while (now - logger.startMs < minutes * 60_000L) logger.cycle(elm, null)
        val infoText = SessionReport(profile.name, AdapterInfo("sim", "12.6V"), info).render(logger, session.readDtcs(), now)
        val trip = TripAnalyzer.analyze(profile.name, out.toString(), infoText)!!
        return Run(session, info, trip, out.toString())
    }

    private fun state(r: Run) = HomeLogic.decide(r.trip, null, recording = true, auto = true, linkFailed = false)

    @Test
    fun healthyCarGetsNoVersion() {
        val r = drive(CarProfile.HEALTHY)
        assertNull(r.trip.top, "findings: ${r.trip.findings.map { it.title }}")
        assertEquals(HomeState.CALM, state(r))
        assertTrue(r.trip.warmIdleSec >= HomeLogic.NEED_IDLE_SEC, "warm idle ${r.trip.warmIdleSec}")
    }

    @Test
    fun leanIdleIsAirLeak() {
        val r = drive(CarProfile.LEAN_IDLE)
        assertEquals("Подсос воздуха на холостом", r.trip.top?.headline)
        assertEquals(HomeState.VERSION, state(r))
        assertTrue(r.trip.top!!.why.any { it.label.startsWith("Задняя лямбда") })
    }

    @Test
    fun leanEverywhereIsFuelDelivery() {
        val r = drive(CarProfile.LEAN_ALL)
        assertEquals("Бедная смесь на всех режимах", r.trip.top?.headline)
    }

    @Test
    fun richIdle() {
        val r = drive(CarProfile.RICH_IDLE)
        assertEquals("Богатая смесь на холостом", r.trip.top?.headline)
    }

    @Test
    fun idleDips() {
        val r = drive(CarProfile.IDLE_DIPS)
        assertEquals("Провалы холостого хода", r.trip.top?.headline, "findings: ${r.trip.findings.map { it.title }}")
        assertTrue(r.trip.metrics.getValue(Metric.RPM_DIPS) >= 3)
    }

    @Test
    fun weakCharging() {
        val r = drive(CarProfile.WEAK_CHARGE)
        assertEquals("Слабая зарядка", r.trip.top?.headline)
    }

    @Test
    fun overheating() {
        val r = drive(CarProfile.OVERHEAT)
        assertEquals("Перегрев", r.trip.top?.headline)
        assertEquals(Severity.BAD, r.trip.top?.severity)
    }

    @Test
    fun thermostatStuckOpen() {
        val r = drive(CarProfile.THERMOSTAT_OPEN, minutes = 25)
        assertEquals("Мотор не прогревается", r.trip.top?.headline, "findings: ${r.trip.findings.map { it.title }}")
        assertEquals(HomeState.VERSION, state(r))
    }

    @Test
    fun canCarHealthy() {
        val r = drive(CarProfile.CAN_HEALTHY)
        assertEquals(6, r.session.protocolNumber)
        assertEquals("DEMOSIMULATED0001", r.info.vin)
        assertTrue(r.session.discoverExtended().isEmpty(), "non-Toyota CAN car has no mode 21")
        assertNull(r.trip.top)
        assertEquals(HomeState.CALM, state(r))
    }

    @Test
    fun canCarWithCodes() {
        val r = drive(CarProfile.CAN_DTC)
        assertEquals(listOf("P0300", "P0420"), r.info.dtcs.stored)
        assertEquals(true, r.info.dtcs.milOn)
        assertEquals(listOf("P0300", "P0420"), r.trip.dtcs)
        assertEquals(HomeState.DTC, state(r))
    }

    @Test
    fun kLineCarWithCode() {
        val r = drive(CarProfile.DEMO)
        assertEquals(4, r.session.protocolNumber)
        assertEquals(listOf("P0171"), r.info.dtcs.stored)
        assertEquals(HomeState.DTC, state(r))
        assertTrue(r.trip.findings.any { it.headline == "Подсос воздуха на холостом" }, "findings: ${r.trip.findings.map { it.headline }}")
    }

    @Test
    fun everyProfileProducesConsistentCsv() {
        for (p in CarProfile.ALL) {
            val r = drive(p, minutes = 6)
            val lines = r.csv.trim().lines()
            val width = lines.first().split(",").size
            assertTrue(lines.all { it.split(",").size == width }, "${p.name}: ragged CSV")
            assertTrue(lines.size > 50, "${p.name}: only ${lines.size} rows")
        }
    }
}

/** The main screen must not claim anything it has no data for. */
class HomeLogicTest {
    private fun trip(minutes: Double, idleSec: Double, top: Boolean, dtcs: List<String>? = emptyList()) = TripSummary(
        "t", null, minutes, 100, emptyMap(), emptyMap(), dtcs,
        if (top) listOf(Finding(Severity.WARN, "x", "e", "a", "высокая")) else emptyList(),
        null, idleSec,
    )

    @Test
    fun notRecording() {
        val last = trip(40.0, 600.0, top = true)
        assertEquals(HomeState.NO_TRIPS, HomeLogic.decide(null, null, recording = false, auto = false, linkFailed = false))
        assertEquals(HomeState.OFF, HomeLogic.decide(null, last, recording = false, auto = false, linkFailed = false))
        assertEquals(HomeState.WAIT, HomeLogic.decide(null, last, recording = false, auto = true, linkFailed = false))
        assertEquals(HomeState.NO_CONNECTION, HomeLogic.decide(null, last, recording = false, auto = true, linkFailed = true))
    }

    @Test
    fun recordingNeedsEnoughData() {
        assertEquals(HomeState.COLLECTING, HomeLogic.decide(null, null, recording = true, auto = false, linkFailed = false))
        assertEquals(HomeState.COLLECTING, HomeLogic.decide(trip(2.0, 0.0, top = true), null, true, false, false))
        assertEquals(HomeState.VERSION, HomeLogic.decide(trip(6.0, 0.0, top = true), null, true, false, false))
        assertEquals(HomeState.COLLECTING, HomeLogic.decide(trip(10.0, 60.0, top = false), null, true, false, false))
        assertEquals(HomeState.CALM, HomeLogic.decide(trip(10.0, 130.0, top = false), null, true, false, false))
        assertEquals(HomeState.DTC, HomeLogic.decide(trip(1.0, 0.0, top = false, dtcs = listOf("P0171")), null, true, false, false))
        assertEquals(0.5, HomeLogic.progress(trip(10.0, 60.0, top = false)), 1e-9)
        assertTrue(!HomeLogic.conclusive(trip(3.0, 600.0, top = true)))
    }
}
