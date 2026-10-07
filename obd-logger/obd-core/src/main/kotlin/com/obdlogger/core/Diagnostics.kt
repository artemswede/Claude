package com.obdlogger.core

import java.util.Locale

/** Mode 02 frame 0: what the sensors read when the ECU set [cause]. */
class FreezeFrame(val cause: String?, val values: List<Value>) {
    class Value(val code: String, val unit: String, val description: String, val value: Double)

    fun of(code: String): Double? = values.firstOrNull { it.code == code }?.value

    /** Total trim per bank (short + long), as the app shows it elsewhere. */
    fun trim(bank: Int): Double? {
        val st = of("stft_b${bank}_pct") ?: return null
        val lt = of("ltft_b${bank}_pct") ?: return null
        return st + lt
    }

    val mode: LiveMode get() = LiveMode.of(of("rpm"), of("speed_kmh"), of("coolant_c"))
}

/** Russian names of trouble codes: the common generic ones, otherwise the system by number. */
object DtcCatalog {
    private val known = mapOf(
        "P0010" to "Привод фаз ГРМ (VVT), банк 1: цепь клапана",
        "P0011" to "Фазы ГРМ (VVT), банк 1: слишком раннее открытие",
        "P0012" to "Фазы ГРМ (VVT), банк 1: слишком позднее открытие",
        "P0100" to "ДМРВ: неисправность цепи",
        "P0101" to "ДМРВ: сигнал вне ожидаемого диапазона",
        "P0102" to "ДМРВ: низкий сигнал",
        "P0103" to "ДМРВ: высокий сигнал",
        "P0110" to "Датчик температуры воздуха на впуске: цепь",
        "P0115" to "Датчик температуры ОЖ: цепь",
        "P0116" to "Датчик температуры ОЖ: сигнал вне диапазона",
        "P0120" to "Датчик положения дросселя: цепь",
        "P0121" to "Датчик положения дросселя: сигнал вне диапазона",
        "P0125" to "Мотор слишком долго не прогревается для работы по лямбде",
        "P0128" to "Мотор не набирает рабочую температуру (термостат)",
        "P0130" to "Лямбда-зонд до катализатора (Б1): цепь",
        "P0131" to "Лямбда-зонд до катализатора (Б1): низкое напряжение",
        "P0132" to "Лямбда-зонд до катализатора (Б1): высокое напряжение",
        "P0133" to "Лямбда-зонд до катализатора (Б1): медленный отклик",
        "P0134" to "Лямбда-зонд до катализатора (Б1): нет активности",
        "P0135" to "Подогрев лямбда-зонда до катализатора (Б1): цепь",
        "P0136" to "Лямбда-зонд после катализатора (Б1): цепь",
        "P0137" to "Лямбда-зонд после катализатора (Б1): низкое напряжение",
        "P0138" to "Лямбда-зонд после катализатора (Б1): высокое напряжение",
        "P0139" to "Лямбда-зонд после катализатора (Б1): медленный отклик",
        "P0141" to "Подогрев лямбда-зонда после катализатора (Б1): цепь",
        "P0150" to "Лямбда-зонд до катализатора (Б2): цепь",
        "P0151" to "Лямбда-зонд до катализатора (Б2): низкое напряжение",
        "P0152" to "Лямбда-зонд до катализатора (Б2): высокое напряжение",
        "P0153" to "Лямбда-зонд до катализатора (Б2): медленный отклик",
        "P0154" to "Лямбда-зонд до катализатора (Б2): нет активности",
        "P0155" to "Подогрев лямбда-зонда до катализатора (Б2): цепь",
        "P0156" to "Лямбда-зонд после катализатора (Б2): цепь",
        "P0157" to "Лямбда-зонд после катализатора (Б2): низкое напряжение",
        "P0158" to "Лямбда-зонд после катализатора (Б2): высокое напряжение",
        "P0159" to "Лямбда-зонд после катализатора (Б2): медленный отклик",
        "P0161" to "Подогрев лямбда-зонда после катализатора (Б2): цепь",
        "P0171" to "Слишком бедная смесь, банк 1",
        "P0172" to "Слишком богатая смесь, банк 1",
        "P0174" to "Слишком бедная смесь, банк 2",
        "P0175" to "Слишком богатая смесь, банк 2",
        "P0190" to "Датчик давления топлива в рампе: цепь",
        "P0191" to "Датчик давления топлива в рампе: сигнал вне диапазона",
        "P0300" to "Пропуски зажигания в разных цилиндрах",
        "P0301" to "Пропуски зажигания, цилиндр 1",
        "P0302" to "Пропуски зажигания, цилиндр 2",
        "P0303" to "Пропуски зажигания, цилиндр 3",
        "P0304" to "Пропуски зажигания, цилиндр 4",
        "P0305" to "Пропуски зажигания, цилиндр 5",
        "P0306" to "Пропуски зажигания, цилиндр 6",
        "P0325" to "Датчик детонации: цепь",
        "P0335" to "Датчик положения коленвала: цепь",
        "P0340" to "Датчик положения распредвала: цепь",
        "P0400" to "Система рециркуляции газов (EGR)",
        "P0420" to "Низкая эффективность катализатора, банк 1",
        "P0430" to "Низкая эффективность катализатора, банк 2",
        "P0440" to "Система улавливания паров топлива (EVAP)",
        "P0441" to "EVAP: неверный поток продувки",
        "P0442" to "EVAP: малая утечка",
        "P0446" to "EVAP: клапан вентиляции",
        "P0455" to "EVAP: большая утечка (часто — крышка бака)",
        "P0456" to "EVAP: очень малая утечка",
        "P0500" to "Датчик скорости автомобиля",
        "P0505" to "Регулятор холостого хода",
        "P0560" to "Напряжение бортсети",
        "P0562" to "Низкое напряжение бортсети",
        "P0563" to "Высокое напряжение бортсети",
    )

