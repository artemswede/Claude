package com.obdlogger.app

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.obdlogger.core.SensorNames
import com.obdlogger.core.SensorStats

/**
 * Live monitor: lines of chosen sensors, min/median/mean/max statistics with a
 * histogram, sensors ranked by instability, and the comparison of recorded trips.
 * Reads [LiveData.store] and the saved trips.
 */
class MonitorPanel(private val context: Activity, container: FrameLayout) {
    private enum class Tab { LINES, STATS, INSTABILITY, TRIPS }

    private val dp = context.resources.displayMetrics.density
    private var tab = Tab.LINES
    /** Sensors on the chart, in colour order. */
    private val selected = mutableListOf<String>()
    private var focused: String? = null
    private var userPicked = false
    private var lastColumns: List<String> = emptyList()
    private var tripsLoadedFor = -1
    @Volatile private var tripsLoading = false

    private val chart = LineChartView(context)
    private val histogram = HistogramView(context)
    private val statsTable = SensorTableView(context).apply { mode = SensorTableView.Mode.STATS }
    private val instabilityTable = SensorTableView(context).apply { mode = SensorTableView.Mode.INSTABILITY }
    private val hint = TextView(context).apply {
        setTextColor(Palette.TEXT)
        textSize = 13f
        setBackgroundColor(Palette.PANEL)
        setPadding(px(12), px(8), px(12), px(8))
    }
    /** Shown when the data is from a finished recording, not live. */
    private val staleBanner = TextView(context).apply {
        setTextColor(Palette.BG)
        setBackgroundColor(Palette.WARN)
        textSize = 13f
        setPadding(px(12), px(6), px(12), px(6))
        visibility = View.GONE
    }
    private val tripsText = TextView(context).apply {
        setTextColor(Palette.TEXT)
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setPadding(px(12), px(8), px(12), px(8))
        setTextIsSelectable(true)
        text = "Загрузка поездок…"
    }
    private val windowButtons = mutableMapOf<Int, Button>()
    private val tabButtons = mutableMapOf<Tab, Button>()
    private val sortButton = button("Сортировка: скачки") { toggleSort() }

