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
TOTAL = 11  # 5 основных + 6 слайдов приложения
GREEN = RGBColor(0x1E, 0x8E, 0x5A)
GRAYBOX = RGBColor(0xEE, 0xEF, 0xF2)

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
    set_run(pp.add_run(), f"{idx} / {TOTAL}", 10, color=GRAY)


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

# ============================================================== ХЕЛПЕРЫ ПРИЛОЖЕНИЯ
def rbox(slide, l, t, w, h, fill=WHITE, line=LINE, line_w=1.0):
    sh = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(l), Inches(t),
                                Inches(w), Inches(h))
    sh.fill.solid(); sh.fill.fore_color.rgb = fill
    if line is None:
        sh.line.fill.background()
    else:
        sh.line.color.rgb = line; sh.line.width = Pt(line_w)
    sh.shadow.inherit = False
    sh.text_frame.word_wrap = True
    return sh


def box_titled(slide, l, t, w, h, title, lines, fill=WHITE, accent=ACCENT,
               title_size=12.5, body_size=9.8, body_color=TEXT):
    sh = rbox(slide, l, t, w, h, fill=fill)
    tf = sh.text_frame
    tf.margin_left = Inches(0.16); tf.margin_right = Inches(0.14)
    tf.margin_top = Inches(0.12); tf.margin_bottom = Inches(0.08)
    p = tf.paragraphs[0]
    set_run(p.add_run(), title, title_size, bold=True, color=accent)
    for ln in lines:
        pp = tf.add_paragraph(); pp.space_before = Pt(4)
        set_run(pp.add_run(), ln, body_size, color=body_color)
    return sh


# ============================================================== SLIDE 6 — кейсы
s = add_slide()
header(s, "Приложение · кейсы", "Что конкуренты уже решают на практике", 6)
rows6 = [
    ["Игрок", "Архетип задач", "Примеры кейсов (клиент → задача)", "Зрелость"],
    ["D-Wave", "Комбинаторная оптимизация",
     "Save-On-Foods (расписания, −80% времени) · Port of LA · NTT DOCOMO (−15% сигналов) · Ford Otosan · VW (трафик, paint shop) · DENSO",
     "Есть продакшен"],
    ["IBM Quantum", "Симуляция + финансы",
     "Boeing (коррозия) · Mercedes (батареи) · Mitsubishi/JSR (OLED) · JPMorgan · HSBC · Cleveland Clinic",
     "В осн. research"],
    ["Multiverse / QC Ware", "Финансы, энергетика, химия",
     "BBVA (портфель) · Iberdrola (батареи в сети) · Telefónica (сжатие LLM) · Goldman Sachs · Covestro · Aisin",
     "PoC / пилоты"],
    ["MS AI Readiness / AWS WA", "Не квантовые — self-service ассессмент",
     "Не отраслевые кейсы, а эталон формата: анкета по pillars → скоринг → приоритизированный отчёт",
     "Массовый продукт"],
]
gt = s.shapes.add_table(5, 4, Inches(0.6), Inches(1.9), Inches(12.13), Inches(4.6)).table
gt.columns[0].width = Inches(2.2); gt.columns[1].width = Inches(2.6)
gt.columns[2].width = Inches(5.5); gt.columns[3].width = Inches(1.83)
fill_table(gt, rows6)
style_table(gt, header_size=11, body_size=9.5, first_col_bold=True)
tb, tf = textbox(s, Inches(0.6), Inches(6.7), Inches(11.8), Inches(0.5))
set_run(tf.paragraphs[0].add_run(),
        "Цифры — заявления вендоров/партнёров (PoC/research-стадия), не аудированный production-ROI. Реально «в проде» — преимущественно оптимизация (D-Wave).",
        9.5, italic=True, color=GRAY)

# ============================================================== SLIDE 7 — процесс
s = add_slide()
header(s, "Приложение · процесс", "Как конкуренты выстраивают процесс с клиентом", 7)
# 4-шаговая воронка
steps = ["1. Discovery\n(поиск задачи)", "2. Assessment\n(оценка применимости)",
         "3. PoC / Pilot\n(прототип на данных)", "4. Production\n(внедрение, сопровождение)"]
