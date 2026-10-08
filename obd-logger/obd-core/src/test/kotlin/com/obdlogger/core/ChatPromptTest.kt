package com.obdlogger.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The AI chat's context: the car's real trips, codes and check logs as a compact text. */
class ChatPromptTest {
    private fun trips(): List<TripSummary> {
        val dir = File(javaClass.getResource("/trips")!!.toURI())
        return dir.listFiles()!!.filter { it.name.endsWith(".csv") }.sortedBy { it.name }.mapNotNull { f ->
            TripAnalyzer.analyze(f.nameWithoutExtension, f.readText(), File(dir, f.nameWithoutExtension + "_info.txt").takeIf { it.exists() }?.readText())
        }
    }

    @Test
    fun systemPromptCarriesTheTripsVersionsAndCodes() {
        val t = trips()
        val text = ChatPrompt.system("Avensis 2.0 D-4", t, emptyList(), "Ошибки: P0136, P0156", null)
        assertTrue(text.contains("Avensis 2.0 D-4"))
        assertTrue(text.contains("P0136"))
        assertTrue(text.contains("ВЫВОДЫ БОРТАЧА"))
        // A version found in the real trips is in the context, with its evidence.
        assertTrue(t.any { it.top != null })
        assertTrue(t.mapNotNull { it.top?.headline }.any { text.contains(it) }, text.take(600))
        // Compact: a few thousand characters, not the raw CSV.
        assertTrue(text.length < 12_000, "length ${text.length}")
    }

    @Test
    fun historyKeepsTheLatest() {
        val all = (1..30).map { ChatMessage(if (it % 2 == 0) "assistant" else "user", "m$it") }
        val h = ChatPrompt.history(all)
        assertTrue(h.size == ChatPrompt.HISTORY && h.last().text == "m30")
    }
}
