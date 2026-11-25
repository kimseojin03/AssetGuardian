"""
자산지킴이 — AI 에이전트 서버 (Gemini 무료 / Claude 선택)

앱이 "이 우대조건, 나한테 해당돼? 갈아타는 게 맞아?"를 물으면
AI가 스스로 도구(tool)를 골라 호출하며 판단한다.

  - get_early_rate : 지금 해지하면 적용되는 중도해지이율
  - calc_switch    : 주어진 금리로 갈아탔을 때 해지 손실/전환 이익/순이익 (정확한 숫자는 코드가 계산)

Claude가 하는 일: 우대조건 문구를 사용자 상황과 대조해 "실제로 받을 금리"를 정하고,
필요한 계산 도구를 호출한 뒤 최종 판단과 이유를 쓴다.

실행: server 폴더의 run_server.bat 더블클릭
  AI 키는 같은 폴더의 .env 파일에 적는다 (서버가 켜질 때 자동으로 읽음)
    GEMINI_API_KEY=AI Studio에서 받은 키      ← 무료 (우선 사용)
    ANTHROPIC_API_KEY=sk-ant-...              ← Claude (유료, 선택)
키가 하나도 없으면 규칙 기반 모드(우대조건 불인정, 기본금리 계산)로 동작한다.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from typing import Any

from fastapi import FastAPI
from pydantic import BaseModel


def _load_env_file() -> None:
    """server/.env 의 KEY=VALUE 줄을 환경변수로 읽는다 (터미널에서 매번 입력하지 않아도 되게)"""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


_load_env_file()

MODEL = os.environ.get("CLAUDE_MODEL", "claude-sonnet-4-6")
# 바쁠 때(503) 다음 모델로 넘어가며 시도. 2026-11 기준 무료 키로 도구 호출이 확인된 모델이 맨 앞.
GEMINI_MODELS = [m.strip() for m in os.environ.get(
    "GEMINI_MODELS", "gemini-3.5-flash,gemini-3.8-flash,gemini-flash-latest,gemini-3.5-flash-lite").split(",") if m.strip()]

app = FastAPI(title="자산지킴이 에이전트 서버")


# ---------------------------------------------------------------- 계산 (앱의 SwitchCalculator.kt 와 동일한 식)
def early_rate(base: float, elapsed: int, term: int, rule: str = "BANK_TABLE", custom: float = 0.0) -> float:
    if rule == "CUSTOM":
        return custom
    if elapsed < 1:
        return 0.1
    ratio = 0.5 if elapsed < 6 else 0.6 if elapsed < 8 else 0.7 if elapsed < 10 else 0.8 if elapsed < 11 else 0.9
    floor = 0.1 if elapsed < 6 else 0.2
    return max(base * ratio * elapsed / term, floor)


def calc_switch(monthly: int, my_rate: float, my_base: float, term: int, elapsed: int, new_rate: float,
                parking: float, tax: float, rule: str = "BANK_TABLE", custom: float = 0.0,
                new_cap: int | None = None) -> dict:
    """앱 SwitchCalculator.kt 와 같은 계산. new_cap = 새 상품 월 납입 한도 (넘는 돈은 파킹통장 금리)"""
    el = max(0, min(elapsed, term))
    remaining = term - el
    t = 1 - tax / 100
    keep = monthly * (my_rate / 100) * sum(term - k + 1 for k in range(1, el + 1)) / 12
    er = early_rate(my_base, el, term, rule, custom)
    early = monthly * (er / 100) * sum(el - k + 1 for k in range(1, el + 1)) / 12
    park = monthly * el * (parking / 100) * remaining / 12
    loss = (keep - early - park) * t
    c = min(monthly, new_cap) if new_cap and new_cap > 0 else monthly
    rsum = remaining * (remaining + 1) / 2
    k = rsum / 12 * t
    gain = (c * new_rate + (monthly - c) * parking - monthly * my_rate) / 100 * k
    be = None if remaining <= 0 or c <= 0 else (loss * 100 / k - (monthly - c) * parking + monthly * my_rate) / c
    out = {"loss_won": round(loss), "gain_won": round(gain), "net_won": round(gain - loss),
           "break_even_rate": None if be is None else round(be, 2), "early_rate": round(er, 3)}
    if c < monthly:
        out["note"] = f"새 상품 월 한도 {c:,}원까지만 새 금리, 나머지 {monthly - c:,}원은 파킹통장 금리로 계산"
    return out


POLICY_WORDS = ("청년도약", "청년희망", "청년내일", "청년미래", "장병내일", "주택청약", "청약", "ISA", "비과세")


def is_policy(name: str) -> bool:
    """정부 지원·비과세 상품: 중도해지하면 비과세 혜택·정부기여금을 잃을 수 있어 계산 밖의 손실이 있음"""
    return any(w.lower() in (name or "").lower() for w in POLICY_WORDS)


# ---------------------------------------------------------------- 요청 모델
class MyProductIn(BaseModel):
    name: str
    final_rate: float
    base_rate: float
    monthly: int
    term_months: int
    elapsed: int
    early_rule: str = "BANK_TABLE"
    custom_early_rate: float = 0.0


class CandidateIn(BaseModel):
    name: str
    base_rate: float
    max_rate: float
    special_condition: str = ""
    join_member: str = ""
    monthly_cap: int | None = None


class ReviewIn(BaseModel):
    my_product: MyProductIn
    candidate: CandidateIn
    user_profile: str = ""
    parking_rate: float = 2.5
    tax_rate: float = 15.4
    threshold_won: int = 5000


def verdict_of(net_sure: int, net_expected: int, threshold: int) -> str:
    if net_sure >= threshold:
        return "SWITCH"
    if net_expected >= threshold:
        return "CONDITIONAL"
    return "KEEP"


# ---------------------------------------------------------------- 도구 정의 (Claude에게 보여주는 설명서)
TOOLS = [
    {
        "name": "get_early_rate",
        "description": "사용자가 지금 기존 적금을 해지하면 적용되는 중도해지이율(연 %)을 돌려준다.",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "calc_switch",
        "description": ("기존 적금을 지금 해지하고, 남은 납입분을 new_rate(연 %) 상품으로 옮겼을 때의 "
                        "세후 해지 손실, 전환 이익, 순이익(원)과 손익분기 금리를 정확히 계산한다. "
                        "우대조건을 일부만 충족한다면 그 금리를 직접 넣어 여러 번 호출해도 된다."),
        "input_schema": {
            "type": "object",
            "properties": {"new_rate": {"type": "number", "description": "새 상품에서 실제로 받을 것으로 예상되는 연 금리(%)"}},
            "required": ["new_rate"],
        },
    },
    {
        "name": "submit_decision",
        "description": "최종 판단을 제출한다. 반드시 마지막에 한 번 호출한다.",
        "input_schema": {
            "type": "object",
            "properties": {
                "expected_rate": {"type": "number", "description": "사용자가 실제로 받을 것으로 판단한 금리(연 %)"},
                "reasoning": {"type": "string", "description": "우대조건별 충족 여부와 판단 이유를 한국어 3~5문장으로"},
            },
            "required": ["expected_rate", "reasoning"],
        },
    },
]

SYSTEM = """너는 '자산지킴이' 앱의 적금 갈아타기 판단 에이전트다.
목표: 사용자가 기존 적금을 해지하고 새 상품으로 갈아타는 게 세후 기준으로 이득인지 판단한다.
규칙:
1. 숫자는 절대 직접 계산하지 말고 반드시 calc_switch 도구로 계산한다.
2. 새 상품의 우대조건 문구를 사용자 상황과 하나씩 대조해, 확실히 충족하는 우대금리만 더해 expected_rate를 정한다.
   사용자 상황에 정보가 없는 조건은 '충족 불확실'로 보고 더하지 않는다. expected_rate는 기본금리~최고금리 사이다.
