package com.obdlogger.core

import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Card in «Доказательства из лога»: a title and figures, highlighted when out of norm. */
class ProofCard(val title: String, val figures: List<Evidence>, val note: String? = null)

/** «Чем это не объясняется»: an alternative and why the log speaks against it. */
class Alternative(val name: String, val why: String, val verdict: String)

/** One row of the plan for the mechanic. */
class PlanStep(val what: String, val detail: String?, val how: String, val expected: String)

/**
 * A version with everything the version card (К2) and the mechanic's plan (К3)
 * show: what it means, the proof from the logs, what speaks against alternatives,
 * the next step and how to tell it helped.
 */
class Hypothesis(
    val finding: Finding,
    val explanation: String,
    val proofs: List<ProofCard>,
    val alternatives: List<Alternative>,
    val nextTitle: String,
    val nextText: String,
    val checklist: List<String>,
    val success: String,
    val plan: List<PlanStep>,
    /** Trips this version is seen in (oldest first) with the key figure. */
    val seenIn: List<Pair<TripSummary, String>>,
    /** Key figures for the plan header. */
    val keyFigures: List<Evidence>,
)

/** Knowledge base: turns a [Finding] plus the trip history into a [Hypothesis]. */
object Hypotheses {
    private val DATE = DateTimeFormatter.ofPattern("dd.MM")

