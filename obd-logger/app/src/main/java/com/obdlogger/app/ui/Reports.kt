package com.obdlogger.app.ui

import com.obdlogger.core.Hypothesis
import com.obdlogger.core.Hypotheses
import com.obdlogger.core.TripAnalyzer

/** Printable HTML (A4) for «Печать / PDF»: the mechanic's plan and the trip report. */
object Reports {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val CSS = """
        @page { size: A4; margin: 16mm 14mm; }
        body { font-family: 'IBM Plex Sans', sans-serif; color: #23211E; font-size: 11pt; }
        h1 { font-size: 22pt; margin: 0 0 4pt; } h2 { font-size: 14pt; margin: 16pt 0 6pt; border-bottom: 2px solid #23211E; padding-bottom: 3pt; }
        .sub { color: #4C4840; } .mono { font-family: 'IBM Plex Mono', monospace; } .amb { color: #8F5A0E; font-weight: 600; }
        table { width: 100%; border-collapse: collapse; } th { text-align: left; font-size: 8.5pt; letter-spacing: .08em; text-transform: uppercase; color: #645E54; border-bottom: 1px solid #B3AB9B; padding: 4pt; }
        td { border-bottom: 1px solid #CFC8BA; padding: 6pt 4pt; vertical-align: top; } .box { border: 1px solid #CFC8BA; border-radius: 8pt; padding: 8pt 10pt; margin: 8pt 0; }
        .check { width: 12pt; height: 12pt; border: 1.5px solid #23211E; display: inline-block; border-radius: 3pt; }
        .foot { color: #645E54; font-size: 9pt; margin-top: 12pt; }
    """

    fun planHtml(h: Hypothesis, car: String, version: String): String = buildString {
        val f = h.finding
        append("<html><head><meta charset='utf-8'><style>$CSS</style></head><body>")
        append("<h1>План проверки для мастера</h1>")
        append("<div class='sub'>${esc(car)} · версия: ${esc(f.headline)} · уверенность ${esc(f.confidence)}</div>")
        append("<div class='box'><table>")
        h.keyFigures.filter { it.value != "—" }.chunked(3).forEach { r ->
            append("<tr>")
            r.forEach { e -> append("<td><div class='sub'>${esc(e.label)}</div><div class='mono ${if (e.deviating) "amb" else ""}'>${esc(e.value)}</div></td>") }
            append("</tr>")
        }
        append("</table></div>")
        append("<p>${esc(h.explanation)}</p>")
        append("<table><tr><th>№</th><th>Что проверить</th><th>Чем</th><th>Ожидаемый результат</th><th>Сделано · результат</th></tr>")
        h.plan.forEachIndexed { i, s ->
            append("<tr><td class='mono'>${i + 1}</td><td><b>${esc(s.what)}</b>${s.detail?.let { "<br><span class='sub'>${esc(it)}</span>" } ?: ""}</td>")
            append("<td>${esc(s.how)}</td><td>${esc(s.expected)}</td><td><span class='check'></span></td></tr>")
        }
        append("</table>")
        if (h.seenIn.isNotEmpty()) {
            append("<h2>Где это видно</h2><table>")
            h.seenIn.forEach { (t, v) -> append("<tr><td class='mono'>${Hypotheses.date(t)}</td><td>${t.durationMin.toInt()} мин</td><td class='mono amb'>${esc(v)}</td></tr>") }
            append("</table>")
        }
        append("<div class='foot'>План — проверяемые шаги по данным записи, а не диагноз. Бортач $version.</div>")
        append("</body></html>")
    }

    fun tripHtml(item: TripItem, h: Hypothesis?, car: String, version: String): String = buildString {
        val s = item.summary
        append("<html><head><meta charset='utf-8'><style>$CSS</style></head><body>")
        append("<h1>Поездка ${esc(HomeModel.tripRange(s))}</h1>")
        append("<div class='sub'>${esc(car)} · ${s.durationMin.toInt()} мин · ${s.rows} строк · коды ЭБУ: ${esc(s.dtcs?.let { if (it.isEmpty()) "нет" else it.joinToString(", ") } ?: "не прочитаны")}</div>")
        append("<h2>Выводы</h2>")
        for (f in s.findings) {
            append("<div class='box'><b>${esc(f.severity.ru.uppercase())} · ${esc(f.title)}</b> <span class='sub'>(уверенность ${esc(f.confidence)})</span><br>")
            append("${esc(f.evidence)}")
            if (f.advice != "—") append("<br><span class='sub'>Проверить: ${esc(f.advice)}</span>")
            append("</div>")
        }
        append("<h2>Показатели по режимам</h2><table><tr><th>Показатель</th><th>Значение</th></tr>")
        s.metrics.forEach { (m, v) -> append("<tr><td>${esc(m.ru)}</td><td class='mono'>${esc(TripAnalyzer.fmt(v))} ${esc(m.unit)}</td></tr>") }
        append("</table>")
        if (h != null) {
            append("<h2>План проверки</h2><table><tr><th>№</th><th>Что проверить</th><th>Чем</th><th>Ожидаемый результат</th></tr>")
            h.plan.forEachIndexed { i, st -> append("<tr><td>${i + 1}</td><td><b>${esc(st.what)}</b></td><td>${esc(st.how)}</td><td>${esc(st.expected)}</td></tr>") }
            append("</table>")
        }
        append("<div class='foot'>Файлы записи: ${esc(item.files().joinToString(", ") { it.name })}. Бортач $version.</div>")
        append("</body></html>")
    }

    /** Plain text of the plan for messengers. */
    fun planText(h: Hypothesis, car: String): String = buildString {
        appendLine("План проверки для мастера — ${h.finding.headline}")
        appendLine(car)
        appendLine()
        h.plan.forEachIndexed { i, s -> appendLine("${i + 1}. ${s.what} — ${s.how}. Ожидаем: ${s.expected}") }
        appendLine()
        appendLine("Как понять, что помогло: ${h.success}")
    }
}
