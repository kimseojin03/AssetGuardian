"""
목표 루트 에이전트 — "말로 하면 AI가 루트를 짜주는" 부분.

AI(Gemini/Claude)가 하는 일 (= 에이전트):
  1) 사용자의 말에서 목표 금액·월 저축액·중간에 필요한 돈(등록금 등)을 알아낸다
  2) search_products 도구로 적금을 찾아보고, 가입조건·우대조건 문장을 사용자 상황과 대조해
     "가입 가능한지 / 실제로 받을 금리가 얼마인지"를 판단한다
  3) simulate_plan 도구로 여러 루트(기간·상품 조합)를 직접 시험해보고 결과를 비교한다
  4) submit_plan 으로 최종 루트와 이유를 제출한다
코드가 하는 일: 상품 검색, 정확한 이자·기간 계산 (AI가 숫자를 지어내지 못하게 최종 숫자는 코드가 다시 계산)
"""
from __future__ import annotations

import json
import time
import math
import os
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


# ---------------------------------------------------------------- 요청 모델
class ProductIn(BaseModel):
    code: str
    kind: str = "적금"            # 적금 / 예금
    bank: str
    name: str
    term: int
    base_rate: float
    max_rate: float
    monthly_cap: int | None = None
    join_member: str = ""
    conditions: str = ""
    note: str = ""
    rsrv: str = ""
    restricted: bool = False      # 앱의 키워드 규칙이 "아무나 가입 못 함"으로 본 상품 (참고용)


class Defaults(BaseModel):
    target_won: int = 0
    income_won: int = 0
    living_won: int = 0


class PlanIn(BaseModel):
    message: str = ""
    profile: str = ""
    defaults: Defaults = Defaults()
    tax_rate: float = 15.4
    parking_rate: float = 2.5
    products: list[ProductIn] = []
    holdings: list[dict[str, Any]] | None = None   # 앱이 보내는 내 적금 (기억에 동기화)
    replan: bool = False                             # 목표 진행 화면의 "다시 짜기" (원래 목표·모은 돈 유지)


# ---------------------------------------------------------------- 계산 (앱 GoalPlanner.kt 와 같은 방식 + 중간 출금)
MAX_ACCOUNTS = 3


def _allocate(monthly: int, cands: list[dict], parking_rate: float) -> list[dict]:
    seen, srt = set(), []
    for c in sorted(cands, key=lambda x: -x["rate"]):
        if c["code"] not in seen:
            seen.add(c["code"]); srt.append(c)
    out: list[dict] = []
    left = monthly
    for p in srt:
        if left <= 0:
            break
        cap = p.get("cap")
        if len(out) == MAX_ACCOUNTS - 1 and cap is not None and cap < left:
            unlimited = next((u for u in srt if u.get("cap") is None and all(o["code"] != u["code"] for o in out)), None)
            if unlimited and unlimited["rate"] > parking_rate:
                out.append({**unlimited, "monthly": left}); left = 0
                break
        put = left if cap is None else min(cap, left)
        if put <= 0:
            continue
        out.append({**p, "monthly": put}); left -= put
        if len(out) == MAX_ACCOUNTS:
            break
    if left > 0:
        out.append({"code": "PARKING", "bank": "아무 은행", "name": "파킹통장", "rate": parking_rate, "cap": None,
                    "monthly": left, "term": 0})
    return out


