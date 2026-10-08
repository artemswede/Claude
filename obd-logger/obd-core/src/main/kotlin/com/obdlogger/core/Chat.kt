package com.obdlogger.core

import java.util.Locale

/** One line of the chat: «user» or «assistant». */
data class ChatMessage(val role: String, val text: String)

/**
 * The one chat of the app: its messages and the running summary of what was compressed
 * out of them («память»). There is no list of chats — only a reset.
 */
class ChatState(val messages: MutableList<ChatMessage> = mutableListOf(), var summary: String = "")

/**
 * How the chat keeps a long conversation: when the request would take more than
 * [THRESHOLD] of the model's window, the older messages (all but the last [PROTECT_LAST])
 * are folded into the summary, sized to about [TARGET_RATIO] of what they were.
 * Facts, numbers, hypotheses and their status must survive the folding.
 */
object ChatMemory {
    /** Conservative window: DeepSeek models take at least 128K tokens. */
    const val WINDOW_TOKENS = 128_000
    const val THRESHOLD = 0.8
    const val TARGET_RATIO = 0.45
    const val PROTECT_LAST = 30

    /** Rough token count for Russian text with numbers (≈2.5 characters a token). */
    fun tokens(text: String): Int = (text.length / 2.5).toInt() + 1

    fun requestTokens(system: String, state: ChatState): Int =
        tokens(system) + tokens(state.summary) + state.messages.sumOf { tokens(it.text) + 4 }

    fun needsCompression(system: String, state: ChatState): Boolean =
        state.messages.size > PROTECT_LAST && requestTokens(system, state) > WINDOW_TOKENS * THRESHOLD

    /** The messages to fold now: everything but the protected tail. */
    fun older(state: ChatState): List<ChatMessage> = state.messages.dropLast(PROTECT_LAST)

    /** Target size of the new summary, in characters. */
    fun targetChars(state: ChatState): Int =
        ((older(state).sumOf { it.text.length } + state.summary.length) * TARGET_RATIO).toInt().coerceAtLeast(1500)

    /** The instruction that folds [older] messages into the summary (sent as its own request). */
    fun compressionPrompt(state: ChatState): String = buildString {
        appendLine("Сожми переписку водителя с помощником-диагностом в конспект примерно на ${targetChars(state)} символов.")
        appendLine("Сохрани обязательно: все числа и даты, какие поездки и датчики обсуждались, выдвинутые гипотезы и их статус " +
            "(подтверждена / опровергнута / проверяется и чем), что уже сделано с машиной и что договорились проверить. " +
            "Пиши по-русски, списком, без вступлений.")
        if (state.summary.isNotBlank()) {
            appendLine()
            appendLine("ПРЕЖНИЙ КОНСПЕКТ:")
            appendLine(state.summary)
        }
        appendLine()
        appendLine("ПЕРЕПИСКА:")
        older(state).forEach { appendLine((if (it.role == "user") "Водитель: " else "Помощник: ") + it.text) }
    }

    /** After a successful fold: the summary replaces the older messages. */
    fun apply(state: ChatState, summary: String) {
        val keep = state.messages.takeLast(PROTECT_LAST)
        state.messages.clear()
        state.messages.addAll(keep)
        state.summary = summary.trim()
    }
}

/** A chart the assistant asked for in its answer. */
sealed class ChartRequest {
    abstract val sensor: String

    /** One value per trip (median in [mode]) — how it changes from trip to trip. */
    data class Trend(override val sensor: String, val mode: DriveMode?) : ChartRequest()

    /** The sensor over one trip; [trip] is a label like «03.10 19:51» or «последняя». */
    data class Trip(override val sensor: String, val trip: String) : ChartRequest()
}

/**
 * Charts in answers: the assistant writes a line «[график: тренд trim_b1 WARM_IDLE]» or
 * «[график: поездка rpm последняя]»; the app draws it under the text.
 */
