package com.obdlogger.core

/**
 * Human- and LLM-readable companion file for the CSV: vehicle facts, trouble
 * codes, a column dictionary, per-column summary and a ready analysis prompt.
 */
class SessionReport(
    private val vehicle: String,
    private val adapter: AdapterInfo,
    private val info: VehicleInfo,
) {
    fun render(logger: DataLogger, finalDtcs: DtcSnapshot?, endMs: Long?): String = buildString {
        val car = vehicle.ifBlank { "не указан" }
        appendLine("=== OBD-логгер: сведения о сессии ===")
        appendLine("Автомобиль (со слов владельца): $car")
        appendLine("Начало записи: ${logger.formatTime(logger.startMs)}")
        if (endMs != null) {
            appendLine("Конец записи:  ${logger.formatTime(endMs)} (длительность ${duration(endMs - logger.startMs)})")
        } else {
            appendLine("Запись ещё идёт или прервалась аварийно — сводка ниже может быть неполной.")
        }
        appendLine("Адаптер: ${adapter.id.ifBlank { "?" }}; напряжение при подключении: ${adapter.voltage ?: "?"}")
        appendLine("Протокол OBD: ${info.protocol} (#${info.protocolNumber})")
        appendLine("VIN: ${info.vin ?: "ЭБУ не сообщает"}")
        appendLine("Стандарт OBD: ${info.obdStandard ?: "?"}")
        appendLine()
        appendLine("=== Коды неисправностей ===")
        appendDtcs("В начале сессии", info.dtcs)
        if (finalDtcs != null) appendDtcs("В конце сессии", finalDtcs)
        appendLine()
        appendLine("=== Запись ===")
        appendLine("Строк данных: ${logger.rows}; средняя длительность цикла опроса: ${logger.avgCycleMs} мс.")
        appendLine("Параметры опрашиваются по очереди, поэтому значения в одной строке сняты с разницей до длительности цикла.")
        appendLine("«Медленные» параметры (температуры, LTFT, уровень топлива, напряжение) опрашиваются раз в 5 циклов; в остальных строках их ячейки пустые.")
        appendLine("Пустая ячейка = нет данных (не опрашивался или ЭБУ не ответил), а не ноль.")
        if (logger.markers.isNotEmpty()) {
            appendLine("Метки водителя (столбец marker): " + logger.markers.joinToString("; ") { "${it.first} в ${it.second}" })
        }
        appendLine()
        appendLine("Поддерживаемые автомобилем PID режима 01:")
        appendLine(info.supportedPids.joinToString(" ") { "%02X".format(it) }.ifEmpty { "—" })
        appendLine()
        appendLine("=== Столбцы CSV ===")
        appendLine("time — локальное время начала цикла опроса")
        appendLine("t_s — секунды от начала записи")
        for (c in logger.columns) {
            val unit = if (c.unit.isNotEmpty()) ", ${c.unit}" else ""
            appendLine("${c.name} — ${c.description}$unit")
        }
        appendLine("marker — метки, поставленные кнопкой «Метка» (водитель отметил момент, когда что-то почувствовал)")
        appendLine()
        appendLine("=== Сводка по столбцам: min / среднее / max (число замеров) ===")
        for (c in logger.columns) {
            val s = logger.stats[c.name] ?: continue
            appendLine("${c.name}: ${Values.format(s.min)} / ${Values.format(s.mean)} / ${Values.format(s.max)} (${s.count})")
        }
        appendLine()
        appendLine("=== Подсказка для анализа нейросетью ===")
        appendLine(prompt(car))
    }

    private fun StringBuilder.appendDtcs(title: String, d: DtcSnapshot) {
        val mil = when (d.milOn) {
            true -> "горит"
            false -> "не горит"
            null -> "неизвестно"
        }
        appendLine("$title: лампа Check Engine (MIL) $mil; ЭБУ сообщает кодов: ${d.reportedCount ?: "?"}")
        appendLine("  Сохранённые (режим 03): ${codes(d.stored)}")
        appendLine("  Ожидающие (режим 07):   ${codes(d.pending)}")
        appendLine("  Постоянные (режим 0A):  ${codes(d.permanent)}")
    }

    private fun codes(list: List<String>?) = when {
        list == null -> "не поддерживается / нет ответа"
        list.isEmpty() -> "нет"
        else -> list.joinToString(", ")
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d ч %02d мин".format(s / 3600, s % 3600 / 60) else "%d мин %02d с".format(s / 60, s % 60)
    }

    companion object {
        fun prompt(car: String) = """
Ты — опытный автодиагност. Прикладываю два файла с одной поездки: CSV с логом датчиков OBD-II и этот файл
со сведениями (коды ошибок, описание столбцов, сводка). Автомобиль: $car.

Проанализируй данные и найди признаки неисправностей:
1. Коды ошибок (сохранённые и ожидающие): что означают для этого двигателя, вероятные причины.
2. Топливные коррекции STFT/LTFT: сумма стабильно за ±10% указывает на подсос воздуха, проблемы
   с ДМРВ, форсунками или давлением топлива. Сравни холостой ход и нагрузку.
3. Лямбда-зонды: частота и размах переключения передних датчиков (0.1–0.9 В), задний датчик
   (ровный сигнал ≈0.6–0.7 В у живого катализатора; если повторяет передний — катализатор слабый).
4. Прогрев: как быстро и до какой температуры выходит ОЖ (термостат — 80–95 °C), переход в CL.
5. ДМРВ: расход воздуха на холостом и при нагрузке относительно объёма двигателя; MAP, нагрузка.
6. Холостой ход: стабильность оборотов, угол опережения, положение дросселя.
7. Напряжение бортсети: заряд генератора (13.8–14.6 В на заведённом), просадки.
8. Моменты из столбца marker: что происходило с параметрами вокруг них (±10 с).

Для каждой найденной проблемы укажи: признак в данных (время/значения), вероятную причину,
степень уверенности и что проверить на машине. Отдельно отметь, каких данных не хватает.
""".trim()
    }
}