def simulate(target: int, monthly: int, term: int, chosen: list[dict], deposit: dict | None,
             tax_rate: float, parking_rate: float, withdrawals: list[dict] | None = None, horizon: int = 240,
             initial: float = 0.0) -> dict:
    tax = 1 - tax_rate / 100
    withdrawals = sorted(withdrawals or [], key=lambda w: w.get("month", 0))
    slots = _allocate(monthly, chosen, parking_rate)
    for s in slots:
        r = s["rate"] / 100
        if s["code"] == "PARKING":
            s["interest_per_cycle"] = round(s["monthly"] * r * term * (term - 1) / 2 / 12 * tax)
        else:
            s["interest_per_cycle"] = round(s["monthly"] * r * term * (term + 1) / 2 / 12 * tax)
    cycle_interest_full = sum(s["interest_per_cycle"] for s in slots)
    dep_rate = max(deposit["rate"] if deposit else 0.0, parking_rate)

    bal = float(initial)   # 이미 모아둔 돈 (목표 진행 점검 때 사용)
    dep_int = sav_int = 0.0
    cyc_principal = 0.0
    factor = 1.0      # 중간 출금으로 적금 일부를 깨면 그 회차 이자가 줄어듦
    cycles, notes = [], []
    cyc_start, reached = 1, None
    for m in range(1, horizon + 1):
        di = bal * dep_rate / 100 / 12 * tax
        bal += di; dep_int += di
        cyc_principal += monthly
        if m - cyc_start + 1 == term:
            ci = cycle_interest_full * factor
            bal += cyc_principal + ci; sav_int += ci
            cycles.append({"number": len(cycles) + 1, "start": cyc_start, "end": m, "completed": True})
            cyc_principal, factor, cyc_start = 0.0, 1.0, m + 1
        for w in withdrawals:
            if w.get("month") == m:
                amt = float(w.get("amount_won", 0))
                from_bal = min(bal, amt); bal -= from_bal
                short = amt - from_bal
                if short > 0:
                    if cyc_principal > 0:
                        factor *= max(0.0, (cyc_principal - short) / cyc_principal)
                    cyc_principal = max(0.0, cyc_principal - short)
                    notes.append(f"{m}개월 차 {w.get('reason') or '출금'} {amt:,.0f}원 중 {short:,.0f}원은 만기 전 적금을 깨야 해요 (그 회차 이자 일부 손실)")
                else:
                    notes.append(f"{m}개월 차 {w.get('reason') or '출금'} {amt:,.0f}원은 만기된 돈으로 낼 수 있어요")
        if bal + cyc_principal >= target:
            reached = m
            break
    if reached and cyc_principal > 0:
        cycles.append({"number": len(cycles) + 1, "start": cyc_start, "end": reached, "completed": False})
    months = reached or horizon
    need = target + sum(float(w.get("amount_won", 0)) for w in withdrawals)
    return {
        "term_months": term,
        "months_needed": reached,
        "final_amount": round(bal + cyc_principal),
        "principal": monthly * months,
        "saving_interest": round(sav_int),
        "deposit_interest": round(dep_int),
        "no_interest_months": math.ceil(need / monthly) if monthly > 0 else None,
        "slots": [{k: s.get(k) for k in ("code", "bank", "name", "rate", "cap", "monthly", "interest_per_cycle")} for s in slots],
        "deposit": deposit,
        "deposit_rate": dep_rate,
        "cycles": cycles,
        "withdrawal_notes": notes,
    }


