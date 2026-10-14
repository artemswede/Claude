package com.obdlogger.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * «Медкарта» машины: журнал ТО и ремонтов, пробег, который водитель вводит сам, и
 * чек-лист «пора / скоро / в норме» по интервалам. Пробег по поездкам не суммируется:
 * приложение может включиться поздно и пропустить часть пути.
 */
enum class Work(val ru: String, val km: Int?, val months: Int?, private val keys: List<String>) {
    // Order matters for [of]: «масло КПП» is the gearbox, not the engine oil.
    GEARBOX("Масло КПП (АКПП/МКПП)", 60_000, null, listOf("кпп", "коробк", "вариатор", "трансмисс")),
    BRAKE_FLUID("Тормозная жидкость", null, 24, listOf("тормозн жидк", "тормозная жидк", "тж ")),
    OIL("Масло и масляный фильтр", 10_000, 12, listOf("масл")),
    AIR_FILTER("Воздушный фильтр", 20_000, 24, listOf("воздушн")),
    CABIN_FILTER("Салонный фильтр", 15_000, 12, listOf("салон")),
    FUEL_FILTER("Топливный фильтр", 40_000, 48, listOf("топливн")),
    PLUGS("Свечи зажигания", 30_000, 36, listOf("свеч")),
    PADS_FRONT("Колодки передние", 15_000, null, listOf("колодки пер", "передние колод", "колодок пер", "колодки спер")),
    PADS_REAR("Колодки задние", 30_000, null, listOf("колодки зад", "задние колод", "колодок зад")),
    DISCS("Тормозные диски", 60_000, null, listOf("диск")),
    COOLANT("Охлаждающая жидкость", 60_000, 48, listOf("антифриз", "тосол", "охлажд", "ож ")),
    TIMING("Ремень или цепь ГРМ", 100_000, null, listOf("грм")),
    AUX_BELT("Ремень навесного", 60_000, 48, listOf("навесн", "ремень генер", "поликлин")),
    BATTERY("Аккумулятор", null, 48, listOf("акб", "аккумул")),
    TIRES("Шины", null, null, listOf(" шин", " резин", "покрышк")),
    OTHER("Прочее", null, null, emptyList());

    companion object {
        /** «поменял колодки спереди» → [PADS_FRONT]; unknown → [OTHER]. */
        fun of(text: String): Work {
            val t = " " + text.lowercase().replace('ё', 'е') + " "
            entries.firstOrNull { it.name == text.trim().uppercase() }?.let { return it }
            entries.firstOrNull { w -> w.ru.lowercase() == text.trim().lowercase() }?.let { return it }
            if ("колодк" in t && "зад" !in t) return PADS_FRONT
            return entries.firstOrNull { w -> w.keys.any { it in t } } ?: OTHER
        }
    }
}

/** One record: what was done, when and at what mileage. */
data class MaintenanceEntry(
    val date: LocalDate,
    val km: Int?,
    val work: Work,
    /** The owner's words for «Прочее» (or a detail of the work). */
    val title: String = "",
    val parts: String = "",
    val cost: String = "",
    val place: String = "",
    val note: String = "",
) {
    val name get() = if (work == Work.OTHER && title.isNotBlank()) title else work.ru
}

/** Mileage entered by the owner on a day. */
data class Mileage(val date: LocalDate, val km: Int)

/** Interval of a work: km and/or months (the owner can change the defaults). */
data class Interval(val km: Int?, val months: Int?)

enum class Due(val ru: String) { NOW("Пора"), SOON("Скоро"), UNKNOWN("Нет записей"), OK("В норме") }

data class CheckItem(val work: Work, val due: Due, val last: MaintenanceEntry?, val kmLeft: Int?, val daysLeft: Long?) {
    /** «через 400 км», «просрочено на 1 200 км», «через 3 мес.» — the nearest of the two. */
    fun left(): String {
        val parts = ArrayList<String>()
        kmLeft?.let { parts += if (it >= 0) "через ${Maintenance.km(it)}" else "просрочено на ${Maintenance.km(-it)}" }
        daysLeft?.let { d ->
            parts += when {
                d < 0 -> "просрочено на ${Maintenance.period(-d)}"
                else -> "через ${Maintenance.period(d)}"
            }
        }
        return parts.joinToString(" или ")
    }
}

