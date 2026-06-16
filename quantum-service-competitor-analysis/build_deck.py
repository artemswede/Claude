# -*- coding: utf-8 -*-
"""
Генератор презентации (тестовое задание BA):
Анализ конкурентов для сервиса предварительной квалификации квантовых задач "Q-Scope".
Стиль: белый фон, минимализм, синие акценты. 5 слайдов. Язык: русский.
"""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn

# ---- Палитра ----
DARK_BLUE = RGBColor(0x1A, 0x3E, 0x8C)
ACCENT    = RGBColor(0x2D, 0x6C, 0xDF)
TEXT      = RGBColor(0x2B, 0x2B, 0x2B)
GRAY      = RGBColor(0x66, 0x66, 0x66)
LIGHTBLUE = RGBColor(0xEC, 0xF1, 0xFB)
ROWALT    = RGBColor(0xF7, 0xF9, 0xFD)
WHITE     = RGBColor(0xFF, 0xFF, 0xFF)
LINE      = RGBColor(0xD9, 0xE1, 0xF2)

FONT = "Calibri"

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
BLANK = prs.slide_layouts[6]
SW, SH = prs.slide_width, prs.slide_height


def add_slide():
    s = prs.slides.add_slide(BLANK)
    # белый фон
    bg = s.background.fill
    bg.solid()
    bg.fore_color.rgb = WHITE
    return s


def textbox(slide, l, t, w, h, anchor=MSO_ANCHOR.TOP):
    tb = slide.shapes.add_textbox(l, t, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = 0
    tf.margin_right = 0
    tf.margin_top = 0
    tf.margin_bottom = 0
    return tb, tf


def set_run(r, text, size, bold=False, color=TEXT, italic=False):
    r.text = text
    r.font.name = FONT
    r.font.size = Pt(size)
    r.font.bold = bold
    r.font.italic = italic
    r.font.color.rgb = color


def header(slide, kicker, title, idx):
    # синяя полоса-акцент слева сверху
    bar = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.6), Inches(0.55),
                                 Inches(0.12), Inches(0.95))
    bar.fill.solid(); bar.fill.fore_color.rgb = ACCENT
    bar.line.fill.background()
    bar.shadow.inherit = False

    tb, tf = textbox(slide, Inches(0.92), Inches(0.5), Inches(11.4), Inches(1.1))
    p = tf.paragraphs[0]
    set_run(p.add_run(), kicker.upper(), 12, bold=True, color=ACCENT)
    p2 = tf.add_paragraph()
    p2.space_before = Pt(2)
    set_run(p2.add_run(), title, 26, bold=True, color=DARK_BLUE)

    # тонкая линия под шапкой
    ln = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.6), Inches(1.62),
                                Inches(12.13), Pt(1.4))
    ln.fill.solid(); ln.fill.fore_color.rgb = LINE
    ln.line.fill.background(); ln.shadow.inherit = False

    # номер слайда
    nb, nf = textbox(slide, Inches(12.4), Inches(6.95), Inches(0.6), Inches(0.4))
    pp = nf.paragraphs[0]; pp.alignment = PP_ALIGN.RIGHT
    set_run(pp.add_run(), f"{idx} / 5", 10, color=GRAY)


def style_table(table, header_size=10.5, body_size=9.5, first_col_bold=False):
    # убрать встроенный стиль бэндинга
    tbl = table._tbl
    tblPr = tbl.tblPr
    tblPr.set('firstRow', '0')
    tblPr.set('bandRow', '0')
    for ri, row in enumerate(table.rows):
        for ci, cell in enumerate(row.cells):
            cell.margin_left = Inches(0.08)
            cell.margin_right = Inches(0.08)
            cell.margin_top = Inches(0.04)
            cell.margin_bottom = Inches(0.04)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            if ri == 0:
                cell.fill.solid(); cell.fill.fore_color.rgb = DARK_BLUE
            else:
                cell.fill.solid()
                cell.fill.fore_color.rgb = ROWALT if ri % 2 == 0 else WHITE
            for p in cell.text_frame.paragraphs:
                for r in p.runs:
                    r.font.name = FONT
                    r.font.size = Pt(header_size if ri == 0 else body_size)
                    if ri == 0:
                        r.font.bold = True
                        r.font.color.rgb = WHITE
                    else:
                        r.font.color.rgb = TEXT
                        if ci == 0 and first_col_bold:
                            r.font.bold = True
                            r.font.color.rgb = DARK_BLUE


def fill_table(table, data):
    for ri, row in enumerate(data):
        for ci, val in enumerate(row):
            table.cell(ri, ci).text = val


# ============================================================== SLIDE 1
s = add_slide()
# крупный левый акцентный блок-«квант»
for i, (x, y, sz, col) in enumerate([
    (0.0, 0.0, 0, None)]):
    pass