3. 필요하면 get_early_rate로 해지 시 이율을 확인하고, 기본금리와 expected_rate로 calc_switch를 각각 호출해 비교한다.
4. 마지막에 submit_decision을 호출한다. 이유에는 어떤 조건을 왜 인정/불인정했는지와 순이익 숫자를 포함한다.
5. 이것은 정보 제공이며 최종 결정은 사용자가 한다는 점을 잊지 말고, 과장하지 않는다."""


def run_tools(name: str, args: dict, req: ReviewIn) -> dict:
    m = req.my_product
    if name == "get_early_rate":
        return {"early_rate": round(early_rate(m.base_rate, m.elapsed, m.term_months, m.early_rule, m.custom_early_rate), 3),
                "elapsed_months": m.elapsed, "term_months": m.term_months}
    if name == "calc_switch":
        return calc_switch(m.monthly, m.final_rate, m.base_rate, m.term_months, m.elapsed, float(args["new_rate"]),
                           req.parking_rate, req.tax_rate, m.early_rule, m.custom_early_rate, req.candidate.monthly_cap)
    return {"error": f"unknown tool {name}"}


def rule_based(req: ReviewIn, note: str) -> dict:
    """Claude API 키가 없거나 호출이 실패하면: 우대조건은 인정하지 않고 기본금리로 보수적으로 판단"""
    m, c = req.my_product, req.candidate
    sure = calc_switch(m.monthly, m.final_rate, m.base_rate, m.term_months, m.elapsed, c.base_rate,
                       req.parking_rate, req.tax_rate, m.early_rule, m.custom_early_rate, req.candidate.monthly_cap)
    return {
        "verdict": verdict_of(sure["net_won"], sure["net_won"], req.threshold_won),
        "expected_rate": c.base_rate,
        "net_won": sure["net_won"],
        "reasoning": (f"우대조건 충족 여부를 확인할 수 없어 기본금리 {c.base_rate}%로 보수적으로 계산했어요. "
                      f"해지 손실 {sure['loss_won']:,}원, 전환 이익 {sure['gain_won']:,}원으로 순이익은 {sure['net_won']:,}원이에요."),
        "tool_calls": ["calc_switch"],
        "mode": note,
    }


def provider() -> str:
    if os.environ.get("GEMINI_API_KEY"):
        return "gemini"
    if os.environ.get("ANTHROPIC_API_KEY"):
        return "claude"
    return "rule"


@app.get("/health")
def health() -> dict:
    p = provider()
    return {"ok": True, "provider": p, "models": GEMINI_MODELS if p == "gemini" else [MODEL] if p == "claude" else []}


@app.post("/agent/review")
def review(req: ReviewIn) -> dict:
    p = provider()
    if p == "rule":
        return _policy_guard(req, rule_based(req, "규칙 기반 모드 (AI 키 없음)"))
    try:
        out = gemini_agent(req) if p == "gemini" else claude_agent(req)
    except Exception as e:  # 네트워크/키 오류 시에도 앱이 멈추지 않도록
        out = rule_based(req, f"규칙 기반 모드 ({p} 호출 실패: {str(e)[:120]})")
    return _policy_guard(req, out)


def _policy_guard(req: ReviewIn, out: dict) -> dict:
    """정책 상품(청년도약계좌 등)은 계산 밖의 손실(비과세 추징·정부기여금)이 있어 갈아타기를 권하지 않는다"""
    if is_policy(req.my_product.name) and out.get("verdict") in ("SWITCH", "CONDITIONAL"):
        out["verdict"] = "KEEP"
        out["reasoning"] = ("정부 지원·비과세 상품이라 중도해지하면 비과세 혜택이나 정부기여금을 잃을 수 있어 유지를 권해요. "
                            "이 손실은 계산에 들어 있지 않아요. 특별중도해지 사유가 있는지 약관을 먼저 확인하세요. "
                            + str(out.get("reasoning", "")))
    return out


def _memory_facts() -> str:
    try:
        import memory
        facts = memory.memory_for_ai()["known_facts"]
        return " / ".join(facts[:12]) if facts else "(없음)"
    except Exception:
        return "(없음)"


def build_user_msg(req: ReviewIn) -> str:
    m, c = req.my_product, req.candidate
    return (
        f"[기존 적금] {m.name} / 최종 {m.final_rate}% (기본 {m.base_rate}%) / 월 {m.monthly:,}원 / "
        f"{m.term_months}개월 중 {m.elapsed}개월 납입\n"
        f"[새 상품] {c.name} / 기본 {c.base_rate}% / 최고 {c.max_rate}%\n"
        f"[우대조건 원문]\n{c.special_condition or '(없음)'}\n"
        f"[가입대상] {c.join_member or '-'}\n"
        f"[사용자 상황] {req.user_profile or '(입력 없음)'}\n"
        f"[앱이 기억하는 사실] {_memory_facts()}\n"
        f"[알림 기준] 세후 {req.threshold_won:,}원 이상 이득일 때"
    )


def finalize(req: ReviewIn, expected: float, reasoning: str, calls: list[str], mode: str) -> dict:
    """AI가 고른 예상 금리로 최종 숫자는 코드가 다시 계산 (AI가 숫자를 지어내지 못하게)"""
    m, c = req.my_product, req.candidate
    expected = max(c.base_rate, min(float(expected), c.max_rate))
    sure = calc_switch(m.monthly, m.final_rate, m.base_rate, m.term_months, m.elapsed, c.base_rate,
                       req.parking_rate, req.tax_rate, m.early_rule, m.custom_early_rate, req.candidate.monthly_cap)
    exp_calc = calc_switch(m.monthly, m.final_rate, m.base_rate, m.term_months, m.elapsed, expected,
                           req.parking_rate, req.tax_rate, m.early_rule, m.custom_early_rate, req.candidate.monthly_cap)
    return {
        "verdict": verdict_of(sure["net_won"], exp_calc["net_won"], req.threshold_won),
        "expected_rate": expected,
        "net_won": exp_calc["net_won"],
        "reasoning": reasoning,
        "tool_calls": calls,
        "mode": mode,
    }


# ---------------------------------------------------------------- Gemini (무료 키)
def gemini_tools() -> list[dict]:
    decls = []
    for t in TOOLS:
        d = {"name": t["name"], "description": t["description"]}
        if t["input_schema"].get("properties"):   # 인자 없는 도구는 parameters 생략
            d["parameters"] = t["input_schema"]
        decls.append(d)
    return [{"functionDeclarations": decls}]


def gemini_call(model: str, body: dict, timeout: float = 30) -> dict:
    req = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "x-goog-api-key": os.environ["GEMINI_API_KEY"]},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as res:
        return json.loads(res.read().decode("utf-8"))


# 모델 상태 기억: 바쁜(503)/한도 초과(429)/느린 모델은 잠시 건너뛰고, 마지막으로 성공한 모델부터 쓴다
_COOL: dict[str, float] = {}
_LAST_OK: list[str] = []
COOLDOWN = {503: 120, 500: 60, 429: 900, "timeout": 300}


def _model_order() -> list[str]:
    now = time.time()
    order = sorted(GEMINI_MODELS, key=lambda m: (m not in _LAST_OK, GEMINI_MODELS.index(m)))
    ready = [m for m in order if _COOL.get(m, 0) <= now]
    return ready or order          # 전부 쉬는 중이면 그래도 한 번씩 시도


def gemini_generate(body: dict, deadline: float | None = None) -> tuple[dict, str]:
    """바쁘거나 한도 초과인 모델은 건너뛰고 다음 모델로. deadline(초 단위 시각)을 넘기면 TimeoutError."""
    last = None
    for model in _model_order():
        left = 30.0 if deadline is None else min(30.0, deadline - time.time())
        if left < 3:
            raise TimeoutError(f"AI 응답 시간 초과 ({last or '응답 없음'})")
        try:
            res = gemini_call(model, body, timeout=left)
            _COOL.pop(model, None)
            _LAST_OK[:] = [model]
            return res, model
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "ignore")[:200]
            last = f"{model} {e.code}: {detail}"
            if e.code in (429, 500, 503):
                _COOL[model] = time.time() + COOLDOWN[e.code]
                continue
            if e.code == 404:   # 신규 사용자에게 막힌 모델 → 다음 모델
                _COOL[model] = time.time() + 3600
                continue
            raise RuntimeError(last)
        except (urllib.error.URLError, TimeoutError, OSError) as e:   # 너무 느리면 잠시 빼고 다음 모델로
            last = f"{model} 연결 문제: {e}"
            _COOL[model] = time.time() + COOLDOWN["timeout"]
    raise RuntimeError(last or "Gemini 응답 없음")


def gemini_agent(req: ReviewIn) -> dict:
    contents: list[dict[str, Any]] = [{"role": "user", "parts": [{"text": build_user_msg(req)}]}]
    calls: list[str] = []
    used = ""
    deadline = time.time() + 140
    for _ in range(8):  # 에이전트 루프: AI가 도구 선택 → 코드가 실행 → 결과를 보고 다음 행동
        body = {"systemInstruction": {"parts": [{"text": SYSTEM}]}, "contents": contents, "tools": gemini_tools()}
        res, used = gemini_generate(body, deadline)
        cand = (res.get("candidates") or [{}])[0]
        content = cand.get("content") or {"role": "model", "parts": []}
        contents.append(content)  # thoughtSignature 포함 그대로 돌려줘야 Gemini 3 계열이 이어서 생각함
        fcs = [p["functionCall"] for p in content.get("parts", []) if "functionCall" in p]
        if not fcs:
            break
        responses = []
        for fc in fcs:
            name, args = fc.get("name", ""), fc.get("args") or {}
            calls.append(name)
            if name == "submit_decision":
                return finalize(req, args.get("expected_rate", req.candidate.base_rate),
                                str(args.get("reasoning", "")), calls, f"Gemini 에이전트 ({used})")
            out = run_tools(name, args, req)
            fr = {"name": name, "response": out}
            if fc.get("id"):
                fr["id"] = fc["id"]
            responses.append({"functionResponse": fr})
        contents.append({"role": "user", "parts": responses})

    fallback = rule_based(req, f"Gemini 에이전트 ({used}) - 최종 제출 없음")
    fallback["tool_calls"] = calls or fallback["tool_calls"]
    return fallback


# ---------------------------------------------------------------- Claude (유료 키)
def claude_agent(req: ReviewIn) -> dict:
    import anthropic

    client = anthropic.Anthropic()
    m, c = req.my_product, req.candidate
    messages: list[dict[str, Any]] = [{"role": "user", "content": build_user_msg(req)}]
    calls: list[str] = []
    last_calc: dict | None = None

    for _ in range(8):  # 에이전트 루프: Claude가 도구를 고르고 → 실행 결과를 다시 보고 → 다음 행동 결정
        res = client.messages.create(model=MODEL, max_tokens=1500, system=SYSTEM, tools=TOOLS, messages=messages)
        messages.append({"role": "assistant", "content": res.content})
        tool_uses = [b for b in res.content if b.type == "tool_use"]
        if not tool_uses:
            break
        results = []
        for tu in tool_uses:
            calls.append(tu.name)
            if tu.name == "submit_decision":
                return finalize(req, tu.input.get("expected_rate", c.base_rate),
                                str(tu.input.get("reasoning", "")), calls, f"Claude 에이전트 ({MODEL})")
            out = run_tools(tu.name, dict(tu.input), req)
            if tu.name == "calc_switch":
                last_calc = out
            results.append({"type": "tool_result", "tool_use_id": tu.id, "content": json.dumps(out, ensure_ascii=False)})
        messages.append({"role": "user", "content": results})

    # submit_decision 없이 끝난 경우
    fallback = rule_based(req, f"Claude 에이전트 ({MODEL}) - 최종 제출 없음")
    fallback["tool_calls"] = calls or fallback["tool_calls"]
    if last_calc:
        fallback["reasoning"] += f" (마지막 계산 순이익 {last_calc['net_won']:,}원)"
    return fallback


# ---------------------------------------------------------------- 목표 루트 에이전트 (/agent/plan)
from plan_agent import router as plan_router  # noqa: E402
from memory import router as memory_router  # noqa: E402
from agent_features import router as features_router  # noqa: E402

app.include_router(plan_router)
app.include_router(memory_router)
app.include_router(features_router)