object ChatCharts {
    private val tag = Regex("\\[график:\\s*(тренд|поездка)\\s+([a-z0-9_]+)(?:\\s+([^\\]]+))?]", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<ChartRequest> = tag.findAll(text).mapNotNull { m ->
        val kind = m.groupValues[1].lowercase(Locale.ROOT)
        val sensor = m.groupValues[2].lowercase(Locale.ROOT)
        val arg = m.groupValues[3].trim()
        if (kind == "тренд") ChartRequest.Trend(sensor, DriveMode.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) })
        else ChartRequest.Trip(sensor, arg.ifEmpty { "последняя" })
    }.distinct().take(4).toList()

    /** The text without the chart tags. */
    fun strip(text: String): String = text.replace(tag, "").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** Per-trip medians of [sensor] in [mode] (whole trip if null), oldest first. */
    fun trend(details: List<TripDetail>, sensor: String, mode: DriveMode?): List<Pair<String, Double>> =
        details.sortedBy { it.summary.start }.mapNotNull { d ->
            val s = d.sensors.firstOrNull { it.code == sensor } ?: return@mapNotNull null
            val v = if (mode == null) s.median else s.byMode.firstOrNull { it.mode == mode }?.median
            v?.let { d.summary.label to it }
        }

    /** The trip a [ChartRequest.Trip] means. */
    fun trip(details: List<TripDetail>, label: String): TripDetail? {
        val sorted = details.sortedBy { it.summary.start }
        return if (label.startsWith("послед", ignoreCase = true)) sorted.lastOrNull()
        else sorted.lastOrNull { it.summary.label.contains(label.trim()) } ?: sorted.lastOrNull()
    }
}

/**
 * What the chat sends along with a question: who it is, what the car is, a summary of
 * this car's trips, check logs and codes, and per-mode statistics of every sensor for
 * the recent trips — so the model can look for patterns itself. Never raw CSV.
 */
