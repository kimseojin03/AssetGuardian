"""
에이전트의 장기 기억 (SQLite 파일 하나: server/guardian_memory.db)

AI(LLM)는 호출할 때마다 아무것도 기억하지 못한다. 그래서
  - 앱이 보내는 행동 기록(상품 조회·비교·링크 클릭·알림 반응)과 보유 적금,
  - AI가 대화에서 알아낸 사실("23살 대학생", "1년 뒤 등록금 필요"),
  - AI가 짰던 루트 기록
을 여기에 쌓아두고, AI가 get_user_memory 도구로 꺼내 보고 save_memory 도구로 새로 적는다.
"""
from __future__ import annotations

import json
import math
import os
import sqlite3
import time
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel

DB_PATH = os.environ.get("GUARDIAN_DB", os.path.join(os.path.dirname(os.path.abspath(__file__)), "guardian_memory.db"))
router = APIRouter()

# 행동별 관심 점수 (오래된 기록은 30일마다 절반으로 줄어듦)
WEIGHTS = {"VIEW": 1.0, "COMPARE": 2.0, "LINK": 3.0, "REGISTER": 5.0, "ALERT_ACCEPT": 3.0, "ALERT_IGNORE": -2.0, "REC_CLICK": 2.0}
HALF_LIFE_DAYS = 30.0


def db() -> sqlite3.Connection:
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    con.executescript("""
    CREATE TABLE IF NOT EXISTS facts(
        id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, category TEXT DEFAULT '',
        source TEXT DEFAULT 'ai', created_at REAL NOT NULL);
    CREATE TABLE IF NOT EXISTS events(
        id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, code TEXT DEFAULT '', kind TEXT DEFAULT '적금',
        term INTEGER DEFAULT 0, bank TEXT DEFAULT '', name TEXT DEFAULT '', rate REAL DEFAULT 0,
        detail TEXT DEFAULT '', at REAL NOT NULL, client_id TEXT UNIQUE);
    CREATE TABLE IF NOT EXISTS holdings(
        id TEXT PRIMARY KEY, bank TEXT, name TEXT, final_rate REAL, base_rate REAL, monthly INTEGER,
        term INTEGER, join_date TEXT, code TEXT);
    CREATE TABLE IF NOT EXISTS plans(
        id INTEGER PRIMARY KEY AUTOINCREMENT, created_at REAL NOT NULL, message TEXT, target INTEGER,
        monthly INTEGER, term INTEGER, months_needed INTEGER, final_amount INTEGER, products TEXT, summary TEXT, mode TEXT);
    """)
    import saving_profile as P
    P.ensure_tables(con)
    return con


# ---------------------------------------------------------------- 쓰기
def add_events(events: list[dict]) -> int:
    with db() as con:
        n = 0
        for e in events:
            try:
                con.execute("INSERT INTO events(type,code,kind,term,bank,name,rate,detail,at,client_id,max_rate) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            (e.get("type", "VIEW"), e.get("code", ""), e.get("kind", "적금"), int(e.get("term", 0) or 0),
                             e.get("bank", ""), e.get("name", ""), float(e.get("rate", 0) or 0), e.get("detail", ""),
                             float(e.get("at", time.time())), e.get("client_id"), float(e.get("max_rate", 0) or 0)))
                n += 1
            except sqlite3.IntegrityError:
                pass  # 같은 기록이 두 번 오면 무시 (앱이 재전송해도 안전)
        return n


def set_holdings(items: list[dict]) -> None:
    with db() as con:
        con.execute("DELETE FROM holdings")
        for h in items:
            con.execute("INSERT INTO holdings VALUES(?,?,?,?,?,?,?,?,?)",
                        (h.get("id"), h.get("bank"), h.get("name"), h.get("final_rate"), h.get("base_rate"),
                         h.get("monthly"), h.get("term"), h.get("join_date"), h.get("code", "")))


