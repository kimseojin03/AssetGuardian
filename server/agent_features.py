"""
에이전트 기능 5종 (모두 같은 기억 DB를 쓴다)

  1) 우대조건 체크리스트   : 우대조건 문장 → AI가 할 일로 바꿈 → 매달 챙길 날짜 계산 → 앱이 알림
  2) 목표 추적 + 재계획     : 매달 실제 저축액 기록 → 계획 대비 앞섬/뒤처짐 계산 → 늦어지면 대안(저축액 조정·재계획) 제시
  3) 대화형 상담           : 질문에 맞춰 AI가 모든 도구(기억·성향·추천·검색·루트·갈아타기·목표·할 일) 중 필요한 걸 골라 씀
  4) 추천 피드백 학습       : 추천을 보여줬는데 안 누른 유형은 점수를 낮추고, 누른 유형은 올림
  5) 주간 리포트           : 목표 진행 · 새로 나온 맞춤 상품 · 이번 주 할 일 · 다가오는 만기 · 성향 변화를 매주 정리
"""
from __future__ import annotations

import calendar
import json
import math
import re
import time
from datetime import date, datetime
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


def ensure_tables(con) -> None:
    con.executescript("""
    CREATE TABLE IF NOT EXISTS tasks(
        id INTEGER PRIMARY KEY AUTOINCREMENT, holding_id TEXT, product TEXT, title TEXT, detail TEXT,
        cadence TEXT, due_day INTEGER, next_due REAL, done_count INTEGER DEFAULT 0, last_done REAL,
        active INTEGER DEFAULT 1, source TEXT, created_at REAL);
    CREATE TABLE IF NOT EXISTS goal(
        id INTEGER PRIMARY KEY CHECK (id = 1), target INTEGER, monthly INTEGER, term INTEGER,
        start_month TEXT, months_needed INTEGER, slots TEXT, deposit TEXT, created_at REAL);
    CREATE TABLE IF NOT EXISTS progress(month TEXT PRIMARY KEY, actual INTEGER, at REAL);
    CREATE TABLE IF NOT EXISTS chat(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, trace TEXT, at REAL);
    CREATE TABLE IF NOT EXISTS seen_products(key TEXT PRIMARY KEY, first_seen REAL);
    CREATE TABLE IF NOT EXISTS reports(id INTEGER PRIMARY KEY AUTOINCREMENT, at REAL, body TEXT);
    """)
    cols = [r[1] for r in con.execute("PRAGMA table_info(goal)")]
    if "carried" not in cols:   # 재계획 전까지 모은 돈 (재계획해도 원래 목표·모은 돈 유지)
        con.execute("ALTER TABLE goal ADD COLUMN carried INTEGER DEFAULT 0")


def _db():
    import memory
    con = memory.db()
    ensure_tables(con)
    return con


def _month(d: date) -> str:
    return f"{d.year:04d}-{d.month:02d}"


def _add_months(ym: str, n: int) -> str:
    y, m = map(int, ym.split("-"))
    m0 = y * 12 + (m - 1) + n
    return f"{m0 // 12:04d}-{m0 % 12 + 1:02d}"


def _months_between(a: str, b: str) -> int:
    ya, ma = map(int, a.split("-")); yb, mb = map(int, b.split("-"))
    return (yb * 12 + mb) - (ya * 12 + ma)


def _llm_text(prompt: str) -> str | None:
    """도구 없이 한 번 묻기 (설명 문장용). AI 키가 없으면 None"""
    try:
        import agent_server as A
        if A.provider() != "gemini":
            return None
        res, _ = A.gemini_generate({"contents": [{"role": "user", "parts": [{"text": prompt}]}]})
        parts = (res.get("candidates") or [{}])[0].get("content", {}).get("parts", [])
        return "".join(p.get("text", "") for p in parts).strip() or None
    except Exception:
        return None


# ================================================================ 1) 우대조건 체크리스트
CADENCES = {"monthly", "once", "until_maturity"}


