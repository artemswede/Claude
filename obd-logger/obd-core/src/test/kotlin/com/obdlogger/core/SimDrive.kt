package com.obdlogger.core

import java.io.StringWriter
import java.time.ZoneId

/** One recorded simulated trip: what the app would have written to disk. */
class SimTrip(val name: String, val csv: String, val info: String, val columns: List<String>, val rows: List<Pair<Long, List<String?>>>)

/**
 * Runs the whole recording pipeline on a simulated car: adapter init, protocol,
 * PID discovery, [minutes] of logging. Used by tests and the screenshot harness.
 */
object SimDrive {
    fun drive(
        profile: CarProfile,
        minutes: Double = 20.0,
        startMs: Long = 1_790_000_000_000L,
        zone: ZoneId = ZoneId.systemDefault(),
        name: String = "obd_sim",
    ): SimTrip {
        var now = startMs
        val clock = { now.also { now += 60 } }
        val elm = SimulatedElm(clock, profile = profile)
        val session = ObdSession(elm, resetDelayMs = 0)
        session.initAdapter()
        check(session.connectEcu()) { "${profile.name}: ECU must answer" }
        val info = session.readVehicleInfo()
        val raw = session.discoverRawPids(info.supportedPids)
        val ext = session.discoverExtended()
        val out = StringWriter()
        val logger = DataLogger(Pids.pollItems(info.supportedPids, session.singleResponse, raw, ext), out,
            clock = clock, zone = zone, defaultHeader = session.defaultHeader)
        logger.writeHeader()
        val rows = ArrayList<Pair<Long, List<String?>>>()
        while (now - logger.startMs < minutes * 60_000L) {
            if (logger.cycle(elm, null).wroteRow) rows += logger.lastRowMs to logger.lastRow
        }
        val infoText = SessionReport(profile.name, AdapterInfo("sim", "12.6V"), info).render(logger, session.readDtcs(), now)
        return SimTrip(name, out.toString(), infoText, logger.columns.map { it.name }, rows)
    }
}