sx, sw, sgap = 0.6, 2.78, 0.33
for i, st in enumerate(steps):
    l = sx + i * (sw + sgap)
    b = rbox(s, l, 1.95, sw, 0.95, fill=DARK_BLUE, line=None)
    tf = b.text_frame; tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    for j, line in enumerate(st.split("\n")):
        p = tf.paragraphs[0] if j == 0 else tf.add_paragraph()
        p.alignment = PP_ALIGN.CENTER
        set_run(p.add_run(), line, 12 if j == 0 else 9.5, bold=(j == 0), color=WHITE)
    if i < 3:
        ar = s.shapes.add_shape(MSO_SHAPE.CHEVRON, Inches(l + sw + 0.02), Inches(2.18),
                                Inches(0.28), Inches(0.5))
        ar.fill.solid(); ar.fill.fore_color.rgb = ACCENT; ar.line.fill.background()
        ar.shadow.inherit = False
tb, tf = textbox(s, Inches(0.6), Inches(3.0), Inches(12.1), Inches(0.4))
set_run(tf.paragraphs[0].add_run(),
        "Общая схема у всех квантовых игроков — консалтинго-ведомая, sales-gated, требует тех-грамотности клиента.",
        10, italic=True, color=GRAY)
# различия игроков
diffs = [
    ("D-Wave Launch", "Проф. услуги: эксперты «переводят» задачу в QUBO; доставка через облако Leap."),
    ("IBM Quantum", "Членство (Network) + доменные working groups + обучение Quantum Business Foundations."),
    ("Multiverse / QC Ware", "Продукт (Singularity / Forge) + консалтинг; discovery → PoC → deployment."),
    ("MS / AWS (эталон)", "Self-service анкета по pillars, без консультанта; хэндофф к партнёрам."),
]
for i, (t_, d_) in enumerate(diffs):
    col, row = i % 2, i // 2
    box_titled(s, 0.6 + col * 6.1, 3.55 + row * 1.05, 5.85, 0.95, t_, [d_],
               accent=DARK_BLUE, title_size=12, body_size=10)
# наш сдвиг
box_titled(s, 0.6, 5.8, 12.13, 1.0,
           "Наш сдвиг (Q-Scope):",
           ["Self-service анкета на входе вместо sales-gated discovery → быстро и дёшево квалифицируем задачу, дальше — структурированный бриф к экспертам. Vendor-нейтрально."],
           fill=LIGHTBLUE, accent=ACCENT, title_size=13, body_size=11)

# ============================================================== SLIDE 8 — движок приоритизации
s = add_slide()
header(s, "Приложение · движок", "Движок приоритизации инициатив", 8)
# левая колонка: шаги 1-2
box_titled(s, 0.6, 1.95, 6.1, 1.85,
           "Шаг 1. Gate-фильтры (любое «нет» → не для кванта)",
           ["1. Классика провисает (экспоненциально / NP-трудно)",
            "2. Формализуемо как QUBO / Hamiltonian (цель + ограничения)",
            "3. Нет I/O-bottleneck (вход мал относительно пространства решений)"],
           accent=DARK_BLUE, title_size=12.5, body_size=10.5)
# скоринг
sc = rbox(s, 0.6, 3.95, 6.1, 2.85, fill=WHITE)
tf = sc.text_frame; tf.margin_left = Inches(0.16); tf.margin_top = Inches(0.12)
set_run(tf.paragraphs[0].add_run(), "Шаг 2. Взвешенный скоринг (0–5)", 12.5, bold=True, color=DARK_BLUE)
for crit, w in [("Бизнес-ценность / ROI", "25%"), ("Классическая твёрдость + speedup", "25%"),
                ("Архетип-fit (симуляция/оптимизация ≫ QML)", "15%"),
                ("Реализуемость (NISQ сейчас vs FT)", "15%"),
                ("Готовность данных/инфры/команды", "10%"),
                ("Допустимость приближённого решения", "10%")]:
    pp = tf.add_paragraph(); pp.space_before = Pt(5)
    r = pp.add_run(); set_run(r, w + "  ", 10.5, bold=True, color=ACCENT)
    set_run(pp.add_run(), crit, 10.5, color=TEXT)
