package com.obdlogger.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Status lamp: grey = off/unknown, amber = trying, green = connected. */
enum class Lamp { OFF, WAIT, OK, FAIL }

/** Process-wide session state shared by [LoggerService] (writer) and [MainActivity] (reader). */
object LoggerState {
    data class Snapshot(
        val running: Boolean = false,
        /** Auto mode: the service waits for the engine and records every trip by itself. */
        val auto: Boolean = false,
        /** A trip is being written right now (false while auto mode waits). */
        val recording: Boolean = false,
        val status: String = "Готов к работе",
        /** Tablet ↔ ECU: Bluetooth, adapter and ECU answering. */
        val link: Lamp = Lamp.OFF,
        val linkText: String = "нет связи",
        /** ECU ↔ engine: engine running (rpm > 300). */
        val engine: Lamp = Lamp.OFF,
        val engineText: String = "неизвестно",
        val rows: Int = 0,
        val elapsedSec: Long = 0,
        val cycleMs: Long = 0,
        /** Wall time of the last written row; the monitor uses it to show data age. */
        val lastDataMs: Long = 0,
        val values: List<Pair<String, String>> = emptyList(),
        val dtcInfo: String = "",
        val markers: Int = 0,
        val exported: List<Uri> = emptyList(),
        /** Bumped when a trip is saved, so the trips tab refreshes. */
        val savedTrips: Int = 0,
        /** CSV of the trip being written, so the main screen can analyse it live. */
        val currentCsv: String? = null,
        /** «ISO 9141-2» — for the lamps tooltip. */
        val protocol: String = "",
        /** Check log in progress (or just finished / aborted). */
        val check: com.obdlogger.core.CheckTest.State? = null,
        /** CSV of the last finished check log, for the result screen. */
        val checkCsv: String? = null,
        /** Codes screen: what is being done with the ECU now («Читаю коды…»), null when idle. */
        val dtcBusy: String? = null,
        /** Last full read of the codes (stored, pending, permanent, MIL). */
        val dtcSnap: com.obdlogger.core.DtcSnapshot? = null,
        /** Freeze frame of that read; null when the ECU keeps none. */
        val freeze: com.obdlogger.core.FreezeFrame? = null,
        /** Saved report of that read (dtc_<time>.txt) and the outcome of a reset, if one was done. */
        val dtcReportFile: String? = null,
        val dtcResult: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    @Volatile
    var snapshot = Snapshot()
        private set

    private var markerCount = 0
    private var pendingMarker: String? = null

    fun update(change: (Snapshot) -> Snapshot) {
        val s = synchronized(this) { change(snapshot).also { snapshot = it } }
        main.post { listeners.forEach { it(s) } }
    }

    fun addListener(l: (Snapshot) -> Unit) = listeners.add(l)
    fun removeListener(l: (Snapshot) -> Unit) = listeners.remove(l)

    @Synchronized
    fun resetMarkers() {
        markerCount = 0
        pendingMarker = null
    }

    /** Queues a marker for the next written CSV row and returns its name. */
    fun requestMarker(): String {
        val name = synchronized(this) {
            markerCount++
            val m = "M$markerCount"
            pendingMarker = pendingMarker?.let { "$it $m" } ?: m
            m
        }
        update { it.copy(markers = markerCount) }
        return name
    }

    @Synchronized
    fun peekMarker(): String? = pendingMarker

    /** Removes [written] from the queue; markers added meanwhile stay pending. */
    @Synchronized
    fun consumeMarker(written: String?) {
        if (written == null) return
        pendingMarker = pendingMarker?.removePrefix(written)?.trim()?.ifEmpty { null }
    }
}
