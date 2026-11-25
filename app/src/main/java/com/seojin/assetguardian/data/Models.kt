package com.seojin.assetguardian.data

/**
 * 앱 전체에서 쓰는 데이터 모양.
 * 금리는 모두 "연 %" 숫자로 저장한다. (예: 3.5 = 연 3.5%)
 * 금액은 "원" 단위 Long.
 */

/** 사용자가 이미 가입해 둔 적금 */
data class MyProduct(
    val id: String,
    val bank: String,
    val name: String,
    val finalRate: Double,          // 우대 포함 최종 금리 (만기까지 유지하면 받는 금리)
    val baseRate: Double,           // 기본 금리 (중도해지이율 계산에 쓰임)
    val monthly: Long,              // 월 납입액 (원)
    val termMonths: Int,            // 계약 기간 (개월)
    val joinDate: String,           // 가입일 yyyy-MM-dd
    val earlyRule: String = EarlyRule.BANK_TABLE,
    val customEarlyRate: Double = 0.0,  // earlyRule == CUSTOM 일 때 사용자가 직접 입력한 중도해지이율
    val productCode: String = ""
)

object EarlyRule {
    /** 시중은행 상품설명서 방식: 기본이율 × (50~90%) × 경과월/계약월 (보수적 기본값) */
    const val BANK_TABLE = "BANK_TABLE"
    /** 상품설명서에서 확인한 이율을 사용자가 직접 입력 */
    const val CUSTOM = "CUSTOM"
}

/** 금감원 API(또는 데모 데이터)에서 가져온 시장 상품 하나 (상품 × 기간 × 적립방식) */
data class MarketProduct(
    val code: String,
    val bank: String,
    val name: String,
    val termMonths: Int,
    val baseRate: Double,           // 기본 금리 intr_rate
    val maxRate: Double,            // 최고 우대 금리 intr_rate2
    val rsrvType: String = "",      // 정액적립식 / 자유적립식
    val specialCondition: String = "",  // 우대조건 원문
    val joinMember: String = "",
    val joinWay: String = "",
    val maxLimit: Long? = null,
    val kind: String = ProductKind.SAVING,   // 적금 / 예금
    val finCoNo: String = "",
    val homepage: String = "",               // 은행 홈페이지 (금감원 금융회사 API)
    val etcNote: String = "",
    val monthlyCap: Long? = null,            // 월 납입 한도 (적금), null = 한도 정보 없음
    val restricted: Boolean = false          // 어린이·장병·서민전용 등 아무나 가입 못 하는 상품
) {
    val key: String get() = "$kind-$code-$termMonths-$rsrvType"
    /** 사용자가 직접 입력한 상품 (VIP·영업점 특판처럼 금감원 공시에 없는 상품) */
    val isCustom: Boolean get() = code.startsWith(CUSTOM_PREFIX)

    companion object { const val CUSTOM_PREFIX = "CUSTOM-" }
}

object ProductKind {
    const val SAVING = "적금"
    const val DEPOSIT = "예금"
}

object Verdict {
    const val SWITCH = "SWITCH"           // 기본금리만으로도 갈아타면 이득
    const val CONDITIONAL = "CONDITIONAL" // 우대조건을 채워야 이득
    const val KEEP = "KEEP"               // 금리는 높아 보이지만 갈아타면 손해 (특판 함정)
    const val MATURITY = "MATURITY"       // 만기 임박 → 재예치 상품 안내
}

/** 에이전트가 내린 판단 한 건 (알림함에 쌓임) */
data class Decision(
    val id: String,
    val createdAt: Long,
    val myId: String,
    val myName: String,
    val candidateKey: String,
    val candidateBank: String,
    val candidateName: String,
    val candidateBaseRate: Double,
    val candidateMaxRate: Double,
    val verdict: String,
    val netSure: Long,      // 기본금리 기준 세후 순이익
    val netMax: Long,       // 최고금리 기준 세후 순이익
    val loss: Long,         // 해지 손실 (세후)
    val gainSure: Long,     // 전환 이익 (기본금리, 세후)
    val breakEven: Double?, // 손익분기 금리 (연 %)
    val elapsed: Int,
    val message: String,
    val isNewProduct: Boolean = false,
    val feedback: String = ""   // "" / "ACCEPT" / "IGNORE"
)

data class AppSettings(
    val apiKey: String = "",
    val thresholdWon: Long = 5_000,     // 이 금액 이상 이득일 때만 알림
    val parkingRate: Double = 2.5,      // 해지한 돈을 넣어둘 파킹통장 금리
    val taxRate: Double = 15.4,         // 이자소득세 (일반과세 15.4%)
    val serverUrl: String = "",         // AI 에이전트 서버 주소 (비우면 에뮬레이터 기본 주소)
    val profile: String = "",           // (선택) 우대조건 판단용 내 상황 설명
    val lastCheck: Long = 0L,
    val demoMode: Boolean = false,
    // 목표 설정 (목표 탭에서 저장)
    val goalAmount: Long = 0L,          // 목표 금액 (원), 0 = 미설정
    val income: Long = 0L,              // 월 수입 (원)
    val living: Long = 0L,              // 생활비 기준 (원)
    val useMaxRate: Boolean = false,    // 우대금리까지 받는다고 가정할지
    val lastPlanMonths: Int = 0,        // 에이전트가 마지막으로 계산한 달성 개월 (더 빠른 루트 알림용)
    val lastWeeklyAt: Long = 0L,        // 주간 리포트 마지막 생성 시각
    val lastProgressAskMonth: String = "",  // "지난달 얼마 저축했어요?" 물어본 달
    val lastGoalAlertMonth: String = ""     // "목표가 늦어지고 있어요" 알린 달
) {
    val monthlySaving: Long get() = (income - living).coerceAtLeast(0)

    /** 서버 주소를 안 적었으면 에뮬레이터에서 내 PC를 가리키는 기본 주소 */
    val agentUrl: String get() = serverUrl.ifBlank { "http://10.0.2.2:8000" }
}

/** 사용자 행동 기록 — 에이전트 장기 기억의 재료 (휴대폰에 쌓고 서버 DB로 동기화) */
data class MemoryEvent(
    val clientId: String,
    val type: String,
    val code: String = "",
    val kind: String = ProductKind.SAVING,
    val term: Int = 0,
    val bank: String = "",
    val name: String = "",
    val rate: Double = 0.0,
    val maxRate: Double = 0.0,
    val detail: String = "",
    val at: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

object EventType {
    const val VIEW = "VIEW"                 // 상품 상세 열어봄
    const val COMPARE = "COMPARE"           // 내 적금과 비교해봄
    const val LINK = "LINK"                 // 은행 홈페이지/상품 페이지 버튼 누름
    const val REGISTER = "REGISTER"         // 내 적금으로 등록
    const val ALERT_ACCEPT = "ALERT_ACCEPT" // 알림 "확인했어요"
    const val ALERT_IGNORE = "ALERT_IGNORE" // 알림 "관심 없어요"
    const val AI_PLAN = "AI_PLAN"           // AI에게 루트 요청
    const val REC_SHOWN = "REC_SHOWN"       // 성향 맞춤 추천에 보여짐 (추천 피드백 학습용)
    const val REC_CLICK = "REC_CLICK"       // 추천 상품을 눌러봄
}
