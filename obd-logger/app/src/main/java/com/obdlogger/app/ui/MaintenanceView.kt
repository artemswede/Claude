package com.obdlogger.app.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import com.obdlogger.app.MaintenanceStore
import com.obdlogger.core.Due
import com.obdlogger.core.Interval
import com.obdlogger.core.Maintenance
import com.obdlogger.core.MaintenanceEntry
import com.obdlogger.core.Mileage
import com.obdlogger.core.Work
import java.time.LocalDate

/**
 * «Журнал → ТО и ремонт»: the mileage the owner enters (never summed from trips — the app may
 * start late and miss part of the way), the checklist «пора / скоро / в норме», the records,
 * CSV export. [ecuKm] — the ECU's odometer when the car reports it (PID A6): only offered.
 */
class MaintenanceView(ctx: Context, private val sc: Bt.Scale, private val ecuKm: () -> Int? = { null },
    private val onExport: () -> Unit = {}) : FrameLayout(ctx) {
    private val p = Bt.LIGHT
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(12), dp(sc.pad), dp(24)) }
    /** Data for the scenes in tests; null — from the store. */
    var fixed: MaintenanceStore.Data? = null

    init {
        setBackgroundColor(p.bg)
        addView(ScrollView(ctx).apply { addView(list) })
    }

    private fun data() = fixed ?: MaintenanceStore.load(context)

    fun refresh() {
        val ctx = context
        val d = data()
        list.removeAllViews()
        // Mileage now: entered by hand, with the date it was entered.
        val cur = d.current
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(top, column(ctx, dp(2),
            ctx.label("Пробег сейчас", sc, p),
            ctx.text(cur?.let { Maintenance.km(it.km) } ?: "не введён", if (sc.phone) 24f else 28f, p.t1, 600, mono = true),
            ctx.text(cur?.let { "введён ${it.date.format(Maintenance.SHOW)} · вносится вручную" } ?: "Введите с приборной панели — по нему считается, что пора менять", sc.cap, p.t3),
        ), 0, 1f)
        addTo(top, ctx.button(if (cur == null) "Ввести пробег" else "Обновить", sc, p, primary = cur == null) { askMileage(null) }, 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(list, card(top, p, dp(16), dp(12)))
        ecuKm()?.takeIf { e -> cur == null || kotlin.math.abs(e - cur.km) > 50 }?.let { e ->
            addTo(list, ctx.button("ЭБУ сообщает ${Maintenance.km(e)} — подставить?", sc, p, primary = false) { askMileage(e) }, dp(8), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // The checklist: the most urgent first.
        val cl = d.checklist()
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(head, ctx.text("По регламенту", sc.hs, p.t1, 700), 0, 1f)
        addTo(head, ctx.text("Интервалы…", sc.p, p.acc, 600).apply {
            setPadding(dp(12), 0, dp(4), 0)
            setOnClickListener { editIntervals() }
        }.tap(), 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(list, head, dp(18))
        val shown = cl.filter { it.due != Due.UNKNOWN }
        if (shown.isEmpty()) addTo(list, ctx.text("Добавьте записи о работах — Бортач посчитает, что и когда пора менять.", sc.p, p.t2), dp(6))
        for (it in shown) {
            val color = when (it.due) { Due.NOW -> p.red; Due.SOON -> p.amb; else -> p.acc }
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48) }
            addTo(r, ctx.chip(it.due.ru, color, if (it.due == Due.NOW) p.redT else if (it.due == Due.SOON) p.ambT else p.accT, 14f), 0, width = dp(if (sc.phone) 90 else 104))
            addTo(r, ctx.text(it.work.ru, sc.p, p.t1, 600, maxLines = 1), 0, 1f)
            addTo(r, ctx.text(it.left(), sc.cap + 1, p.t2, maxLines = 1), 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
            addTo(list, r, dp(4))
        }
        cl.filter { it.due == Due.UNKNOWN }.takeIf { it.isNotEmpty() }?.let { u ->
            addTo(list, ctx.text("Нет записей: " + u.joinToString(", ") { it.work.ru.lowercase() }, sc.cap + 1, p.t3), dp(8))
        }

        // Records.
        val rh = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addTo(rh, ctx.text("Записи · ${d.entries.size}", sc.hs, p.t1, 700), 0, 1f)
        if (d.entries.isNotEmpty()) addTo(rh, ctx.button("Выгрузить CSV", sc, p, primary = false) { onExport() }, 0, width = ViewGroup.LayoutParams.WRAP_CONTENT)
        addTo(rh, ctx.button("＋ Запись", sc, p, primary = true) { editEntry(null) }, dp(0), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        (rh.getChildAt(rh.childCount - 1).layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        addTo(list, rh, dp(20))
        if (d.entries.isEmpty()) addTo(list, ctx.text("Пока пусто. Можно и из чата: «поменял колодки на 182 тыс.» — ИИ предложит запись.", sc.p, p.t2), dp(6))
        for (e in d.entries.sortedWith(compareByDescending<MaintenanceEntry> { it.date }.thenByDescending { it.km ?: 0 })) {
            val r = column(ctx, dp(2),
                row(ctx, dp(10), Gravity.CENTER_VERTICAL,
                    ctx.text(e.date.format(Maintenance.SHOW), sc.p, p.t2, mono = true),
                    ctx.text(e.km?.let { Maintenance.km(it) } ?: "—", sc.p, p.t2, mono = true),
                    ctx.text(e.name, sc.p, p.t1, 600, maxLines = 1)),
                ctx.text(listOf(e.parts, e.cost.takeIf { it.isNotBlank() }?.let { "$it ₽" }.orEmpty(), e.place, e.note).filter { it.isNotBlank() }.joinToString(" · "), sc.cap + 1, p.t3, maxLines = 2),
            )
            r.setPadding(0, dp(10), 0, dp(10))
            r.minimumHeight = dp(48)
            r.setOnClickListener { editEntry(e) }
            addTo(list, r)
            addTo(list, hline(ctx, p.line))
        }
    }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) = EditText(context).apply {
        this.hint = hint
        setText(value)
        inputType = type
        setSingleLine(true)
    }

    private fun parseKm(s: String) = com.obdlogger.core.MaintenanceTag.km(s)

    private fun askMileage(prefill: Int?) {
        val d = data()
        val f = field("например 182 400", prefill?.toString() ?: "", InputType.TYPE_CLASS_NUMBER)
        AlertDialog.Builder(context)
            .setTitle("Пробег сейчас, км")
            .setMessage("С приборной панели. Бортач не суммирует километры поездок — запись может начаться позже, чем вы тронулись.")
            .setView(pad(f))
            .setPositiveButton("Сохранить") { _, _ ->
                val km = parseKm(f.text.toString()) ?: return@setPositiveButton
                val cur = d.current
                val save = { MaintenanceStore.addMileage(context, Mileage(d.today, km)); refresh() }
                if (cur != null && km < cur.km) AlertDialog.Builder(context)
                    .setMessage("${Maintenance.km(km)} меньше прежнего (${Maintenance.km(cur.km)}). Сохранить всё равно?")
                    .setPositiveButton("Сохранить") { _, _ -> save() }.setNegativeButton("Отмена", null).show()
                else save()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun pad(v: android.view.View) = FrameLayout(context).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(v) }

    /** A new record ([old] null) or an edit; «Удалить» for an existing one. */
    fun editEntry(old: MaintenanceEntry?, preset: MaintenanceEntry? = null) {
        val d = data()
        val e = old ?: preset
        val works = Work.entries.toList()
        val spin = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, works.map { it.ru })
            setSelection(works.indexOf(e?.work ?: Work.OIL).coerceAtLeast(0))
            minimumHeight = dp(48)
        }
        val title = field("что именно (для «Прочее»)", e?.title.orEmpty())
        val date = field("дата ДД.ММ.ГГГГ", (e?.date ?: d.today).format(Maintenance.SHOW), InputType.TYPE_CLASS_DATETIME)
        val km = field("пробег, км", (e?.km ?: d.current?.km)?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        val parts = field("детали, артикул", e?.parts.orEmpty())
        val cost = field("стоимость, ₽", e?.cost.orEmpty(), InputType.TYPE_CLASS_NUMBER)
        val place = field("где делали", e?.place.orEmpty())
        val note = field("заметка", e?.note.orEmpty())
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        listOf(spin, title, date, km, parts, cost, place, note).forEach { box.addView(it) }
        val b = AlertDialog.Builder(context)
            .setTitle(if (old == null) "Запись в журнал" else "Запись")
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton("Сохранить") { _, _ ->
                val n = MaintenanceEntry(
                    com.obdlogger.core.MaintenanceTag.date(date.text.toString(), d.today) ?: d.today,
                    parseKm(km.text.toString()), works[spin.selectedItemPosition], title.text.toString().trim(),
                    parts.text.toString().trim(), cost.text.toString().trim(), place.text.toString().trim(), note.text.toString().trim())
                if (old == null) MaintenanceStore.add(context, n) else MaintenanceStore.replace(context, old, n)
                refresh()
            }
            .setNegativeButton("Отмена", null)
        if (old != null) b.setNeutralButton("Удалить") { _, _ ->
            AlertDialog.Builder(context).setMessage("Удалить запись «${old.name}»?")
                .setPositiveButton("Удалить") { _, _ -> MaintenanceStore.replace(context, old, null); refresh() }
                .setNegativeButton("Отмена", null).show()
        }
        b.show()
    }

    private fun editIntervals() {
        val d = data()
        val works = Work.entries.filter { it != Work.OTHER }
        AlertDialog.Builder(context)
            .setTitle("Интервалы замены")
            .setItems(works.map { w -> d.intervals[w].let { i -> "${w.ru}: " + listOfNotNull(i?.km?.let { Maintenance.km(it) }, i?.months?.let { "$it мес." }).ifEmpty { listOf("по осмотру") }.joinToString(" или ") } }.toTypedArray()) { _, i ->
                val w = works[i]
                val cur = d.intervals[w]
                val km = field("км (пусто — не считать)", cur?.km?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
                val mo = field("месяцев (пусто — не считать)", cur?.months?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
                val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(km); addView(mo) }
                AlertDialog.Builder(context).setTitle(w.ru).setView(box)
                    .setPositiveButton("Сохранить") { _, _ ->
                        MaintenanceStore.setIntervals(context, d.intervals + (w to Interval(km.text.toString().toIntOrNull(), mo.text.toString().toIntOrNull())))
                        refresh()
                    }
                    .setNeutralButton("По умолчанию") { _, _ -> MaintenanceStore.setIntervals(context, d.intervals + (w to Interval(w.km, w.months))); refresh() }
                    .setNegativeButton("Отмена", null).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
