package com.obdlogger.app.ui

import com.obdlogger.core.AttentionSort
import com.obdlogger.core.LiveMode
import com.obdlogger.core.Norms
import com.obdlogger.core.SensorNames
import com.obdlogger.core.Severity
import com.obdlogger.core.Hypothesis
import com.obdlogger.core.Hypotheses
import com.obdlogger.core.TripAnalyzer
import com.obdlogger.core.TripComparison
import com.obdlogger.core.TripDetail
import com.obdlogger.core.Verdict
import java.time.format.DateTimeFormatter

/** Everything the trip report needs; the heavy parts are built off the main thread. */
class ReportData(
    val item: TripItem,
    /** Per-row data of the trip (charts, per-mode statistics); null if the CSV could not be read. */
    val detail: TripDetail?,
    /** This car's trips up to and including this one, sorted by the worst problem first. */
    val comparison: TripComparison.Table?,
    /** This car's check logs, oldest first. */
    val checks: List<TripItem> = emptyList(),
    val hypothesis: Hypothesis? = null,
    /** The trip was cut off (power loss or kill) and finished at the next start. */
    val interrupted: Boolean = false,
)

/**
 * Printable HTML (A4) for «Печать / PDF»: the trip report with charts and the
 * comparison with previous trips, and the mechanic's plan. Charts are inline SVG
 * ([ReportCharts]), so the PDF stays sharp.
 */
object Reports {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private val DAY = DateTimeFormatter.ofPattern("dd.MM")
    private val DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /** Brand fonts from the app's assets (base URL file:///android_asset/), so the PDF looks like the app. */
    private const val FONTS = """
        @font-face { font-family: 'IBM Plex Sans'; font-weight: 400; src: url('fonts/IBMPlexSans-Regular.ttf'); }
        @font-face { font-family: 'IBM Plex Sans'; font-weight: 500; src: url('fonts/IBMPlexSans-Medium.ttf'); }
        @font-face { font-family: 'IBM Plex Sans'; font-weight: 600; src: url('fonts/IBMPlexSans-SemiBold.ttf'); }
        @font-face { font-family: 'IBM Plex Sans'; font-weight: 700; src: url('fonts/IBMPlexSans-Bold.ttf'); }
        @font-face { font-family: 'IBM Plex Mono'; font-weight: 400; src: url('fonts/IBMPlexMono-Regular.ttf'); }
        @font-face { font-family: 'IBM Plex Mono'; font-weight: 500; src: url('fonts/IBMPlexMono-Medium.ttf'); }
        @font-face { font-family: 'IBM Plex Mono'; font-weight: 600; src: url('fonts/IBMPlexMono-SemiBold.ttf'); }
    """