# правая колонка: матрица 2x2
mx, my, q = 7.45, 2.45, 2.35
set_run(textbox(s, Inches(mx), Inches(1.98), Inches(q*2), Inches(0.35))[1].paragraphs[0].add_run(),
        "Шаг 3. Матрица 2×2 → приоритет", 12.5, bold=True, color=DARK_BLUE)
quad = [
    (0, 0, "Watchlist / glide path", LIGHTBLUE, DARK_BLUE),
    (1, 0, "PoC сейчас", ACCENT, WHITE),
    (0, 1, "Отбросить", GRAYBOX, GRAY),
    (1, 1, "Классический / гибридный солвер", LIGHTBLUE, DARK_BLUE),
]
for cx, cy, label, fill, txtc in quad:
    b = rbox(s, mx + cx * (q + 0.06), my + cy * (q + 0.06), q, q, fill=fill, line=WHITE, line_w=2)
    tfq = b.text_frame; tfq.vertical_anchor = MSO_ANCHOR.MIDDLE
    p = tfq.paragraphs[0]; p.alignment = PP_ALIGN.CENTER
    set_run(p.add_run(), label, 11, bold=True, color=txtc)
# оси
set_run(textbox(s, Inches(mx - 0.05), Inches(my - 0.32), Inches(q*2), Inches(0.3))[1].paragraphs[0].add_run(),
        "↑ Квантовый потенциал", 9.5, bold=True, color=GRAY)
axb = textbox(s, Inches(mx), Inches(my + 2*q + 0.18), Inches(q*2+0.06), Inches(0.3))[1]
axb.paragraphs[0].alignment = PP_ALIGN.CENTER
set_run(axb.paragraphs[0].add_run(), "Бизнес-ценность / реализуемость →", 9.5, bold=True, color=GRAY)

# ============================================================== SLIDE 9 — MVP бриф
s = add_slide()
header(s, "Приложение · MVP", "Бриф задачи и подготовка клиента", 9)
# левый: структура брифа
sc = rbox(s, 0.6, 1.95, 6.3, 4.85, fill=WHITE)
tf = sc.text_frame; tf.margin_left = Inches(0.18); tf.margin_top = Inches(0.14)
set_run(tf.paragraphs[0].add_run(), "Структура брифа (12 секций)", 13, bold=True, color=DARK_BLUE)
for ln in ["Владелец задачи и роли (кто решает, кто платит)",
           "Бизнес-задача в одном предложении: «как есть» → «как надо»",
           "Текущее решение и боль (время / стоимость / качество)",
           "Тип задачи и цель (что значит «лучше»)",
           "Переменные решения (что можно менять)",
           "Ограничения (жёсткие / мягкие)",
           "Масштаб (сколько объектов; рост со временем)",
           "Данные (объём, формат, доступность, чувствительность)",
           "Метрика успеха и базовая линия",
           "Частота и режим (разово / real-time)",
           "Экономика (стоимость, цена ошибки, эффект)",
           "Готовность и ограничения (ИТ, бюджет, комплаенс)"]:
    pp = tf.add_paragraph(); pp.space_before = Pt(3)
    set_run(pp.add_run(), "•  " + ln, 10, color=TEXT)
# правый: кого позвать
box_titled(s, 7.1, 1.95, 5.63, 4.85,
           "Кого пригласить и что подготовить",
           ["Бизнес-заказчик — боль, метрика, эффект",
            "Профильные операции — как устроен процесс, ограничения, объёмы",
            "Владелец данных — какие данные, объём, качество, доступ",
            "ИТ / инфраструктура — где данные, интеграции, безопасность",
            "Финансы — стоимость текущего решения, цена ошибки, ROI",
            "Комплаенс (опц.) — ограничения на данные",
            "",
            "Сервис выдаёт этот чек-лист, чтобы клиент пришёл подготовленным и собрал нужные службы."],
           fill=LIGHTBLUE, accent=ACCENT, title_size=13, body_size=10.3)

