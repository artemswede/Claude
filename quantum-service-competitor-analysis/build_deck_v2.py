# -*- coding: utf-8 -*-
"""
Q-Scope — улучшенная презентация по шаблону «Анализ рынка и концепция продукта».
Стиль: белый фон, минимализм, яркие синие акценты, векторные иконки.
Слайды 1–5 — по шаблону; 6–12 — приложение (кейсы, процесс, движок, бриф, анкета, матрица, библиотека).
"""
from pptx import Presentation
from pptx.util import Inches as In, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from collections import defaultdict

# ---------- палитра ----------
ACCENT = RGBColor(0x25, 0x63, 0xEB)   # яркий синий
ACC2   = RGBColor(0x3B, 0x82, 0xF6)
DARK   = RGBColor(0x12, 0x2A, 0x5E)   # глубокий навигатор для заголовков
TEXT   = RGBColor(0x29, 0x2E, 0x38)
GRAY   = RGBColor(0x6B, 0x72, 0x80)
LIGHT  = RGBColor(0xEA, 0xF1, 0xFE)   # светло-голубой чип
LIGHT2 = RGBColor(0xF5, 0xF8, 0xFE)
LINE   = RGBColor(0xDD, 0xE5, 0xF2)
WHITE  = RGBColor(0xFF, 0xFF, 0xFF)
GREEN  = RGBColor(0x1E, 0x9E, 0x66)
GRAYBX = RGBColor(0xEE, 0xF0, 0xF4)
PURPLE = RGBColor(0x7A, 0x4F, 0xC0)
ORANGE = RGBColor(0xE0, 0x8A, 0x1E)
FONT = "Calibri"
TOTAL = 12

R = MSO_SHAPE.ROUNDED_RECTANGLE
prs = Presentation()
prs.slide_width = In(13.333); prs.slide_height = In(7.5)
BLANK = prs.slide_layouts[6]


def slide():
    s = prs.slides.add_slide(BLANK)
    s.background.fill.solid(); s.background.fill.fore_color.rgb = WHITE
    return s


def tbx(s, l, t, w, h, anchor=MSO_ANCHOR.TOP):
    b = s.shapes.add_textbox(In(l), In(t), In(w), In(h)); tf = b.text_frame
    tf.word_wrap = True; tf.vertical_anchor = anchor
    tf.margin_left = 0; tf.margin_right = 0; tf.margin_top = 0; tf.margin_bottom = 0
    return tf


def rn(p, t, sz, b=False, c=TEXT, i=False):
    r = p.add_run(); r.text = t; f = r.font
    f.name = FONT; f.size = Pt(sz); f.bold = b; f.italic = i; f.color.rgb = c
    return r


def box(s, l, t, w, h, fill=WHITE, line=LINE, lw=1.0, rounded=True):
    sh = s.shapes.add_shape(R if rounded else MSO_SHAPE.RECTANGLE, In(l), In(t), In(w), In(h))
    sh.fill.solid(); sh.fill.fore_color.rgb = fill
    if line is None:
        sh.line.fill.background()
    else:
        sh.line.color.rgb = line; sh.line.width = Pt(lw)
    sh.shadow.inherit = False
    sh.text_frame.word_wrap = True
    return sh


# ---------- иконки ----------
def _shape(s, kind, l, t, w, h, fill=ACCENT, line=None, lw=1.5, rot=0):
    sh = s.shapes.add_shape(kind, In(l), In(t), In(w), In(h))
    if fill is None:
        sh.fill.background()
    else:
        sh.fill.solid(); sh.fill.fore_color.rgb = fill
    if line is None:
        sh.line.fill.background()
    else:
        sh.line.color.rgb = line; sh.line.width = Pt(lw)
    sh.shadow.inherit = False
    if rot:
        sh.rotation = rot
    return sh


def draw_icon(s, cx, cy, sz, kind, color=ACCENT):
    h = sz / 2.0
    if kind == 'atom':
        for rot in (0, 60, -60):
            _shape(s, MSO_SHAPE.OVAL, cx - h, cy - sz * 0.21, sz, sz * 0.42,
                   fill=None, line=color, lw=1.7, rot=rot)
        _shape(s, MSO_SHAPE.OVAL, cx - sz * 0.11, cy - sz * 0.11, sz * 0.22, sz * 0.22, fill=color)
    elif kind == 'search':
        d = sz * 0.66
        _shape(s, MSO_SHAPE.OVAL, cx - sz * 0.40, cy - sz * 0.40, d, d, fill=None, line=color, lw=2.4)
        _shape(s, R, cx + sz * 0.10, cy + sz * 0.10, sz * 0.34, sz * 0.13, fill=color, rot=45)
    elif kind == 'grid':
        g = sz * 0.40; gap = sz * 0.10
        for ix in (0, 1):
            for iy in (0, 1):
                _shape(s, R, cx - g - gap / 2 + ix * (g + gap), cy - g - gap / 2 + iy * (g + gap),
                       g, g, fill=color)
    elif kind == 'funnel':
        _shape(s, MSO_SHAPE.TRAPEZOID, cx - h, cy - sz * 0.30, sz, sz * 0.5, fill=color, rot=180)
        _shape(s, MSO_SHAPE.RECTANGLE, cx - sz * 0.07, cy + sz * 0.18, sz * 0.14, sz * 0.22, fill=color)
    elif kind == 'target':
        _shape(s, MSO_SHAPE.DONUT, cx - h, cy - h, sz, sz, fill=color)
        _shape(s, MSO_SHAPE.OVAL, cx - sz * 0.13, cy - sz * 0.13, sz * 0.26, sz * 0.26, fill=color)
    else:  # одиночная встроенная фигура
        _shape(s, kind, cx - h, cy - h, sz, sz, fill=color)