    private val systems = listOf(
        "P00" to "топливо и воздух, фазы ГРМ",
        "P01" to "топливо и воздух (датчики смеси, ДМРВ, лямбда)",
        "P02" to "топливо и воздух (форсунки, давление топлива)",
        "P03" to "зажигание и пропуски",
        "P04" to "экология (EGR, катализатор, EVAP)",
        "P05" to "скорость, холостой ход, бортсеть",
        "P06" to "блок управления и его выходы",
        "P07" to "коробка передач", "P08" to "коробка передач", "P09" to "коробка передач",
        "P1" to "код производителя (двигатель)", "P3" to "код производителя (двигатель)",
        "C" to "шасси (ABS, подвеска)", "B" to "кузов (подушки, комфорт)", "U" to "связь между блоками",
    )

    fun describe(code: String): String = known[code]
        ?: systems.firstOrNull { code.startsWith(it.first) }?.let { "Код системы: ${it.second}" }
        ?: "Описание неизвестно"
}

/**
 * Why a code may have been set, from what the app can see: the freeze frame (the
 * moment the ECU set it) and the recording. Facts with numbers, not verdicts.
 */
object DtcExplain {
    private fun f(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    private fun pct(v: Double) = TripAnalyzer.pct(v)

    /** «2100 об/мин, 60 км/ч, ОЖ 88 °C, нагрузка 45 %» — the conditions in the freeze frame. */
    fun conditions(ff: FreezeFrame): String = listOfNotNull(
        ff.of("rpm")?.let { "${it.toInt()} об/мин" },
        ff.of("speed_kmh")?.let { "${it.toInt()} км/ч" },
        ff.of("coolant_c")?.let { "ОЖ ${it.toInt()} °C" },
        ff.of("engine_load_pct")?.let { "нагрузка ${it.toInt()} %" },
    ).joinToString(", ").ifEmpty { "условия не сохранены" } + " · ${ff.mode.ru}"

    /** Freeze frame values outside their norm for the mode the code was set in. */
    fun outOfNorm(ff: FreezeFrame): List<String> {
        val mode = ff.mode
        val list = ArrayList<String>()
        for (b in 1..2) ff.trim(b)?.let { t ->
            Norms.of("trim_b$b", mode)?.takeIf { it.state(t) != NormState.IN }?.let { n -> list += "коррекция Б$b ${pct(t)} — ${n.word(n.state(t))} (${n.text})" }
        }
        for (v in ff.values) {
            if (v.code.startsWith("stft") || v.code.startsWith("ltft")) continue
            val n = Norms.of(v.code, mode) ?: continue
            val st = n.state(v.value)
            if (st == NormState.IN || st == NormState.NONE) continue
            list += "${SensorNames.label(v.code).lowercase()} ${f(v.value)} ${v.unit} — ${n.word(st)} (${n.text})"
        }
        return list
    }

    private fun bankOf(code: String): Int = when (code) {
        in setOf("P0150", "P0151", "P0152", "P0153", "P0154", "P0155", "P0156", "P0157", "P0158", "P0159", "P0161", "P0174", "P0175", "P0430") -> 2
        else -> 1
    }

    /** Facts behind [code]; [all] are every code the ECU reports now (codes that share a cause). */
    fun explain(code: String, ff: FreezeFrame?, store: SeriesStore?, all: List<String>): List<String> {
        val out = ArrayList<String>()
        if (ff != null && (ff.cause == null || ff.cause == code)) {
            out += "Записан при: ${conditions(ff)}."
            outOfNorm(ff).takeIf { it.isNotEmpty() }?.let { out += "В этот момент за нормой: ${it.joinToString("; ")}." }
        }
        val b = bankOf(code)
        val n = code.drop(1).toIntOrNull() ?: -1
        fun vals(c: String) = store?.values(c) ?: DoubleArray(0)
        when {
            // Rear O2 (after the catalyst): circuit / low / high / slow / heater.
            n in 136..141 || n in 156..161 -> {
                // Warm engine only, and percentiles: a cold start or one spike must not decide.
                val warm = store?.valuesWhere("o2_b${b}s2_v", "coolant_c") { it >= 70 }?.takeIf { it.size >= 10 } ?: vals("o2_b${b}s2_v")
                if (warm.size >= 10) {
                    val sorted = warm.sorted()
                    val lo = sorted[(sorted.size * 0.05).toInt()]
                    val hi = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
                    out += when {
                        hi < 0.1 -> "В записи (прогретый мотор) датчик после катализатора Б$b почти всё время ≈${f(sorted[sorted.size / 2])} В: сигнала нет. Живой датчик держит 0.45–0.85 В."
                        hi - lo < 0.05 -> "В записи датчик после катализатора Б$b стоит на ${f(sorted[sorted.size / 2])} В и не меняется — сигнал «замёрз»."
                        else -> "В записи датчик после катализатора Б$b обычно ${f(lo)}–${f(hi)} В: сигнал есть, возможно, ошибка была временной (разъём, провод)."
                    }
                }
                val pair = if (b == 1) "P0156" else "P0136"
                if (pair in all) out += "Оба задних датчика сразу — редкое совпадение для самих датчиков. Сначала проверьте общее: разъёмы и проводку за катализаторами, предохранитель подогрева (EFI/O2 HTR)."
                out += "На смесь задний датчик почти не влияет: ЭБУ по нему только проверяет катализатор."
            }
            // Front O2 (before the catalyst).
            n in 130..135 || n in 150..155 -> {
                val v = vals("o2_b${b}s1_v")
                if (v.size >= 10) out += if (Norms.frontO2Switching(v.takeLast(60).toDoubleArray()) == true)
                    "В записи датчик до катализатора Б$b переключается ${f(v.min())}–${f(v.max())} В — сейчас работает."
                else "В записи датчик до катализатора Б$b не переключается (${f(v.min())}–${f(v.max())} В) — подозрение на датчик, его подогрев или проводку."
            }
            // Lean / rich mixture.
            n == 171 || n == 174 || n == 172 || n == 175 -> {
                val idle = store?.valuesWhere("trim_b$b", "rpm") { it in 500.0..1000.0 } ?: DoubleArray(0)
                val load = store?.valuesWhere("trim_b$b", "rpm") { it >= 2000 } ?: DoubleArray(0)
                if (idle.size >= 10 && load.size >= 10) {
                    val i = idle.average()
                    val l = load.average()
                    out += "В записи коррекция Б$b: на холостом ${pct(i)}, от 2000 об/мин ${pct(l)}."
                    if (n == 171 || n == 174) out += when {
                        i > l + 5 -> "Больше на холостом и уменьшается с оборотами — типично для подсоса воздуха (вакуумные шланги, прокладка впуска, клапан PCV)."
                        l > i + 5 -> "Больше под нагрузкой — типично для нехватки топлива (фильтр, насос, регулятор давления) или загрязнённого ДМРВ."
                        else -> "Примерно одинаково везде — проверьте ДМРВ и давление топлива."
                    }
                }
                if ((n == 171 && "P0174" in all) || (n == 174 && "P0171" in all)) out += "Бедно в обоих банках — причина общая для всего мотора (подсос до дросселя, ДМРВ, топливо)."
            }
            n in 300..306 -> {
                out += "Пропуски чаще всего — свеча, катушка или форсунка этого цилиндра; поменяйте катушки местами и посмотрите, переедет ли код."
                ff?.of("engine_load_pct")?.let { if (it > 60) out += "Код записан под нагрузкой — сначала свечи и катушки." }
            }
            n == 420 || n == 430 -> {
                val front = vals("o2_b${b}s1_v")
                val rear = vals("o2_b${b}s2_v")
                if (front.size >= 10 && rear.size >= 10) out += "В записи задний датчик Б$b меняется ${f(rear.min())}–${f(rear.max())} В при переднем ${f(front.min())}–${f(front.max())} В. Если задний «копирует» передний — катализатор не держит кислород."
            }
            n == 128 || n == 125 || n in 115..116 -> {
                val c = vals("coolant_c")
                if (c.isNotEmpty()) out += "Максимальная температура ОЖ в записи ${c.max().toInt()} °C (норма 80–100). Ниже 80 после 10–15 минут езды — термостат."
            }
            n in 100..103 -> {
                val idle = store?.valuesWhere("maf_gs", "rpm") { it in 500.0..1000.0 } ?: DoubleArray(0)
                if (idle.size >= 10) out += "ДМРВ на холостом в записи ${f(idle.average())} г/с (для мотора 2.0 обычно 2.5–4 г/с)."
            }
            n in 560..563 -> {
                val v = vals("battery_v")
                if (v.isNotEmpty()) out += "Напряжение в записи ${f(v.min())}–${f(v.max())} В (на работающем моторе норма 13.5–14.8)."
            }
        }
        return out
    }
}

/** Everything about the codes at one moment, as text: kept before a reset and shared with a mechanic. */
object DtcReport {
    fun render(title: String, car: String, snap: DtcSnapshot, ff: FreezeFrame?, store: SeriesStore?): String = buildString {
        appendLine("=== $title ===")
        if (car.isNotBlank()) appendLine("Машина: $car")
        appendLine("Лампа Check Engine: ${when (snap.milOn) { true -> "горит"; false -> "не горит"; null -> "неизвестно" }}")
        val all = (snap.stored.orEmpty() + snap.pending.orEmpty() + snap.permanent.orEmpty()).distinct()
        fun section(name: String, codes: List<String>?) {
            appendLine()
            appendLine("$name: " + when {
                codes == null -> "блок не ответил"
                codes.isEmpty() -> "нет"
                else -> codes.joinToString(", ")
            })
            codes.orEmpty().forEach { c ->
                appendLine("  $c — ${DtcCatalog.describe(c)}")
                DtcExplain.explain(c, ff, store, all).forEach { appendLine("    • $it") }
            }
        }
        section("Сохранённые коды", snap.stored)
        section("Неподтверждённые (текущий цикл)", snap.pending)
        if (snap.permanent != null) section("Постоянные (не сбрасываются сканером)", snap.permanent)
        if (ff != null) {
            appendLine()
            appendLine("Стоп-кадр${ff.cause?.let { " (код $it)" } ?: ""}: значения датчиков в момент ошибки")
            ff.values.forEach { v ->
                appendLine("  ${SensorNames.label(v.code)}: ${Values.format(v.value)} ${v.unit}".trimEnd())
            }
        }
    }
}
