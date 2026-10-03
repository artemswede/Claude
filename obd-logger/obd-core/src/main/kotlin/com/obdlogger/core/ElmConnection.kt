package com.obdlogger.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Text command channel to an ELM327-compatible adapter. */
interface ElmIo {
    /**
     * Sends [cmd] and returns everything the adapter printed before the `>` prompt.
     * Throws [ElmTimeoutException] if the prompt does not arrive within [timeoutMs]
     * and [IOException] if the link itself is broken.
     */
    fun command(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}

class ElmTimeoutException(val cmd: String, val partial: String) :
    Exception("Адаптер не ответил на '$cmd' вовремя")

/**
 * ELM327 over a pair of byte streams (Bluetooth SPP socket on Android).
 *
 * Uses `available()` polling instead of blocking reads so a silent clone
 * adapter can never hang the caller past the timeout.
 */
class ElmConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val trace: (String) -> Unit = {},
) : ElmIo {

    override fun command(cmd: String, timeoutMs: Long): String {
        discardPending()
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()

        val text = StringBuilder()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val available = input.available()
            if (available > 0) {
                val buf = ByteArray(available)
                val n = input.read(buf)
                if (n < 0) throw IOException("Соединение с адаптером закрыто")
                for (i in 0 until n) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    if (c == '>') {
                        val result = stripEcho(cmd, text.toString())
                        trace("$cmd -> ${oneLine(result)}")
                        return result
                    }
                    if (c != '\u0000') text.append(c)
                }
            } else {
                if (System.nanoTime() > deadline) {
                    trace("$cmd -> TIMEOUT [${oneLine(text.toString())}]")
                    throw ElmTimeoutException(cmd, text.toString())
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    /** Drops late output of a previous timed-out command so it is not taken as this reply. */
    private fun discardPending() {
        while (input.available() > 0) {
            if (input.read(ByteArray(input.available())) < 0) {
                throw IOException("Соединение с адаптером закрыто")
            }
        }
    }

    private fun oneLine(s: String) = s.trim().replace(Regex("[\r\n]+"), " | ")

    companion object {
        private const val POLL_INTERVAL_MS = 5L

        /** Drops the adapter's echo of [cmd] (clones often keep echo on despite ATE0). */
        fun stripEcho(cmd: String, reply: String): String {
            val lines = reply.split('\r', '\n')
            val first = lines.indexOfFirst { it.isNotBlank() }
            if (first < 0) return reply
            val norm = { s: String -> s.replace(" ", "").uppercase() }
            return if (norm(lines[first]) == norm(cmd)) lines.drop(first + 1).joinToString("\r") else reply
        }
    }
}