def chip(s, l, t, sz, kind, fill=LIGHT, color=ACCENT, ratio=0.54):
    box(s, l, t, sz, sz, fill=fill, line=None)
    draw_icon(s, l + sz / 2, t + sz / 2, sz * ratio, kind, color)


def header(s, kicker, title, idx, icon='atom'):
    chip(s, 0.6, 0.52, 0.95, icon)
    tf = tbx(s, 1.72, 0.52, 10.7, 1.0)
    rn(tf.paragraphs[0], kicker.upper(), 12, True, ACCENT)
    p = tf.add_paragraph(); p.space_before = Pt(2)
    rn(p, title, 24, True, DARK)
    box(s, 0.6, 1.62, 12.13, 0.02, fill=LINE, line=None, rounded=False)
    box(s, 0.6, 1.61, 1.7, 0.045, fill=ACCENT, line=None, rounded=False)
    nf = tbx(s, 12.3, 6.96, 0.7, 0.35); nf.paragraphs[0].alignment = PP_ALIGN.RIGHT
    rn(nf.paragraphs[0], f"{idx} / {TOTAL}", 10, c=GRAY)


def style_table(t, hs=10.5, bs=9.5, first_bold=True):
    t._tbl.tblPr.set('firstRow', '0'); t._tbl.tblPr.set('bandRow', '0')
    for ri, row in enumerate(t.rows):
        for ci, cell in enumerate(row.cells):
            cell.margin_left = In(0.09); cell.margin_right = In(0.08)
            cell.margin_top = In(0.04); cell.margin_bottom = In(0.04)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            cell.fill.solid()
            cell.fill.fore_color.rgb = DARK if ri == 0 else (LIGHT2 if ri % 2 == 0 else WHITE)
            for p in cell.text_frame.paragraphs:
                for r in p.runs:
                    r.font.name = FONT; r.font.size = Pt(hs if ri == 0 else bs)
                    if ri == 0:
                        r.font.bold = True; r.font.color.rgb = WHITE
                    else:
                        r.font.color.rgb = TEXT
                        if ci == 0 and first_bold:
                            r.font.bold = True; r.font.color.rgb = DARK


def fill_table(t, data):
    for ri, row in enumerate(data):
        for ci, v in enumerate(row):
            t.cell(ri, ci).text = v


# ================================================== SLIDE 1 — титульный
s = slide()
# фоновый «квант» справа
draw_icon(s, 10.6, 4.4, 3.7, 'atom', RGBColor(0xCF, 0xDF, 0xFA))
chip(s, 0.9, 0.85, 1.1, 'atom')
rn(tbx(s, 2.2, 1.06, 9, 0.5).paragraphs[0],
   "ТЕСТОВОЕ ЗАДАНИЕ · БИЗНЕС-АНАЛИТИК", 13, True, ACCENT)
tf = tbx(s, 0.9, 2.35, 8.7, 2.4)
rn(tf.paragraphs[0], "Концепция и оценка конкурентного", 30, True, DARK)
for ln in ["окружения сервиса для квалификации", "квантовых задач"]:
    rn(tf.add_paragraph(), ln, 30, True, DARK)
box(s, 0.93, 4.5, 3.0, 0.06, fill=ACCENT, line=None, rounded=False)
tf = tbx(s, 0.9, 4.8, 8.5, 1.6)
rn(tf.paragraphs[0], "Тестовое задание на позицию бизнес-аналитика в продуктовую команду.",
   15, True, TEXT)
p = tf.add_paragraph(); p.space_before = Pt(6)
rn(p, "Кому и зачем: продуктовой команде — концепция продукта и анализ конкурентного окружения,",
   12, c=GRAY)
p = tf.add_paragraph()
rn(p, "чтобы определить отличия сервиса и состав первой версии (MVP).", 12, c=GRAY)
tf = tbx(s, 0.9, 6.75, 9, 0.4)
rn(tf.paragraphs[0], "Рабочее название продукта: Q-Scope   ·   июнь 2026", 11, True, ACCENT)

# ================================================== SLIDE 2 — выбор конкурентов
s = slide()
header(s, "Шаг 1 · выбор конкурентов и аналогов",
       "Сервисы-референсы: кого взяли и почему", 2, 'search')
