"""
저축 성향 분석 + 성향 맞춤 추천 (코드로 계산 — AI 프롬프트에 맡기지 않음)

1) 행동 기록(조회·비교·링크·등록·알림 반응)에서 7가지 지표를 계산한다
   저축은행 비중 / 금리 수준(시장 대비 백분위) / 우대조건 의존도 / 단기 선호 / 장기 선호 / 탐색 폭 / 갈아타기 적극성
2) 지표로 4가지 성향 점수(0~100)를 만들고 가장 높은 것을 주 성향으로 정한다
     안정형        : 1금융권 · 조건 단순 · 기본금리 중시
     수익추구형    : 시장 상위 금리 · 저축은행 · 넓게 탐색 (적금 안에서의 '공격형')
     우대조건 활용형 : 기본금리는 낮아도 우대조건으로 최고금리를 노림
     단기 유동형   : 6~12개월 짧은 상품, 돈을 오래 묶지 않음
3) 근거 문장, 신뢰도, 내가 말한 성향과의 차이, 시간에 따른 변화를 DB에 저장한다
4) 추천: 상품마다 '성향 적합도'를 계산해 순위를 매기고, 어떤 지표 때문에 맞는지 이유를 붙인다
"""
from __future__ import annotations

import bisect
import json
import math
import time
from typing import Any

TYPES = {"STABLE": "안정형", "YIELD": "수익추구형", "PERK": "우대조건 활용형", "SHORT": "단기 유동형"}
TYPE_DESC = {
    "STABLE": "1금융권의 조건 단순한 상품을 선호해요. 기본금리로 확실하게 받는 걸 중시해요.",
    "YIELD": "금리가 높은 상품을 찾아 저축은행까지 넓게 비교해요. 적금 안에서 가장 공격적인 성향이에요.",
    "PERK": "기본금리보다 우대조건을 채워 최고금리를 받는 상품에 관심이 많아요.",
    "SHORT": "6~12개월 짧은 상품을 주로 봐요. 돈을 오래 묶어두지 않으려는 성향이에요.",
}
POSITIVE = {"VIEW": 1.0, "COMPARE": 2.0, "LINK": 3.0, "REGISTER": 5.0, "REC_CLICK": 2.0}
HALF_LIFE_DAYS = 30.0
MIN_EVENTS = 5          # 이보다 적으면 "분석하기엔 기록이 부족"


def is_savings_bank(bank: str) -> bool:
    return "저축은행" in (bank or "")


def _pct(sorted_rates: list[float], r: float) -> float:
    """시장 같은 기간 상품들 중 이 금리가 상위 몇 %인지 (0=최저, 1=최고)"""
    if not sorted_rates:
        return max(0.0, min(1.0, (r - 2.5) / 2.5))   # 시장 데이터가 없으면 2.5%~5% 구간으로 근사
    i = bisect.bisect_left(sorted_rates, r)
    j = bisect.bisect_right(sorted_rates, r)
    return ((i + j) / 2) / len(sorted_rates)


# ---------------------------------------------------------------- 1) 지표
def features(events: list[dict], market: dict[int, list[float]], now: float | None = None) -> dict:
    now = now or time.time()
    W = sb = rate = cond = short = long_ = 0.0
    products, banks = set(), set()
    for e in events:
        base_w = POSITIVE.get(e.get("type", ""))
        if not base_w or not e.get("code"):
            continue
        w = base_w * math.pow(0.5, (now - float(e["at"])) / 86400 / HALF_LIFE_DAYS)
        term = int(e.get("term") or 0)
        base = float(e.get("rate") or 0)
        mx = float(e.get("max_rate") or 0) or base
        W += w
        sb += w * is_savings_bank(e.get("bank", ""))
        rate += w * _pct(market.get(term, []), base)
        cond += w * min(max(mx - base, 0) / 3.0, 1.0)       # 우대 폭 3%p 이상이면 최대
        short += w * (0 < term <= 12)
        long_ += w * (term >= 24)
        products.add((e["code"], term)); banks.add(e.get("bank", ""))
    acc = sum(1 for e in events if e.get("type") == "ALERT_ACCEPT")
    ign = sum(1 for e in events if e.get("type") == "ALERT_IGNORE")
    n = sum(1 for e in events if e.get("type") in POSITIVE and e.get("code"))
    if W == 0:
        return {"n": 0, "weight": 0}
    return {
        "n": n,
        "weight": round(W, 2),
        "savings_bank_share": round(sb / W, 3),
        "rate_percentile": round(rate / W, 3),
        "condition_dependence": round(cond / W, 3),
        "short_share": round(short / W, 3),
        "long_share": round(long_ / W, 3),
        "explore_breadth": round(min(len(banks) / 6.0, 1.0), 3),     # 6개 은행 이상 보면 최대
        "switch_activeness": round((acc + 0.5) / (acc + ign + 1.0), 3),  # 알림 확인 비율 (기록 없으면 0.5)
        "distinct_products": len(products),
        "distinct_banks": len(banks),
    }