# ============================================================== SLIDE 10 — MVP анкета (Акинатор)
s = add_slide()
header(s, "Приложение · MVP", "Адаптивная анкета без брифа (чат-бот «Акинатор»)", 10)
# вертикальная воронка слева
flow = [
    ("Уровень 0", "Отрасль и роль"),
    ("Уровень 1", "Тип задачи → архетип: оптимизация / симуляция / сэмплинг / ML"),
    ("Уровень 2", "Признаки комбинаторного взрыва и боль классики (gate G1)"),
    ("Уровень 3", "Формализуемость: цель + переменные + ограничения (gate G2)"),
    ("Уровень 4", "Данные и I/O-bottleneck (gate G3)"),
    ("Уровень 5", "Масштаб, частота, экономика → бизнес-ценность"),
    ("Уровень 6", "Готовность: данные / ИТ / команда / комплаенс"),
]
fy = 1.95
for i, (lv, d_) in enumerate(flow):
    b = rbox(s, 0.6, fy, 7.4, 0.6, fill=(LIGHTBLUE if i % 2 == 0 else WHITE))
    tf = b.text_frame; tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    tf.margin_left = Inches(0.14); tf.margin_top = Inches(0.02); tf.margin_bottom = Inches(0.02)
    p = tf.paragraphs[0]
    set_run(p.add_run(), lv + ":  ", 10.5, bold=True, color=ACCENT)
    set_run(p.add_run(), d_, 10, color=TEXT)
    fy += 0.68
# принцип сверху-справа
box_titled(s, 8.25, 1.95, 4.48, 2.0,
           "Принцип (как Акинатор)",
           ["От общего к частному, ветвление по ответам",
            "Закрытые ответы → детерминированный скоринг",
            "«Не знаю» не блокирует; живой индикатор «похоже на…»",
            "8–15 вопросов до результата"],
           accent=DARK_BLUE, title_size=12, body_size=9.8)
# выход снизу-справа
box_titled(s, 8.25, 4.1, 4.48, 2.7,
           "Выход: сценарий + эффект",
           ["Вердикт: Перспективно → PoC · Watchlist · Классики достаточно",
            "Сценарий: «ваша задача — {подтип}, формализуется как QUBO, подход — гибридный солвер, похоже на {референс-кейс}»",
            "Оценка эффекта (вилка по аналогии) — не гарантия",
            "Кого позвать + кнопка «передать экспертам» → авто-бриф (PDF)"],
           fill=GREEN, accent=WHITE, title_size=12.5, body_size=9.8, body_color=WHITE)

# ============================================================== SLIDE 11 — матрица 2×2 (16 кейсов)
s = add_slide()
header(s, "Приложение · матрица", "Карта 16 кейсов: квантовый потенциал × бизнес-ценность", 11)

DIRCOL = {
    "Логистика": ACCENT,
    "Производство": GREEN,
    "Химия": RGBColor(0x7A, 0x4F, 0xC0),
    "Финансы": RGBColor(0xE0, 0x8A, 0x1E),
}
points = [
    ("L1", 5, 5, "Логистика"), ("L2", 2, 4, "Логистика"), ("L3", 4, 1, "Логистика"), ("L4", 1, 1, "Логистика"),
    ("P1", 5, 5, "Производство"), ("P2", 2, 4, "Производство"), ("P3", 4, 1, "Производство"), ("P4", 1, 1, "Производство"),
    ("C1", 4, 5, "Химия"), ("C2", 2, 5, "Химия"), ("C3", 4, 1, "Химия"), ("C4", 1, 1, "Химия"),
    ("F1", 5, 4, "Финансы"), ("F2", 2, 4, "Финансы"), ("F3", 4, 1, "Финансы"), ("F4", 1, 1, "Финансы"),
]
PL, PT, PW, PH = 1.4, 2.0, 8.0, 4.5          # рамка области графика
MIDX, MIDY = PL + PW / 2, PT + PH / 2
# квадранты-фоны
quads = [
    (PL, PT, "Watchlist", LIGHTBLUE, RGBColor(0x6E, 0x8A, 0xC0)),
    (MIDX, PT, "PoC сейчас", RGBColor(0xE3, 0xF3, 0xEA), RGBColor(0x4F, 0x9E, 0x73)),
    (PL, MIDY, "Отбросить", GRAYBOX, RGBColor(0xA0, 0xA6, 0xB0)),
    (MIDX, MIDY, "Классический солвер", RGBColor(0xF3, 0xF7, 0xFC), RGBColor(0x7E, 0x90, 0xAE)),
]
for qx, qy, label, fill, txtc in quads:
    r = rbox(s, qx, qy, PW / 2, PH / 2, fill=fill, line=WHITE, line_w=1.5)
    tf = r.text_frame; tf.vertical_anchor = MSO_ANCHOR.TOP
    tf.margin_left = Inches(0.1); tf.margin_top = Inches(0.06)
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.RIGHT if "PoC" in label or "Класс" in label else PP_ALIGN.LEFT
    set_run(p.add_run(), label, 12, bold=True, color=txtc)