# ---------------------------------------------------------------- 도구
class Toolbox:
    """AI가 부르는 도구들. 요청에 담겨 온 상품 목록 안에서만 동작한다."""

    def __init__(self, req: PlanIn):
        self.req = req
        self.by_code: dict[tuple[str, int], ProductIn] = {}
        for p in req.products:
            if p.kind == "적금":
                self.by_code[(p.code, p.term)] = p
        deps = [p for p in req.products if p.kind == "예금" and p.term == 12 and not p.restricted]
        best = max(deps, key=lambda d: d.base_rate, default=None)
        self.deposit = None if best is None or best.base_rate < req.parking_rate else \
            {"code": best.code, "bank": best.bank, "name": best.name, "rate": best.base_rate}
        self.trace: list[str] = []
        self.calls: list[str] = []
        try:
            import memory
            self.interest = memory.interest_map()
        except Exception:
            self.interest = {}

    # 1) 상품 검색
    def search_products(self, term_months: int, min_base_rate: float = 0.0, keyword: str = "", limit: int = 12) -> dict:
        limit = max(1, min(int(limit or 12), 20))
        items = [p for (c, t), p in self.by_code.items()
                 if t == int(term_months) and p.base_rate >= float(min_base_rate or 0)
                 and (not keyword or keyword in p.name or keyword in p.bank or keyword in p.conditions)]
        items.sort(key=lambda p: (-p.base_rate, -p.max_rate))
        self.trace.append(f"{term_months}개월 적금 검색" + (f" (기본금리 {min_base_rate}% 이상)" if min_base_rate else "") +
                          f" → {len(items)}개 중 상위 {min(limit, len(items))}개 확인")
        return {"count": len(items), "products": [{
            "code": p.code, "bank": p.bank, "name": p.name, "term_months": p.term,
            "base_rate": p.base_rate, "max_rate": p.max_rate,
            "monthly_cap_won": p.monthly_cap, "join_member": p.join_member[:150],
            "conditions": p.conditions[:260], "note": p.note[:150],
            "app_flag_restricted": p.restricted,
            "user_interest_score": self.interest.get(f"{p.code}|{p.term}", 0),
        } for p in items[:limit]]}

    # 3) 기억 읽기 / 쓰기
    def get_user_memory(self) -> dict:
        import memory
        m = memory.memory_for_ai()
        self.trace.append(f"기억 불러오기 → 아는 사실 {len(m['known_facts'])}개, 관심 상품 {len(m['interested_products'])}개, "
                          f"내 적금 {len(m['my_savings'])}개, 지난 루트 {len(m['previous_plans'])}개")
        return m

    def get_saving_profile(self) -> dict:
        import memory
        p = memory.get_profile()
        self.trace.append("저축 성향 분석 불러오기 → " + (f"{p['label']} (신뢰도 {p['confidence_label']})" if p.get("ready")
                                                          else "기록 부족으로 분석 전"))
        return {k: p.get(k) for k in ("ready", "label", "scores", "evidence", "confidence_label", "stated_label", "conflict", "message")}

    def recommend_products(self, term_months: int | None = None, limit: int = 8) -> dict:
        import memory
        prods = [{"code": p.code, "kind": p.kind, "bank": p.bank, "name": p.name, "term": p.term, "base_rate": p.base_rate,
                  "max_rate": p.max_rate, "monthly_cap": p.monthly_cap, "rsrv": p.rsrv, "restricted": p.restricted}
                 for p in self.req.products]
        r = memory.recommend(prods, int(term_months) if term_months else None, int(limit or 8))
        head = "성향 맞춤 추천 계산" + (f" ({term_months}개월)" if term_months else "")
        self.trace.append(head + (f" → 1위 {r['items'][0]['name']} (적합도 {r['items'][0]['fit']})" if r["items"] else " → 결과 없음"))
        return {"items": [{k: i[k] for k in ("code", "bank", "name", "term", "base_rate", "max_rate", "fit", "reasons")} for i in r["items"]]}

    def set_stated(self, t: str) -> dict:
        import memory
        import saving_profile as P
        memory.set_stated(t)
        self.trace.append(f"내가 말한 성향 기록: {P.TYPES.get(t, t)}")
        return {"saved": t in P.TYPES}

    def save_memory(self, fact: str, category: str = "") -> dict:
        import memory
        r = memory.add_fact(fact, category, source="ai")
        if r.get("saved"):
            self.trace.append(f"새로 기억함: {fact}")
        return r

    def _chosen(self, term: int, products: list[dict]) -> tuple[list[dict], list[str]]:
        chosen, warn = [], []
        for x in products or []:
            p = self.by_code.get((str(x.get("code")), int(term)))
            if p is None:
                warn.append(f"{x.get('code')}: {term}개월 상품 목록에 없음 (무시)")
                continue
            rate = float(x.get("expected_rate", p.base_rate))
            if rate < p.base_rate or rate > p.max_rate:
                warn.append(f"{p.name}: 예상금리 {rate}%는 범위({p.base_rate}~{p.max_rate}%) 밖이라 조정")
                rate = min(max(rate, p.base_rate), p.max_rate)
            chosen.append({"code": p.code, "bank": p.bank, "name": p.name, "rate": rate, "cap": p.monthly_cap, "term": p.term})
        return chosen, warn

    # 2) 루트 시뮬레이션
    def simulate_plan(self, target_won: int, monthly_won: int, term_months: int, products: list[dict],
                      withdrawals: list[dict] | None = None) -> dict:
        term = int(term_months)
        chosen, warn = self._chosen(term, products)
        if not chosen:
            return {"error": "선택한 상품이 없어요. search_products로 찾은 code를 넣어주세요.", "warnings": warn}
        r = simulate(int(target_won), int(monthly_won), term, chosen, self.deposit,
                     self.req.tax_rate, self.req.parking_rate, withdrawals)
        r["warnings"] = warn
        mn = r["months_needed"]
        self.trace.append(f"루트 계산: {term}개월 적금 {len(chosen)}개 → " +
                          (f"{mn}개월 만에 달성" if mn else "20년 안에 불가"))
        return r