# ---------------------------------------------------------------- 2) 성향 점수
def type_scores(f: dict) -> dict[str, float]:
    sb, rp, cd = f["savings_bank_share"], f["rate_percentile"], f["condition_dependence"]
    sh, lg, ex, sw = f["short_share"], f["long_share"], f["explore_breadth"], f["switch_activeness"]
    s = {
        "STABLE": 0.40 * (1 - sb) + 0.30 * (1 - cd) + 0.15 * (1 - rp) + 0.15 * lg,
        "YIELD": 0.40 * rp + 0.30 * sb + 0.15 * ex + 0.15 * sw,
        "PERK": 0.60 * cd + 0.25 * (1 - sb) + 0.15 * sw,
        "SHORT": 0.75 * sh + 0.25 * (1 - lg),
    }
    return {k: round(v * 100, 1) for k, v in s.items()}


def evidence(f: dict) -> list[str]:
    """성향 판단의 근거를 숫자 그대로 문장으로 (AI가 지어낸 말이 아님)"""
    out = []
    out.append(f"최근 본 상품의 {round(f['savings_bank_share'] * 100)}%가 저축은행, "
               f"{round((1 - f['savings_bank_share']) * 100)}%가 1금융권 은행이에요.")
    top = round((1 - f["rate_percentile"]) * 100)
    out.append(f"본 상품들의 기본금리는 같은 기간 시장 상품 중 평균 상위 {max(top, 1)}% 수준이에요.")
    out.append(f"우대조건 의존도 {round(f['condition_dependence'] * 100)}점 "
               f"(기본금리와 최고금리 차이가 큰 상품을 볼수록 높아요).")
    out.append(f"12개월 이하 상품 비중 {round(f['short_share'] * 100)}%, 24개월 이상 {round(f['long_share'] * 100)}%예요.")
    out.append(f"서로 다른 은행 {f['distinct_banks']}곳, 상품 {f['distinct_products']}개를 살펴봤어요.")
    return out


def confidence(f: dict, scores: dict[str, float]) -> tuple[float, str]:
    if f.get("n", 0) == 0:
        return 0.0, "없음"
    vals = sorted(scores.values(), reverse=True)
    margin = vals[0] - vals[1]
    c = min(1.0, f["n"] / 20.0) * min(1.0, 0.4 + margin / 20.0)
    label = "높음" if c >= 0.65 else "보통" if c >= 0.35 else "낮음"
    return round(c, 2), label


# ---------------------------------------------------------------- 3) 저장 / 조회
def ensure_tables(con) -> None:
    con.executescript("""
    CREATE TABLE IF NOT EXISTS market_stats(term INTEGER PRIMARY KEY, rates TEXT, updated_at REAL);
    CREATE TABLE IF NOT EXISTS profile_history(id INTEGER PRIMARY KEY AUTOINCREMENT, at REAL, type TEXT,
        scores TEXT, features TEXT, confidence REAL);
    CREATE TABLE IF NOT EXISTS profile_stated(id INTEGER PRIMARY KEY CHECK (id = 1), type TEXT, at REAL);
    """)
    cols = [r[1] for r in con.execute("PRAGMA table_info(events)")]
    if "max_rate" not in cols:
        con.execute("ALTER TABLE events ADD COLUMN max_rate REAL DEFAULT 0")


