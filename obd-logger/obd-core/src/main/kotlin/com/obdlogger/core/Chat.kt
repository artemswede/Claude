package com.obdlogger.core

/** One line of the chat: «user» or «assistant». */
data class ChatMessage(val role: String, val text: String)

/**
 * What the AI chat sends along with a question: who it is, what the car is, and a
 * compact summary of this car's trips, check logs and codes — never raw CSV (too big,
 * and not needed: the summary is what Бортач itself reasons from).
 */
object ChatPrompt {
    /** History kept in a request: the last few exchanges are enough and keep it cheap. */
    const val HISTORY = 12

    fun system(car: String, trips: List<TripSummary>, checks: List<CheckResult>, codes: String, live: String?): String = buildString {
        appendLine(
            "Ты — помощник-диагност в приложении «Бортач» (OBD-II логгер на ELM327). Отвечай по-русски, коротко и по делу, " +
                "для водителя без спецподготовки. Опирайся только на данные ниже; если данных не хватает — скажи, какую поездку " +
                "или проверочный лог записать (проверочный лог: 4 мин на стоянке — ХХ 2 мин, 2500 об/мин 1 мин, ХХ 1 мин). " +
                "Различай факт из данных и предположение. Не советуй опасного; при угрозе безопасности — сначала остановиться.",
        )
        appendLine()
        appendLine("МАШИНА: ${car.ifBlank { "не названа" }}")
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
        val recent = trips.sortedBy { it.start }.takeLast(8)
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
        if (checks.isNotEmpty()) {
            appendLine()
            appendLine("ПРОВЕРОЧНЫЕ ЛОГИ (медианы; коррекция = LTFT+STFT, %):")
            for (c in checks.sortedBy { it.start }.takeLast(4)) {
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

    /** The history to send with a new question: the latest [HISTORY] lines. */
    fun history(all: List<ChatMessage>): List<ChatMessage> = all.takeLast(HISTORY)
}