cards = [
    (MSO_SHAPE.GEAR_6, "D-Wave (Launch + Professional Services)",
     "Квантовый вендор + консалтинг (проф. услуги)",
     "Прямой конкурент (ближайший)",
     "Уже оказывает услугу use-case identification & assessment — эталон самой квалификации задачи."),
    (MSO_SHAPE.CLOUD, "IBM Quantum (Network, QBF)",
     "Облачная платформа + экосистема и обучение",
     "Альтернативный способ",
     "Формирует спрос через курс Quantum Business Foundations и working groups — «обучение + сообщество»."),
    (MSO_SHAPE.CUBE, "Multiverse / QC Ware",
     "Вендор квантового ПО + консалтинг",
     "Косвенный аналог",
     "Чёткая модель discovery → PoC и quantum-inspired «ценность уже сегодня»."),
    (MSO_SHAPE.FOLDED_CORNER, "MS AI Readiness / AWS Well-Architected",
     "Бесплатный self-service инструмент-ассессмент (другая индустрия)",
     "Альтернативный способ + ориентир по формату",
     "Идеальное анкетирование: pillars → скоринг → отчёт. UX-эталон формата для нас."),
]
cw = (12.13 - 3 * 0.3) / 4
for i, (ic, name, typ, status, why) in enumerate(cards):
    l = 0.6 + i * (cw + 0.3)
    c = box(s, l, 1.95, cw, 4.75, fill=WHITE, line=LINE)
    box(s, l, 1.95, cw, 0.12, fill=ACCENT, line=None)  # верхняя полоска
    chip(s, l + 0.18, 2.25, 0.8, ic)
    tf = tbx(s, l + 0.18, 3.15, cw - 0.36, 3.4)
    rn(tf.paragraphs[0], name, 11.5, True, DARK)
    for lbl, val in [("Тип решения", typ), ("Статус", status), ("Причина выбора", why)]:
        p = tf.add_paragraph(); p.space_before = Pt(7)
        rn(p, lbl, 9, True, ACCENT)
        p2 = tf.add_paragraph(); p2.space_before = Pt(1)
        rn(p2, val, 9.3, c=TEXT)

# ================================================== SLIDE 3 — матрица сравнения
s = slide()
header(s, "Шаг 2 · сравнительный анализ",
       "Сравнительная матрица по критериям", 3, 'grid')
rows = [
    ["Критерий", "D-Wave", "IBM Quantum", "Multiverse / QC Ware", "MS / AWS (ассессмент)"],
    ["Целевая аудитория", "Enterprise: операции + бизнес-спонсоры",
     "Разработчики, R&D, инноваторы", "Крупный enterprise (фин/энерго/пром)",
     "Бизнес + IT, широкий self-service"],
    ["Ценностное предложение", "От задачи до продакшена на своём кванте",
     "Облачный доступ + обучение и экосистема", "Готовое квантовое/q-inspired решение под задачу",
     "Быстрая самооценка готовности без консультанта"],
    ["Клиентские задачи", "Оптимизация (расписания, логистика)",
     "Симуляция химии, оптимизация, ML", "Портфель, риск, оптимизация, химия",
     "Оценка зрелости / архитектуры по pillars"],
    ["Ключевые отрасли", "Производство, логистика, финансы, госсектор",
     "Life sciences, материалы, финансы", "Финансы, энергетика, промышленность",
     "Кросс-отраслевые (агностик)"],
    ["Ориентир для нас", "Сама услуга оценки use-case + отраслевые примеры",
     "Обучающий контент и типовые вопросы", "Разбивка задач на архетипы; «ценность сегодня»",
     "Формат анкеты, скоринг, приоритизированный отчёт"],
    ["Порог входа / модель", "Высокий; консалтинг + доступ к железу",
     "Средний-высокий; членство + cloud", "Высокий; проектный консалтинг / лицензии",
     "Низкий; бесплатно, self-service (лид-ген)"],
]
t = s.shapes.add_table(7, 5, In(0.6), In(1.82), In(12.13), In(5.05)).table
t.columns[0].width = In(2.1)
for ci in range(1, 5):
    t.columns[ci].width = In(2.5075)
fill_table(t, rows)
style_table(t, hs=10, bs=8.7)

# ================================================== SLIDE 4 — преимущества
s = slide()
header(s, "Шаг 3 · конкурентные преимущества",
       "Чем Q-Scope выделяется на рынке", 4, MSO_SHAPE.STAR_5_POINT)