    private const val CSS = """
        @page { size: A4; margin: 14mm 13mm; }
        body { font-family: 'IBM Plex Sans', 'Roboto', sans-serif; color: #23211E; font-size: 10.5pt; line-height: 1.35; }
        h1 { font-size: 21pt; margin: 0 0 3pt; } h2 { font-size: 13pt; margin: 16pt 0 6pt; border-bottom: 2px solid #23211E; padding-bottom: 3pt; break-after: avoid; page-break-after: avoid; }
        h3 { font-size: 11pt; margin: 10pt 0 4pt; }
        .sub { color: #4C4840; } .small { font-size: 8.5pt; color: #645E54; } .mono { font-family: 'IBM Plex Mono', monospace; }
        .amb { color: #8F5A0E; font-weight: 600; } .ok { color: #2F6B5E; font-weight: 600; } .red { color: #A3352B; font-weight: 600; }
        table { width: 100%; border-collapse: collapse; } th { text-align: left; font-size: 8pt; letter-spacing: .06em; text-transform: uppercase; color: #645E54; border-bottom: 1px solid #B3AB9B; padding: 4pt; }
        td { border-bottom: 1px solid #CFC8BA; padding: 5pt 4pt; vertical-align: middle; } td.num, th.num { text-align: right; white-space: nowrap; } tr.out td { background: #F3E6D0; }
        .box { border: 1px solid #CFC8BA; border-radius: 8pt; padding: 8pt 10pt; margin: 6pt 0; break-inside: avoid; page-break-inside: avoid; }
        .version { border: 1.5px dashed #2F6B5E; } .warn { border-left: 4px solid #8F5A0E; }
        .figs { display: flex; gap: 14pt; flex-wrap: wrap; } .figs div { min-width: 90pt; } .figs b { display: block; font-family: 'IBM Plex Mono', monospace; font-size: 13pt; font-weight: 500; }
        .chip { display: inline-block; padding: 1pt 7pt; border-radius: 10pt; font-size: 8.5pt; font-weight: 600; background: #E3DED3; color: #4C4840; }
        .chip.t-warn { background: #F0DFC2; color: #8F5A0E; } .chip.t-ok { background: #D6E3DD; color: #2F6B5E; }
        .chart { break-inside: avoid; page-break-inside: avoid; margin: 4pt 0 2pt; } .lane { border-bottom: 1px solid #CFC8BA; break-inside: avoid; page-break-inside: avoid; }
        .keep { break-inside: avoid; page-break-inside: avoid; }
        .check { width: 12pt; height: 12pt; border: 1.5px solid #23211E; display: inline-block; border-radius: 3pt; }
        .foot { color: #645E54; font-size: 8.5pt; margin-top: 14pt; border-top: 1px solid #CFC8BA; padding-top: 6pt; }
    """

    private fun head(title: String) = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>${esc(title)}</title><style>$FONTS$CSS</style></head><body>"

    // ---- trip report ----