def add_fact(text: str, category: str = "", source: str = "ai") -> dict:
    text = (text or "").strip()
    if not text:
        return {"saved": False, "reason": "빈 내용"}
    with db() as con:
        for r in con.execute("SELECT id, text FROM facts"):
            a, b = r["text"].replace(" ", ""), text.replace(" ", "")
            if a == b or (len(b) > 6 and (a in b or b in a)):
                if len(b) > len(a):   # 더 자세한 내용이면 갱신
                    con.execute("UPDATE facts SET text=?, created_at=? WHERE id=?", (text, time.time(), r["id"]))
                    return {"saved": True, "updated": r["id"]}
                return {"saved": False, "reason": "이미 기억하고 있음", "id": r["id"]}
        cur = con.execute("INSERT INTO facts(text,category,source,created_at) VALUES(?,?,?,?)",
                          (text, category, source, time.time()))
        return {"saved": True, "id": cur.lastrowid}


def delete_fact(fid: int) -> None:
    with db() as con:
        con.execute("DELETE FROM facts WHERE id=?", (fid,))


def add_plan(p: dict) -> None:
    plan = p.get("plan") or {}
    with db() as con:
        con.execute("INSERT INTO plans(created_at,message,target,monthly,term,months_needed,final_amount,products,summary,mode)"
                    " VALUES(?,?,?,?,?,?,?,?,?,?)",
                    (time.time(), p.get("message", ""), p.get("target_won"), p.get("monthly_won"), p.get("term_months"),
                     plan.get("months_needed"), plan.get("final_amount"),
                     json.dumps([{"name": s.get("name"), "bank": s.get("bank"), "monthly": s.get("monthly"), "rate": s.get("rate")}
                                 for s in plan.get("slots", [])], ensure_ascii=False),
                     p.get("summary", ""), p.get("mode", "")))


def clear_all() -> None:
    import agent_features as F
    with db() as con:
        F.ensure_tables(con)
        for t in ("facts", "events", "holdings", "plans", "profile_history", "profile_stated",
                  "tasks", "goal", "progress", "chat", "seen_products", "reports"):
            con.execute(f"DELETE FROM {t}")


# ---------------------------------------------------------------- 읽기 (분석)
def interests(limit: int = 10) -> list[dict]:
    now = time.time()
    agg: dict[tuple, dict] = {}
    with db() as con:
        for e in con.execute("SELECT * FROM events WHERE code != ''"):
            w = WEIGHTS.get(e["type"], 0.0) * math.pow(0.5, (now - e["at"]) / 86400 / HALF_LIFE_DAYS)
            k = (e["code"], e["kind"], e["term"])
            a = agg.setdefault(k, {"code": e["code"], "kind": e["kind"], "term": e["term"], "bank": e["bank"],
                                   "name": e["name"], "rate": e["rate"], "score": 0.0, "views": 0, "links": 0, "last_at": 0})
            a["score"] += w
            a["views"] += e["type"] == "VIEW"
            a["links"] += e["type"] == "LINK"
            a["last_at"] = max(a["last_at"], e["at"])
    out = [a for a in agg.values() if a["score"] > 0.3]
    out.sort(key=lambda a: -a["score"])
    for a in out:
        a["score"] = round(a["score"], 2)
    return out[:limit]


def recent_views(limit: int = 15) -> list[dict]:
    with db() as con:
        rows = con.execute("SELECT code,kind,term,bank,name,rate,MAX(at) AS at FROM events "
                           "WHERE type IN ('VIEW','COMPARE','LINK') AND code!='' GROUP BY code,kind,term "
                           "ORDER BY at DESC LIMIT ?", (limit,)).fetchall()
    return [dict(r) for r in rows]