adv = [
    (MSO_SHAPE.NO_SYMBOL, "Независимость от вендоров",
     "Конкуренты привязывают оценку к своему железу/платформе.",
     "Непредвзятая рекомендация и охват любых задач и поставщиков → доверие."),
    (MSO_SHAPE.LIGHTNING_BOLT, "Сниженный порог входа (self-service)",
     "Квантовые офферы — sales-gated консалтинг; спрос на self-service анкеты доказан (MS/AWS).",
     "Быстрый сигнал без обязательств → масштабируемая воронка лидов."),
    (MSO_SHAPE.ROUNDED_RECTANGULAR_CALLOUT, "Язык бизнеса, без квантовой физики",
     "Конкуренты требуют от клиента тех-грамотности (IBM/D-Wave).",
     "Доступно бизнес-заказчику; структурирует задачу в бизнес-терминах."),
    (MSO_SHAPE.STAR_5_POINT, "Честное управление ожиданиями",
     "Рынок перегрет хайпом, гарантий эффекта нет.",
     "Ранний отсев нецелевых кейсов → качественные лиды и стандартизированные данные."),
    (MSO_SHAPE.RIGHT_ARROW, "Мост к экспертам + структурированный бриф",
     "Assessment-инструменты заканчиваются отчётом и не ведут к next step.",
     "Готовый бриф экономит время экспертов; чёткий next step и точка монетизации."),
]
pos = [(0.6, 1.95), (4.72, 1.95), (8.83, 1.95), (2.66, 4.45), (6.77, 4.45)]
for (ic, title, obs, val), (l, tp) in zip(adv, pos):
    box(s, l, tp, 3.9, 2.35, fill=WHITE, line=LINE)
    box(s, l, tp, 3.9, 0.1, fill=ACCENT, line=None)
    chip(s, l + 0.16, tp + 0.22, 0.62, ic)
    tf = tbx(s, l + 0.92, tp + 0.2, 2.85, 0.7, MSO_ANCHOR.MIDDLE)
    rn(tf.paragraphs[0], title, 12, True, DARK)
    tf = tbx(s, l + 0.18, tp + 1.02, 3.55, 1.2)
    rn(tf.paragraphs[0], "Наблюдение: ", 9.3, True, ACCENT)
    rn(tf.paragraphs[0], obs, 9.3, c=GRAY)
    p = tf.add_paragraph(); p.space_before = Pt(4)
    rn(p, "Ценность: ", 9.6, True, ACCENT)
    rn(p, val, 9.6, c=TEXT)

# ================================================== SLIDE 5 — MVP
s = slide()
header(s, "Шаг 4 · рекомендации для разработчиков",
       "Состав первой версии продукта (MVP)", 5, MSO_SHAPE.GEAR_6)
feat = [
    (MSO_SHAPE.ROUNDED_RECTANGULAR_CALLOUT, "Интерактивная анкета на языке бизнеса",
     "Реализует «язык бизнеса» и «низкий порог»: задача, боль, масштаб, цель."),
    (MSO_SHAPE.GEAR_6, "Система скоринга (rule-based классификатор)",
     "Архетип + вердикт «перспективно / неочевидно / вряд ли»; ядро приоритизации без запуска кванта."),
    ('target', "Прозрачный скоринг + дисклеймер",
     "Поддерживает «честные ожидания»: понятная логика и «без гарантии эффекта»."),
    (MSO_SHAPE.FOLDED_CORNER, "Бриф-артефакт на выходе",
     "Реализует «мост к экспертам»: одностраничный структурированный бриф задачи."),
    (MSO_SHAPE.RIGHT_ARROW, "Флоу хэндоффа к экспертам",
     "Запись на консультацию / очередь экспертов + лид-капчер."),
    (MSO_SHAPE.CUBE, "База референсов и типовых вопросов",
     "Подсказки и примеры use-case по отраслям (ориентир — D-Wave / MS / AWS)."),
    (MSO_SHAPE.CAN, "Сбор данных + жёсткий скоуп",
     "Обезличенные анкеты → база знаний; без интеграций и реального кванта в v1."),
]
for i, (ic, title, why) in enumerate(feat):
    col, row = i % 2, i // 2
    l = 0.6 + col * 6.15
    tp = 1.92 + row * 1.05
    if i == 6:
        l = 0.6; w = 6.0
    chip(s, l, tp, 0.78, ic)
    tf = tbx(s, l + 0.95, tp - 0.02, 5.0, 1.0)
    rn(tf.paragraphs[0], f"{i+1}. {title}", 12, True, DARK)
    p = tf.add_paragraph(); p.space_before = Pt(2)
    rn(p, why, 9.6, c=GRAY)
# фокус
box(s, 6.75, 5.07, 6.0, 1.78, fill=LIGHT, line=None)
chip(s, 6.95, 5.27, 0.62, MSO_SHAPE.HEART, fill=WHITE)
tf = tbx(s, 7.72, 5.3, 4.85, 1.4)
rn(tf.paragraphs[0], "Фокус MVP", 12, True, ACCENT)
p = tf.add_paragraph(); p.space_before = Pt(3)
rn(p, "Структурировать задачу и передать её экспертам — без избыточного усложнения на старте.",
   10.5, c=TEXT)

