package com.obdlogger.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.obdlogger.app.LoggerState
import com.obdlogger.core.CheckResult
import com.obdlogger.core.CheckTest
import com.obdlogger.core.TripAnalyzer
import kotlin.math.abs

/** What the check-log page asks the activity to do. */
interface CheckActions {
    fun back()
    fun startTest()
    fun stopTest()
    /** Clears the finished / aborted state. */
    fun closeTest()
}

/**
 * Проверочный лог (П1–П4): preparation with the start conditions, the running step
 * with rpm corridor and timer, the aborted screen and the result compared with the
 * previous check log.
 */
class CheckView(ctx: Context, private val sc: Bt.Scale, private val actions: CheckActions) : FrameLayout(ctx) {
    private val p = Bt.LIGHT
    private var last: String? = null

    init {
        setBackgroundColor(p.bg)
    }

    /** [results]: this check and the previous one, when the test is done. */
    fun bind(s: LoggerState.Snapshot, results: Pair<CheckResult?, CheckResult?>?) {
        val st = s.check
        val key = when {
            st == null -> "prep:" + prepKey(s)
            st.phase == CheckTest.Phase.RUNNING -> "run:${st.step.n}:${st.leftSec}:${st.rpm?.toInt()}"
            st.phase == CheckTest.Phase.ABORTED -> "abort"
            else -> "done:${results != null}"
        }
        if (key == last) return
        last = key
        removeAllViews()
        val v = when {
            st == null -> prep(s)
            st.phase == CheckTest.Phase.RUNNING -> running(st)
            st.phase == CheckTest.Phase.ABORTED -> aborted(st)
            else -> result(results)
        }
        addView(v)
    }

    private fun value(s: LoggerState.Snapshot, code: String) = s.values.firstOrNull { it.first == code }?.second?.toDoubleOrNull()

    private fun prepKey(s: LoggerState.Snapshot) = "${s.recording}:${value(s, "rpm")?.toInt()?.div(100)}:${value(s, "speed_kmh")?.toInt()}:${value(s, "coolant_c")?.toInt()}"

    private fun header(title: String): View {
        val back = context.text("← Обзор", if (sc.phone) 16f else 19f, p.t1, 500).apply { setOnClickListener { actions.back() } }.tap()
        return column(context, dp(10), back, context.text(title, if (sc.phone) 26f else 36f, p.t1, 700))
    }

