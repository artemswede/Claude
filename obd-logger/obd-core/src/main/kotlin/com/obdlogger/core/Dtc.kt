package com.obdlogger.core

import java.util.Locale

/** Diagnostic trouble codes (modes 03 / 07 / 0A). */
object Dtc {
    fun decode(hi: Int, lo: Int): String =
        String.format(Locale.ROOT, "%c%d%X%02X", "PCBU"[hi shr 6], (hi shr 4) and 0x3, hi and 0xF, lo)

    /**
     * [responseMode] is 0x43, 0x47 or 0x4A. On CAN the byte after the mode is a
     * code count; on K-line / J1850 every frame carries 3 codes padded with zeros.
     */
    fun parse(raw: String, responseMode: Int, isCan: Boolean): List<String> {
        val codes = LinkedHashSet<String>()
        for (m in ElmResponse.messages(raw)) {
            if (m.u(0) != responseMode) continue
            var i = if (isCan) 2 else 1
            while (i + 1 < m.size) {
                val hi = m.u(i)
                val lo = m.u(i + 1)
                if (hi != 0 || lo != 0) codes.add(decode(hi, lo))
                i += 2
            }
        }
        return codes.toList()
    }
}