# ================================================== SLIDE 6 — кейсы (приложение)
s = slide()
header(s, "Приложение · кейсы", "Что конкуренты уже решают на практике", 6, MSO_SHAPE.STAR_5_POINT)
rows = [
    ["Игрок", "Архетип задач", "Примеры кейсов (клиент → задача)", "Зрелость"],
    ["D-Wave", "Комбинаторная оптимизация",
     "Save-On-Foods (расписания, −80% времени) · Port of LA · NTT DOCOMO (−15%) · Ford Otosan · VW · DENSO",
     "Есть продакшен"],
    ["IBM Quantum", "Симуляция + финансы",
     "Boeing (коррозия) · Mercedes (батареи) · Mitsubishi/JSR (OLED) · JPMorgan · HSBC · Cleveland Clinic",
     "В осн. research"],
    ["Multiverse / QC Ware", "Финансы, энергетика, химия",
     "BBVA · Iberdrola · Telefónica (LLM) · Goldman Sachs · Covestro · Aisin",
     "PoC / пилоты"],
    ["MS / AWS", "Self-service ассессмент (не квант)",
     "Не отраслевые кейсы, а эталон формата: анкета по pillars → скоринг → приоритизированный отчёт",
     "Массовый продукт"],
]
t = s.shapes.add_table(5, 4, In(0.6), In(1.9), In(12.13), In(4.5)).table
t.columns[0].width = In(2.2); t.columns[1].width = In(2.7)
t.columns[2].width = In(5.4); t.columns[3].width = In(1.83)
fill_table(t, rows); style_table(t, hs=11, bs=9.3)
rn(tbx(s, 0.6, 6.55, 12, 0.5).paragraphs[0],
   "Цифры — заявления вендоров/партнёров (PoC/research), не аудированный production-ROI. В проде преимущественно оптимизация.",
   9.3, c=GRAY, i=True)

# ================================================== SLIDE 7 — процесс
s = slide()
header(s, "Приложение · процесс", "Как конкуренты выстраивают процесс с клиентом", 7, MSO_SHAPE.RIGHT_ARROW)
steps = ["1. Discovery", "2. Assessment", "3. PoC / Pilot", "4. Production"]
subs = ["поиск задачи", "оценка применимости", "прототип на данных", "внедрение и сопровождение"]
cw = 2.78
for i, st in enumerate(steps):
    l = 0.6 + i * (cw + 0.33)
    b = box(s, l, 1.95, cw, 0.95, fill=DARK, line=None)
    tf = b.text_frame; tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    tf.paragraphs[0].alignment = PP_ALIGN.CENTER
    rn(tf.paragraphs[0], st, 12.5, True, WHITE)
    p = tf.add_paragraph(); p.alignment = PP_ALIGN.CENTER
    rn(p, subs[i], 9.5, c=RGBColor(0xC9, 0xD8, 0xF5))
    if i < 3:
        _shape(s, MSO_SHAPE.CHEVRON, l + cw + 0.02, 2.2, 0.28, 0.5, fill=ACCENT)
rn(tbx(s, 0.6, 3.02, 12.1, 0.4).paragraphs[0],
   "Общая схема у квантовых игроков — консалтинго-ведомая, sales-gated, требует тех-грамотности клиента.",
   10, c=GRAY, i=True)
diffs = [
    ("D-Wave Launch", "Проф. услуги: эксперты «переводят» задачу в QUBO; доставка через облако Leap."),
    ("IBM Quantum", "Членство (Network) + working groups + обучение Quantum Business Foundations."),
    ("Multiverse / QC Ware", "Продукт (Singularity / Forge) + консалтинг; discovery → PoC → deployment."),
    ("MS / AWS (эталон)", "Self-service анкета по pillars без консультанта; хэндофф к партнёрам."),
]
for i, (tt, dd) in enumerate(diffs):
    col, row = i % 2, i // 2
    l = 0.6 + col * 6.1; tp = 3.55 + row * 1.05
    box(s, l, tp, 5.85, 0.95, fill=WHITE, line=LINE)
    tf = tbx(s, l + 0.16, tp + 0.12, 5.5, 0.8)
    rn(tf.paragraphs[0], tt, 12, True, DARK)
    p = tf.add_paragraph(); p.space_before = Pt(2)
    rn(p, dd, 10, c=TEXT)
box(s, 0.6, 5.8, 12.13, 1.0, fill=LIGHT, line=None)
tf = tbx(s, 0.85, 5.95, 11.7, 0.8)
rn(tf.paragraphs[0], "Наш сдвиг (Q-Scope):  ", 13, True, ACCENT)
rn(tf.paragraphs[0],
   "self-service анкета на входе вместо sales-gated discovery → быстро и дёшево квалифицируем задачу, дальше — бриф к экспертам. Vendor-нейтрально.",
   11, c=TEXT)

# ================================================== SLIDE 8 — движок приоритизации
s = slide()
header(s, "Приложение · движок", "Движок приоритизации инициатив", 8, MSO_SHAPE.GEAR_6)
box(s, 0.6, 1.95, 6.1, 1.95, fill=WHITE, line=LINE)
tf = tbx(s, 0.78, 2.08, 5.8, 1.7)
rn(tf.paragraphs[0], "Шаг 1. Gate-фильтры (любое «нет» → не для кванта)", 12.5, True, DARK)
for ln in ["1. Классика провисает (экспоненциально / NP-трудно)",
           "2. Формализуемо как QUBO / Hamiltonian (цель + ограничения)",
           "3. Нет I/O-bottleneck (вход мал относительно решений)"]:
    p = tf.add_paragraph(); p.space_before = Pt(5); rn(p, ln, 10.5, c=TEXT)