def next_due(cadence: str, due_day: int, now: float | None = None) -> float:
    now_d = datetime.fromtimestamp(now or time.time())
    if cadence == "once":
        return (now or time.time()) + 86400
    day = max(1, min(int(due_day or 25), 28))
    cand = now_d.replace(day=day, hour=9, minute=0, second=0, microsecond=0)
    if cand.timestamp() <= now_d.timestamp():
        y, m = (now_d.year + (now_d.month // 12), now_d.month % 12 + 1)
        cand = cand.replace(year=y, month=m)
    return cand.timestamp()


RULES = [  # AI가 없을 때 쓰는 규칙 (문장에 이 단어가 있으면 이런 할 일)
    (r"급여", "급여이체 실적 유지하기", "매달 급여(또는 50만원 이상 입금)가 이 은행 계좌로 들어오게 해야 우대금리를 받아요.", "monthly", 25),
    (r"카드", "카드 사용 실적 채우기", "이 은행 카드로 정해진 금액 이상 써야 해요. 금액은 우대조건 원문을 확인하세요.", "monthly", 25),
    (r"자동이체", "적금 자동이체 유지하기", "매달 자동이체로 납입해야 우대금리가 붙어요. 잔액 부족으로 빠지지 않게 확인하세요.", "monthly", 1),
    (r"마케팅|수신\s*동의", "마케팅 수신 동의 유지하기", "만기까지 마케팅 동의를 철회하면 우대금리가 빠질 수 있어요.", "until_maturity", 1),
    (r"첫\s*거래|신규\s*고객", "첫 거래 조건 확인하기", "가입 시점에 이 은행과 거래가 없어야 우대를 받아요. 가입 전에 확인하세요.", "once", 1),
    (r"오픈뱅킹", "오픈뱅킹 등록하기", "이 은행 앱에서 다른 은행 계좌를 오픈뱅킹으로 등록해야 해요.", "once", 1),
    (r"추천|친구", "친구 추천 우대 챙기기", "추천인 코드 입력 등 추천 조건을 확인하세요.", "once", 1),
    (r"청약", "주택청약 보유 조건 확인하기", "이 은행 주택청약 통장을 보유해야 우대가 붙는지 확인하세요.", "once", 1),
    (r"공과금|관리비|통신비", "공과금 자동이체 등록하기", "공과금·통신비 자동이체를 이 은행으로 걸어두고 매달 빠져나가는지 확인하세요.", "monthly", 20),
    (r"비대면|앱|스마트폰|모바일", "앱(비대면)으로 가입하기", "앱으로 가입해야 우대가 붙는 상품이에요.", "once", 1),
]


def rule_tasks(conditions: str) -> list[dict]:
    out, seen = [], set()
    for pat, title, detail, cad, day in RULES:
        if re.search(pat, conditions or "") and title not in seen:
            seen.add(title)
            m = re.search(r"(\d[\d,]*)\s*만\s*원", conditions) if "카드" in title else None
            d = detail if not m else f"이 은행 카드로 매달 {m.group(1)}만원 이상 써야 해요."
            out.append({"title": title, "detail": d, "cadence": cad, "due_day": day})
    return out


def ai_tasks(product: str, conditions: str, profile_text: str) -> list[dict] | None:
    prompt = (
        "다음은 적금 상품의 우대조건 원문이다. 사용자가 우대금리를 놓치지 않도록 '해야 할 일' 목록으로 바꿔라.\n"
        f"[상품] {product}\n[우대조건 원문]\n{conditions}\n[사용자 상황] {profile_text or '(없음)'}\n\n"
        "규칙: 사용자가 이미 할 수 없는 조건(나이 등)은 빼고, 실제 행동이 필요한 것만. 각 항목은 "
        '{"title": 20자 이내, "detail": 구체적 행동과 금액·횟수 1~2문장, "cadence": "monthly"|"once"|"until_maturity", '
        '"due_day": 매달 챙길 날(1~28)} 형식. JSON 배열만 출력.')
    txt = _llm_text(prompt)
    if not txt:
        return None
    m = re.search(r"\[.*\]", txt, re.S)
    if not m:
        return None
    try:
        items = json.loads(m.group(0))
    except Exception:
        return None
    out = []
    for it in items[:8]:
        if not isinstance(it, dict) or not it.get("title"):
            continue
        cad = it.get("cadence") if it.get("cadence") in CADENCES else "monthly"
        try:
            day = max(1, min(int(it.get("due_day", 25)), 28))
        except Exception:
            day = 25
        out.append({"title": str(it["title"])[:40], "detail": str(it.get("detail", ""))[:200], "cadence": cad, "due_day": day})
    return out or None


def create_checklist(holding: dict, conditions: str, profile_text: str = "") -> dict:
    product = f"{holding.get('bank', '')} {holding.get('name', '')}".strip()
    items = ai_tasks(product, conditions, profile_text)
    source = "ai"
    if items is None:
        items, source = rule_tasks(conditions), "rule"
    now = time.time()
    with _db() as con:
        con.execute("DELETE FROM tasks WHERE holding_id=?", (holding.get("id"),))
        for it in items:
            con.execute("INSERT INTO tasks(holding_id,product,title,detail,cadence,due_day,next_due,source,created_at)"
                        " VALUES(?,?,?,?,?,?,?,?,?)",
                        (holding.get("id"), product, it["title"], it["detail"], it["cadence"], it["due_day"],
                         next_due(it["cadence"], it["due_day"], now), source, now))
    return {"source": source, "tasks": list_tasks(holding.get("id"))}


def list_tasks(holding_id: str | None = None) -> list[dict]:
    with _db() as con:
        q = "SELECT * FROM tasks WHERE active=1" + (" AND holding_id=?" if holding_id else "") + " ORDER BY next_due"
        rows = con.execute(q, (holding_id,) if holding_id else ()).fetchall()
    return [dict(r) for r in rows]


def due_tasks(within_days: float = 1.0) -> list[dict]:
    lim = time.time() + within_days * 86400
    return [t for t in list_tasks() if t["next_due"] and t["next_due"] <= lim]


def task_done(tid: int) -> dict:
    with _db() as con:
        r = con.execute("SELECT * FROM tasks WHERE id=?", (tid,)).fetchone()
        if not r:
            return {"ok": False}
        if r["cadence"] == "once":
            con.execute("UPDATE tasks SET active=0, done_count=done_count+1, last_done=? WHERE id=?", (time.time(), tid))
        else:
            nd = next_due(r["cadence"], r["due_day"], max(time.time(), (r["next_due"] or 0)) + 3600)
            con.execute("UPDATE tasks SET done_count=done_count+1, last_done=?, next_due=? WHERE id=?", (time.time(), nd, tid))
    return {"ok": True}


def delete_task(tid: int) -> None:
    with _db() as con:
        con.execute("UPDATE tasks SET active=0 WHERE id=?", (tid,))


# ================================================================ 2) 목표 추적 + 재계획
def set_goal_from_plan(out: dict, replan: bool = False) -> None:
    """새 루트를 추적할 목표로 등록. replan=True면 원래 목표 금액과 지금까지 모은 돈을 이어간다."""
    plan = out.get("plan") or {}
    if not plan.get("months_needed"):
        return
    target, carried = out.get("target_won"), 0
    with _db() as con:
        if replan:
            g = con.execute("SELECT * FROM goal WHERE id=1").fetchone()
            if g:
                saved = con.execute("SELECT COALESCE(SUM(actual),0) FROM progress").fetchone()[0]
                carried = int(g["carried"] or 0) + int(saved)
                target = g["target"]
        con.execute("INSERT OR REPLACE INTO goal(id,target,monthly,term,start_month,months_needed,slots,deposit,created_at,carried)"
                    " VALUES(1,?,?,?,?,?,?,?,?,?)",
                    (target, out.get("monthly_won"), plan.get("term_months"), _month(date.today()),
                     plan.get("months_needed"), json.dumps(plan.get("slots") or [], ensure_ascii=False),
                     json.dumps(plan.get("deposit"), ensure_ascii=False), time.time(), carried))
        con.execute("DELETE FROM progress")


def record_progress(month: str, actual: int) -> dict:
    if not re.fullmatch(r"\d{4}-\d{2}", month or ""):
        return {"error": "month는 YYYY-MM 형식"}
    with _db() as con:
        con.execute("INSERT OR REPLACE INTO progress VALUES(?,?,?)", (month, int(actual), time.time()))
    return goal_status()


def _sim(goal: dict, monthly: int, initial: float, tax: float = 15.4, parking: float = 2.5) -> int | None:
    import plan_agent as PA
    slots = [s for s in json.loads(goal["slots"] or "[]") if s.get("code") != "PARKING"]
    chosen = [{"code": s["code"], "bank": s.get("bank"), "name": s.get("name"), "rate": s["rate"], "cap": s.get("cap")} for s in slots]
    if not chosen or monthly <= 0:
        return None
    dep = json.loads(goal["deposit"]) if goal.get("deposit") else None
    r = PA.simulate(int(goal["target"]), int(monthly), int(goal["term"]), chosen, dep, tax, parking, initial=initial)
    return r["months_needed"] if initial < goal["target"] else 0


def goal_status() -> dict:
    with _db() as con:
        g = con.execute("SELECT * FROM goal WHERE id=1").fetchone()
        prog = [dict(r) for r in con.execute("SELECT month, actual FROM progress ORDER BY month")]
    if not g:
        return {"active": False, "message": "AI 목표 루트를 한 번 짜면 그 루트를 목표로 추적해요."}
    g = dict(g)
    cur = _month(date.today())
    elapsed = max(_months_between(g["start_month"], cur), len(prog))   # 지난 달 수 (이번 달은 진행 중)
    carried = int(g.get("carried") or 0)                                # 재계획 전까지 모은 돈
    planned_total = carried + g["monthly"] * len(prog)
    actual_total = carried + sum(p["actual"] for p in prog)
    gap = actual_total - planned_total
    recent = [p["actual"] for p in prog[-3:]]
    pace = round(sum(recent) / len(recent)) if recent else g["monthly"]
    orig_left = max(g["months_needed"] - len(prog), 0)
    left_now = _sim(g, pace, actual_total)
    left_plan = _sim(g, g["monthly"], actual_total)
    delay = (left_now - orig_left) if (left_now is not None) else None

    options = []
    if left_now is not None:
        options.append({"kind": "keep", "title": f"지금 속도(월 {pace:,}원) 그대로",
                        "months_left": left_now, "monthly": pace,
                        "text": f"앞으로 {left_now}개월 → 원래 계획보다 " +
                                (f"{delay}개월 늦어요" if delay and delay > 0 else f"{-delay}개월 빨라요" if delay and delay < 0 else "같아요")})
    if left_now is not None and delay and delay > 0:
        hi = None
        cand = (pace // 10_000 + 1) * 10_000
        while cand <= max(pace, g["monthly"]) * 3:       # 원래 날짜를 맞추는 최소 월 저축액 (1만원 단위로 올려보며 계산)
            m = _sim(g, cand, actual_total)
            if m is not None and m <= orig_left:
                hi = cand
                break
            cand += 10_000
        hi = hi or max(pace, g["monthly"]) * 3
        options.append({"kind": "catch_up", "title": f"월 {hi:,}원으로 늘리기", "months_left": orig_left, "monthly": hi,
                        "text": f"원래 날짜({orig_left}개월 뒤)에 맞추려면 매달 {hi - pace:,}원 더 저축해야 해요"})
        options.append({"kind": "replan", "title": "AI에게 루트 다시 짜달라기", "months_left": None, "monthly": pace,
                        "text": "지금 저축 속도와 모아둔 돈을 기준으로 상품 조합부터 다시 찾아요"})
    base = max(planned_total - carried, 1)
    status = "no_data" if not prog else ("behind" if gap < -0.1 * base else "ahead" if gap > 0.1 * base else "on_track")
    return {
        "active": True, "target": g["target"], "monthly_plan": g["monthly"], "term": g["term"], "start_month": g["start_month"],
        "months_needed_plan": g["months_needed"], "months_recorded": len(prog), "months_elapsed": elapsed,
        "planned_total": planned_total, "actual_total": actual_total, "gap": gap, "carried": carried,
        "progress_pct": round(actual_total / g["target"] * 100, 1) if g["target"] else 0,
        "status": status, "pace": pace, "months_left_plan": left_plan, "months_left_now": left_now,
        "delay_months": delay, "options": options, "records": prog,
        "next_record_month": _add_months(prog[-1]["month"], 1) if prog else _add_months(cur, -1) if cur != g["start_month"] else cur,
    }


# ================================================================ 4) 추천 피드백 학습
ATTRS = {
    "sb": ("저축은행 상품", lambda p: "저축은행" in (p.get("bank") or "")),
    "short": ("12개월 이하 상품", lambda p: 0 < int(p.get("term") or 0) <= 12),
    "perk": ("우대조건 의존 상품", lambda p: float(p.get("max_rate") or 0) - float(p.get("rate") or p.get("base_rate") or 0) >= 1.0),
}


def learned_factors(con) -> tuple[dict, list[str]]:
    """추천 노출(REC_SHOWN) 대비 클릭·링크·등록 비율을 유형별로 비교해 추천 점수 보정값을 만든다"""
    shown = [dict(r) for r in con.execute("SELECT * FROM events WHERE type='REC_SHOWN'")]
    if len(shown) < 6:
        return {}, []
    acted = {(r["code"], r["term"]) for r in con.execute(
        "SELECT code, term FROM events WHERE type IN ('REC_CLICK','LINK','REGISTER','COMPARE')")}
    S = len(shown)
    C = sum((e["code"], e["term"]) in acted for e in shown)
    base_ctr = (C + 1) / (S + 2)
    factors, notes = {}, []
    for key, (label, fn) in ATTRS.items():
        for val in (True, False):
            grp = [e for e in shown if fn(e) == val]
            if len(grp) < 4:
                continue
            c = sum((e["code"], e["term"]) in acted for e in grp)
            ctr = (c + 1) / (len(grp) + 2)
            f = max(0.8, min(1.2, math.sqrt(ctr / base_ctr)))
            if abs(f - 1) >= 0.05:
                factors[(key, val)] = f
                name = label if val else label.replace("저축은행", "1금융권").replace("12개월 이하", "24개월 이상").replace("우대조건 의존", "조건 단순")
                notes.append(f"추천한 {name} {len(grp)}개 중 {c}개를 눌러봐서 이 유형 점수를 ×{f:.2f} 했어요")
    return factors, notes


def apply_learning(items: list[dict], factors: dict) -> list[dict]:
    if not factors:
        return items
    for it in items:
        mult = 1.0
        for key, (_, fn) in ATTRS.items():
            mult *= factors.get((key, bool(fn(it))), 1.0)
        if abs(mult - 1) >= 0.01:
            it["fit"] = round(min(100.0, it["fit"] * mult), 1)
            it.setdefault("reasons", []).append(("내 반응으로 점수 올림" if mult > 1 else "내 반응으로 점수 낮춤") + f" ×{mult:.2f}")
    items.sort(key=lambda x: -x["fit"])
    return items


# ================================================================ 5) 주간 리포트
def weekly_report(products: list[dict]) -> dict:
    import memory
    now = time.time()
    with _db() as con:
        known = {r[0] for r in con.execute("SELECT key FROM seen_products")}
        first_run = len(known) == 0
        new_keys = []
        for p in products:
            if (p.get("kind") or "적금") != "적금":
                continue
            k = f"{p.get('code')}|{p.get('term')}"
            if k not in known:
                new_keys.append(k)
                con.execute("INSERT OR IGNORE INTO seen_products VALUES(?,?)", (k, now))
        holds = [dict(r) for r in con.execute("SELECT * FROM holdings")]
    rec = memory.recommend(products, None, 30)
    new_fit = [] if first_run else [i for i in rec["items"] if f"{i['code']}|{i['term']}" in set(new_keys) and i["fit"] >= 60][:3]
    tasks_week = due_tasks(7)
    maturities = []
    for h in holds:
        try:
            jd = datetime.strptime(h["join_date"], "%Y-%m-%d").date()
            y, m = divmod(jd.month - 1 + int(h["term"]), 12)
            md = date(jd.year + y, m + 1, min(jd.day, calendar.monthrange(jd.year + y, m + 1)[1]))
            days = (md - date.today()).days
            if 0 <= days <= 30:
                maturities.append({"name": f"{h['bank']} {h['name']}", "days": days, "date": md.isoformat()})
        except Exception:
            pass
    prof = rec["profile"]
    hist = prof.get("history") or []
    week_ago = [h for h in hist if h["at"] <= now - 6 * 86400]
    changed = (week_ago and prof.get("ready") and week_ago[0]["type"] != prof.get("type"))
    gs = goal_status()
    body = {
        "at": now,
        "goal": gs if gs.get("active") else None,
        "new_products": new_fit,
        "new_count": 0 if first_run else len(new_keys),
        "first_run": first_run,
        "tasks": [{"title": t["title"], "product": t["product"], "next_due": t["next_due"]} for t in tasks_week],
        "maturities": maturities,
        "profile": {"label": prof.get("label"), "confidence": prof.get("confidence_label"),
                    "changed_from": week_ago[0]["label"] if changed else None} if prof.get("ready") else None,
    }
    lines = []
    if body["goal"]:
        g = body["goal"]
        lines.append(f"목표 {g['target']:,}원 중 {g['actual_total']:,}원({g['progress_pct']}%) 모음, 상태 {g['status']}")
    lines.append(f"새로 나온 적금 {body['new_count']}개 중 성향에 맞는 것 {len(new_fit)}개")
    lines.append(f"이번 주 챙길 우대조건 {len(tasks_week)}개, 30일 안 만기 {len(maturities)}개")
    if body["profile"]:
        lines.append(f"저축 성향 {body['profile']['label']}" + (f" (지난주 {body['profile']['changed_from']}에서 바뀜)" if changed else ""))
    body["summary_lines"] = lines
    body["ai_summary"] = _llm_text("다음 주간 저축 리포트를 사용자에게 2~3문장 ~요체로 요약해. 새 숫자를 만들지 마.\n" + "\n".join(lines))
    with _db() as con:
        con.execute("INSERT INTO reports(at, body) VALUES(?,?)", (now, json.dumps(body, ensure_ascii=False)))
    return body


def latest_report() -> dict | None:
    with _db() as con:
        r = con.execute("SELECT body FROM reports ORDER BY at DESC LIMIT 1").fetchone()
    return json.loads(r[0]) if r else None


# ================================================================ 3) 대화형 상담
CHAT_SYSTEM = """너는 '자산지킴이' 앱의 저축 상담 에이전트다. 사용자의 적금·목표·성향을 기억하고 있으며, 질문에 답하기 위해 도구를 골라 쓴다.
규칙:
- 처음 질문을 받으면 필요한 경우 get_user_memory / get_saving_profile / get_goal_status 로 사용자를 먼저 파악한다.
- 숫자(이자, 손익, 기간)는 절대 직접 계산하지 말고 calc_switch, simulate_plan 같은 도구 결과만 말한다.
- 상품을 추천할 땐 recommend_products(성향 맞춤)를 우선 쓰고, 금리만 볼 땐 search_products.
- 사용자가 "이번 달 50만원 넣었어"처럼 저축 실적을 말하면 record_progress로 기록한다.
- 새로 알게 된 사실은 save_memory, 사용자가 성향을 말하면 set_stated_preference.
- 우대조건 챙길 일을 물으면 get_tasks.
- 답은 4~6문장 이내 ~요체. 도구 결과의 숫자를 근거로 말하고, 정보 제공이며 결정은 사용자가 한다는 점을 지킨다."""


def chat_tools() -> list[dict]:
    import plan_agent as PA
    keep = {"get_user_memory", "get_saving_profile", "recommend_products", "search_products", "simulate_plan",
            "save_memory", "set_stated_preference"}
    tools = [t for t in PA.TOOLS if t["name"] in keep]
    tools += [
        {"name": "calc_switch",
         "description": "내가 가진 적금(holding_id)을 지금 해지하고 다른 상품(code, 기간)으로 갈아탈 때 세후 해지 손실·전환 이익·순이익(원)을 계산한다.",
         "input_schema": {"type": "object", "properties": {
             "holding_id": {"type": "string", "description": "get_user_memory의 my_savings에 있는 id"},
             "new_rate": {"type": "number", "description": "새 상품에서 받을 연 금리(%)"},
             "new_monthly_cap": {"type": "integer", "description": "새 상품 월 납입 한도(원). 상품 정보에 monthly_cap이 있으면 꼭 넣는다"}},
             "required": ["holding_id", "new_rate"]}},
        {"name": "get_goal_status",
         "description": "추적 중인 목표의 진행률, 계획 대비 앞섬/뒤처짐, 남은 기간, 대안(저축액 조정 등)을 불러온다.",
         "input_schema": {"type": "object", "properties": {}, "required": []}},
        {"name": "record_progress",
         "description": "사용자가 말한 월별 실제 저축액을 기록한다. month는 YYYY-MM.",
         "input_schema": {"type": "object", "properties": {
             "month": {"type": "string"}, "actual_won": {"type": "integer"}}, "required": ["month", "actual_won"]}},
        {"name": "get_tasks",
         "description": "우대조건을 지키기 위해 챙겨야 할 일 목록(체크리스트)과 다음 기한을 불러온다.",
         "input_schema": {"type": "object", "properties": {}, "required": []}},
    ]
    return tools


class ChatToolbox:
    def __init__(self, req: "ChatIn"):
        import plan_agent as PA
        self.pa = PA.Toolbox(PA.PlanIn(products=req.products, tax_rate=req.tax_rate, parking_rate=req.parking_rate))
        self.req = req
        self.trace = self.pa.trace
        self.calls = self.pa.calls

    def run(self, name: str, args: dict) -> dict:
        import plan_agent as PA
        if name in {"get_user_memory", "get_saving_profile", "recommend_products", "search_products", "simulate_plan",
                    "save_memory", "set_stated_preference"}:
            return PA._execute(self.pa, name, args)
        self.calls.append(name)
        try:
            if name == "calc_switch":
                import agent_server as A
                import memory
                with memory.db() as con:
                    h = con.execute("SELECT * FROM holdings WHERE id=?", (str(args.get("holding_id")),)).fetchone()
                if not h:
                    return {"error": "그 id의 적금이 없어요. get_user_memory의 my_savings를 확인하세요."}
                h = dict(h)
                try:
                    jd = datetime.strptime(h["join_date"], "%Y-%m-%d").date()
                    el = max(0, min((date.today().year - jd.year) * 12 + date.today().month - jd.month, int(h["term"])))
                except Exception:
                    el = 0
                cap = args.get("new_monthly_cap")
                r = A.calc_switch(int(h["monthly"]), float(h["final_rate"]), float(h["base_rate"]), int(h["term"]), el,
                                  float(args.get("new_rate", 0)), self.req.parking_rate, self.req.tax_rate,
                                  new_cap=int(cap) if cap else None)
                if A.is_policy(h["name"]):
                    r["warning"] = ("정부 지원·비과세 상품이라 중도해지하면 비과세 혜택·정부기여금을 잃을 수 있고, "
                                    "이 손실은 계산에 없음. 갈아타기를 권하지 말고 특별중도해지 사유를 확인하라고 안내할 것")
                self.trace.append(f"갈아타기 계산: {h['name']} {el}개월 차 → 순이익 {r['net_won']:,}원"
                                  + (" (정책 상품 경고)" if r.get("warning") else ""))
                return {**r, "elapsed_months": el, "holding": h["name"]}
            if name == "get_goal_status":
                g = goal_status()
                self.trace.append("목표 진행 확인" + (f" → {g['progress_pct']}%, 상태 {g['status']}" if g.get("active") else " → 목표 없음"))
                return {k: v for k, v in g.items() if k != "records"}
            if name == "record_progress":
                g = record_progress(str(args.get("month")), int(args.get("actual_won", 0)))
                self.trace.append(f"저축 실적 기록: {args.get('month')} {int(args.get('actual_won', 0)):,}원")
                return {k: v for k, v in g.items() if k != "records"}
            if name == "get_tasks":
                ts = list_tasks()
                self.trace.append(f"우대조건 할 일 {len(ts)}개 확인")
                return {"tasks": [{"title": t["title"], "product": t["product"], "detail": t["detail"],
                                   "next_due": datetime.fromtimestamp(t["next_due"]).strftime("%Y-%m-%d")} for t in ts]}
        except Exception as e:
            return {"error": f"{type(e).__name__}: {e}"}
        return {"error": f"알 수 없는 도구 {name}"}


def chat_history(limit: int = 30) -> list[dict]:
    with _db() as con:
        rows = con.execute("SELECT * FROM chat ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    out = [dict(r) for r in reversed(rows)]
    for r in out:
        r["trace"] = json.loads(r["trace"] or "[]")
    return out


def chat(req: "ChatIn") -> dict:
    import agent_server as A
    import memory
    if req.holdings is not None:
        memory.set_holdings(req.holdings)
    memory.save_market([p.model_dump() if hasattr(p, "model_dump") else p.dict() for p in req.products])
    history = chat_history(12)
    with _db() as con:
        con.execute("INSERT INTO chat(role,text,trace,at) VALUES('user',?,?,?)", (req.message, "[]", time.time()))
    tb = ChatToolbox(req)
    p = A.provider()
    reply, mode = None, ""
    if p == "gemini":
        mode = "Gemini 상담 에이전트"
        contents: list[dict[str, Any]] = [{"role": "user" if h["role"] == "user" else "model", "parts": [{"text": h["text"]}]}
                                          for h in history]
        contents.append({"role": "user", "parts": [{"text": f"[오늘 {date.today().isoformat()}] {req.message}"}]})
        decls = [{"name": t["name"], "description": t["description"],
                  **({"parameters": t["input_schema"]} if t["input_schema"].get("properties") else {})} for t in chat_tools()]
        deadline = time.time() + 140
        try:
            for _ in range(10):
                res, used = A.gemini_generate({"systemInstruction": {"parts": [{"text": CHAT_SYSTEM}]},
                                               "contents": contents, "tools": [{"functionDeclarations": decls}]}, deadline)
                mode = f"Gemini 상담 에이전트 ({used})"
                content = (res.get("candidates") or [{}])[0].get("content") or {"role": "model", "parts": []}
                contents.append(content)
                fcs = [x["functionCall"] for x in content.get("parts", []) if "functionCall" in x]
                if not fcs:
                    reply = "".join(x.get("text", "") for x in content.get("parts", [])).strip()
                    break
                parts = []
                for fc in fcs:
                    fr = {"name": fc.get("name"), "response": tb.run(fc.get("name", ""), fc.get("args") or {})}
                    if fc.get("id"):
                        fr["id"] = fc["id"]
                    parts.append({"functionResponse": fr})
                contents.append({"role": "user", "parts": parts})
        except Exception as e:
            reply = None
            mode = f"규칙 기반 (AI 호출 실패: {str(e)[:80]})"
    if not reply:
        reply = rule_chat(req.message, tb)
        mode = mode or "규칙 기반 (AI 키 없음)"
    with _db() as con:
        con.execute("INSERT INTO chat(role,text,trace,at) VALUES('assistant',?,?,?)",
                    (reply, json.dumps(tb.trace, ensure_ascii=False), time.time()))
    return {"reply": reply, "trace": tb.trace, "tool_calls": tb.calls, "mode": mode}


def rule_chat(msg: str, tb: ChatToolbox) -> str:
    """AI 없이: 키워드로 도구 하나를 골라 결과를 문장으로"""
    if re.search(r"추천|뭐가 좋|어떤 (적금|상품)", msg):
        r = tb.run("recommend_products", {"limit": 3})
        items = r.get("items") or []
        return "성향 맞춤 추천 상위 3개예요: " + ", ".join(f"{i['name']}(적합도 {i['fit']})" for i in items) if items else "추천할 상품이 없어요."
    if re.search(r"목표|얼마나 모|진행", msg):
        g = tb.run("get_goal_status", {})
        return (f"목표 {g['target']:,}원 중 {g['actual_total']:,}원({g['progress_pct']}%)을 모았어요." if g.get("active")
                else g.get("message", "목표가 없어요."))
    if re.search(r"할 일|우대|챙길", msg):
        t = tb.run("get_tasks", {})["tasks"]
        return "챙길 일: " + ", ".join(f"{x['title']}({x['next_due']})" for x in t) if t else "챙길 우대조건이 없어요."
    m = re.search(r"(\d+)\s*만\s*원?\s*(넣|저축|모았)", msg)
    if m:
        g = tb.run("record_progress", {"month": _add_months(_month(date.today()), -1), "actual_won": int(m.group(1)) * 10_000})
        return f"지난달 {int(m.group(1)):,}만원 저축을 기록했어요. 지금까지 {g.get('actual_total', 0):,}원이에요."
    return "AI 키가 없어서 간단한 질문만 답할 수 있어요. (추천 / 목표 진행 / 챙길 일 / 'n만원 넣었어')"


# ================================================================ API
class HoldingIn(BaseModel):
    id: str
    bank: str = ""
    name: str = ""
    term: int = 12
    join_date: str = ""


class ChecklistIn(BaseModel):
    holding: HoldingIn
    conditions: str = ""
    profile: str = ""


class ProgressIn(BaseModel):
    month: str
    actual_won: int


class ChatIn(BaseModel):
    message: str
    products: list[Any] = []
    holdings: list[dict[str, Any]] | None = None
    tax_rate: float = 15.4
    parking_rate: float = 2.5


class WeeklyIn(BaseModel):
    products: list[dict[str, Any]] = []


@router.post("/agent/checklist")
def api_checklist(body: ChecklistIn) -> dict:
    return create_checklist(body.holding.model_dump() if hasattr(body.holding, "model_dump") else body.holding.dict(),
                            body.conditions, body.profile)


@router.get("/tasks")
def api_tasks() -> dict:
    return {"tasks": list_tasks()}


@router.get("/tasks/due")
def api_due(days: float = 1.0) -> dict:
    return {"tasks": due_tasks(days)}


@router.post("/tasks/{tid}/done")
def api_done(tid: int) -> dict:
    return task_done(tid)


@router.delete("/tasks/{tid}")
def api_del_task(tid: int) -> dict:
    delete_task(tid)
    return {"ok": True}


@router.get("/goal/status")
def api_goal() -> dict:
    return goal_status()


@router.post("/goal/progress")
def api_progress(body: ProgressIn) -> dict:
    return record_progress(body.month, body.actual_won)


@router.post("/agent/chat")
def api_chat(body: ChatIn) -> dict:
    import plan_agent as PA
    body.products = [PA.ProductIn(**p) if isinstance(p, dict) else p for p in body.products]
    return chat(body)


@router.get("/agent/chat/history")
def api_chat_history() -> dict:
    return {"messages": chat_history(50)}


@router.delete("/agent/chat")
def api_chat_clear() -> dict:
    with _db() as con:
        con.execute("DELETE FROM chat")
    return {"ok": True}


@router.post("/agent/weekly")
def api_weekly(body: WeeklyIn) -> dict:
    return weekly_report(body.products)


@router.get("/agent/weekly/latest")
def api_weekly_latest() -> dict:
    return latest_report() or {}