TOOLS = [
    {"name": "get_user_memory",
     "description": ("이 사용자에 대해 앱이 기억하는 것을 불러온다: 이전에 알게 된 사실, 보유 적금, 관심 상품(조회·링크 클릭으로 계산한 점수), "
                     "최근 본 적금, 성향(선호 기간·저축은행 비중), 지난번 AI 루트. 작업을 시작할 때 먼저 호출한다."),
     "input_schema": {"type": "object", "properties": {}, "required": []}},
    {"name": "get_saving_profile",
     "description": ("행동 기록을 코드로 분석한 이 사용자의 저축 성향(안정형/수익추구형/우대조건 활용형/단기 유동형), 성향별 점수, "
                     "근거 숫자, 신뢰도, 사용자가 직접 말한 성향과의 차이를 불러온다."),
     "input_schema": {"type": "object", "properties": {}, "required": []}},
    {"name": "recommend_products",
     "description": ("저축 성향 점수로 상품마다 '성향 적합도'(0~100)를 계산한 순위와 이유를 받는다. "
                     "search_products(금리순)와 달리 이 사용자에게 맞는 순서다. 기간을 정하면 그 기간만."),
     "input_schema": {"type": "object", "properties": {
         "term_months": {"type": "integer", "description": "6, 12, 24, 36 (선택)"},
         "limit": {"type": "integer", "description": "기본 8"}}, "required": []}},
    {"name": "set_stated_preference",
     "description": "사용자가 대화에서 자기 성향을 직접 말했을 때(예: '나는 안전한 게 좋아') 그 성향을 기록한다.",
     "input_schema": {"type": "object", "properties": {
         "type": {"type": "string", "enum": ["STABLE", "YIELD", "PERK", "SHORT"],
                  "description": "STABLE=안정형, YIELD=수익추구형, PERK=우대조건 활용형, SHORT=단기 유동형"}},
         "required": ["type"]}},
    {"name": "save_memory",
     "description": ("대화에서 새로 알게 된, 다음에도 쓸모 있는 사실을 장기 기억에 저장한다. 예: '23살 대학생', '1년 뒤 등록금 300만원 필요', "
                     "'애큐온저축은행과 이미 거래 중이라 첫거래 우대 불가', '원금 보장 상품만 원함'. 이미 아는 사실은 저장하지 않는다."),
     "input_schema": {"type": "object", "properties": {
         "fact": {"type": "string", "description": "한 문장 사실"},
         "category": {"type": "string", "description": "나이·직업 / 목표 / 거래은행 / 성향 / 일정 중 하나"}},
         "required": ["fact"]}},
    {"name": "search_products",
     "description": "적금 상품을 검색한다. 기간(개월)별로 기본금리 높은 순. 가입대상(join_member)·우대조건(conditions)·기타(note) 원문이 함께 나온다.",
     "input_schema": {"type": "object", "properties": {
         "term_months": {"type": "integer", "description": "6, 12, 24, 36 중 하나"},
         "min_base_rate": {"type": "number", "description": "최소 기본금리(연 %)"},
         "keyword": {"type": "string", "description": "상품명·은행명·조건에 들어간 단어 (선택)"},
         "limit": {"type": "integer", "description": "최대 개수 (기본 12, 최대 20)"}},
         "required": ["term_months"]}},
    {"name": "simulate_plan",
     "description": ("고른 적금들로 목표까지 몇 개월 걸리는지 정확히 계산한다. 매달 저축액을 expected_rate 높은 순으로 월 한도까지 나눠 넣고, "
                     "만기마다 목돈을 1년 예금으로 굴린다. withdrawals로 중간에 빠져나갈 돈(등록금 등)을 넣을 수 있다. "
                     "한 루트는 한 가지 기간(term_months)의 적금들로만 이뤄진다 — 다른 기간 상품 code는 무시되고 warnings에 나온다. "
                     "기간이나 상품 조합을 바꿔 여러 번 호출해 비교해라."),
     "input_schema": {"type": "object", "properties": {
         "target_won": {"type": "integer"},
         "monthly_won": {"type": "integer"},
         "term_months": {"type": "integer"},
         "products": {"type": "array", "items": {"type": "object", "properties": {
             "code": {"type": "string"},
             "expected_rate": {"type": "number", "description": "이 사용자가 실제로 받을 것으로 판단한 연 금리(%)"}},
             "required": ["code", "expected_rate"]}},
         "withdrawals": {"type": "array", "items": {"type": "object", "properties": {
             "month": {"type": "integer", "description": "지금부터 몇 개월 차"},
             "amount_won": {"type": "integer"},
             "reason": {"type": "string"}}, "required": ["month", "amount_won"]}}},
         "required": ["target_won", "monthly_won", "term_months", "products"]}},
    {"name": "submit_plan",
     "description": "최종 루트를 제출한다. 반드시 마지막에 한 번 호출한다.",
     "input_schema": {"type": "object", "properties": {
         "target_won": {"type": "integer"},
         "monthly_won": {"type": "integer"},
         "term_months": {"type": "integer"},
         "products": {"type": "array", "items": {"type": "object", "properties": {
             "code": {"type": "string"},
             "expected_rate": {"type": "number"},
             "reason": {"type": "string", "description": "이 상품을 고른 이유와 받을 수 있다고 본 우대조건"}},
             "required": ["code", "expected_rate", "reason"]}},
         "withdrawals": {"type": "array", "items": {"type": "object", "properties": {
             "month": {"type": "integer"}, "amount_won": {"type": "integer"}, "reason": {"type": "string"}},
             "required": ["month", "amount_won"]}},
         "excluded": {"type": "array", "items": {"type": "object", "properties": {
             "name": {"type": "string"}, "reason": {"type": "string"}}, "required": ["name", "reason"]},
             "description": "금리는 높지만 뺀 상품과 이유 (2~4개)"},
         "summary": {"type": "string", "description": "사용자에게 하는 3~4문장 설명 (반말 X, ~요체)"},
         "cautions": {"type": "array", "items": {"type": "string"}}},
         "required": ["target_won", "monthly_won", "term_months", "products", "summary"]}},
]