object Maintenance {
    private val CSV_DATE = DateTimeFormatter.ISO_LOCAL_DATE
    val SHOW: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Warn in the list below this; the reminder after a trip uses [REMIND_KM] / [REMIND_DAYS]. */
    const val SOON_KM = 1_000
    const val SOON_DAYS = 30L
    const val REMIND_KM = 500
    const val REMIND_DAYS = 14L

    fun km(v: Int): String = "%,d км".format(v).replace(',', ' ')

    fun period(days: Long): String = when {
        days < 45 -> "$days дн."
        else -> "${(days + 15) / 30} мес."
    }

    fun defaults(): Map<Work, Interval> = Work.entries.associateWith { Interval(it.km, it.months) }

    // ---- CSV: date;km;work;title;parts;cost;place;note ----

    const val HEADER = "date;km;work;title;parts;cost;place;note"

    private fun esc(s: String) = if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"").replace('\n', ' ') + "\"" else s

    private fun split(line: String): List<String> {
        val out = ArrayList<String>()
        val b = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && line.getOrNull(i + 1) == '"' -> { b.append('"'); i++ }
                c == '"' -> q = !q
                c == ';' && !q -> { out += b.toString(); b.clear() }
                else -> b.append(c)
            }
            i++
        }
        out += b.toString()
        return out
    }

    fun encode(entries: List<MaintenanceEntry>): String = buildString {
        append(HEADER).append('\n')
        for (e in entries) append(listOf(e.date.format(CSV_DATE), e.km?.toString().orEmpty(), e.work.name, e.title, e.parts, e.cost, e.place, e.note).joinToString(";") { esc(it) }).append('\n')
    }

    fun decode(text: String): List<MaintenanceEntry> = text.lines().drop(1).mapNotNull { l ->
        if (l.isBlank()) return@mapNotNull null
        val f = split(l)
        val date = try { LocalDate.parse(f[0], CSV_DATE) } catch (_: Exception) { return@mapNotNull null }
        MaintenanceEntry(date, f.getOrNull(1)?.toIntOrNull(), runCatching { Work.valueOf(f.getOrNull(2).orEmpty()) }.getOrDefault(Work.OTHER),
            f.getOrNull(3).orEmpty(), f.getOrNull(4).orEmpty(), f.getOrNull(5).orEmpty(), f.getOrNull(6).orEmpty(), f.getOrNull(7).orEmpty())
    }

    fun encodeMileage(list: List<Mileage>): String = list.joinToString("\n") { "${it.date.format(CSV_DATE)};${it.km}" }

    fun decodeMileage(text: String): List<Mileage> = text.lines().mapNotNull { l ->
        val f = l.split(';')
        val d = try { LocalDate.parse(f[0].trim(), CSV_DATE) } catch (_: Exception) { return@mapNotNull null }
        f.getOrNull(1)?.trim()?.toIntOrNull()?.let { Mileage(d, it) }
    }

    /** Intervals as «OIL=10000/12,…» for the settings; missing works keep the default. */
    fun encodeIntervals(m: Map<Work, Interval>): String = m.entries.joinToString(",") { (w, i) -> "${w.name}=${i.km ?: ""}/${i.months ?: ""}" }

    fun decodeIntervals(s: String?): Map<Work, Interval> {
        val out = defaults().toMutableMap()
        s?.split(',')?.forEach { p ->
            val w = runCatching { Work.valueOf(p.substringBefore('=')) }.getOrNull() ?: return@forEach
            val v = p.substringAfter('=', "")
            out[w] = Interval(v.substringBefore('/').toIntOrNull(), v.substringAfter('/', "").toIntOrNull())
        }
        return out
    }

    /** The newest mileage (by date, then the larger number). */
    fun current(list: List<Mileage>): Mileage? = list.maxWithOrNull(compareBy<Mileage> { it.date }.thenBy { it.km })

    /**
     * «Пора / скоро / нет записей / в норме» for every work with an interval, the most urgent
     * first. Km left = last record's km + interval − current mileage; days left = last date +
     * months − today.
     */
    fun checklist(entries: List<MaintenanceEntry>, mileage: List<Mileage>, intervals: Map<Work, Interval>, today: LocalDate): List<CheckItem> {
        val now = current(mileage)?.km
        val out = ArrayList<CheckItem>()
        for ((w, i) in intervals) {
            if (w == Work.OTHER || (i.km == null && i.months == null)) continue
            val last = entries.filter { it.work == w }.maxWithOrNull(compareBy<MaintenanceEntry> { it.date }.thenBy { it.km ?: 0 })
            if (last == null) { out += CheckItem(w, Due.UNKNOWN, null, null, null); continue }
            val kmLeft = if (i.km != null && last.km != null && now != null) last.km + i.km - now else null
            val daysLeft = i.months?.let { ChronoUnit.DAYS.between(today, last.date.plusMonths(it.toLong())) }
            val due = when {
                (kmLeft != null && kmLeft <= 0) || (daysLeft != null && daysLeft <= 0) -> Due.NOW
                (kmLeft != null && kmLeft < SOON_KM) || (daysLeft != null && daysLeft < SOON_DAYS) -> Due.SOON
                else -> Due.OK
            }
            out += CheckItem(w, due, last, kmLeft, daysLeft)
        }
        return out.sortedWith(compareBy<CheckItem> { it.due.ordinal }.thenBy { it.kmLeft ?: Int.MAX_VALUE }.thenBy { it.daysLeft ?: Long.MAX_VALUE })
    }

    /** One line for the notification after a trip, or null: «Скоро ТО: масло — через 400 км». */
    fun reminder(items: List<CheckItem>): String? {
        val near = items.filter { it.due == Due.NOW || (it.kmLeft != null && it.kmLeft < REMIND_KM) || (it.daysLeft != null && it.daysLeft < REMIND_DAYS) }
            .filter { it.due != Due.UNKNOWN }
        if (near.isEmpty()) return null
        val first = near.first()
        return (if (first.due == Due.NOW) "Пора ТО: " else "Скоро ТО: ") + first.work.ru.lowercase() + " — " + first.left() +
            if (near.size > 1) " (и ещё ${near.size - 1})" else ""
    }

    /** «МЕДКАРТА» for the AI: mileage, the last works, what is due. */
    fun render(entries: List<MaintenanceEntry>, mileage: List<Mileage>, intervals: Map<Work, Interval>, today: LocalDate): String {
        val cur = current(mileage)
        if (entries.isEmpty() && cur == null) return "МЕДКАРТА: водитель пока ничего не записал (пробег и ТО вносятся вручную в Журнал → ТО и ремонт или тегами из чата)."
        val sb = StringBuilder("МЕДКАРТА (журнал ТО, ведёт водитель):\n")
        sb.append("Пробег: ").append(cur?.let { "${km(it.km)} (введён ${it.date.format(SHOW)})" } ?: "не введён").append('\n')
        if (entries.isNotEmpty()) {
            sb.append("Последние работы:\n")
            entries.sortedByDescending { it.date }.take(12).forEach { e ->
                sb.append("- ${e.date.format(SHOW)}").append(e.km?.let { " · ${km(it)}" } ?: "").append(" · ${e.name}")
                if (e.parts.isNotBlank()) sb.append(" · ${e.parts}")
                if (e.note.isNotBlank()) sb.append(" · ${e.note}")
                sb.append('\n')
            }
        }
        val cl = checklist(entries, mileage, intervals, today)
        fun names(d: Due) = cl.filter { it.due == d }.joinToString("; ") { it.work.ru.lowercase() + (it.left().takeIf { s -> s.isNotBlank() }?.let { s -> " ($s)" } ?: "") }
        names(Due.NOW).takeIf { it.isNotBlank() }?.let { sb.append("Пора по регламенту: $it\n") }
        names(Due.SOON).takeIf { it.isNotBlank() }?.let { sb.append("Скоро: $it\n") }
        names(Due.UNKNOWN).takeIf { it.isNotBlank() }?.let { sb.append("Нет записей: $it\n") }
        return sb.toString().trimEnd()
    }
}

