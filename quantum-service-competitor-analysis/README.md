# Тестовое задание BA — анализ конкурентов для сервиса квалификации квантовых задач

Деливерабл: **`Анализ-конкурентов-Q-Scope.pptx`** (5 слайдов, 16:9, белый фон / синие акценты, рус.).

## Трактовка продукта
«Q-Scope» (рабочее название) — сервис **предварительной квалификации** бизнес-задач на
применимость квантовых вычислений. Помогает описать задачу на языке бизнеса, даёт предварительный
сигнал перспективности и **передаёт структурированную задачу экспертам**. Не QC-провайдер, эффект
не гарантируется.

## Структура презентации
1. **Контекст и продукт** — что запускаем, позиционирование, метод анализа (3 слоя).
2. **Выбор конкурентов и аналогов** — 4 решения: почему выбрали, тип, близость к нам.
3. **Сравнительная матрица** — 8 критериев × 4 решения.
4. **Конкурентные преимущества** — 5 шт. по логике «в чём → наблюдение → ценность».
5. **Рекомендации для MVP** — 7 шт.

## Выбранные конкуренты / аналоги
- **D-Wave Launch + Professional Services** — прямой конкурент (use-case identification & assessment).
- **IBM Quantum** (Network / working groups / Quantum Business Foundations) — альтернативный способ.
- **Multiverse Computing / QC Ware** — аналог (квантовый консалтинг/софт, discovery → PoC).
- **Microsoft AI Readiness + AWS Well-Architected** — неквантовый ориентир по формату анкеты/скоринга.

## Как пересобрать
```bash
pip install python-pptx
python3 build_deck.py
```

## Источники
- D-Wave Professional Services / Launch: https://www.dwavequantum.com/solutions-and-products/professional-services/
- IBM Quantum: https://www.ibm.com/quantum · working groups: https://www.ibm.com/quantum/blog/quantum-working-groups
- Microsoft AI Readiness Assessment: https://learn.microsoft.com/en-us/assessments/
- AWS Well-Architected Tool: https://aws.amazon.com/blogs/mt/scale-operational-readiness-reviews-with-aws-well-architected-tool/
