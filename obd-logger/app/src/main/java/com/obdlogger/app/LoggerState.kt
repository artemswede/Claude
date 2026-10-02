package com.obdlogger.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide session state shared by [LoggerService] (writer) and [MainActivity] (reader). */
object LoggerState {
    data class Snapshot(
        val running: Boolean = false,
        val status: String = "Готов к работе",
        val rows: Int = 0,
        val elapsedSec: Long = 0,
        val cycleMs: Long = 0,
        val values: List<Pair<String, String>> = emptyList(),
        val dtcInfo: String = "",
        val markers: Int = 0,
        val exported: List<Uri> = emptyList(),
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