    private val linesPage = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val statsPage = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val instabilityPage = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tripsPage = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(px(6), px(6), px(6), px(6))
        }
        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((t, title) in listOf(Tab.LINES to "Линии", Tab.STATS to "Статистика", Tab.INSTABILITY to "Нестабильность", Tab.TRIPS to "Поездки")) {
            val b = button(title) { show(t) }
            tabButtons[t] = b
            tabs.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs)
        root.addView(staleBanner)

        val windows = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        for (m in listOf(1, 5, 15)) {
            val b = button("$m мин") { setWindow(m) }
            windowButtons[m] = b
            windows.addView(b)
        }
        linesPage.addView(windows)
        linesPage.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        linesPage.addView(caption("Подпись каждой линии стоит справа на высоте её последней точки. Каждая линия масштабирована к своему min–max за окно. Линии добавляются нажатием на датчик во вкладках «Статистика» и «Нестабильность» (до 8)."))

        statsPage.addView(histogram, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(150)))
        statsPage.addView(caption("Шкала: от min до max за запись. Белая риска — медиана, оранжевая — среднее. Цифры: min / медиана / среднее / max."))
        statsPage.addView(scroll(statsTable), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        instabilityPage.addView(sortButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        instabilityPage.addView(hint)
        instabilityPage.addView(scroll(instabilityTable), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        tripsPage.addView(button("Обновить сравнение") { loadTrips(force = true) })
        val tripsScroll = ScrollView(context).apply {
            addView(HorizontalScrollView(context).apply { addView(tripsText) })
        }
        tripsPage.addView(tripsScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val pages = FrameLayout(context)
        for (p in listOf(linesPage, statsPage, instabilityPage, tripsPage)) pages.addView(p)
        root.addView(pages, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(root)

        statsTable.onRowClick = ::onSensorClick
        instabilityTable.onRowClick = ::onSensorClick
        setWindow(5)
        show(Tab.LINES)
    }

    /** Recomputes everything from the store; call about once a second. */
    fun refresh() {
        val state = LoggerState.snapshot
        val store = LiveData.store
        val live = state.recording
        val last = store.lastTime()
        staleBanner.visibility = if (!live && last != null) View.VISIBLE else View.GONE
        if (!live && last != null) {
            staleBanner.text = "Запись не идёт — показаны данные прошлой записи, последняя точка ${clock(last)}"
        }
        if (store.columns != lastColumns) {
            lastColumns = store.columns
            if (!userPicked) {
                selected.clear()
                selected += DEFAULT_LINES.filter { it in lastColumns }.take(5)
            }
            selected.retainAll(lastColumns.toSet())
            if (focused?.let { it in lastColumns } != true) focused = selected.firstOrNull()
        }
        val colours = selected.withIndex().associate { (i, n) -> n to Palette.LINES[i % Palette.LINES.size] }

        when (tab) {
            Tab.LINES -> {
                val end = last ?: 0L
                chart.endMs = end
                chart.live = live
                chart.lines = selected.map { name ->
                    val (t, v) = store.series(name, end - chart.windowMs)
                    LineChartView.Line(name, colours.getValue(name), t, v)
                }
            }
            Tab.STATS -> {
                val stats = store.stats()
                statsTable.onChart = colours
                statsTable.focused = focused
                statsTable.update(stats)
                val f = focused
                histogram.update(f.orEmpty(), stats.firstOrNull { it.name == f },
                    if (f != null) store.histogram(f, 30) else IntArray(0), f?.let { colours[it] } ?: Palette.ACCENT)
            }
            Tab.INSTABILITY -> {
                val stats = store.stats()
                instabilityTable.onChart = colours
                instabilityTable.focused = focused
                instabilityTable.update(stats)
                hint.text = instabilityHint(instabilityTable.rows)
            }
            Tab.TRIPS -> if (tripsLoadedFor != state.savedTrips) loadTrips(force = false)
        }
    }

    /** Analyses saved trips off the UI thread (reads every CSV) and shows the comparison. */
    private fun loadTrips(force: Boolean) {
        if (tripsLoading) return
        val version = LoggerState.snapshot.savedTrips
        if (!force && tripsLoadedFor == version) return
        tripsLoading = true
        tripsText.text = "Анализ поездок…"
        Thread {
            val text = try {
                val count = SessionFiles.tripCsvs(context).size
                if (count == 0) {
                    "Сохранённых поездок пока нет. После каждой записи здесь появится разбор поездки, " +
                        "сравнение с прошлыми и прогноз отклонений."
                } else {
                    "Поездок сохранено: $count. Сравниваются последние 6.\n\n" + SessionFiles.compareRecent(context)
                }
            } catch (e: Exception) {
                "Не удалось проанализировать поездки: $e"
            }
            context.runOnUiThread {
                tripsText.text = text
                tripsLoadedFor = version
                tripsLoading = false
            }
        }.start()
    }

    private fun instabilityHint(ranked: List<SensorStats>): String {
        if (ranked.isEmpty()) return "Нужно хотя бы несколько строк записи."
        val metric = if (instabilityTable.sortByCv) {
            "Вариация (CV) = стандартное отклонение / среднее. Не считается для величин около нуля (коррекции, углы)."
        } else {
            "Скачки = средний скачок между соседними замерами за последние 300 строк, в % от обычного диапазона датчика."
        }
        val top = ranked.take(3).mapNotNull { s -> SensorHints.hint(s.name)?.let { "• ${SensorNames.label(s.name)}: $it" } }
        return metric + "\nЗависит от режима езды: сравнивайте одинаковые участки (холостой ход с холостым).\n" +
            top.joinToString("\n")
    }

    private fun onSensorClick(name: String) {
        userPicked = true
        focused = name
        if (name in selected) selected.remove(name) else {
            if (selected.size >= Palette.LINES.size) selected.removeAt(0)
            selected += name
        }
        refresh()
    }

    private fun toggleSort() {
        instabilityTable.sortByCv = !instabilityTable.sortByCv
        sortButton.text = if (instabilityTable.sortByCv) "Сортировка: вариация (CV)" else "Сортировка: скачки"
        refresh()
    }

    private fun setWindow(minutes: Int) {
        chart.windowMs = minutes * 60_000L
        windowButtons.forEach { (m, b) -> b.alpha = if (m == minutes) 1f else 0.5f }
        refresh()
    }

    private fun show(t: Tab) {
        tab = t
        linesPage.visibility = if (t == Tab.LINES) View.VISIBLE else View.GONE
        statsPage.visibility = if (t == Tab.STATS) View.VISIBLE else View.GONE
        instabilityPage.visibility = if (t == Tab.INSTABILITY) View.VISIBLE else View.GONE
        tripsPage.visibility = if (t == Tab.TRIPS) View.VISIBLE else View.GONE
        tabButtons.forEach { (k, b) -> b.alpha = if (k == t) 1f else 0.5f }
        refresh()
    }

    private fun button(title: String, onClick: () -> Unit) = Button(context).apply {
        text = title
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun caption(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(Palette.MUTED)
        textSize = 11f
        setPadding(px(4), px(4), px(4), px(4))
    }

    private fun scroll(v: View) = ScrollView(context).apply { addView(v) }

    private fun px(v: Int) = (v * dp).toInt()

    companion object {
        private val DEFAULT_LINES = listOf("rpm", "speed_kmh", "coolant_c", "stft_b1_pct", "maf_gs", "o2_b1s1_v", "map_kpa")
    }
}
