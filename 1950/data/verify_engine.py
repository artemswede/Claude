# -*- coding: utf-8 -*-
"""
Проверка таблицы истинности вердикта (фикс A3) на 16 кейсах из scenarios.csv.
Проверяем слой РЕШЕНИЯ: (gates + X + Y) → вердикт. Берём из CSV колонки gate/X/Y и сверяем
предсказание движка с колонкой ВЕРДИКТ. (Слой скоринга answers→X/Y проверяется тестами Шага 5.)
"""
import csv

CSV = "/home/user/Claude/1950/data/scenarios.csv"

def norm_verdict(v):
    v = v.strip()
    if v.startswith("PoC сейчас"):
        return "PoC сейчас"      # 'research-grade' схлопываем в PoC
    return v

def is_fail(g):
    # жёсткий FAIL только при "Fail"; "Частично"/"Borderline" считаем мягким PASS (см. A3)
    return g.strip().lower() == "fail"

def predict(g1, g2, g3, x, y):
    if is_fail(g1) or is_fail(g2) or is_fail(g3):
        return "Классический солвер" if x >= 3 else "Отбросить"
    # все gate PASS (с учётом мягких)
    return "PoC сейчас" if (y >= 3 and x >= 3) else "Watchlist"

rows = list(csv.DictReader(open(CSV, encoding="utf-8-sig"), delimiter=";"))
ok = 0; bad = []
for r in rows:
    g1 = r["Gate G1: классика провисает"]; g2 = r["Gate G2: формализуемо (QUBO/Hamiltonian)"]
    g3 = r["Gate G3: нет I/O-bottleneck"]
    x = int(r["Балл бизнес-ценность (X, 0-5)"]); y = int(r["Балл квантовый потенциал (Y, 0-5)"])
    exp = norm_verdict(r["ВЕРДИКТ (квадрант)"])
    pred = predict(g1, g2, g3, x, y)
    if pred == exp:
        ok += 1
    else:
        bad.append((r["ID"], exp, pred, g1, g2, g3, x, y))

print(f"Совпало: {ok}/{len(rows)}")
if bad:
    print("Расхождения:")
    for b in bad:
        print("  ", b)
else:
    print("✓ Таблица истинности вердикта воспроизводит все 16 кейсов CSV.")