SYSTEM = """너는 '자산지킴이' 앱의 목표 루트 설계 에이전트다. 사용자가 목표 금액까지 가장 빨리, 현실적으로 갈 수 있는 적금 루트를 짠다.
순서:
0. 먼저 get_user_memory와 get_saving_profile로 이 사용자에 대해 기억하는 것과 저축 성향(행동 기록을 코드로 분석한 결과)을 확인한다.
   상품 후보는 recommend_products(성향 적합도 순)로 먼저 보고, 필요하면 search_products(금리 순)로 보충한다.
   성향이 안정형이면 1금융권·조건 단순 상품을, 수익추구형이면 고금리·저축은행을, 우대조건 활용형이면 사용자가 채울 수 있는 우대조건이 많은 상품을,
   단기 유동형이면 짧은 기간을 우선한다. 사용자가 말한 성향과 행동이 다르면(conflict) summary에서 짚어준다.
   사용자가 자기 성향을 말하면 set_stated_preference로 기록한다.
   기억 확인: 아는 사실(나이·거래 은행·필요 자금 등)은 다시 묻지 말고 활용하고,
   관심 상품(user_interest_score가 높은 상품)은 조건이 맞으면 우선 검토하며, 지난 루트가 있으면 이번 결과와 비교해 summary에 무엇이 달라졌는지 말한다.
   보유 적금이 있으면 그 돈은 이미 묶여 있다는 점을 고려한다.
1. 사용자 말과 [기본값]에서 목표 금액(target_won), 매달 저축액(monthly_won = 수입 - 생활비), 중간에 필요한 돈(withdrawals)을 정한다.
   "2년 안에" 같은 기한이 있으면 기억해 두고 결과가 기한을 넘는지 확인한다.
2. search_products로 적금을 찾는다. 기한·중간 출금 시점을 생각해 알맞은 기간(6/12/24/36개월)을 고르고, 최소 2가지 기간을 검색한다.
3. 각 상품의 join_member(가입대상)·conditions(우대조건)·note를 사용자 상황과 하나씩 대조한다.
   - 가입 자격이 안 되는 상품(어린이·장병·기초수급자·반려동물 보유 등)은 뺀다. app_flag_restricted는 참고만 한다.
   - expected_rate는 기본금리 + "사용자 상황으로 확실히 충족되는" 우대금리만 더한다. 모르는 조건은 더하지 않는다.
   - 월 납입 한도(monthly_cap_won)가 작으면 여러 상품을 같이 쓴다 (최대 3개).
   - 한 루트 안의 적금은 모두 같은 기간이어야 한다 (simulate_plan의 term_months와 같은 기간에서 찾은 code만 사용).
4. 숫자는 절대 직접 계산하지 말고 simulate_plan으로 계산한다. 기간이나 조합을 바꿔 최소 2번 이상 비교한다.
   중간 출금이 적금 만기 전이면 손실이 생기므로(withdrawal_notes 확인) 더 짧은 기간이 나은지 따져본다.
5. 이번 대화에서 새로 알게 된 사실(나이·직업·목표·필요 자금 일정·이미 거래 중인 은행·성향)은 save_memory로 저장한다.
6. 가장 좋은 루트로 submit_plan을 호출한다. 상품마다 고른 이유, 뺀 상품과 이유, 주의사항을 쓴다.
과장하지 말고, 정보 제공이며 최종 결정은 사용자가 한다는 점을 지킨다. 모든 글은 한국어 ~요체로 쓴다."""