# заголовочный блок
tb, tf = textbox(s, Inches(0.9), Inches(0.7), Inches(11.5), Inches(0.6))
set_run(tf.paragraphs[0].add_run(), "ТЕСТОВОЕ ЗАДАНИЕ · БИЗНЕС-АНАЛИТИК", 13,
        bold=True, color=ACCENT)

tb, tf = textbox(s, Inches(0.9), Inches(1.25), Inches(11.5), Inches(1.6))
p = tf.paragraphs[0]
set_run(p.add_run(), "Анализ конкурентов и аналогов.", 34, bold=True, color=DARK_BLUE)
p2 = tf.add_paragraph()
set_run(p2.add_run(), "Рекомендации для первой версии продукта (MVP)", 34, bold=True,
        color=DARK_BLUE)

# линия
ln = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.92), Inches(3.05),
                        Inches(3.2), Pt(3))
ln.fill.solid(); ln.fill.fore_color.rgb = ACCENT
ln.line.fill.background(); ln.shadow.inherit = False

# карточка «что за продукт»
card = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.9), Inches(3.4),
                          Inches(7.2), Inches(3.3))
card.fill.solid(); card.fill.fore_color.rgb = LIGHTBLUE
card.line.color.rgb = LINE; card.line.width = Pt(1); card.shadow.inherit = False
ctf = card.text_frame; ctf.word_wrap = True
ctf.margin_left = Inches(0.3); ctf.margin_right = Inches(0.3)
ctf.margin_top = Inches(0.22)
cp = ctf.paragraphs[0]
set_run(cp.add_run(), "Что мы запускаем — «Q-Scope»", 16, bold=True, color=DARK_BLUE)
for txt in [
    "Сервис предварительной квалификации бизнес-задач на применимость квантовых вычислений.",
    "Помогает описать задачу на языке бизнеса, даёт предварительный сигнал перспективности и передаёт структурированную задачу экспертам.",
    "Это НЕ QC-провайдер и не доступ к «железу», а слой квалификации и маршрутизации use-case'ов. Эффект не гарантируется."]:
    pp = ctf.add_paragraph(); pp.space_before = Pt(7)
    set_run(pp.add_run(), "•  " + txt, 12.5, color=TEXT)

# карточка «метод анализа»
card2 = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(8.35), Inches(3.4),
                           Inches(4.05), Inches(3.3))
card2.fill.solid(); card2.fill.fore_color.rgb = WHITE
card2.line.color.rgb = ACCENT; card2.line.width = Pt(1.5); card2.shadow.inherit = False
c2 = card2.text_frame; c2.word_wrap = True
c2.margin_left = Inches(0.25); c2.margin_right = Inches(0.25); c2.margin_top = Inches(0.22)
cp = c2.paragraphs[0]
set_run(cp.add_run(), "Метод анализа: 3 слоя", 15, bold=True, color=ACCENT)
for n, txt in [
    ("1", "Прямые квантовые игроки"),
    ("2", "Смежный квантовый консалтинг и софт"),
    ("3", "Неквантовые assessment-инструменты — ориентир по формату")]:
    pp = c2.add_paragraph(); pp.space_before = Pt(11)
    r = pp.add_run(); set_run(r, n + "   ", 14, bold=True, color=DARK_BLUE)
    set_run(pp.add_run(), txt, 12.5, color=TEXT)

# ============================================================== SLIDE 2
s = add_slide()
header(s, "Шаг 1 · выбор конкурентов и аналогов",
       "Кого сравниваем и насколько они близки нам", 2)

rows2 = [
    ["Решение", "Почему выбрали", "Тип решения", "Близость к нам"],
    ["D-Wave Launch + Professional Services",
     "Делает ровно use-case identification & assessment для enterprise",
     "Квантовый вендор + проф. услуги",
     "Прямой конкурент (ближайший)"],
    ["IBM Quantum (Network, working groups, Quantum Business Foundations)",
     "Лидер рынка; формирует спрос и поиск применений через комьюнити и обучение",
     "Облачная QC-платформа + экосистема",
     "Альтернативный способ (доступ + обучение)"],
    ["Multiverse Computing / QC Ware",
     "Показывают модель discovery → PoC в крупном enterprise",
     "Квантовый софт / консалтинг",
     "Аналог (дорогой проектный консалтинг)"],
    ["Microsoft AI Readiness + AWS Well-Architected",
     "Неквантовые, но решают ту же мета-задачу «подходит ли технология X»; эталон формата",
     "Self-service assessment-инструмент",
     "Альтернативный способ + ориентир по формату"],
]
gt = s.shapes.add_table(5, 4, Inches(0.6), Inches(1.95), Inches(12.13), Inches(4.9))
table = gt.table
table.columns[0].width = Inches(3.0)
table.columns[1].width = Inches(4.5)
table.columns[2].width = Inches(2.5)
table.columns[3].width = Inches(2.13)
fill_table(table, rows2)
style_table(table, header_size=12, body_size=11, first_col_bold=True)

