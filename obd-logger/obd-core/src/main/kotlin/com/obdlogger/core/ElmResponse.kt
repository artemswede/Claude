package com.obdlogger.core

internal fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

/** Parsing of raw ELM327 text replies (headers off, spaces on or off). */
object ElmResponse {
    private val FRAME_INDEX = Regex("^([0-9A-Fa-f]):\\s*(.*)$")

    fun lines(raw: String): List<String> =
        raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Data messages contained in a reply. Multi-frame ISO 15765 (CAN) replies,
     * printed by the ELM as `0: ..`, `1: ..` lines, are joined into one message.
     * Non-hex lines (`SEARCHING...`, `NO DATA`, echo of AT commands) and the
     * 3-digit CAN byte-count line are skipped.
     */
    fun messages(raw: String): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var multi: MutableList<Byte>? = null
        for (line in lines(raw)) {
            val frame = FRAME_INDEX.matchEntire(line)
            if (frame != null) {
                val bytes = parseHex(frame.groupValues[2]) ?: continue
                var target = multi
                if (frame.groupValues[1] == "0" && target != null) {
                    result.add(target.toByteArray())
                    target = null
                }
                if (target == null) target = mutableListOf()
                target.addAll(bytes.toList())
                multi = target
                continue
            }
            val bytes = parseHex(line) ?: continue
            if (bytes.size < 2) continue
            multi?.let { result.add(it.toByteArray()) }
            multi = null
            result.add(bytes)
        }
        multi?.let { result.add(it.toByteArray()) }
        return result
    }

    fun parseHex(text: String): ByteArray? {
        val compact = text.filterNot { it == ' ' }
        if (compact.isEmpty() || compact.length % 2 != 0) return null
        if (!compact.all { it in '0'..'9' || it.uppercaseChar() in 'A'..'F' }) return null
        return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** What the adapter printed instead of data (`NO DATA`, `UNABLE TO CONNECT`...), or null if data is present. */
    fun error(raw: String): String? {
        if (messages(raw).isNotEmpty()) return null
        return lines(raw).filterNot { it.startsWith("SEARCHING") }.joinToString(" ").ifEmpty { "пустой ответ" }
    }

    /** Payload of a mode 01 reply for [pid], without the `41 xx` prefix. */
    fun pidData(raw: String, pid: Int): ByteArray? =
        messages(raw)
            .firstOrNull { it.size > 2 && it.u(0) == 0x41 && it.u(1) == pid }
            ?.let { it.copyOfRange(2, it.size) }
}