    // П1
    private fun prep(s: LoggerState.Snapshot): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(24)) }
        addTo(page, header("Проверочный лог"))
        addTo(page, context.text("4 минуты на стоянке: прогретый холостой 2:00 → 2500 об/мин 1:00 → холостой 1:00. Одинаковые условия — честное сравнение до и после ремонта.", if (sc.phone) 16f else 20f, p.t2), dp(10))
        val rpm = value(s, "rpm")
        val speed = value(s, "speed_kmh")
        val coolant = value(s, "coolant_c")
        fun cond(ok: Boolean, title: String, now: String) = row(context, dp(14), Gravity.CENTER_VERTICAL,
            context.text(if (ok) "✓" else "✗", 22f, if (ok) p.acc else p.amb, 700),
            column(context, dp(2), context.text(title, if (sc.phone) 16f else 20f, p.t1, 600), context.text(now, if (sc.phone) 13f else 16f, p.t2)))
        addTo(page, cond(s.recording, "Идёт запись", if (s.recording) "данные приходят" else "заведите двигатель — запись начнётся сама"), dp(20))
        addTo(page, cond(rpm != null && rpm > 300, "Мотор работает", rpm?.let { "${it.toInt()} об/мин" } ?: "нет данных"), dp(14))
        addTo(page, cond(speed != null && speed < 0.5, "Машина стоит", speed?.let { "${it.toInt()} км/ч" } ?: "нет данных"), dp(14))
        addTo(page, cond(coolant != null && coolant >= CheckTest.WARM_C, "Мотор прогрет до ${CheckTest.WARM_C.toInt()} °C", coolant?.let { "сейчас ${it.toInt()} °C" } ?: "нет данных"), dp(14))
        addTo(page, context.text("Свет, печка и кондиционер — как обычно, но одинаково в каждом тесте.", if (sc.phone) 13f else 16f, p.t3), dp(18))
        val blocker = if (!s.recording) "Запись не идёт." else CheckTest.blocker(rpm, speed, coolant)
        val start = context.button("Начать тест", sc, p, primary = true) { actions.startTest() }.apply {
            isEnabled = blocker == null
            alpha = if (blocker == null) 1f else 0.4f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(sc.btnBigH))
        }
        addTo(page, start, dp(22), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        blocker?.let { addTo(page, context.text(it, if (sc.phone) 14f else 17f, p.amb), dp(8)) }
        return ScrollView(context).apply { addView(page) }
    }

    // П2
    private fun running(st: CheckTest.State): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(20)) }
        val steps = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        CheckTest.STEPS.forEachIndexed { i, step ->
            val on = step.n == st.step.n
            val c = column(context, dp(6),
                HomeView.Progress(context, st.progress[i].toFloat(), p.acc, p.s3),
                context.text("${step.n} ${step.title.replace("Держите ", "")} ${step.durationSec / 60}:00", if (sc.phone) 12f else 15f, if (on) p.t1 else p.t2, if (on) 600 else 400, maxLines = 2))
            steps.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, step.durationSec.toFloat()).apply { if (i > 0) leftMargin = dp(6) })
        }
        addTo(page, steps)
        val left = column(context, dp(8),
            context.label("Шаг ${st.step.n} из 3", sc, p),
            context.text(st.step.title, if (sc.phone) 24f else 34f, p.t1, 700),
            row(context, dp(10), Gravity.BOTTOM,
                context.text(st.rpm?.toInt()?.toString() ?: "—", if (sc.phone) 72f else 120f, p.t1, 400, mono = true),
                context.text("об/мин", if (sc.phone) 16f else 22f, p.t2)),
            Corridor(context, p, st).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)) },
        )
        val chip = when {
            st.inCorridor -> context.chip("✓ в коридоре ${st.step.lo}–${st.step.hi}", p.acc, p.accT, 16f)
            st.off == "ниже" -> context.chip("ниже ${st.step.lo} — ${if (st.step.n == 2) "добавьте газ" else "таймер на паузе"}", p.amb, p.ambT, 16f)
            st.off == "выше" -> context.chip("выше ${st.step.hi} — ${if (st.step.n == 2) "отпустите чуть газ" else "отпустите педаль"}", p.amb, p.ambT, 16f)
            else -> context.chip("жду данные от ЭБУ", p.t2, p.s3, 16f)
        }
        addTo(left, chip, dp(6), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        val stop = context.text("Остановить тест · удерживайте 1.5 с", if (sc.phone) 15f else 18f, p.t1, 600).apply {
            gravity = Gravity.CENTER
            background = roundRect(p.s1, dp(12).toFloat(), dp(1), p.line2)
            setPadding(dp(20), 0, dp(20), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(sc.btnH))
            holdToStop(this)
        }
        val right = column(context, dp(10),
            context.label("Осталось на шаге", sc, p),
            context.text("%d:%02d".format(st.leftSec / 60, st.leftSec % 60), if (sc.phone) 48f else 72f, p.t1, 400, mono = true),
            HomeView.Progress(context, 1f - st.leftSec.toFloat() / st.step.durationSec, p.acc, p.s3),
            context.text(st.step.hint, if (sc.phone) 15f else 19f, p.t2),
            context.text("Вне коридора таймер шага на паузе. Всего осталось ${st.totalLeftSec / 60}:%02d.".format(st.totalLeftSec % 60), if (sc.phone) 13f else 15f, p.t3))
        if (sc.phone) {
            addTo(page, left, dp(18)); addTo(page, right, dp(18)); addTo(page, stop, dp(18), width = ViewGroup.LayoutParams.WRAP_CONTENT)
            return ScrollView(context).apply { addView(page) }
        }
        val two = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        two.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val rcol = column(context, 0, right)
        addTo(rcol, stop, dp(28), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        two.addView(rcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply { leftMargin = dp(40) })
        addTo(page, two, dp(24))
        return page
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun holdToStop(v: View) {
        val stopper = Runnable { actions.stopTest() }
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> view.postDelayed(stopper, 1500)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.removeCallbacks(stopper)
            }
            true
        }
    }

    // П3
    private fun aborted(st: CheckTest.State): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(24)) }
        addTo(page, header("Тест прерван"))
        addTo(page, context.text(st.abortReason ?: "Тест остановлен.", if (sc.phone) 18f else 24f, p.t1), dp(12))
        addTo(page, context.text("Прерванный тест не сохраняется: сравнивать можно только полные. Поездка при этом записывается как обычно.", if (sc.phone) 15f else 18f, p.t2), dp(10))
        addTo(page, row(context, dp(12), Gravity.CENTER_VERTICAL,
            context.button("Повторить", sc, p, primary = true) { actions.closeTest() },
            context.button("На главный", sc, p, primary = false) { actions.closeTest(); actions.back() }), dp(22))
        return page
    }

    // П4
    private fun result(r: Pair<CheckResult?, CheckResult?>?): View {
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sc.pad), dp(16), dp(sc.pad), dp(24)) }
        addTo(page, header("Проверочный лог записан"))
        val now = r?.first
        val prev = r?.second
        if (now == null) {
            addTo(page, context.text("Считаю результат…", sc.p, p.t3), dp(12))
            return page
        }
        addTo(page, context.text(if (prev == null) "Это первый проверочный лог — он станет эталоном «до». Следующий сравнится с ним." else "Сравнение с проверочным логом ${prev.start?.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")) ?: ""}.",
            if (sc.phone) 16f else 20f, p.t2), dp(10))
        val rows = listOf<Triple<String, Double?, Double?>>(
            Triple("Коррекция Б1 на ХХ", now.idleTrim, prev?.idleTrim),
            Triple("Коррекция Б1 на 2500", now.revTrim, prev?.revTrim),
            Triple("Коррекция Б2 на ХХ", now.idleTrimB2, prev?.idleTrimB2),
            Triple("Обороты ХХ", now.idleRpm, prev?.idleRpm),
            Triple("Лямбда после кат., ХХ", now.rearO2Idle, prev?.rearO2Idle),
            Triple("Расход воздуха на ХХ", now.idleMaf, prev?.idleMaf),
        ).filter { it.second != null }
        val table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun fmt(title: String, v: Double?) = when {
            v == null -> "—"
            title.startsWith("Коррекция") -> TripAnalyzer.pct(v)
            title.startsWith("Обороты") -> "${v.toInt()}"
            else -> TripAnalyzer.fmt(v)
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
        head.addView(context.label("Показатель", sc, p), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        head.addView(context.label("Было", sc, p).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(context.label("Сейчас", sc, p).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        table.addView(head)
        table.addView(hline(context, p.line2))
        for ((title, v, was) in rows) {
            val trimOut = title.startsWith("Коррекция") && v != null && abs(v) > 10
            val r0 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(14)) }
            r0.addView(context.text(title, if (sc.phone) 15f else 19f, p.t1, 600), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
            r0.addView(context.text(fmt(title, was), if (sc.phone) 15f else 19f, p.t2, 400, mono = true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r0.addView(context.text(fmt(title, v), if (sc.phone) 15f else 19f, if (trimOut) p.amb else p.t1, 600, mono = true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            table.addView(r0)
            table.addView(hline(context, p.line))
        }
        addTo(page, table, dp(16))
        val idle = now.idleTrim
        val verdict = when {
            idle == null -> "На холостом не хватило данных о коррекции."
            abs(idle) <= 10 && (now.rearO2Idle ?: 1.0) >= 0.45 -> "Коррекция на ХХ в норме, задняя лямбда не падает — смесь в порядке."
            prev?.idleTrim != null && abs(idle) < abs(prev.idleTrim!!) - 3 -> "Стало лучше, но коррекция на ХХ ещё за нормой."
            else -> "Коррекция на ХХ за нормой."
        }
        addTo(page, card(context.text(verdict, if (sc.phone) 16f else 20f, p.t1, 600), p, dp(18), dp(14)), dp(16))
        addTo(page, context.button("Готово", sc, p, primary = true) { actions.closeTest(); actions.back() }, dp(18), width = ViewGroup.LayoutParams.WRAP_CONTENT)
        return ScrollView(context).apply { addView(page) }
    }

    /** Rpm scale 1500…3500 (or 400…1400 on idle) with the corridor and a marker. */
    private class Corridor(ctx: Context, private val p: Bt.Palette, private val st: CheckTest.State) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val lo = if (st.step.n == 2) 1500.0 else 400.0
            val hi = if (st.step.n == 2) 3500.0 else 1400.0
            val w = width.toFloat()
            val barH = 20 * d
            fun x(v: Double) = (w * ((v - lo) / (hi - lo))).toFloat().coerceIn(0f, w)
            fill.color = p.s3
            c.drawRect(0f, 0f, w, barH, fill)
            fill.color = if (st.inCorridor) p.acc else p.amb
            c.drawRect(x(st.step.lo.toDouble()), 0f, x(st.step.hi.toDouble()), barH, fill)
            st.rpm?.let {
                fill.color = p.t1
                c.drawRect(x(it) - 1.5f * d, -4 * d, x(it) + 1.5f * d, barH + 6 * d, fill)
            }
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.t2; textSize = 14 * d; typeface = Bt.sans(context) }
            for (v in listOf(lo, st.step.lo.toDouble(), st.step.hi.toDouble(), hi)) {
                val s = v.toInt().toString()
                c.drawText(s, (x(v) - t.measureText(s) / 2).coerceIn(0f, w - t.measureText(s)), barH + 18 * d, t)
            }
        }
    }
}
