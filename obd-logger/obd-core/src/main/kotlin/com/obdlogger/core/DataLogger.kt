package com.obdlogger.core

import java.io.IOException
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Csv {
    fun row(values: List<String?>): String = values.joinToString(",") { v ->
        when {
            v == null -> ""
            v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } -> "\"" + v.replace("\"", "\"\"") + "\""
            else -> v
        }
    }
}

class ColumnStats {
    var count = 0
        private set
    var min = Double.POSITIVE_INFINITY
        private set
    var max = Double.NEGATIVE_INFINITY
        private set
    private var sum = 0.0
    val mean get() = if (count == 0) Double.NaN else sum / count

    fun add(v: Double) {
        count++
        sum += v
        if (v < min) min = v
        if (v > max) max = v
    }
}

data class CycleResult(
    val wroteRow: Boolean,
    val durationMs: Long,
    val error: String?,
    /** The adapter printed its boot banner: it rebooted (e.g. voltage dip while cranking) and lost its settings. */
    val adapterReset: Boolean = false,
)

/**
 * Polls [items] in cycles and writes one CSV row per cycle. Slow items are
 * polled every [slowEvery] cycles; in the other rows their cells stay empty.
 */
class DataLogger(
    val items: List<PollItem>,
    private val out: Writer,
    private val slowEvery: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
    /** Protocol default header; items with their own header are switched to and back. */
    private val defaultHeader: String? = null,
) {
    private var currentHeader = defaultHeader
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
    val columns: List<Column> = items.flatMap { it.columns }
    val startMs = clock()
    var lastRowMs = startMs
        private set
    var rows = 0
        private set
    private var cycles = 0
    private var totalCycleMs = 0L
    private var consecutiveTimeouts = 0
    val stats = LinkedHashMap<String, ColumnStats>()
    /** Last known value per column. */
    val latest = LinkedHashMap<String, String>()
    val markers = mutableListOf<Pair<String, String>>()
    /** Values of the last written row, aligned with [columns]. */
    var lastRow: List<String?> = emptyList()
        private set

    val avgCycleMs get() = if (rows == 0) 0L else totalCycleMs / rows

    /** The adapter was reset (reconnect): its header is the default again. */
    fun onReconnect() {
        currentHeader = defaultHeader
    }

    fun formatTime(ms: Long): String = timeFormat.format(Instant.ofEpochMilli(ms))

    fun writeHeader() {
        out.write(Csv.row(listOf("time", "t_s") + columns.map { it.name } + "marker"))
        out.write("\n")
        out.flush()
    }

    /** Polls one cycle. A row is written only if the ECU answered at least one request. */
    fun cycle(elm: ElmIo, marker: String?): CycleResult {
        val includeSlow = cycles % slowEvery == 0
        cycles++
        val t = clock()
        val values = ArrayList<String?>(columns.size)
        var ecuAnswered = 0
        var ecuFailed = 0
        var lastError: String? = null
        var adapterReset = false
        for (item in items) {
            // Engine off / restarting: stop early instead of waiting out every request of the cycle.
            if (ecuAnswered == 0 && ecuFailed >= SILENT_REQUESTS_TO_GIVE_UP) break
            if (item.slow && !includeSlow) {
                repeat(item.columns.size) { values.add(null) }
                continue
            }
            val raw = try {
                val header = item.header ?: defaultHeader
                if (header != null && header != currentHeader) {
                    elm.command("ATSH$header")
                    currentHeader = header
                }
                elm.command(item.request).also { consecutiveTimeouts = 0 }
            } catch (e: ElmTimeoutException) {
                if (++consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) throw IOException("Адаптер перестал отвечать", e)
                null
            }
            if (raw != null && raw.contains("ELM327")) {
                adapterReset = true
                break
            }
            val parsed = raw?.let(item::parse) ?: List(item.columns.size) { null }
            if (parsed.any { it != null }) {
                if (item.fromEcu) ecuAnswered++
            } else if (item.fromEcu) {
                ecuFailed++
                lastError = raw?.let(ElmResponse::error) ?: "таймаут"
            }
            values.addAll(parsed)
        }
        val duration = clock() - t
        if (adapterReset) return CycleResult(false, duration, "адаптер перезагрузился", adapterReset = true)
        if (ecuAnswered == 0) return CycleResult(false, duration, lastError)

        out.write(Csv.row(listOf(formatTime(t), Values.format((t - startMs) / 1000.0)) + values + marker))
        out.write("\n")
        out.flush()
        rows++
        lastRow = values
        totalCycleMs += duration
        lastRowMs = t
        if (marker != null) markers.add(marker to formatTime(t))
        columns.forEachIndexed { i, c ->
            val v = values[i] ?: return@forEachIndexed
            latest[c.name] = v
            v.toDoubleOrNull()?.let { stats.getOrPut(c.name, ::ColumnStats).add(it) }
        }
        return CycleResult(true, duration, lastError)
    }

    companion object {
        const val MAX_CONSECUTIVE_TIMEOUTS = 5
        const val SILENT_REQUESTS_TO_GIVE_UP = 4
    }
}
