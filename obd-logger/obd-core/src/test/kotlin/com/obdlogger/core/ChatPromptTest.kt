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
    fun statsGiveEverySensorPerModeForPatterns() {
        val t = trips()
        val details = t.map { s ->
            val dir = File(javaClass.getResource("/trips")!!.toURI())
            TripDetail(TripAnalyzer.table(File(dir, s.name + ".csv").readText())!!, s)
        }
        val text = ChatPrompt.system("Avensis", t, emptyList(), "", null, details, memory = "Гипотеза: подсос воздуха — проверяется")
        assertTrue(text.contains("СТАТИСТИКА ДАТЧИКОВ ПО РЕЖИМАМ"))
        assertTrue(text.contains("trim_b1: ") && text.contains("WARM_IDLE "), text.takeLast(800))
        assertTrue(text.contains("ПАМЯТЬ ЧАТА") && text.contains("подсос воздуха — проверяется"))
        // Still well inside the window, with room for a long conversation.
        assertTrue(ChatMemory.tokens(text) < ChatMemory.WINDOW_TOKENS / 3, "tokens ${ChatMemory.tokens(text)}")
        // The trend the assistant may ask for comes from the same numbers.
        assertTrue(ChatCharts.trend(details, "trim_b1", DriveMode.WARM_IDLE).size >= 2)
        assertTrue(ChatCharts.trip(details, "последняя") != null)
    }

    @Test
    fun longChatIsFoldedKeepingTheLast30() {
        val big = "x".repeat(4_500)
        val state = ChatState((1..60).map { ChatMessage(if (it % 2 == 0) "assistant" else "user", "m$it $big") }.toMutableList())
        val system = "s".repeat(10_000)
        // 60 × 4 500 chars ≈ 108K tokens + system > 80 % of 128K.
        assertTrue(ChatMemory.needsCompression(system, state))
        assertTrue(ChatMemory.older(state).size == 30 && ChatMemory.older(state).first().text.startsWith("m1 "))
        val prompt = ChatMemory.compressionPrompt(state)
        assertTrue(prompt.contains("гипотезы") && prompt.contains("m1 "))
        assertTrue(ChatMemory.targetChars(state) in 55_000..65_000, "${ChatMemory.targetChars(state)}")
        ChatMemory.apply(state, "конспект")
        assertTrue(state.messages.size == ChatMemory.PROTECT_LAST && state.messages.first().text.startsWith("m31 ") && state.summary == "конспект")
        assertTrue(!ChatMemory.needsCompression(system, state))
        // A short chat is never folded.
        assertTrue(!ChatMemory.needsCompression(system, ChatState((1..40).map { ChatMessage("user", "коротко") }.toMutableList())))
    }

    @Test
    fun chartTagsAreReadAndRemoved() {
        val answer = "Смотрите тренд:\n[график: тренд trim_b1 WARM_IDLE]\nи поездку:\n[график: поездка rpm 03.10 19:51]\n[график: тренд o2_b1s2_v]"
        val charts = ChatCharts.parse(answer)
        assertTrue(charts == listOf(
            ChartRequest.Trend("trim_b1", DriveMode.WARM_IDLE),
            ChartRequest.Trip("rpm", "03.10 19:51"),
            ChartRequest.Trend("o2_b1s2_v", null),
        ), charts.toString())
        assertTrue(!ChatCharts.strip(answer).contains("[график"))
    }
}
