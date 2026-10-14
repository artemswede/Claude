package com.obdlogger.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaintenanceTest {
    private val today = LocalDate.of(2026, 10, 14)

    @Test
    fun worksAreRecognisedFromWords() {
        assertEquals(Work.PADS_FRONT, Work.of("поменял колодки спереди"))
        assertEquals(Work.PADS_REAR, Work.of("колодки задние"))
        assertEquals(Work.GEARBOX, Work.of("масло в АКПП"))
        assertEquals(Work.OIL, Work.of("Масло и фильтр"))
        assertEquals(Work.TIMING, Work.of("ремень ГРМ с роликами"))
        assertEquals(Work.OTHER, Work.of("помыл машину"))
        assertEquals(Work.OIL, Work.of("OIL"))
    }

    @Test
    fun csvRoundTripKeepsSemicolonsAndQuotes() {
        val e = listOf(
            MaintenanceEntry(LocalDate.of(2026, 10, 5), 182_000, Work.PADS_FRONT, parts = "Brembo P83; \"ceramic\"", cost = "4500", place = "сервис", note = "скрипели"),
            MaintenanceEntry(LocalDate.of(2026, 3, 1), null, Work.OTHER, title = "Помыл радиатор"),
        )
        assertEquals(e, Maintenance.decode(Maintenance.encode(e)))
        val m = listOf(Mileage(today, 182_400), Mileage(LocalDate.of(2026, 9, 1), 181_000))
        assertEquals(m, Maintenance.decodeMileage(Maintenance.encodeMileage(m)))
        assertEquals(182_400, Maintenance.current(m)?.km)
    }

    @Test
    fun checklistByKmAndMonths() {
        val entries = listOf(
            MaintenanceEntry(LocalDate.of(2026, 1, 10), 172_800, Work.OIL),       // 10 000 км → до 182 800: осталось 400
            MaintenanceEntry(LocalDate.of(2024, 9, 1), 150_000, Work.BRAKE_FLUID), // 24 мес. — просрочено
            MaintenanceEntry(LocalDate.of(2026, 6, 1), 179_000, Work.AIR_FILTER),  // 20 000 км — в норме
        )
        val cl = Maintenance.checklist(entries, listOf(Mileage(today, 182_400)), Maintenance.defaults(), today)
        val oil = cl.first { it.work == Work.OIL }
        assertEquals(Due.SOON, oil.due)
        assertEquals(400, oil.kmLeft)
        assertEquals(Due.NOW, cl.first { it.work == Work.BRAKE_FLUID }.due)
        assertEquals(Due.OK, cl.first { it.work == Work.AIR_FILTER }.due)
        assertEquals(Due.UNKNOWN, cl.first { it.work == Work.TIMING }.due)
        assertEquals(Due.NOW, cl.first().due)
        val r = Maintenance.reminder(cl)!!
        assertTrue(r.startsWith("Пора ТО: тормозная жидкость"), r)
        val med = Maintenance.render(entries, listOf(Mileage(today, 182_400)), Maintenance.defaults(), today)
        assertTrue("Пробег: 182 400 км" in med, med)
        assertTrue("Пора по регламенту: тормозная жидкость" in med, med)
        assertTrue("Скоро: масло и масляный фильтр (через 400 км" in med, med)
    }

    @Test
    fun intervalsCanBeChanged() {
        val m = Maintenance.decodeIntervals("OIL=7500/6,TIMING=/")
        assertEquals(Interval(7500, 6), m[Work.OIL])
        assertEquals(Interval(null, null), m[Work.TIMING])
        assertEquals(Interval(20_000, 24), m[Work.AIR_FILTER])
        assertEquals(m, Maintenance.decodeIntervals(Maintenance.encodeIntervals(m)))
    }

    @Test
    fun tagsFromTheChat() {
        val text = "Коротко: записал.\n[запись ТО: колодки перед; пробег 182 000; дата 05.10; заметка скрипели]\n[пробег: 182 400 км]\n[запись ТО: масло Mobil 5W-30; пробег 182 тыс.]"
        val t = MaintenanceTag.parse(text, today)
        assertEquals(3, t.size)
        assertEquals(Work.PADS_FRONT, t[0].work)
        assertEquals(182_000, t[0].km)
        assertEquals(LocalDate.of(2026, 10, 5), t[0].date)
        assertEquals("скрипели", t[0].note)
        assertEquals(Work.OIL, t[1].work)
        assertEquals(182_000, t[1].km)
        assertTrue(t[2].mileageOnly)
        assertEquals(182_400, t[2].km)
        assertEquals("Сохранить пробег: 182 400 км", t[2].button())
        assertEquals("Коротко: записал.", MaintenanceTag.strip(text).trim())
        // A date later than today without a year is last year's.
        assertEquals(LocalDate.of(2025, 12, 20), MaintenanceTag.date("дата 20.12", today))
        assertEquals(today, t[0].entry(today).copy(date = today).date)
    }

    @Test
    fun vinDecoding() {
        assertTrue(Vin.valid("SB1BR56L00E123456"))
        assertTrue(!Vin.valid("SB1BR56L00E12345"))
        assertTrue(!Vin.valid("SB1BR56L00E12345O"))
        assertEquals("Toyota", Vin.maker("SB1BR56L00E123456"))
        assertEquals(2005, Vin.year("JTDBR32E550123456", 2026))
        assertEquals(2015, Vin.year("WVWZZZ3CZFE123456", 2026))
        assertEquals("Toyota, 2005 (по VIN)", Vin.describe("JTDBR32E550123456", 2026))
        assertNull(Vin.describe("123"))
    }

    @Test
    fun jsonCardBecomesText() {
        val t = ChatAnswer.fromCard("Подсос воздуха", listOf("обороты 540 → мало", "коррекция +22 %"), "осмотреть шланги", "средняя — один эпизод", "",
            listOf("[график: наложение rpm trim_b1 последняя]", "проверка: холостой"))
        val a = ChatAnswer.parse(ChatCharts.strip(ChatTests.strip(t)))
        assertTrue(a.structured)
        assertEquals("Подсос воздуха", a.short)
        assertEquals(2, a.why.size)
        assertEquals(1, ChatCharts.parse(t).size)
        assertEquals(1, ChatTests.parse(t).size)
    }
}