def build_user_msg(req: PlanIn) -> str:
    d = req.defaults
    return (f"[사용자 말]\n{req.message or '(없음 - 기본값 사용)'}\n\n"
            f"[사용자 상황]\n{req.profile or '(입력 없음)'}\n\n"
            f"[기본값 - 말에 없으면 사용]\n목표 금액 {d.target_won:,}원 / 월 수입 {d.income_won:,}원 / 생활비 {d.living_won:,}원 "
            f"→ 월 저축 가능액 {max(0, d.income_won - d.living_won):,}원\n"
            f"[계산 조건] 이자소득세 {req.tax_rate}% / 파킹통장 {req.parking_rate}%")


# ---------------------------------------------------------------- 결과 만들기
def finalize(tb: Toolbox, args: dict, mode: str) -> dict:
    term = int(args.get("term_months", 12))
    target = int(args.get("target_won", tb.req.defaults.target_won))
    monthly = int(args.get("monthly_won", max(0, tb.req.defaults.income_won - tb.req.defaults.living_won)))
    prods = args.get("products") or []
    withdrawals = args.get("withdrawals") or []
    chosen, warn = tb._chosen(term, prods)
    plan = simulate(target, monthly, term, chosen, tb.deposit, tb.req.tax_rate, tb.req.parking_rate, withdrawals) \
        if chosen and monthly > 0 and target > 0 else None
    reasons = {str(p.get("code")): str(p.get("reason", "")) for p in prods}
    tb.trace.append("최종 루트 제출 (숫자는 코드가 다시 계산)")
    return {
        "mode": mode,
        "target_won": target,
        "monthly_won": monthly,
        "term_months": term,
        "summary": str(args.get("summary", "")),
        "cautions": [str(c) for c in (args.get("cautions") or [])] + warn,
        "excluded": [{"name": str(e.get("name", "")), "reason": str(e.get("reason", ""))} for e in (args.get("excluded") or [])],
        "reasons": reasons,
        "withdrawals": withdrawals,
        "plan": plan,
        "trace": tb.trace,
        "tool_calls": tb.calls,
    }