# ============================================================== SLIDE 3
s = add_slide()
header(s, "Шаг 2 · сравнительный анализ",
       "Сравнительная матрица по ключевым критериям", 3)

rows3 = [
    ["Критерий", "D-Wave Launch", "IBM Quantum", "Multiverse / QC Ware", "MS AI Readiness / AWS WA"],
    ["Целевая аудитория", "Enterprise: тех-команды + бизнес-спонсоры",
     "Разработчики, исследователи, инноваторы", "Крупный enterprise (фин/энерго/пром)",
     "Бизнес + IT, широкий self-service"],
    ["Ценностное предложение", "От поиска задачи до продакшена на своём кванте",
     "Облачный доступ + экосистема и обучение", "Готовые квантовые решения под задачу",
     "Быстро оценить готовность без консультанта"],
    ["Клиентские задачи", "Оптимизация, логистика, расписания",
     "Химия/материалы, оптимизация, ML", "Оптимизация портфеля, риск, прогноз",
     "Оценка зрелости/архитектуры по пилларам"],
    ["Отрасли", "Производство, логистика, финансы, life sciences, госсектор",
     "Life sciences, материалы, финансы, энергетика", "Финансы, энергетика, промышленность",
     "Кросс-отраслевые"],
    ["Формат / механика (ориентир)", "Проф. услуги + воркшопы, assessment",
     "Working groups, воркшопы, курсы", "Проектный discovery → PoC",
     "Анкета, пиллары, скоринг, отчёт"],
    ["Бизнес-модель", "Доступ к железу + консалтинг", "Cloud access + membership",
     "Проектный консалтинг / лицензии", "Бесплатный self-service (лид-ген)"],
    ["Vendor-нейтральность", "Привязка к D-Wave (annealing)", "Привязка к IBM",
     "Частичная (свой софт)", "Методология переносима"],
    ["Артефакт на выходе", "Use-case + план внедрения", "Знания / прототипы",
     "PoC / решение", "Скоринг + персональный отчёт"],
]
gt = s.shapes.add_table(9, 5, Inches(0.6), Inches(1.8), Inches(12.13), Inches(5.05))
table = gt.table
table.columns[0].width = Inches(2.1)
for ci in range(1, 5):
    table.columns[ci].width = Inches(2.5075)
fill_table(table, rows3)
style_table(table, header_size=10, body_size=8.5, first_col_bold=True)

# подпись-вывод
tb, tf = textbox(s, Inches(0.6), Inches(6.95), Inches(11.6), Inches(0.4))
r = tf.paragraphs[0].add_run()
set_run(r, "Вывод: формат self-service анкеты/скоринга (MS/AWS) + отраслевая разбивка задач (D-Wave) — лучшие ориентиры для нас.",
        10, italic=True, color=GRAY)

# ============================================================== SLIDE 4
s = add_slide()
header(s, "Шаг 3 · конкурентные преимущества",
       "Чем Q-Scope может отличаться на рынке", 4)

advs = [
    ("Vendor-нейтральность (hardware-agnostic)",
     "Наблюдение: D-Wave/IBM привязывают оценку к своему железу.",
     "Ценность: непредвзятая рекомендация и охват любых задач/поставщиков → доверие."),
    ("Self-service и низкий порог входа",
     "Наблюдение: квантовые офферы — sales-gated консалтинг; спрос на self-service анкеты доказан (MS/AWS).",
     "Ценность: быстрый сигнал без обязательств → масштабируемая воронка лидов."),
    ("Язык бизнеса без квантовой экспертизы",
     "Наблюдение: IBM/D-Wave предполагают тех-грамотность пользователя.",
     "Ценность: доступно бизнес-заказчику; структурирует задачу в бизнес-терминах."),
    ("Честное управление ожиданиями",
     "Наблюдение: рынок перегрет хайпом, гарантий эффекта нет.",
     "Ценность: ранний отсев неподходящих кейсов → качественные лиды и доверие."),
    ("Мост к экспертам + структурированный бриф",
     "Наблюдение: assessment-инструменты заканчиваются отчётом и не ведут к next step.",
     "Ценность: готовый бриф экономит время экспертов; чёткий next step и точка монетизации."),
]
# 5 карточек: 3 сверху, 2 снизу
positions = [
    (0.6, 1.9, 3.9, 2.35), (4.72, 1.9, 3.9, 2.35), (8.83, 1.9, 3.9, 2.35),
    (2.65, 4.45, 3.9, 2.35), (6.77, 4.45, 3.9, 2.35),
]
for (title, obs, val), (l, t, w, h) in zip(advs, positions):
    card = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(l), Inches(t),
                              Inches(w), Inches(h))
    card.fill.solid(); card.fill.fore_color.rgb = WHITE
    card.line.color.rgb = LINE; card.line.width = Pt(1); card.shadow.inherit = False
    # верхняя акцентная полоска
    strip = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(l), Inches(t),
                               Inches(w), Inches(0.12))
    strip.fill.solid(); strip.fill.fore_color.rgb = ACCENT
    strip.line.fill.background(); strip.shadow.inherit = False
    tf = card.text_frame; tf.word_wrap = True
    tf.margin_left = Inches(0.18); tf.margin_right = Inches(0.18); tf.margin_top = Inches(0.16)
    p = tf.paragraphs[0]
    set_run(p.add_run(), title, 12.5, bold=True, color=DARK_BLUE)
    p1 = tf.add_paragraph(); p1.space_before = Pt(6)
    set_run(p1.add_run(), obs, 9.5, color=GRAY)
    p2 = tf.add_paragraph(); p2.space_before = Pt(5)
    set_run(p2.add_run(), val, 10, bold=True, color=TEXT)