    fun tripHtml(d: ReportData, car: String, version: String): String = buildString {
        val item = d.item
        val s = item.summary
        append(head("Бортач — поездка ${HomeModel.tripRange(s)}"))
        append("<h1>Поездка ${esc(HomeModel.tripRange(s))}</h1>")
        append("<div class='sub'>${esc(car.ifBlank { "Машина без названия" })}</div>")
        val idleMin = d.detail?.modeMinutes?.get(com.obdlogger.core.DriveMode.WARM_IDLE) ?: (s.warmIdleSec / 60)
        append("<div class='box figs'>")
        fig("длительность", "${s.durationMin.toInt()} мин")
        s.metrics[com.obdlogger.core.Metric.COOLANT_MAX]?.let { fig("мотор прогрет до", "${it.toInt()} °C") }
        fig("прогретый холостой", "${idleMin.toInt()} мин")
        fig("строк · опрос", "${s.rows} · ${d.detail?.pollSec?.let { String.format(java.util.Locale.ROOT, "%.1f с", it) } ?: "—"}")
        fig("коды ЭБУ", s.dtcs?.let { if (it.isEmpty()) "нет" else it.joinToString(", ") } ?: "не прочитаны")
        append("</div>")
        if (d.interrupted) append("<div class='box warn'>Запись прервана выключением питания или системой; данные до этого момента сохранены, итоговые коды ЭБУ в конце поездки не прочитаны.</div>")

        // 1. Conclusions, worst first.
        append("<h2>1 · Выводы</h2>")
        val real = s.findings.filter { it.severity >= Severity.WATCH }
        if (real.isEmpty()) append("<div class='box'>Отклонений не найдено: смесь, холостой, зарядка и температура в норме.</div>")
        real.forEachIndexed { i, f ->
            val cls = if (i == 0 && f.severity >= Severity.WARN) "box version" else "box"
            append("<div class='$cls'><b>${if (f.severity >= Severity.WARN) "Есть версия" else "Наблюдаем"}: ${esc(f.headline)}</b> <span class='small'>· уверенность ${esc(f.confidence)}</span><br>")
            append("<span class='sub'>${esc(f.evidence)}</span>")
            if (f.why.isNotEmpty()) {
                append("<table style='margin-top:4pt'>")
                f.why.forEach { e -> append("<tr><td>${esc(e.label)}</td><td class='num mono ${if (e.deviating) "amb" else ""}'>${esc(e.value)}</td></tr>") }
                append("</table>")
            }
            if (f.advice != "—") append("<div class='small' style='margin-top:4pt'>Проверить: ${esc(f.advice)}</div>")
            append("</div>")
        }

        // 2. Charts.
        s.trace?.takeIf { !item.isCheck }?.let { tr ->
            // The sensor the main version is about; the mixture chart only when the problem is the mixture.
            val focus = HomeModel.focusOf(s)
            append("<h2>2 · ${esc(SensorNames.label(focus))} за поездку</h2>")
            if (focus == "trim_b1") {
                append("<div class='small'>Полоса — норма ±10 %, серые подложки — прогретый холостой, янтарным — где ЭБУ добавляет топливо сверх нормы.</div>")
                append("<div class='chart'>${ReportCharts.trimChart(tr)}</div>")
            } else {
                append("<div class='small'>Полоса — норма, серые подложки — прогретый холостой.</div>")
                val ms = tr.minutes.map { (it * 60_000).toLong() }
                val v = tr.of(focus)!!.map { if (it.isNaN()) null else it }
                append("<div class='chart'>${ReportCharts.lane(focus, SensorNames.label(focus), SensorNames.unit(focus), ms, v, tr.modes.toList(), Norms.of(focus, LiveMode.IDLE), tr.start, h = 220)}</div>")
            }
        }
        d.detail?.let { det ->
            val codes = (det.rating(AttentionSort.DEVIATION).map { it.code } + LANES).filter { det.table.has(it) }.distinct().take(6)
            if (codes.isNotEmpty()) {
                append("<h2>3 · Ключевые датчики за поездку</h2>")
                append("<div class='small'>Сверху — самые проблемные. У каждого своя шкала; полоса — норма для прогретого холостого.</div>")
                codes.forEach { c ->
                    val svg = ReportCharts.lane(c, SensorNames.label(c), SensorNames.unit(c), det.table.ms, det.series(c), det.table.modes, Norms.of(c, LiveMode.IDLE), det.table.start)
                    if (svg.isNotEmpty()) append("<div class='lane'>$svg</div>")
                }
            }
            // 4. Per-mode statistics of the worst sensors.
            val worst = det.rating(AttentionSort.DEVIATION).filter { (it.deviation ?: 0.0) > 0 }.take(3)
            if (worst.isNotEmpty()) {
                append("<h2>4 · Где именно отклонение: по режимам</h2>")
                worst.forEach { x ->
                    append("<div class='keep'><h3>${esc(SensorNames.label(x.code))} <span class='small mono'>${esc(SensorNames.source(x.code))}</span></h3>")
                    append("<table><tr><th>Режим</th><th class='num'>Доля</th><th class='num'>Медиана</th><th class='num'>Мин…макс</th><th>Оценка</th></tr>")
                    x.byMode.sortedByDescending { it.mode == com.obdlogger.core.DriveMode.WARM_IDLE }.forEach { m ->
                        val cls = if (m.verdict == Verdict.OUT) "out" else ""
                        val chip = when (m.verdict) { Verdict.OUT -> "t-warn"; Verdict.OK -> "t-ok"; else -> "" }
                        append("<tr class='$cls'><td>${esc(m.mode.ru)}</td><td class='num mono'>${(m.share * 100).toInt()} %</td>")
                        append("<td class='num mono'>${m.median?.let { esc(Num.fmt(x.code, it)) } ?: "—"}</td>")
                        append("<td class='num mono'>${if (m.verdict == Verdict.NOT_RATED) "—" else "${m.min?.let { esc(Num.fmt(x.code, it)) }}…${m.max?.let { esc(Num.fmt(x.code, it)) }}"}</td>")
                        append("<td><span class='chip $chip'>${esc(m.verdict.ru)}</span></td></tr>")
                    }
                    append("</table></div>")
                }
            }
        }

        // 5. Comparison with previous trips of this car.
        d.comparison?.takeIf { it.trips.size >= 2 }?.let { append(comparison(it, s.name)) }
            ?: append("<h2>5 · Сравнение с прошлыми поездками</h2><div class='box'>Сравнивать пока не с чем: это первая поездка этой машины с данными на прогретом холостом.</div>")

        // 6. Check logs.
        if (d.checks.size >= 2) append(checks(d.checks))

        d.hypothesis?.let { h ->
            append("<h2>План проверки</h2>")
            append(planTable(h, withDone = false))
            append("<div class='small'>Как понять, что помогло: ${esc(h.success)}</div>")
        }
        append("<div class='foot'>Разбор — ориентир по данным записи, а не диагноз. Файлы: ${esc(item.files().joinToString(", ") { it.name })}. Бортач $version.</div>")
        append("</body></html>")
    }