def rule_based(req: PlanIn, note: str) -> dict:
    """AI 없이: 기본값으로, 아무나 가입 가능한 상품을 기본금리로 넣어 기간별 비교"""
    tb = Toolbox(req)
    d = req.defaults
    monthly = max(0, d.income_won - d.living_won)
    best = None
    for term in (6, 12, 24, 36):
        cands = [p for (c, t), p in tb.by_code.items() if t == term and not p.restricted]
        cands.sort(key=lambda p: -p.base_rate)
        prods = [{"code": p.code, "expected_rate": p.base_rate, "reason": "기본금리 상위 (우대조건 미반영)"} for p in cands[:6]]
        if not prods or monthly <= 0 or d.target_won <= 0:
            continue
        r = tb.simulate_plan(d.target_won, monthly, term, prods)
        mn = r.get("months_needed") or 10_000
        if best is None or mn < best[0] or (mn == best[0] and r.get("final_amount", 0) > best[1]):
            best = (mn, r.get("final_amount", 0), term, prods)
    if best is None:
        return {"mode": note, "summary": "목표 금액과 저축액(수입-생활비)을 먼저 정해주세요.", "plan": None,
                "trace": tb.trace, "tool_calls": [], "cautions": [], "excluded": [], "reasons": {},
                "target_won": d.target_won, "monthly_won": monthly, "term_months": 12, "withdrawals": []}
    return finalize(tb, {"term_months": best[2], "target_won": d.target_won, "monthly_won": monthly,
                         "products": best[3],
                         "summary": "AI 없이 규칙으로 계산했어요. 말로 적은 조건(등록금 등)과 우대조건은 반영되지 않았어요."}, note)


# ---------------------------------------------------------------- AI 루프 (Gemini / Claude 공통)
def _execute(tb: Toolbox, name: str, args: dict) -> dict:
    tb.calls.append(name)
    try:
        if name == "search_products":
            return tb.search_products(**{k: args[k] for k in ("term_months", "min_base_rate", "keyword", "limit") if k in args})
        if name == "get_user_memory":
            return tb.get_user_memory()
        if name == "save_memory":
            return tb.save_memory(str(args.get("fact", "")), str(args.get("category", "")))
        if name == "get_saving_profile":
            return tb.get_saving_profile()
        if name == "recommend_products":
            return tb.recommend_products(args.get("term_months"), args.get("limit", 8))
        if name == "set_stated_preference":
            return tb.set_stated(str(args.get("type", "")))
        if name == "simulate_plan":
            return tb.simulate_plan(args.get("target_won", 0), args.get("monthly_won", 0), args.get("term_months", 12),
                                    args.get("products") or [], args.get("withdrawals"))
    except Exception as e:  # 도구 오류는 AI에게 알려 스스로 고치게 함
        return {"error": f"{type(e).__name__}: {e}"}
    return {"error": f"알 수 없는 도구 {name}"}


