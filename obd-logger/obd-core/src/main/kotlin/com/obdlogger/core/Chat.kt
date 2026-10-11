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

    /** The window used for the threshold: the model's own, capped at [WINDOW_CAP] so a request stays quick on a head unit. */
    const val WINDOW_CAP = 200_000

    fun requestTokens(system: String, state: ChatState): Int =
        tokens(system) + tokens(state.summary) + state.messages.sumOf { tokens(it.text) + 4 }

    fun needsCompression(system: String, state: ChatState, window: Int = WINDOW_TOKENS): Boolean =
        state.messages.size > PROTECT_LAST && requestTokens(system, state) > minOf(window, WINDOW_CAP) * THRESHOLD

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

    /** A moment marked on the chart: «20:05:12 провал». */
    data class Mark(val time: java.time.LocalTime, val label: String)

    /** Part of a trip: clock times from–to, or the last [lastMin] minutes; all null — the whole trip. */
    data class Window(val from: java.time.LocalTime? = null, val to: java.time.LocalTime? = null, val lastMin: Int? = null)

    /** One value per trip (median in [mode]) — how it changes from trip to trip. */
    data class Trend(override val sensor: String, val mode: DriveMode?) : ChartRequest()

    /** The sensor over one trip; [trip] is a label like «03.10 19:51» or «последняя». */
    data class Trip(override val sensor: String, val trip: String, val window: Window? = null, val marks: List<Mark> = emptyList()) : ChartRequest()

    /** Two to four sensors on one time axis of one trip, each on its own scale. */
    data class Overlay(val sensors: List<String>, val trip: String, val window: Window? = null, val marks: List<Mark> = emptyList()) : ChartRequest() {
        override val sensor get() = sensors.first()
    }

    /** One to four sensors right now, from the live recording, refreshed every second; the last [minutes]. */
    data class Live(val sensors: List<String>, val minutes: Int = 5) : ChartRequest() {
        override val sensor get() = sensors.first()
    }
}

/**
 * Charts in answers: the assistant writes a line «[график: тренд trim_b1 WARM_IDLE]» or
 * «[график: поездка rpm последняя]»; the app draws it under the text.
 */
object ChatCharts {
    private val tag = Regex("\\[график:\\s*(тренд|поездка|наложение)\\s+([^\\]]+)]", RegexOption.IGNORE_CASE)
    private val code = Regex("^[a-z][a-z0-9_]*$")