def taste() -> dict:
    """행동 기록으로 본 성향: 선호 기간, 저축은행 비중, 관심 금리대, 알림 반응"""
    with db() as con:
        ev = [dict(r) for r in con.execute("SELECT * FROM events WHERE type IN ('VIEW','COMPARE','LINK','REGISTER')")]
        acc = con.execute("SELECT COUNT(*) FROM events WHERE type='ALERT_ACCEPT'").fetchone()[0]
        ign = con.execute("SELECT COUNT(*) FROM events WHERE type='ALERT_IGNORE'").fetchone()[0]
    if not ev:
        return {"events": 0, "alert_accept": acc, "alert_ignore": ign}
    terms: dict[int, int] = {}
    for e in ev:
        if e["term"]:
            terms[e["term"]] = terms.get(e["term"], 0) + 1
    sb = sum(1 for e in ev if "저축은행" in (e["bank"] or ""))
    rates = [e["rate"] for e in ev if e["rate"]]
    return {
        "events": len(ev),
        "favorite_term": max(terms, key=terms.get) if terms else None,
        "term_counts": terms,
        "savings_bank_share": round(sb / len(ev), 2),
        "avg_rate_viewed": round(sum(rates) / len(rates), 2) if rates else None,
        "alert_accept": acc,
        "alert_ignore": ign,
    }


def summary() -> dict:
    with db() as con:
        facts = [dict(r) for r in con.execute("SELECT id,text,category,source,created_at FROM facts ORDER BY created_at DESC")]
        holds = [dict(r) for r in con.execute("SELECT * FROM holdings")]
        plans = [dict(r) for r in con.execute("SELECT * FROM plans ORDER BY created_at DESC LIMIT 10")]
        counts = {r["type"]: r["n"] for r in con.execute("SELECT type, COUNT(*) AS n FROM events GROUP BY type")}
    for p in plans:
        try:
            p["products"] = json.loads(p.get("products") or "[]")
        except Exception:
            p["products"] = []
    return {"facts": facts, "holdings": holds, "interests": interests(), "recent": recent_views(),
            "taste": taste(), "plans": plans, "counts": counts, "profile": get_profile()}


# ---------------------------------------------------------------- 저축 성향 (profile.py)
def get_profile(save: bool = True) -> dict:
    import saving_profile as P
    con = db()
    try:
        return P.compute(con, save=save)
    finally:
        con.close()


def set_stated(t: str | None) -> None:
    import saving_profile as P
    with db() as con:
        if t and t in P.TYPES:
            con.execute("INSERT OR REPLACE INTO profile_stated(id,type,at) VALUES(1,?,?)", (t, time.time()))
        else:
            con.execute("DELETE FROM profile_stated")


def save_market(products: list) -> None:
    import saving_profile as P
    with db() as con:
        P.save_market(con, products)


def recommend(products: list[dict], term: int | None = None, limit: int = 8) -> dict:
    import saving_profile as P
    prof = get_profile()
    with db() as con:
        held = {r[0] for r in con.execute("SELECT code FROM holdings") if r[0]}
    items = P.recommend(products, prof, interests(), held, limit=max(limit * 2, 12), term=term)
    import agent_features as F
    with db() as con:
        factors, notes = F.learned_factors(con)
    items = F.apply_learning(items, factors)[:limit]      # 추천에 대한 내 반응으로 보정
    return {"profile": prof, "items": items, "learning": notes}


def memory_for_ai() -> dict:
    """AI 도구 get_user_memory 결과 — 필요한 것만 짧게"""
    s = summary()
    pr = s.get("profile") or {}
    return {
        "saving_profile": ({"type": pr.get("label"), "confidence": pr.get("confidence_label"), "scores": pr.get("scores"),
                            "evidence": pr.get("evidence"), "stated": pr.get("stated_label"), "conflict": pr.get("conflict")}
                           if pr.get("ready") else {"ready": False, "stated": pr.get("stated_label"), "message": pr.get("message")}),
        "known_facts": [f["text"] for f in s["facts"]][:20],
        "my_savings": [{k: h[k] for k in ("id", "bank", "name", "final_rate", "monthly", "term", "join_date")} for h in s["holdings"]],
        "interested_products": [{k: i[k] for k in ("code", "bank", "name", "term", "rate", "score", "views", "links")} for i in s["interests"]],
        "recently_viewed": [f"{r['bank']} {r['name']} ({r['term']}개월, {r['rate']}%)" for r in s["recent"][:8]],
        "taste": s["taste"],
        "previous_plans": [{"date": time.strftime("%Y-%m-%d", time.localtime(p["created_at"])), "target": p["target"],
                            "monthly": p["monthly"], "term": p["term"], "months_needed": p["months_needed"],
                            "products": [x.get("name") for x in p["products"]]} for p in s["plans"][:3]],
    }