def gemini_loop(req: PlanIn) -> dict:
    import agent_server as A
    tb = Toolbox(req)
    decls = [{"name": t["name"], "description": t["description"],
              **({"parameters": t["input_schema"]} if t["input_schema"].get("properties") else {})} for t in TOOLS]
    contents: list[dict[str, Any]] = [{"role": "user", "parts": [{"text": build_user_msg(req)}]}]
    used = ""
    deadline = time.time() + 140          # 앱이 3분 기다리므로 그 전에 끝낸다 (넘으면 규칙 기반으로 대체)
    for _ in range(14):
        body = {"systemInstruction": {"parts": [{"text": SYSTEM}]}, "contents": contents,
                "tools": [{"functionDeclarations": decls}]}
        try:
            res, used = A.gemini_generate(body, deadline)
        except Exception as e:
            out = rule_based(req, f"AI가 바빠서 규칙 기반으로 대체 ({str(e)[:100]})")
            out["trace"] = tb.trace + out["trace"]
            return out
        content = (res.get("candidates") or [{}])[0].get("content") or {"role": "model", "parts": []}
        contents.append(content)
        fcs = [p["functionCall"] for p in content.get("parts", []) if "functionCall" in p]
        if not fcs:
            contents.append({"role": "user", "parts": [{"text": "도구로 계산한 뒤 submit_plan을 호출해 주세요."}]})
            continue
        parts = []
        for fc in fcs:
            name, args = fc.get("name", ""), fc.get("args") or {}
            if name == "submit_plan":
                tb.calls.append(name)
                return finalize(tb, args, f"Gemini 에이전트 ({used})")
            fr = {"name": name, "response": _execute(tb, name, args)}
            if fc.get("id"):
                fr["id"] = fc["id"]
            parts.append({"functionResponse": fr})
        contents.append({"role": "user", "parts": parts})
    out = rule_based(req, f"Gemini 에이전트 ({used}) - 제출 없이 끝나 규칙 기반으로 대체")
    out["trace"] = tb.trace + out["trace"]
    return out


def claude_loop(req: PlanIn) -> dict:
    import anthropic
    import agent_server as A
    client = anthropic.Anthropic()
    tb = Toolbox(req)
    messages: list[dict[str, Any]] = [{"role": "user", "content": build_user_msg(req)}]
    for _ in range(14):
        res = client.messages.create(model=A.MODEL, max_tokens=2500, system=SYSTEM, tools=TOOLS, messages=messages)
        messages.append({"role": "assistant", "content": res.content})
        uses = [b for b in res.content if b.type == "tool_use"]
        if not uses:
            messages.append({"role": "user", "content": "도구로 계산한 뒤 submit_plan을 호출해 주세요."})
            continue
        results = []
        for tu in uses:
            if tu.name == "submit_plan":
                tb.calls.append(tu.name)
                return finalize(tb, dict(tu.input), f"Claude 에이전트 ({A.MODEL})")
            results.append({"type": "tool_result", "tool_use_id": tu.id,
                            "content": json.dumps(_execute(tb, tu.name, dict(tu.input)), ensure_ascii=False)})
        messages.append({"role": "user", "content": results})
    return rule_based(req, f"Claude 에이전트 ({A.MODEL}) - 제출 없이 끝나 규칙 기반으로 대체")


@router.post("/agent/plan")
def agent_plan(req: PlanIn) -> dict:
    import agent_server as A
    import memory
    if req.holdings is not None:
        memory.set_holdings(req.holdings)
    memory.save_market([p.model_dump() if hasattr(p, "model_dump") else p.dict() for p in req.products])
    p = A.provider()
    if p == "rule":
        out = rule_based(req, "규칙 기반 모드 (AI 키 없음)")
    else:
        try:
            out = gemini_loop(req) if p == "gemini" else claude_loop(req)
        except Exception as e:
            out = rule_based(req, f"규칙 기반 모드 ({p} 호출 실패: {str(e)[:120]})")
    if out.get("plan"):
        memory.add_plan({**out, "message": req.message})   # 루트 기록은 코드가 자동 저장 (다음에 비교용)
        import agent_features as F
        F.set_goal_from_plan(out, req.replan)                           # 이 루트를 "추적할 목표"로 등록
    return out