    fun of(f: Finding, trips: List<TripSummary>): Hypothesis {
        val sorted = trips.sortedBy { it.start }
        val last = sorted.lastOrNull()
        val m = last?.metrics.orEmpty()
        fun pct(x: Double?) = x?.let { TripAnalyzer.pct(it) } ?: "—"
        fun v(x: Double?, unit: String) = x?.let { "${TripAnalyzer.fmt(it)} $unit" } ?: "—"
        fun series(metric: Metric) = sorted.mapNotNull { t -> t.metrics[metric]?.let { t to it } }
        fun seen(metric: Metric, show: (Double) -> String) = series(metric).takeLast(5).map { (t, x) -> t to show(x) }
        val idleB1 = m[Metric.IDLE_TRIM_B1]
        val cruiseB1 = m[Metric.CRUISE_TRIM_B1]
        val rearIdle = m[Metric.IDLE_REAR_O2]
        val rearCruise = m[Metric.CRUISE_REAR_O2]
        val maf = m[Metric.IDLE_MAF]
        val trend = series(Metric.IDLE_TRIM_B1).takeLast(3).joinToString(" → ") { pct(it.second).removeSuffix(" %") }
        val common = listOf(
            Evidence("Коррекция ХХ, банк 1", pct(idleB1), deviating = idleB1?.let { abs(it) > 10 } == true),
            Evidence("Коррекция в движении", pct(cruiseB1), deviating = cruiseB1?.let { abs(it) > 10 } == true),
            Evidence("Лямбда после кат., ХХ / движение", "${v(rearIdle, "").trim()} / ${v(rearCruise, "В")}"),
            Evidence("Обороты ХХ", m[Metric.IDLE_RPM]?.toInt()?.toString() ?: "—"),
            Evidence("Напряжение, мотор работает", v(m[Metric.CHARGE_V], "В")),
            Evidence("Расход воздуха на ХХ", v(maf, "г/с")),
        )

        return when (f.kind) {
            "air_leak" -> Hypothesis(
                f,
                "На прогретом холостом ЭБУ добавляет топливо, а в движении — нет. Похоже, лишний воздух попадает во впуск мимо датчика расхода только при сильном разрежении.",
                listOfNotNull(
                    ProofCard("Отклонение только на холостом", listOfNotNull(
                        Evidence("ХХ стоя", pct(idleB1), deviating = true),
                        cruiseB1?.let { Evidence("Движение", pct(it), emphasis = true) },
                    )),
                    trend.takeIf { series(Metric.IDLE_TRIM_B1).size >= 2 }?.let { ProofCard("Как менялось от поездки к поездке", listOf(Evidence("Б1 на ХХ", "$trend %"))) },
                    rearIdle?.let { ProofCard("Лямбда после катализатора «видит» бедно", listOfNotNull(
                        Evidence("на ХХ", "${TripAnalyzer.fmt(it)} В", deviating = it < 0.45),
                        rearCruise?.let { c -> Evidence("в движении", "${TripAnalyzer.fmt(c)} В") },
                    ), "Датчик живой (в движении в норме), значит на ХХ в выхлопе правда лишний кислород.") },
                    maf?.let { ProofCard("ДМРВ на ХХ показывает мало воздуха", listOf(Evidence("расход воздуха", "${TripAnalyzer.fmt(it)} г/с")),
                        "Часть воздуха проходит мимо датчика — ЭБУ её не учитывает и докручивает топливо.") },
                ),
                listOfNotNull(
                    Alternative("Слабый бензонасос, фильтр", "Бедно было бы и под нагрузкой, а там ${pct(cruiseB1)}.", "менее вероятно").takeIf { cruiseB1 != null },
                    Alternative("Загрязнённый ДМРВ", "Ошибка датчика обычно видна на всех режимах, а не только на ХХ.", "менее вероятно"),
                    Alternative("Неисправный лямбда-зонд", "Передний зонд переключается, задний в движении в норме.", "маловероятно"),
                ),
                "Проверить впуск дымогенератором",
                "Мотор заглушен, дым во впуск после ДМРВ. Где выходит дым — там подсос:",
                listOf("Шланг вентиляции картера (PCV)", "Шланг вакуумного усилителя тормозов", "Прокладка патрубка EGR", "Трубки вихревых заслонок (SCV), если они есть"),
                "Коррекция на ХХ в пределах ±10 %, задняя лямбда на ХХ не падает к нулю.",
                listOf(
                    PlanStep("Шланг вентиляции картера (PCV)", "от клапана PCV к впускному коллектору", "Дымогенератор, мотор заглушен", "Дым не выходит, шланг без трещин у штуцеров"),
                    PlanStep("Шланг вакуумного усилителя тормозов", "и обратный клапан", "Дымогенератор", "Дым не выходит; педаль не проваливается на заглушенном"),
                    PlanStep("Прокладка патрубка EGR", null, "Дымогенератор", "Дыма нет у фланца"),
                    PlanStep("Трубки вихревых заслонок (SCV), если они есть", "и вакуумный привод", "Дымогенератор, ручной вакуумный насос", "Трубки целые, привод держит вакуум"),
                    PlanStep("Контроль после ремонта", "прогретый мотор, на стоянке", "Проверочный лог в Бортаче, 4 мин", "Коррекция на ХХ в пределах ±10 %; задняя лямбда на ХХ не падает к нулю"),
                ),
                seen(Metric.IDLE_TRIM_B1) { pct(it) },
                common,
            )
            "lean_all" -> Hypothesis(
                f,
                "ЭБУ добавляет топливо и на холостом, и под нагрузкой. Так бывает, когда топлива не хватает: падает давление или форсунки льют меньше.",
                listOfNotNull(ProofCard("Бедно на всех режимах", listOfNotNull(
                    Evidence("ХХ", pct(idleB1), deviating = true), cruiseB1?.let { Evidence("Движение", pct(it), deviating = abs(it) > 10) },
                ))),
                listOf(Alternative("Подсос воздуха", "Подсос сильнее всего на ХХ, а здесь отклонение и под нагрузкой.", "менее вероятно")),
                "Проверить подачу топлива",
                "Замерить давление топлива на ХХ и под нагрузкой, осмотреть фильтр:",
                listOf("Давление топлива в рампе", "Топливный фильтр / сетка насоса", "Производительность форсунок", "ДМРВ — показания на 2500 об/мин"),
                "Коррекция на всех режимах в пределах ±10 %.",
                listOf(
                    PlanStep("Давление топлива", "на ХХ и при перегазовке", "Манометр на рампу", "В норме для мотора, не падает под нагрузкой"),
                    PlanStep("Топливный фильтр, сетка насоса", null, "Осмотр / замена", "Чистые"),
                    PlanStep("Форсунки", null, "Стенд, проливка", "Равная производительность"),
                    PlanStep("Контроль после ремонта", null, "Проверочный лог в Бортаче", "Коррекция в пределах ±10 %"),
                ),
                seen(Metric.IDLE_TRIM_B1) { pct(it) }, common,
            )
            "rich_idle" -> Hypothesis(
                f,
                "На холостом ЭБУ убирает топливо — смеси больше, чем нужно. Частые причины: подтекающие форсунки, продувка адсорбера, неверная температура ОЖ.",
                listOf(ProofCard("ЭБУ убирает топливо на ХХ", listOfNotNull(Evidence("ХХ", pct(idleB1), deviating = true), cruiseB1?.let { Evidence("Движение", pct(it)) }))),
                listOf(Alternative("Лямбда-зонд", "Зонд переключается, значит видит смесь правильно.", "менее вероятно")),
                "Проверить топливную часть на ХХ",
                "По порядку:", listOf("Клапан продувки адсорбера (EVAP)", "Форсунки на подтекание", "Датчик температуры ОЖ", "Давление топлива"),
                "Коррекция на ХХ в пределах ±10 %.",
                listOf(
                    PlanStep("Клапан продувки адсорбера", null, "Пережать шланг, смотреть коррекцию", "Коррекция не меняется"),
                    PlanStep("Форсунки", "подтекание", "Стенд", "Не капают"),
                    PlanStep("Датчик температуры ОЖ", null, "Сравнить с термометром", "Совпадает"),
                    PlanStep("Контроль после ремонта", null, "Проверочный лог в Бортаче", "Коррекция в пределах ±10 %"),
                ),
                seen(Metric.IDLE_TRIM_B1) { pct(it) }, common,
            )
            "dips" -> Hypothesis(
                f,
                "На прогретом моторе с отпущенной педалью обороты проваливаются ниже 560. Чаще всего это нагар на дроссельной заслонке или в EGR.",
                listOf(ProofCard("Провалы на холостом", listOfNotNull(
                    Evidence("провалов за поездку", "${m[Metric.RPM_DIPS]?.toInt() ?: 0}", deviating = true),
                    m[Metric.IDLE_RPM]?.let { Evidence("обороты ХХ, медиана", "${it.toInt()}") },
                ))),
                listOf(Alternative("Пропуски зажигания", "Кодов P030x нет.", "менее вероятно")),
                "Чистка дросселя и EGR",
                "Затем обучение холостого хода:", listOf("Дроссельная заслонка", "Клапан и канал EGR", "Подсос воздуха", "Аккумулятор и клеммы"),
                "Провалов нет на трёх поездках подряд.",
                listOf(
                    PlanStep("Дроссельная заслонка", null, "Очиститель, затем обучение ХХ", "Обороты ХХ ровные"),
                    PlanStep("Клапан EGR и канал", "нагар особенно част на моторах с непосредственным впрыском", "Снятие, чистка", "Клапан закрывается полностью"),
                    PlanStep("Контроль после ремонта", null, "Поездка с остановками", "Провалов нет"),
                ),
                seen(Metric.RPM_DIPS) { "${it.toInt()} провалов" }, common,
            )
            "weak_charge" -> Hypothesis(
                f,
                "На работающем моторе напряжение ниже 13.5 В — аккумулятор недозаряжается.",
                listOf(ProofCard("Напряжение на работающем", listOfNotNull(
                    m[Metric.CHARGE_V]?.let { Evidence("медиана", "${TripAnalyzer.fmt(it)} В", deviating = true) },
                    m[Metric.CHARGE_V_MIN]?.let { Evidence("минимум", "${TripAnalyzer.fmt(it)} В") },
                ))),
                listOf(Alternative("Аккумулятор", "При исправном генераторе напряжение было бы 13.8–14.6 В даже со старым АКБ.", "менее вероятно")),
                "Проверить зарядку",
                "Мультиметр на клеммы АКБ при 2000 об/мин:", listOf("Ремень генератора", "Регулятор напряжения", "Клеммы и масса", "Генератор"),
                "Напряжение на работающем 13.8–14.6 В.",
                listOf(
                    PlanStep("Ремень генератора", null, "Осмотр, натяжение", "Не проскальзывает"),
                    PlanStep("Клеммы и масса", null, "Осмотр, падение напряжения", "Чистые, затянуты"),
                    PlanStep("Генератор и регулятор", null, "Мультиметр, нагрузочная вилка", "13.8–14.6 В под нагрузкой"),
                ),
                seen(Metric.CHARGE_V) { "${TripAnalyzer.fmt(it)} В" }, common,
            )
            "overheat" -> Hypothesis(
                f,
                "Температура охлаждающей жидкости поднималась выше 104 °C.",
                listOf(ProofCard("Температура ОЖ", listOfNotNull(m[Metric.COOLANT_MAX]?.let { Evidence("максимум", "${it.toInt()} °C", deviating = true) }))),
                emptyList(),
                "Проверить охлаждение", "Не откладывая:", listOf("Уровень ОЖ", "Вентилятор радиатора", "Термостат", "Помпа"),
                "Температура не выше 100 °C в пробках.",
                listOf(
                    PlanStep("Уровень ОЖ и утечки", null, "Осмотр на холодном", "Уровень в норме"),
                    PlanStep("Вентилятор радиатора", null, "Включается ли при 95–98 °C", "Включается"),
                    PlanStep("Термостат", null, "Температура открытия", "Открывается при ~82 °C"),
                ),
                seen(Metric.COOLANT_MAX) { "${it.toInt()} °C" }, common,
            )
            "cold_engine" -> Hypothesis(
                f,
                "За долгую поездку мотор не прогрелся до рабочей температуры. Обычно это открытый термостат.",
                listOf(ProofCard("Температура ОЖ", f.why)),
                listOf(Alternative("Датчик температуры", "Возможна, если печка греет нормально.", "возможно")),
                "Проверить термостат", "", listOf("Термостат", "Датчик температуры ОЖ"),
                "Мотор прогревается до 80–95 °C за 10–15 минут езды.",
                listOf(
                    PlanStep("Термостат", null, "Замена / проверка в воде", "Открывается при ~82 °C"),
                    PlanStep("Датчик температуры ОЖ", null, "Сравнить с термометром", "Совпадает"),
                ),
                emptyList(), common,
            )
            else -> Hypothesis(
                f, f.evidence, if (f.why.isNotEmpty()) listOf(ProofCard(f.headline, f.why)) else emptyList(), emptyList(),
                "Что проверить", f.advice, emptyList(), "Отклонение уходит на следующих поездках.",
                listOf(PlanStep(f.headline, f.evidence, f.advice, "Отклонение уходит")),
                emptyList(), common,
            )
        }
    }

    fun date(t: TripSummary) = t.start?.format(DATE) ?: t.name
}