def interest_map() -> dict[str, float]:
    return {f"{i['code']}|{i['term']}": i["score"] for i in interests(50)}


# ---------------------------------------------------------------- API
class EventsIn(BaseModel):
    events: list[dict[str, Any]] = []


class HoldingsIn(BaseModel):
    holdings: list[dict[str, Any]] = []


class FactIn(BaseModel):
    text: str
    category: str = ""


@router.post("/memory/events")
def post_events(body: EventsIn) -> dict:
    return {"saved": add_events(body.events)}


@router.post("/memory/holdings")
def post_holdings(body: HoldingsIn) -> dict:
    set_holdings(body.holdings)
    return {"ok": True}


@router.get("/memory/summary")
def get_summary() -> dict:
    return summary()


@router.post("/memory/facts")
def post_fact(body: FactIn) -> dict:
    return add_fact(body.text, body.category, source="user")


@router.delete("/memory/facts/{fid}")
def del_fact(fid: int) -> dict:
    delete_fact(fid)
    return {"ok": True}


class StatedIn(BaseModel):
    type: str | None = None


class MarketIn(BaseModel):
    products: list[dict[str, Any]] = []


class RecommendIn(BaseModel):
    products: list[dict[str, Any]] = []
    term: int | None = None
    limit: int = 8


@router.get("/memory/profile")
def api_profile() -> dict:
    return get_profile()


@router.post("/memory/profile/stated")
def api_stated(body: StatedIn) -> dict:
    set_stated(body.type)
    return get_profile()


@router.post("/memory/market")
def api_market(body: MarketIn) -> dict:
    save_market(body.products)
    return {"ok": True}


@router.post("/agent/recommend")
def api_recommend(body: RecommendIn) -> dict:
    """성향 맞춤 추천: 점수와 이유는 코드가 계산하고, AI는 결과를 2~3문장으로 설명만 한다"""
    save_market(body.products)
    r = recommend(body.products, body.term, body.limit)
    r["ai_comment"] = _ai_comment(r)
    return r


def _ai_comment(r: dict) -> str | None:
    try:
        import agent_server as A
        if A.provider() != "gemini" or not r["items"]:
            return None
        pr = r["profile"]
        top = [f"{i['bank']} {i['name']}({i['term']}개월, 기본 {i['base_rate']}%, 적합도 {i['fit']}, 이유: {', '.join(i['reasons'])})"
               for i in r["items"][:3]]
        prompt = (f"사용자 저축 성향: {pr.get('label') or pr.get('stated_label') or '분석 전'} "
                  f"(근거: {' / '.join(pr.get('evidence') or [])})\n추천 상위 3개: {' ; '.join(top)}\n"
                  "위 계산 결과만 근거로, 왜 이 상품들이 이 사용자에게 맞는지 2~3문장 ~요체로 설명해. 새로운 숫자를 만들지 마.")
        res, _ = A.gemini_generate({"contents": [{"role": "user", "parts": [{"text": prompt}]}]})
        parts = (res.get("candidates") or [{}])[0].get("content", {}).get("parts", [])
        return "".join(p.get("text", "") for p in parts).strip() or None
    except Exception:
        return None


@router.delete("/memory/all")
def del_all() -> dict:
    clear_all()
    return {"ok": True}