def px(v): return PL + 0.4 + (v / 5.0) * (PW - 0.8)
def py(v): return PT + 0.3 + (PH - 0.6) - (v / 5.0) * (PH - 0.6)

from collections import defaultdict
groups = defaultdict(list)
for pid, x, y, d in points:
    groups[(x, y)].append((pid, d))
OFF = {
    1: [(0, 0)],
    2: [(-0.21, 0), (0.21, 0)],
    3: [(-0.22, -0.13), (0.22, -0.13), (0.0, 0.22)],
    4: [(-0.21, -0.21), (0.21, -0.21), (-0.21, 0.21), (0.21, 0.21)],
}
D = 0.32
for (x, y), members in groups.items():
    offs = OFF[len(members)]
    for i, (pid, d) in enumerate(members):
        dx, dy = offs[i]
        cx, cy = px(x) + dx, py(y) + dy
        o = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(cx - D / 2), Inches(cy - D / 2),
                               Inches(D), Inches(D))
        o.fill.solid(); o.fill.fore_color.rgb = DIRCOL[d]
        o.line.color.rgb = WHITE; o.line.width = Pt(1.2); o.shadow.inherit = False
        tf = o.text_frame; tf.word_wrap = False
        tf.margin_left = 0; tf.margin_right = 0; tf.margin_top = 0; tf.margin_bottom = 0
        tf.vertical_anchor = MSO_ANCHOR.MIDDLE
        pp = tf.paragraphs[0]; pp.alignment = PP_ALIGN.CENTER
        set_run(pp.add_run(), pid, 7.5, bold=True, color=WHITE)

# оси
ya = textbox(s, Inches(PL - 0.05), Inches(PT - 0.32), Inches(4), Inches(0.3))[1]
set_run(ya.paragraphs[0].add_run(), "↑ Квантовый потенциал", 10, bold=True, color=GRAY)
xa = textbox(s, Inches(PL), Inches(PT + PH + 0.08), Inches(PW), Inches(0.3))[1]
xa.paragraphs[0].alignment = PP_ALIGN.CENTER
set_run(xa.paragraphs[0].add_run(), "Бизнес-ценность / реализуемость →", 10, bold=True, color=GRAY)

# легенда
lx = 9.75
lg = textbox(s, Inches(lx), Inches(2.0), Inches(2.95), Inches(0.35))[1]
set_run(lg.paragraphs[0].add_run(), "Направления", 12.5, bold=True, color=DARK_BLUE)
for i, (name, col) in enumerate(DIRCOL.items()):
    yy = 2.5 + i * 0.42
    dot = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(lx), Inches(yy), Inches(0.22), Inches(0.22))
    dot.fill.solid(); dot.fill.fore_color.rgb = col; dot.line.fill.background()
    dot.shadow.inherit = False
    tb2 = textbox(s, Inches(lx + 0.32), Inches(yy - 0.02), Inches(2.6), Inches(0.3))[1]
    set_run(tb2.paragraphs[0].add_run(), name, 11, color=TEXT)
# пояснение
note = box_titled(s, lx, 4.35, 2.95, 2.45,
                  "Как читать",
                  ["Каждая точка — 1 кейс (ID из CSV)",
                   "Положение = баллы движка (X×Y)",
                   "Точки в одной ячейке разнесены для читаемости",
                   "Квадрант = вердикт приоритизации"],
                  fill=WHITE, accent=DARK_BLUE, title_size=12, body_size=9.8)

out = "/home/user/Claude/quantum-service-competitor-analysis/Анализ-конкурентов-Q-Scope.pptx"
prs.save(out)
print("Saved:", out)
print("Slides:", len(prs.slides.__iter__.__self__._sldIdLst))