sc = box(s, 0.6, 4.05, 6.1, 2.78, fill=WHITE, line=LINE)
tf = tbx(s, 0.78, 4.18, 5.8, 2.6)
rn(tf.paragraphs[0], "Шаг 2. Взвешенный скоринг (0–5)", 12.5, True, DARK)
for crit, w in [("Бизнес-ценность / ROI", "25%"), ("Классическая твёрдость + speedup", "25%"),
                ("Архетип-fit (симуляция/оптимизация ≫ QML)", "15%"),
                ("Реализуемость (NISQ сейчас vs FT)", "15%"),
                ("Готовность данных / инфры / команды", "10%"),
                ("Допустимость приближённого решения", "10%")]:
    p = tf.add_paragraph(); p.space_before = Pt(4)
    rn(p, w + "  ", 10.5, True, ACCENT); rn(p, crit, 10.5, c=TEXT)
# матрица 2x2
mx, my, q = 7.5, 2.45, 2.3
rn(tbx(s, mx, 1.98, q * 2, 0.35).paragraphs[0], "Шаг 3. Матрица 2×2 → приоритет", 12.5, True, DARK)
quad = [(0, 0, "Watchlist", LIGHT, DARK), (1, 0, "PoC сейчас", ACCENT, WHITE),
        (0, 1, "Отбросить", GRAYBX, GRAY), (1, 1, "Классический / гибридный солвер", LIGHT2, DARK)]
for cx, cy, lab, fill, tc in quad:
    b = box(s, mx + cx * (q + 0.06), my + cy * (q + 0.06), q, q, fill=fill, line=WHITE, lw=2)
    b.text_frame.vertical_anchor = MSO_ANCHOR.MIDDLE
    b.text_frame.paragraphs[0].alignment = PP_ALIGN.CENTER
    rn(b.text_frame.paragraphs[0], lab, 11, True, tc)
rn(tbx(s, mx, my - 0.32, q * 2, 0.3).paragraphs[0], "↑ Квантовый потенциал", 9.5, True, GRAY)
ax = tbx(s, mx, my + 2 * q + 0.16, q * 2 + 0.06, 0.3); ax.paragraphs[0].alignment = PP_ALIGN.CENTER
rn(ax.paragraphs[0], "Бизнес-ценность / реализуемость →", 9.5, True, GRAY)

# ================================================== SLIDE 9 — MVP бриф
s = slide()
header(s, "Приложение · MVP", "Бриф задачи и подготовка клиента", 9, MSO_SHAPE.FOLDED_CORNER)
box(s, 0.6, 1.95, 6.3, 4.85, fill=WHITE, line=LINE)
tf = tbx(s, 0.78, 2.1, 6.0, 4.6)
rn(tf.paragraphs[0], "Структура брифа (12 секций)", 13, True, DARK)
for ln in ["Владелец задачи и роли (кто решает, кто платит)",
           "Бизнес-задача в одном предложении: «как есть» → «как надо»",
           "Текущее решение и боль (время / стоимость / качество)",
           "Тип задачи и цель (что значит «лучше»)",
           "Переменные решения (что можно менять)", "Ограничения (жёсткие / мягкие)",
           "Масштаб (число объектов; рост со временем)",
           "Данные (объём, формат, доступность, чувствительность)",
           "Метрика успеха и базовая линия", "Частота и режим (разово / real-time)",
           "Экономика (стоимость, цена ошибки, эффект)",
           "Готовность и ограничения (ИТ, бюджет, комплаенс)"]:
    p = tf.add_paragraph(); p.space_before = Pt(3); rn(p, "•  " + ln, 10, c=TEXT)
box(s, 7.1, 1.95, 5.63, 4.85, fill=LIGHT, line=None)
tf = tbx(s, 7.3, 2.1, 5.3, 4.6)
rn(tf.paragraphs[0], "Кого пригласить и что подготовить", 13, True, ACCENT)
for ln in ["Бизнес-заказчик — боль, метрика, эффект",
           "Профильные операции — процесс, ограничения, объёмы",
           "Владелец данных — какие данные, объём, доступ",
           "ИТ / инфраструктура — где данные, интеграции, безопасность",
           "Финансы — стоимость решения, цена ошибки, ROI",
           "Комплаенс (опц.) — ограничения на данные"]:
    p = tf.add_paragraph(); p.space_before = Pt(6); rn(p, "•  " + ln, 10.3, c=TEXT)
p = tf.add_paragraph(); p.space_before = Pt(10)
rn(p, "Сервис выдаёт этот чек-лист, чтобы клиент пришёл подготовленным и собрал нужные службы.",
   10, True, DARK)

# ================================================== SLIDE 10 — Акинатор
s = slide()
header(s, "Приложение · MVP", "Адаптивная анкета без брифа (чат-бот «Акинатор»)", 10,
       MSO_SHAPE.ROUNDED_RECTANGULAR_CALLOUT)