/**
 * Journal records the assistant proposes in its answer — saved only after the owner presses:
 * `[запись ТО: колодки перед; пробег 182000; дата 05.10; заметка …]` and `[пробег: 182400]`.
 */
data class MaintenanceTag(
    val mileageOnly: Boolean,
    val km: Int?,
    val work: Work = Work.OTHER,
    val title: String = "",
    /** null — today. */
    val date: LocalDate? = null,
    val parts: String = "",
    val cost: String = "",
    val place: String = "",
    val note: String = "",
) {
    fun button(): String = if (mileageOnly) "Сохранить пробег: ${Maintenance.km(km ?: 0)}"
    else "＋ Записать в журнал: " + (if (work == Work.OTHER && title.isNotBlank()) title else work.ru).lowercase() +
        (km?.let { " · ${Maintenance.km(it)}" } ?: "") + (date?.let { " · ${it.format(DateTimeFormatter.ofPattern("dd.MM"))}" } ?: "")

    fun entry(today: LocalDate) = MaintenanceEntry(date ?: today, km, work, if (work == Work.OTHER) title else "", parts, cost, place, note)

    companion object {
        private val record = Regex("\\[запись ТО:\\s*([^\\]]+)]", RegexOption.IGNORE_CASE)
        private val mileage = Regex("\\[пробег:\\s*([^\\]]+)]", RegexOption.IGNORE_CASE)

        /** «182 400», «182400 км», «182 тыс.» → 182400. */
        fun km(s: String): Int? {
            val t = s.lowercase().replace(' ', ' ')
            val num = Regex("(\\d[\\d ]*(?:[.,]\\d+)?)").find(t)?.groupValues?.get(1)?.replace(" ", "")?.replace(',', '.') ?: return null
            val v = num.toDoubleOrNull() ?: return null
            val k = if ("тыс" in t && v < 10_000) v * 1000 else v
            return k.toInt().takeIf { it in 1..3_000_000 }
        }

        /** «05.10», «05.10.2026» → the date; without a year — this year, or the last one if that would be in the future. */
        fun date(s: String, today: LocalDate): LocalDate? {
            val m = Regex("(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?").find(s) ?: return null
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            return try {
                if (y != null) LocalDate.of(y, mo, d)
                else LocalDate.of(today.year, mo, d).let { if (it.isAfter(today)) it.minusYears(1) else it }
            } catch (_: Exception) { null }
        }

        fun parse(text: String, today: LocalDate = LocalDate.now()): List<MaintenanceTag> {
            val out = ArrayList<MaintenanceTag>()
            for (m in record.findAll(text)) {
                val parts = m.groupValues[1].split(';').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isEmpty()) continue
                var km: Int? = null
                var date: LocalDate? = null
                var note = ""
                var det = ""
                var cost = ""
                var place = ""
                val what = ArrayList<String>()
                for (p in parts) {
                    val l = p.lowercase()
                    when {
                        l.startsWith("пробег") -> km = km(p.substringAfter(' ', ""))
                        l.startsWith("дата") -> date = date(p, today)
                        l.startsWith("заметка") -> note = p.substringAfter(' ', "").trim()
                        l.startsWith("детали") || l.startsWith("артикул") -> det = p.substringAfter(' ', "").trim()
                        l.startsWith("стоимость") || l.startsWith("цена") -> cost = p.substringAfter(' ', "").trim()
                        l.startsWith("где") || l.startsWith("сервис") -> place = p.substringAfter(' ', "").trim()
                        else -> what += p
                    }
                }
                val title = what.joinToString(", ")
                out += MaintenanceTag(false, km, Work.of(title), title, date, det, cost, place, note)
            }
            for (m in mileage.findAll(text)) km(m.groupValues[1])?.let { out += MaintenanceTag(true, it) }
            return out
        }

        fun strip(text: String): String = text.replace(record, "").replace(mileage, "")
    }
}