def save_market(con, products: list[Any]) -> None:
    """앱이 보낸 상품 목록으로 기간별 기본금리 분포를 저장 (금리 백분위 계산용)"""
    by: dict[int, list[float]] = {}
    for p in products:
        get = p.get if isinstance(p, dict) else (lambda k, d=None, _p=p: getattr(_p, k, d))
        if (get("kind", "적금") or "적금") != "적금":
            continue
        t = int(get("term", 0) or 0)
        r = get("base_rate", None)
        if t and r is not None:
            by.setdefault(t, []).append(float(r))
    for t, rates in by.items():
        if len(rates) >= 5:
            con.execute("INSERT OR REPLACE INTO market_stats VALUES(?,?,?)", (t, json.dumps(sorted(rates)), time.time()))


def load_market(con) -> dict[int, list[float]]:
    return {r[0]: json.loads(r[1]) for r in con.execute("SELECT term, rates FROM market_stats")}


def compute(con, save: bool = True) -> dict:
    ensure_tables(con)
    events = [dict(r) for r in con.execute("SELECT * FROM events")]
    market = load_market(con)
    f = features(events, market)
    stated_row = con.execute("SELECT type, at FROM profile_stated WHERE id=1").fetchone()
    stated = stated_row[0] if stated_row else None
    history = [{"at": r[0], "type": r[1], "label": TYPES.get(r[1], r[1])}
               for r in con.execute("SELECT at, type FROM profile_history ORDER BY at DESC LIMIT 10")]
    base = {"stated": stated, "stated_label": TYPES.get(stated) if stated else None, "history": history,
            "types": TYPES, "market_terms": sorted(market.keys())}
    if f.get("n", 0) < MIN_EVENTS:
        return {**base, "ready": False, "n": f.get("n", 0), "need": MIN_EVENTS,
                "message": f"상품을 {MIN_EVENTS - f.get('n', 0)}번 더 살펴보면 성향을 분석할 수 있어요."}
    scores = type_scores(f)
    primary = max(scores, key=scores.get)
    conf, conf_label = confidence(f, scores)
    conflict = None
    if stated and stated != primary and conf >= 0.35:
        conflict = (f"직접 고른 성향은 '{TYPES[stated]}'인데, 실제로 살펴본 상품은 '{TYPES[primary]}'에 더 가까워요. "
                    f"추천은 두 가지를 섞어서 계산해요.")
    if save:
        last = con.execute("SELECT type, at FROM profile_history ORDER BY at DESC LIMIT 1").fetchone()
        if last is None or last[0] != primary or time.time() - last[1] > 86400:
            con.execute("INSERT INTO profile_history(at,type,scores,features,confidence) VALUES(?,?,?,?,?)",
                        (time.time(), primary, json.dumps(scores), json.dumps(f), conf))
            con.commit()
            base["history"] = [{"at": time.time(), "type": primary, "label": TYPES[primary]}] + history
    return {**base, "ready": True, "n": f["n"], "type": primary, "label": TYPES[primary], "description": TYPE_DESC[primary],
            "scores": scores, "features": f, "evidence": evidence(f), "confidence": conf, "confidence_label": conf_label,
            "conflict": conflict}


# ---------------------------------------------------------------- 4) 추천
def _blend(profile: dict) -> dict[str, float]:
    """성향 점수를 가중치로 (직접 고른 성향이 있으면 40% 섞음)"""
    if not profile.get("ready"):
        w = {k: 0.0 for k in TYPES}
        w[profile.get("stated") or "STABLE"] = 1.0
        return w
    sc = profile["scores"]
    sq = {k: v * v for k, v in sc.items()}          # 제곱: 주 성향이 추천을 더 강하게 이끌도록
    tot = sum(sq.values()) or 1
    w = {k: v / tot for k, v in sq.items()}
    st = profile.get("stated")
    if st:
        w = {k: 0.6 * v + (0.4 if k == st else 0) for k, v in w.items()}
    return w