    private fun StringBuilder.fig(label: String, value: String) {
        append("<div><span class='small'>${esc(label)}</span><b>${esc(value)}</b></div>")
    }

    private val LANES = listOf("trim_b1", "trim_b2", "o2_b1s2_v", "rpm", "maf_gs", "coolant_c", "battery_v")

    private fun fmtMetric(unit: String, v: Double): String = when (unit) {
        "%" -> TripAnalyzer.pct(v)
        "об/мин", "раз", "°C" -> "${v.toInt()}"
        else -> "${TripAnalyzer.fmt(v)} $unit"
    }

    /** «Сравнение с прошлыми поездками»: worst first, a sparkline per row, then the forecast. */
    fun comparison(t: TripComparison.Table, current: String? = null): String = buildString {
        // Heading, note and table stay on one page.
        append("<div class='keep'>")
        append("<h2>5 · Сравнение с прошлыми поездками этой машины</h2>")
        append("<div class='small'>Сверху — самое проблемное сейчас: устраните одно — поднимется следующее. Значения на прогретом холостом стоя, если не указано иное.</div>")
        val days = t.trips.map { it.start?.format(DAY) ?: "?" }
        val dates = if (days.toSet().size < days.size) t.trips.map { it.start?.format(DAY_TIME) ?: "?" } else days
        append("<table><tr><th>Показатель</th>")
        dates.forEachIndexed { i, dt -> append("<th class='num'>${esc(dt)}${if (t.trips[i].name == current) " ●" else ""}</th>") }
        append("<th>Тренд</th><th>Вывод</th></tr>")
        for (r in t.rows) {
            val cls = if (r.tone == TripComparison.Tone.WARN || r.tone == TripComparison.Tone.BAD) "out" else ""
            append("<tr class='$cls'><td><b>${esc(r.title)}</b>${if (r.code.isNotEmpty()) "<br><span class='small mono'>${esc(r.code)}</span>" else ""}</td>")
            r.values.forEachIndexed { i, v -> append("<td class='num mono'${if (i == r.values.lastIndex) " style='font-weight:600'" else ""}>${v?.let { esc(fmtMetric(r.unit, it)) } ?: "—"}</td>") }
            append("<td>${ReportCharts.sparkline(r.values, r.normLo, r.normHi)} ${esc(r.arrow)}</td>")
            val tone = when (r.tone) {
                TripComparison.Tone.WARN, TripComparison.Tone.BAD -> "t-warn"
                TripComparison.Tone.OK -> "t-ok"
                else -> ""
            }
            append("<td><span class='chip $tone'>${esc(r.verdict)}</span></td></tr>")
        }
        append("</table>")
        t.forecast?.let { f ->
            append("<div class='box keep' style='display:flex;gap:12pt;align-items:center'><div style='flex:1'><b>Прогноз · ориентир, не диагноз.</b> ${esc(f.text)}<br>")
            append("<span class='small'>Уверенность ${esc(f.confidence)}: ${esc(f.confidenceWhy)}.</span></div>${ReportCharts.forecast(f)}</div>")
        }
        append("</div>")
    }