flow = [("Уровень 0", "Отрасль и роль"),
        ("Уровень 1", "Тип задачи → архетип: оптимизация / симуляция / сэмплинг / ML"),
        ("Уровень 2", "Признаки комбинаторного взрыва и боль классики (gate G1)"),
        ("Уровень 3", "Формализуемость: цель + переменные + ограничения (gate G2)"),
        ("Уровень 4", "Данные и I/O-bottleneck (gate G3)"),
        ("Уровень 5", "Масштаб, частота, экономика → бизнес-ценность"),
        ("Уровень 6", "Готовность: данные / ИТ / команда / комплаенс")]
fy = 1.95
for i, (lv, d) in enumerate(flow):
    b = box(s, 0.6, fy, 7.4, 0.6, fill=(LIGHT if i % 2 == 0 else WHITE), line=LINE)
    b.text_frame.vertical_anchor = MSO_ANCHOR.MIDDLE
    b.text_frame.margin_left = In(0.14)
    p = b.text_frame.paragraphs[0]
    rn(p, lv + ":  ", 10.5, True, ACCENT); rn(p, d, 10, c=TEXT)
    fy += 0.68
box(s, 8.25, 1.95, 4.48, 2.0, fill=WHITE, line=LINE)
tf = tbx(s, 8.43, 2.08, 4.15, 1.8)
rn(tf.paragraphs[0], "Принцип (как Акинатор)", 12, True, DARK)
for ln in ["От общего к частному, ветвление по ответам",
           "Закрытые ответы → детерминированный скоринг",
           "«Не знаю» не блокирует; индикатор «похоже на…»",
           "8–15 вопросов до результата"]:
    p = tf.add_paragraph(); p.space_before = Pt(3); rn(p, "•  " + ln, 9.8, c=TEXT)
box(s, 8.25, 4.1, 4.48, 2.7, fill=GREEN, line=None)
tf = tbx(s, 8.43, 4.24, 4.15, 2.5)
rn(tf.paragraphs[0], "Выход: сценарий + эффект", 12.5, True, WHITE)
for ln in ["Вердикт: PoC · Watchlist · Классики достаточно",
           "Сценарий: «ваша задача — {подтип}, формализуется как QUBO, подход — гибридный солвер, как кейс {референс}»",
           "Оценка эффекта (вилка по аналогии) — не гарантия",
           "Кнопка «передать экспертам» → авто-бриф (PDF)"]:
    p = tf.add_paragraph(); p.space_before = Pt(4); rn(p, "•  " + ln, 9.6, c=WHITE)

# ================================================== SLIDE 11 — матрица 16 кейсов
s = slide()
header(s, "Приложение · карта кейсов", "16 кейсов: квантовый потенциал × бизнес-ценность", 11, 'target')
DIRCOL = {"Логистика": ACCENT, "Производство": GREEN, "Химия": PURPLE, "Финансы": ORANGE}
points = [("L1", 5, 5, "Логистика"), ("L2", 2, 4, "Логистика"), ("L3", 4, 1, "Логистика"), ("L4", 1, 1, "Логистика"),
          ("P1", 5, 5, "Производство"), ("P2", 2, 4, "Производство"), ("P3", 4, 1, "Производство"), ("P4", 1, 1, "Производство"),
          ("C1", 4, 5, "Химия"), ("C2", 2, 5, "Химия"), ("C3", 4, 1, "Химия"), ("C4", 1, 1, "Химия"),
          ("F1", 5, 4, "Финансы"), ("F2", 2, 4, "Финансы"), ("F3", 4, 1, "Финансы"), ("F4", 1, 1, "Финансы")]
PL, PT, PW, PH = 1.4, 2.0, 8.0, 4.5
MIDX, MIDY = PL + PW / 2, PT + PH / 2
for qx, qy, lab, fill, tc, al in [
    (PL, PT, "Watchlist", LIGHT, RGBColor(0x5C, 0x7A, 0xB8), PP_ALIGN.LEFT),
    (MIDX, PT, "PoC сейчас", RGBColor(0xE4, 0xF4, 0xEB), RGBColor(0x3E, 0x8E, 0x65), PP_ALIGN.RIGHT),
    (PL, MIDY, "Отбросить", GRAYBX, RGBColor(0x9A, 0xA1, 0xAC), PP_ALIGN.LEFT),
    (MIDX, MIDY, "Классический солвер", LIGHT2, RGBColor(0x7E, 0x90, 0xAE), PP_ALIGN.RIGHT)]:
    b = box(s, qx, qy, PW / 2, PH / 2, fill=fill, line=WHITE, lw=1.5)
    b.text_frame.margin_left = In(0.1); b.text_frame.margin_top = In(0.06)
    b.text_frame.paragraphs[0].alignment = al
    rn(b.text_frame.paragraphs[0], lab, 12, True, tc)
fx = lambda v: PL + 0.4 + (v / 5.0) * (PW - 0.8)
fyy = lambda v: PT + 0.3 + (PH - 0.6) - (v / 5.0) * (PH - 0.6)
groups = defaultdict(list)
for pid, x, y, d in points:
    groups[(x, y)].append((pid, d))
OFF = {1: [(0, 0)], 2: [(-0.21, 0), (0.21, 0)],
       3: [(-0.22, -0.13), (0.22, -0.13), (0, 0.22)],
       4: [(-0.21, -0.21), (0.21, -0.21), (-0.21, 0.21), (0.21, 0.21)]}