# ============================================================== SLIDE 5
s = add_slide()
header(s, "Шаг 4 · рекомендации для разработчиков",
       "Что включить в первую версию (MVP)", 5)

recs = [
    ("Анкета на языке бизнеса", "Модульно: задача · текущее решение и боль · масштаб, данные, ограничения · тип цели."),
    ("Rule-based классификатор архетипов", "Оптимизация / симуляция / сэмплинг / QML → вердикт «перспективно / неочевидно / вряд ли»."),
    ("Прозрачный скоринг + дисклеймер", "Понятная логика оценки и обязательное «без гарантии эффекта»."),
    ("Артефакт на выходе — бриф", "Одностраничный структурированный бриф задачи + предварительный вердикт для экспертов."),
    ("Флоу хэндоффа к экспертам", "Запись на консультацию / очередь экспертов + лид-капчер."),
    ("Библиотека вопросов и примеров", "Типовые вопросы и use-case по отраслям (логистика, финансы, производство, life sciences)."),
    ("Сбор данных и жёсткий скоуп", "Обезличенные анкеты → база знаний; аналитика воронки. Без интеграций и реального запуска кванта в v1."),
]
top = 1.9
col_w = 6.0
row_h = 0.69
for i, (title, desc) in enumerate(recs):
    col = 0 if i < 4 else 1
    row = i if i < 4 else i - 4
    l = 0.6 + col * (col_w + 0.5)
    t = top + row * (row_h + 0.12)
    # номер-кружок
    num = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(l), Inches(t), Inches(0.5), Inches(0.5))
    num.fill.solid(); num.fill.fore_color.rgb = ACCENT; num.line.fill.background()
    num.shadow.inherit = False
    ntf = num.text_frame; ntf.word_wrap = False
    np = ntf.paragraphs[0]; np.alignment = PP_ALIGN.CENTER
    set_run(np.add_run(), str(i + 1), 15, bold=True, color=WHITE)
    # текст
    tb, tf = textbox(s, Inches(l + 0.65), Inches(t - 0.05), Inches(col_w - 0.65),
                     Inches(row_h + 0.1))
    p = tf.paragraphs[0]
    set_run(p.add_run(), title, 12.5, bold=True, color=DARK_BLUE)
    p2 = tf.add_paragraph(); p2.space_before = Pt(1)
    set_run(p2.add_run(), desc, 9.8, color=GRAY)

# 8-й блок (нижний правый) — вывод
l = 0.6 + 1 * (col_w + 0.5)
t = top + 3 * (row_h + 0.12)
card = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(l - 0.05), Inches(t - 0.05),
                          Inches(col_w), Inches(row_h + 0.1))
card.fill.solid(); card.fill.fore_color.rgb = LIGHTBLUE
card.line.fill.background(); card.shadow.inherit = False
tf = card.text_frame; tf.word_wrap = True
tf.margin_left = Inches(0.18); tf.margin_top = Inches(0.06)
set_run(tf.paragraphs[0].add_run(),
        "Принцип MVP: максимально быстрый и честный предварительный сигнал + чистый бриф для экспертов.",
        10.5, bold=True, color=DARK_BLUE)

out = "/home/user/Claude/quantum-service-competitor-analysis/Анализ-конкурентов-Q-Scope.pptx"
prs.save(out)
print("Saved:", out)
print("Slides:", len(prs.slides.__iter__.__self__._sldIdLst))