    private fun checks(list: List<TripItem>): String = buildString {
        val shown = list.sortedBy { it.summary.start }.takeLast(4)
        append("<h2>6 · Проверочные логи: до и после</h2>")
        append("<div class='small'>4 минуты на стоянке в одинаковых условиях — самое честное сравнение до и после ремонта.</div>")
        append("<table class='keep'><tr><th>Показатель</th>")
        shown.forEach { append("<th class='num'>${esc(it.summary.start?.format(DAY_TIME) ?: "?")}</th>") }
        append("</tr>")
        val rows = listOf<Pair<String, (com.obdlogger.core.CheckResult) -> String?>>(
            "Коррекция Б1 на ХХ" to { c -> c.idleTrim?.let { TripAnalyzer.pct(it) } },
            "Коррекция Б1 на 2500" to { c -> c.revTrim?.let { TripAnalyzer.pct(it) } },
            "Коррекция Б2 на ХХ" to { c -> c.idleTrimB2?.let { TripAnalyzer.pct(it) } },
            "Обороты ХХ" to { c -> c.idleRpm?.toInt()?.toString() },
            "Лямбда после кат., ХХ" to { c -> c.rearO2Idle?.let { "${TripAnalyzer.fmt(it)} В" } },
            "Расход воздуха на ХХ" to { c -> c.idleMaf?.let { "${TripAnalyzer.fmt(it)} г/с" } },
        )
        for ((title, get) in rows) {
            val vals = shown.map { it.check?.let(get) }
            if (vals.all { it == null }) continue
            append("<tr><td>${esc(title)}</td>")
            vals.forEach { append("<td class='num mono'>${esc(it ?: "—")}</td>") }
            append("</tr>")
        }
        append("</table>")
    }

    private fun planTable(h: Hypothesis, withDone: Boolean): String = buildString {
        append("<table class='keep'><tr><th>№</th><th>Что проверить</th><th>Чем</th><th>Ожидаемый результат</th>${if (withDone) "<th>Сделано · результат</th>" else ""}</tr>")
        h.plan.forEachIndexed { i, s ->
            append("<tr><td class='mono'>${i + 1}</td><td><b>${esc(s.what)}</b>${s.detail?.let { "<br><span class='small'>${esc(it)}</span>" } ?: ""}</td>")
            append("<td>${esc(s.how)}</td><td>${esc(s.expected)}</td>${if (withDone) "<td><span class='check'></span></td>" else ""}</tr>")
        }
        append("</table>")
    }

    // ---- plan for the mechanic ----

    fun planHtml(h: Hypothesis, car: String, version: String, comparison: TripComparison.Table? = null): String = buildString {
        val f = h.finding
        append(head("Бортач — план проверки"))
        append("<h1>План проверки для мастера</h1>")
        append("<div class='sub'>${esc(car.ifBlank { "Машина без названия" })} · версия: ${esc(f.headline)} · уверенность ${esc(f.confidence)}</div>")
        append("<div class='box'><table>")
        h.keyFigures.filter { it.value != "—" }.chunked(3).forEach { r ->
            append("<tr>")
            r.forEach { e -> append("<td><div class='small'>${esc(e.label)}</div><div class='mono ${if (e.deviating) "amb" else ""}'>${esc(e.value)}</div></td>") }
            append("</tr>")
        }
        append("</table></div>")
        append("<p>${esc(h.explanation)}</p>")
        append(planTable(h, withDone = true))
        append("<div class='small' style='margin-top:4pt'>Как понять, что помогло: ${esc(h.success)}</div>")
        if (h.seenIn.isNotEmpty()) {
            append("<h2>Где это видно</h2><table class='keep'>")
            h.seenIn.forEach { (t, v) -> append("<tr><td class='mono'>${Hypotheses.date(t)}</td><td>${t.durationMin.toInt()} мин · ХХ ${(t.warmIdleSec / 60).toInt()} мин</td><td class='num mono amb'>${esc(v)}</td></tr>") }
            append("</table>")
        }
        comparison?.takeIf { it.trips.size >= 2 }?.let { append(comparison(it)) }
        append("<div class='foot'>План — проверяемые шаги по данным записи, а не диагноз. Бортач $version.</div>")
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