DT = 0.32
for (x, y), mem in groups.items():
    for i, (pid, d) in enumerate(mem):
        dx, dy = OFF[len(mem)][i]
        cx, cy = fx(x) + dx, fyy(y) + dy
        o = _shape(s, MSO_SHAPE.OVAL, cx - DT / 2, cy - DT / 2, DT, DT, fill=DIRCOL[d], line=WHITE, lw=1.2)
        o.text_frame.word_wrap = False
        for m in ('left', 'right', 'top', 'bottom'):
            setattr(o.text_frame, 'margin_' + m, 0)
        o.text_frame.vertical_anchor = MSO_ANCHOR.MIDDLE
        o.text_frame.paragraphs[0].alignment = PP_ALIGN.CENTER
        rn(o.text_frame.paragraphs[0], pid, 7.5, True, WHITE)
rn(tbx(s, PL - 0.05, PT - 0.32, 4, 0.3).paragraphs[0], "↑ Квантовый потенциал", 10, True, GRAY)
xa = tbx(s, PL, PT + PH + 0.08, PW, 0.3); xa.paragraphs[0].alignment = PP_ALIGN.CENTER
rn(xa.paragraphs[0], "Бизнес-ценность / реализуемость →", 10, True, GRAY)
lx = 9.75
rn(tbx(s, lx, 2.0, 2.95, 0.35).paragraphs[0], "Направления", 12.5, True, DARK)
for i, (name, col) in enumerate(DIRCOL.items()):
    yy = 2.5 + i * 0.42
    _shape(s, MSO_SHAPE.OVAL, lx, yy, 0.22, 0.22, fill=col)
    rn(tbx(s, lx + 0.32, yy - 0.02, 2.6, 0.3).paragraphs[0], name, 11, c=TEXT)
box(s, lx, 4.35, 2.95, 2.45, fill=WHITE, line=LINE)
tf = tbx(s, lx + 0.16, 4.5, 2.65, 2.2)
rn(tf.paragraphs[0], "Как читать", 12, True, DARK)
for ln in ["Точка — 1 кейс (ID из CSV)", "Положение = баллы движка (X×Y)",
           "Точки в ячейке разнесены (jitter)", "Квадрант = вердикт приоритизации"]:
    p = tf.add_paragraph(); p.space_before = Pt(4); rn(p, "•  " + ln, 9.8, c=TEXT)

# ================================================== SLIDE 12 — библиотека сценариев
s = slide()
header(s, "Приложение · библиотека", "Сценарии и эффекты: задача → референс → эффект", 12, MSO_SHAPE.CUBE)
rows = [
    ["Подтип задачи", "Архетип", "Референс-кейс", "Эффект (заявл.)", "Горизонт"],
    ["Расписание персонала/смен", "Оптимизация", "Save-On-Foods (D-Wave)", "−80% времени планирования", "Сейчас"],
    ["Производственный план", "Оптимизация", "Ford Otosan (D-Wave)", "−80% времени (в продакшене)", "Сейчас"],
    ["Логистика/порт/маршруты", "Оптимизация", "Port of LA, DENSO, VW", "+throughput, −простои", "Сейчас"],
    ["Сеть/телеком", "Оптимизация", "NTT DOCOMO", "−15% сигнальной нагрузки", "Сейчас"],
    ["Портфель/капитал", "Оптимизация", "BBVA (Multiverse, q-inspired)", "Эффективнее классики (PoC)", "Сейчас"],
    ["Размещение активов в сети", "Оптимизация", "Iberdrola (Multiverse)", "Сопоставимо/лучше классики", "Сейчас"],
    ["Риск/деривативы (Монте-Карло)", "Сэмплинг", "Goldman, JPMorgan", "Квадратичное ускорение (research)", "Средне-/долгосрок"],
    ["Молекулы/материалы/батареи", "Симуляция", "Boeing, Mercedes, Mitsubishi", "Точнее классики (research)", "Средне-/долгосрок"],
    ["Катализаторы/новые материалы", "Симуляция", "Covestro (QC Ware)", "Готовность к 200–500 кубитам", "Долгосрок"],
    ["Дизайн молекул/пептидов", "Симуляция/оптим.", "Menten AI (D-Wave)", "Дизайн «at scale»", "Сейчас"],
    ["Прогноз/классификация", "ML / QML", "HSBC, Crédit Agricole", "Watchlist (data-loading риск)", "Долгосрок"],
]
t = s.shapes.add_table(13, 5, In(0.6), In(1.82), In(12.13), In(5.0)).table
for ci, wd in zip(range(5), [3.0, 2.0, 3.0, 2.6, 1.53]):
    t.columns[ci].width = In(wd)
fill_table(t, rows); style_table(t, hs=10, bs=8.6)

OUT = "/home/user/Claude/quantum-service-competitor-analysis/Концепция-Q-Scope-презентация.pptx"
prs.save(OUT)
print("Saved:", OUT, "| slides:", len(prs.slides._sldIdLst))