object ChatPrompt {
    fun system(
        car: String,
        trips: List<TripSummary>,
        checks: List<CheckResult>,
        codes: String,
        live: String?,
        details: List<TripDetail> = emptyList(),
        memory: String = "",
    ): String = buildString {
        appendLine(
            "Ты — помощник-диагност в приложении «Бортач» (OBD-II логгер на ELM327). Отвечай по-русски, для водителя без спецподготовки, " +
                "но не упрощай выводы. Работай как исследователь: ищи закономерности между датчиками, режимами и поездками, " +
                "выдвигай гипотезы, для каждой — какие данные её подтверждают, какие опровергают и чем её проверить. " +
                "Отделяй факт из данных от предположения. Если данных не хватает — скажи, какую поездку или проверочный лог записать " +
                "(проверочный лог: 4 мин на стоянке — ХХ 2 мин, 2500 об/мин 1 мин, ХХ 1 мин). " +
                "Помни разговор и прежние гипотезы (см. ПАМЯТЬ ЧАТА). Не советуй опасного.",
        )
        appendLine()
        appendLine("ГРАФИКИ: если график поможет, вставь в ответ отдельной строкой тег — приложение нарисует его под текстом:")
        appendLine("  [график: тренд <датчик> <режим>] — медиана датчика по поездкам; режим: ${DriveMode.entries.joinToString("/") { it.name }} или пусто (вся поездка);")
        appendLine("  [график: поездка <датчик> <дата время поездки или «последняя»>] — датчик по ходу одной поездки " +
            "(доступны: ${Focus.CODES.joinToString(", ")}).")
        appendLine("Датчики — коды из статистики ниже (trim_b1 = LTFT+STFT банк 1, o2_b1s2_v = лямбда после катализатора и т. д.). Не больше 3 графиков в ответе.")
        appendLine()
        appendLine("МАШИНА: ${car.ifBlank { "не названа" }}")
        if (memory.isNotBlank()) {
            appendLine()
            appendLine("ПАМЯТЬ ЧАТА (конспект прежнего разговора):")
            appendLine(memory.trim())
        }
        if (codes.isNotBlank()) {
            appendLine()
            appendLine("КОДЫ ЭБУ:")
            appendLine(codes.trim())
        }
        live?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("СЕЙЧАС (идёт запись):")
            appendLine(it.trim())
        }
        val recent = trips.sortedBy { it.start }.takeLast(12)
        if (recent.isNotEmpty()) {
            appendLine()
            appendLine("ВЫВОДЫ БОРТАЧА ПО ПОЕЗДКАМ (старые сверху):")
            for (t in recent) {
                val v = t.findings.filter { it.severity >= Severity.WATCH }
                appendLine("• ${t.label}, ${t.durationMin.toInt()} мин, ХХ ${(t.warmIdleSec / 60).toInt()} мин" +
                    (t.dtcs?.takeIf { it.isNotEmpty() }?.let { ", коды ${it.joinToString()}" } ?: "") +
                    if (v.isEmpty()) ": отклонений нет" else ": " + v.joinToString("; ") { "${it.headline} (${it.severity.ru}, уверенность ${it.confidence}) — ${it.evidence}" })
            }
            appendLine()
            appendLine(TripComparison.render(recent).trim())
        } else {
            appendLine()
            appendLine("Поездок пока нет.")
        }
        if (details.isNotEmpty()) {
            appendLine()
            appendLine(stats(details))
        }
        if (checks.isNotEmpty()) {
            appendLine()
            appendLine("ПРОВЕРОЧНЫЕ ЛОГИ (медианы; коррекция = LTFT+STFT, %):")
            for (c in checks.sortedBy { it.start }.takeLast(6)) {
                appendLine("• ${c.start?.let { java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm").format(it) } ?: c.name}: " +
                    listOfNotNull(
                        c.idleTrim?.let { "коррекция Б1 ХХ ${TripAnalyzer.pct(it)}" },
                        c.revTrim?.let { "Б1 на 2500 ${TripAnalyzer.pct(it)}" },
                        c.idleTrimB2?.let { "Б2 ХХ ${TripAnalyzer.pct(it)}" },
                        c.idleRpm?.let { "обороты ХХ ${it.toInt()}" },
                        c.rearO2Idle?.let { "задняя лямбда ХХ ${TripAnalyzer.fmt(it)} В" },
                        c.idleMaf?.let { "ДМРВ ХХ ${TripAnalyzer.fmt(it)} г/с" },
                    ).joinToString(", "))
            }
        }
    }

    private fun n(v: Double?): String = v?.let { String.format(Locale.ROOT, if (kotlin.math.abs(it) >= 100) "%.0f" else "%.2f", it).trimEnd('0').trimEnd('.') } ?: "—"

    /**
     * Every decoded sensor per mode for the recent trips: «медиана (мин…макс)», deviation and
     * jumps — the raw material for patterns the summaries above do not name.
     */
    fun stats(details: List<TripDetail>, trips: Int = 6): String = buildString {
        appendLine("СТАТИСТИКА ДАТЧИКОВ ПО РЕЖИМАМ (последние поездки; медиана (мин…макс); откл. — % времени вне нормы; скачки — %):")
        val modes = DriveMode.entries
        appendLine("Режимы: " + modes.joinToString(", ") { "${it.name} = ${it.ru}" })
        for (d in details.sortedBy { it.summary.start }.takeLast(trips)) {
            appendLine("— Поездка ${d.summary.label}, ${d.summary.durationMin.toInt()} мин; доли режимов: " +
                d.modeShares.filterValues { it > 0.01 }.entries.joinToString(", ") { "${it.key.name} ${(it.value * 100).toInt()}%" })
            for (s in d.sensors) {
                if (s.code.startsWith("pid01_") || s.code.startsWith("m21_")) continue
                val byMode = s.byMode.filter { it.median != null }.joinToString("; ") { "${it.mode.name} ${n(it.median)} (${n(it.min)}…${n(it.max)})" }
                appendLine("  ${s.code}: $byMode" +
                    (s.deviation?.let { " · откл. ${it.toInt()}%" } ?: "") +
                    (s.jitter?.let { " · скачки ${it.toInt()}%" } ?: ""))
            }
        }
    }

    /** The messages sent with a question: everything kept (the older part lives in the summary). */
    fun history(state: ChatState): List<ChatMessage> = state.messages.toList()
}