    fun parse(text: String): List<ChartRequest> = tag.findAll(text).mapNotNull { m ->
        val kind = m.groupValues[1].lowercase(Locale.ROOT)
        val words = m.groupValues[2].trim().split(Regex("\\s+"))
        val sensors = words.takeWhile { code.matches(it.lowercase(Locale.ROOT)) && DriveMode.entries.none { d -> d.name.equals(it, true) } }.map { it.lowercase(Locale.ROOT) }
        val arg = words.drop(sensors.size).joinToString(" ").trim()
        if (sensors.isEmpty()) return@mapNotNull null
        val now = Regex("^(сейчас|live)(?=\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(arg)
        when {
            now && kind != "тренд" -> ChartRequest.Live(sensors.take(4),
                Regex("(\\d+)\\s*мин").find(arg)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 60) ?: 5)
            else -> {
                val (trip, window, marks) = tail(arg)
                when (kind) {
                    "тренд" -> ChartRequest.Trend(sensors.first(), DriveMode.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) })
                    "наложение" -> ChartRequest.Overlay(sensors.take(4), trip, window, marks)
                    else -> ChartRequest.Trip(sensors.first(), trip, window, marks)
                }
            }
        }
    }.distinct().take(4).toList()

    private const val T = "(\\d{1,2}:\\d{2}(?::\\d{2})?)"

    private fun time(s: String): java.time.LocalTime? = runCatching {
        val p = s.split(":").map { it.toInt() }
        java.time.LocalTime.of(p[0], p[1], p.getOrElse(2) { 0 })
    }.getOrNull()

    /**
     * After the sensors: the trip, then an optional window — «20:02–20:12», «последние 10 мин»,
     * «с 20:05 10 мин» — and up to 3 marks «отметка 20:05:12 провал». A window that does not
     * parse is dropped (the whole trip is drawn).
     */
    private fun tail(arg: String): Triple<String, ChartRequest.Window?, List<ChartRequest.Mark>> {
        val parts = arg.split(Regex("\\s*отметка\\s+", RegexOption.IGNORE_CASE))
        val marks = parts.drop(1).mapNotNull { m ->
            val r = Regex("^$T\\s*(.*)$").find(m.trim()) ?: return@mapNotNull null
            time(r.groupValues[1])?.let { ChartRequest.Mark(it, r.groupValues[2].trim().take(30)) }
        }.take(3)
        var rest = parts.first()
        var window: ChartRequest.Window? = null
        Regex("$T\\s*[–—-]\\s*$T").find(rest)?.let { m ->
            val a = time(m.groupValues[1])
            val b = time(m.groupValues[2])
            if (a != null && b != null) window = ChartRequest.Window(a, b)
            rest = rest.removeRange(m.range)
        }
        Regex("последни[ейх]\\s+(\\d+)\\s*мин", RegexOption.IGNORE_CASE).find(rest)?.let { m ->
            window = ChartRequest.Window(lastMin = m.groupValues[1].toInt().coerceIn(1, 600))
            rest = rest.removeRange(m.range)
        }
        Regex("(?:^|\\s)с\\s+$T\\s+(\\d+)\\s*мин", RegexOption.IGNORE_CASE).find(rest)?.let { m ->
            val a = time(m.groupValues[1])
            if (a != null) window = ChartRequest.Window(a, a.plusMinutes(m.groupValues[2].toLong()))
            rest = rest.removeRange(m.range)
        }
        val trip = rest.trim().ifEmpty { "последняя" }
        return Triple(trip, window, marks)
    }

    /** Milliseconds from the trip start of a clock [time] in trip [d] (the trip may cross midnight). */
    fun msOf(d: TripDetail, time: java.time.LocalTime): Long? {
        val start = d.summary.start ?: return null
        var at = start.toLocalDate().atTime(time)
        if (at.isBefore(start.minusHours(1))) at = at.plusDays(1)
        return java.time.Duration.between(start, at).toMillis()
    }

    /**
     * The part of trip [d] to draw, in ms from its start: the window, else 5 minutes around
     * the first mark, else null (the whole trip). A window outside the trip is dropped.
     */
    fun range(d: TripDetail, window: ChartRequest.Window?, marks: List<ChartRequest.Mark> = emptyList()): LongRange? {
        val end = d.table.ms.lastOrNull() ?: return null
        val r = when {
            window?.lastMin != null -> (end - window.lastMin * 60_000L).coerceAtLeast(0)..end
            window?.from != null && window.to != null -> {
                val a = msOf(d, window.from) ?: return null
                val b = msOf(d, window.to) ?: return null
                a.coerceAtLeast(0)..b.coerceAtMost(end)
            }
            marks.isNotEmpty() -> msOf(d, marks.first().time)?.let { m -> (m - 150_000).coerceAtLeast(0)..(m + 150_000).coerceAtMost(end) }
            else -> null
        } ?: return null
        return if (r.last - r.first < 10_000) null else r
    }

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
    /**
     * Who the assistant is and how it answers — Victor Cheng's case method (answer first,
     * one hypothesis tested against the data, MECE tree, a number behind every claim, «so
     * what»), a fixed short format the app lays out, the tags for charts and actions, and one
     * example (small models follow an example better than a description).
     */
    val ROLE = """Ты — диагност-консультант в приложении «Бортач». Перед тобой данные OBD-II одной машины: её поездки, расчёты Бортача, «как обычно у этой машины», коды ошибок, проверки. Собеседник — водитель, не механик.

КАК ДУМАТЬ (метод Виктора Ченга):
1. Сначала вывод, потом доказательства. Первая строка ответа — ответ на вопрос.
2. От гипотез: назови самую вероятную причину и проверь её данными. Не перечисляй всё подряд.
3. Дерево причин без пересечений: воздух / топливо / зажигание / охлаждение / зарядка / датчики и проводка / механика. Ветки без признаков в данных отбрасывай одной фразой. Ветки, которых у этой машины нет (нет датчиков), пропускай.
4. Каждый довод — с числом: датчик, значение, режим, время или поездка. Например: «коррекция Б1 +19 % на холостом в 20:05, обычно у вас +4 %».
5. «И что?» — после факта скажи, что он значит для машины и для водителя.
6. Сравнивай с тем, как обычно у ЭТОЙ машины (блок ОБЫЧНО У ЭТОЙ МАШИНЫ), общие нормы — вторично. Начинающееся изменение важнее разового выброса: скажи, что меняется, с какой скоростью и когда станет проблемой.

ПРАВИЛА:
- Только данные ниже. Нет данных — так и скажи и предложи, что их даст: обычная поездка, проверка или наблюдение. Не выдумывай чисел, кодов датчиков и кодов ошибок.
- Коды датчиков в тегах — только из СТАТИСТИКИ ДАТЧИКОВ.
- Пиши по-русски, просто, без жаргона; термин один раз поясни в скобках.
- Без жирного шрифта и заголовков с #. Списки — строками, начинающимися с «- ».
- Длина: обычный вопрос — до 8 строк текста плюс графики. «Поиск гипотез» — по его формату.
- Не советуй опасного. Если ехать нельзя — скажи это первой строкой.
- Помни разговор и прежние гипотезы (ПАМЯТЬ ЧАТА).

ФОРМАТ ОБЫЧНОГО ОТВЕТА:
Коротко: <вывод одной фразой, до 90 знаков: что случилось и на что похоже>
Почему:
- <факт с числом и временем> → <что это значит>
- <2–4 факта, в том же порядке, что датчики на графике>
Что сделать: <1–3 шага, сначала дешёвые и решающие>
Уверенность: высокая / средняя / низкая — <почему>
<теги графиков и действий, каждый отдельной строкой>

ГРАФИКИ — показывай ровно то, о чём говоришь:
- Окно 3–15 минут вокруг нужного момента, а не вся поездка (часовой график не разглядеть). Вся поездка — только для тренда за поездку.
- 2–4 датчика, которые вместе доказывают мысль. Отметь ключевой момент — время бери из блока КЛЮЧЕВЫЕ МОМЕНТЫ или из сырых данных.
- Не больше 3 графиков. Если водитель просит конкретное окно и датчики — сделай ровно так.
Теги (каждый отдельной строкой):
  [график: наложение <датчик1> <датчик2> [<датчик3> <датчик4>] <поездка> <окно> отметка <ЧЧ:ММ:СС> <подпись>]
    поездка — «последняя» или дата и время из списка поездок («03.10 19:51»);
    окно — «20:02–20:12», «последние 10 мин» или «с 20:05 10 мин»; отметок до 3, каждая со словом «отметка».
  [график: поездка <датчик> <поездка> <окно> отметка …] — один датчик.
  [график: тренд <датчик> <режим>] — медиана по поездкам; режим: COLD / WARM_IDLE / ROLL_TO_STOP / CRUISE / HEAVY или пусто.
  [график: наложение <датчик1> <датчик2> … сейчас 10 мин] — живой график идущей записи за последние N минут.

ДЕЙСТВИЯ — предлагаешь ты, запускает водитель:
  [проверка: холостой|смесь|зарядка|прогрев] — короткая проверка (холостой 2 мин стоя; смесь 4 мин ХХ→2500→ХХ; зарядка 2 мин без нагрузки и с фарами, печкой, обогревом; прогрев от холодного пуска до 80 °C). Только когда она решающая.
  [наблюдать: <датчик> <знак> <число> [когда WARM_IDLE|CRUISE|COLD]] — Бортач следит за условием в следующих ${WatchRule.TRIPS} поездках и пришлёт результат. Знаки: > < >= <=.
Когда приходит «Результат проверки» или «Наблюдение» — дай вердикт: гипотеза подтверждена, опровергнута или нужна ещё поездка, и что дальше.

ПРИМЕР:
Вопрос: почему дёргает на светофоре?
Коротко: на прогретом холостом обороты проваливаются до 540 (обычно 690) — похоже на подсос воздуха.
Почему:
- В 20:05:12 обороты 540 при ДМРВ 1.4 г/с → мотор получает воздух мимо ДМРВ.
- Коррекция Б1 в тот же момент +22 % (обычно +4 %) → ЭБУ доливает топливо, компенсируя неучтённый воздух.
Что сделать: осмотреть шланги после ДМРВ и вентиляцию картера; затем проверка «Холостой ход».
Уверенность: средняя — один эпизод, нужна ещё поездка.
[график: наложение rpm maf_gs trim_b1 последняя 20:02–20:09 отметка 20:05:12 провал]
[проверка: холостой]"""

    private val HMS = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * «Ключевые моменты» of one trip with exact clock times — so the assistant can pick the
     * window of a chart: start and end, the first episodes and the longest of each event,
     * the coolant peak and the voltage low.
     */
    fun moments(d: TripDetail): String = buildString {
        val t = d.table
        val times = t.times
        val first = times.firstNotNullOfOrNull { it } ?: return@buildString
        val last = times.lastOrNull { it != null } ?: first
        appendLine("КЛЮЧЕВЫЕ МОМЕНТЫ · поездка ${d.summary.label} (${first.format(HMS)}–${last.format(HMS)}; t_s=0 в сырых данных = ${first.format(HMS)}):")
        fun at(i: Int) = times.getOrNull(i)?.format(HMS) ?: "?"
        for (e in Relations.events(t)) {
            if (e.rows.isEmpty()) continue
            // Episodes: runs of rows no more than 2 apart.
            val eps = ArrayList<IntRange>()
            var a = e.rows.first()
            var b = a
            for (r in e.rows.drop(1)) {
                if (r - b <= 2) b = r else { eps += a..b; a = r; b = r }
            }
            eps += a..b
            fun dur(ep: IntRange) = ((t.ms[ep.last] - t.ms[ep.first]) / 1000).coerceAtLeast(1)
            val longest = eps.maxByOrNull { dur(it) }!!
            appendLine("  ${e.kind}: ${eps.size} эпиз.; первые: " + eps.take(3).joinToString(", ") { "${at(it.first)} (${dur(it)} с)" } +
                "; самый долгий: ${at(longest.first)} (${dur(longest)} с)")
        }
        fun extreme(code: String, max: Boolean, label: String, unit: String) {
            val v = t.raw(code)
            val i = v.indices.filter { v[it] != null }.let { idx -> if (max) idx.maxByOrNull { v[it]!! } else idx.minByOrNull { v[it]!! } } ?: return
            appendLine("  $label ${Values.format(v[i]!!)} $unit в ${at(i)}")
        }
        extreme("coolant_c", true, "максимум ОЖ", "°C")
        extreme("battery_v", false, "минимум напряжения", "В")
        extreme("rpm", true, "максимум оборотов", "об/мин")
    }

    fun system(
        car: String,
        trips: List<TripSummary>,
        checks: List<CheckResult>,
        codes: String,
        live: String?,
        details: List<TripDetail> = emptyList(),
        memory: String = "",
        /** Bortach's exact calculations and raw rows (see [research]). */
        research: String = "",
        /** This car's own normal and the changes against it ([Baseline.render]). */
        baseline: String = "",
    ): String = buildString {
        // The fixed part first: the same opening of every request lets the model cache it.
        appendLine(ROLE)
        appendLine()
        appendLine("МАШИНА: ${car.ifBlank { "не названа" }}")
        if (memory.isNotBlank()) {
            appendLine()
            appendLine("ПАМЯТЬ ЧАТА (конспект прежнего разговора):")
            appendLine(memory.trim())
        }
        if (baseline.isNotBlank()) {
            appendLine()
            appendLine(baseline.trim())
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
            appendLine("ПРОВЕРКИ БОРТАЧА (медианы; коррекция = LTFT+STFT, %):")
            for (c in checks.sortedBy { it.start }.takeLast(8)) {
                appendLine("• ${c.kind.title}, ${c.start?.let { java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm").format(it) } ?: c.name}: ${c.summary()}")
            }
        }
        if (research.isNotBlank()) {
            appendLine()
            appendLine(research.trim())
        }
    }

    /**
     * «Поиск гипотез»: the built-in research brief. Hypothesis-driven like a strategy
     * consultant (Victor Cheng's case method: issue tree, MECE, answer first), applied to
     * the engine; the answer is client-ready — the owner can hand it to a mechanic.
     */
    const val HYPOTHESIS_BRIEF = """Проведи глубокое исследование работы двигателя по всем данным выше: выводам Бортача, статистике по режимам, РАСЧЁТАМ БОРТАЧА (корреляции, что меняется раньше, события) и СЫРЫМ ДАННЫМ. Работай как консультант McKinsey по методу Виктора Ченга: от гипотез, дерево вопросов, MECE, вывод первым.

Порядок работы (думай подробно, в ответ выноси только результат):
1. Картина: что изменилось по сравнению с ОБЫЧНЫМ ДЛЯ ЭТОЙ МАШИНЫ (блок «ОБЫЧНО У ЭТОЙ МАШИНЫ»), в каких режимах, как меняется от поездки к поездке; общие нормы — вторично. Отдели нормальные связи (обороты↔ДМРВ, нагрузка↔дроссель) от подозрительных.
2. Дерево проблемы, MECE, по всем системам, не только холостой ход: воздух и наддув (подсос, ДМРВ/ДАД, дроссель, PCV, EGR, турбина) / топливо (давление, насос, форсунки, фильтр) / зажигание (свечи, катушки) / охлаждение (термостат, вентилятор, помпа) / зарядка (генератор, АКБ, масса) / датчики и проводка / управление и механика (фазы, компрессия). Ветки, которых у этой машины нет или по которым нет датчиков, пропусти. Для каждой ветки — есть ли в данных признаки.
3. Гипотезы: 3–5 самых вероятных. Для каждой — механизм (почему так происходит физически), доказательства ЗА с конкретными числами из данных (датчик, режим, поездка, r, события), доказательства ПРОТИВ, чего в данных не хватает.
4. Зависимости: какие датчики влияют друг на друга и в какую сторону, что опережает что; покажи это наложением графиков.
5. Проверка: для каждой гипотезы — самый дешёвый решающий тест (что сделать, чем, сколько времени, какой результат подтвердит, какой опровергнет). Если решающим будет короткая проверка Бортача или наблюдение в поездке — предложи её тегом.
6. Раннее предупреждение: какие датчики начинают уходить от обычного для этой машины, с какой скоростью, когда это станет проблемой.

Формат ответа — client-ready, по пирамиде Минто:
ГЛАВНЫЙ ВЫВОД — одно-два предложения: что с машиной и что делать.
СИТУАЦИЯ · ОСЛОЖНЕНИЕ · ВОПРОС — по одной строке.
ГИПОТЕЗЫ — нумерованно, от самой вероятной; у каждой: уверенность (высокая/средняя/низкая), механизм, «за» (с числами), «против», решающий тест.
ЗАВИСИМОСТИ — 3–5 строк «A → B: что видно, число».
ПЛАН ДЕЙСТВИЙ — по порядку: дёшево и решающе сначала; что можно сделать самому, что у мастера; ориентир стоимости.
РАННЕЕ ПРЕДУПРЕЖДЕНИЕ — что меняется по сравнению с обычным у этой машины, темп, через сколько поездок станет проблемой, что проверить сейчас.
РИСКИ — что будет, если не делать; когда ехать нельзя.
ЧТО ДАЛЬШЕ — что сузит выбор: обычная поездка, короткая проверка или наблюдение.
Вставь 2–3 графика тегами: тренд по поездкам и наложение за окно 3–15 минут вокруг ключевого момента, с отметкой (не всю поездку). Факты отделяй от предположений. Без воды, без общих советов, только по данным этой машины."""
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

    /**
     * The data block for deep questions: exact relations for each of the recent trips and the
     * raw rows of the newest [rawTrips] trips (≈ 45–50K tokens per 46 minutes).
     */
    fun research(details: List<TripDetail>, rawTrips: Int): String = buildString {
        val sorted = details.sortedBy { it.summary.start }
        for (d in sorted.takeLast(3)) {
            appendLine(moments(d).trim())
            appendLine()
        }
        for (d in sorted.takeLast(3)) {
            appendLine(Relations.render(d).trim())
            appendLine()
        }
        for (d in sorted.takeLast(rawTrips)) {
            appendLine(Relations.rawTable(d).trim())
            appendLine()
        }
    }

    /** The messages sent with a question: everything kept (the older part lives in the summary). */
    fun history(state: ChatState): List<ChatMessage> = state.messages.toList()
}