def recommend(products: list[dict], profile: dict, interests: list[dict] | None = None,
              held_codes: set[str] | None = None, limit: int = 8, term: int | None = None) -> list[dict]:
    held_codes = held_codes or set()
    liked_banks = {i.get("bank") for i in (interests or [])[:5]}
    liked_terms = {i.get("term") for i in (interests or [])[:5]}
    sav = [p for p in products if (p.get("kind") or "적금") == "적금" and not p.get("restricted")
           and (term is None or int(p.get("term", 0)) == term)]
    by_term_base: dict[int, list[float]] = {}
    by_term_max: dict[int, list[float]] = {}
    for p in sav:
        by_term_base.setdefault(int(p["term"]), []).append(float(p["base_rate"]))
        by_term_max.setdefault(int(p["term"]), []).append(float(p["max_rate"]))
    for d in (by_term_base, by_term_max):
        for k in d:
            d[k].sort()
    w = _blend(profile)
    out, seen = [], set()
    for p in sav:
        key = (p["code"], int(p["term"]))
        if key in seen:
            continue
        seen.add(key)
        t = int(p["term"])
        sb = 1.0 if is_savings_bank(p.get("bank", "")) else 0.0
        rp = _pct(by_term_base[t], float(p["base_rate"]))
        mp = _pct(by_term_max[t], float(p["max_rate"]))
        spread = min(max(float(p["max_rate"]) - float(p["base_rate"]), 0) / 3.0, 1.0)
        short = 1.0 if t <= 12 else 0.0
        fit_by_type = {
            "STABLE": 0.40 * (1 - sb) + 0.35 * (1 - spread) + 0.25 * rp,
            "YIELD": 0.65 * rp + 0.20 * mp + 0.15 * sb,
            "PERK": 0.55 * spread + 0.45 * mp,
            "SHORT": 0.65 * short + 0.35 * rp,
        }
        fit = sum(w[k] * fit_by_type[k] for k in TYPES)
        bonus, reasons = 0.0, []
        if p.get("bank") in liked_banks:
            bonus += 0.04; reasons.append("관심 있게 본 은행")
        if t in liked_terms:
            bonus += 0.03; reasons.append(f"자주 본 {t}개월 상품")
        if p["code"] in held_codes:
            bonus -= 0.15; reasons.append("이미 가입한 상품")
        # 이 상품이 내 성향에 맞는 이유 = 가중치 × 적합도 기여가 큰 지표
        contrib = []
        if w["STABLE"] > 0.15:
            if sb == 0: contrib.append((w["STABLE"] * 0.40, "1금융권 은행"))
            if spread < 0.2: contrib.append((w["STABLE"] * 0.35, "우대조건 없이도 금리가 거의 그대로"))
        if w["YIELD"] > 0.15 and rp >= 0.7:
            contrib.append((w["YIELD"] * 0.65 * rp, f"기본금리 시장 상위 {max(1, round((1 - rp) * 100))}%"))
        if w["PERK"] > 0.15 and spread >= 0.4:
            contrib.append((w["PERK"] * 0.55 * spread, f"우대 받으면 +{float(p['max_rate']) - float(p['base_rate']):.1f}%p"))
        if w["SHORT"] > 0.15 and short:
            contrib.append((w["SHORT"] * 0.65, f"{t}개월로 짧게 묶임"))
        contrib.sort(reverse=True)
        reasons = [c[1] for c in contrib[:3]] + reasons
        out.append({**{k: p.get(k) for k in ("code", "bank", "name", "term", "base_rate", "max_rate", "monthly_cap", "rsrv")},
                    "fit": round(max(0.0, min(1.0, fit + bonus)) * 100, 1), "reasons": reasons})
    out.sort(key=lambda x: -x["fit"])
    return out[:limit]
