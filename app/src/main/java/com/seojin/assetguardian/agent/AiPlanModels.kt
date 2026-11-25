package com.seojin.assetguardian.agent

import com.google.gson.annotations.SerializedName

/** 서버 /agent/plan 응답 — AI 에이전트가 짠 목표 루트 (숫자는 서버 코드가 다시 계산한 값) */
data class AiPlan(
    val mode: String? = null,
    @SerializedName("target_won") val targetWon: Long = 0,
    @SerializedName("monthly_won") val monthlyWon: Long = 0,
    @SerializedName("term_months") val termMonths: Int = 12,
    val summary: String? = null,
    val cautions: List<String>? = null,
    val excluded: List<AiExcluded>? = null,
    val reasons: Map<String, String>? = null,
    val withdrawals: List<AiWithdrawal>? = null,
    val plan: AiPlanResult? = null,
    val trace: List<String>? = null,
    @SerializedName("tool_calls") val toolCalls: List<String>? = null,
    // 앱에서 채우는 값
    val message: String? = null,
    val createdAt: Long = 0
)

data class AiExcluded(val name: String? = null, val reason: String? = null)

data class AiWithdrawal(
    val month: Int = 0,
    @SerializedName("amount_won") val amountWon: Long = 0,
    val reason: String? = null
)

data class AiPlanResult(
    @SerializedName("term_months") val termMonths: Int = 12,
    @SerializedName("months_needed") val monthsNeeded: Int? = null,
    @SerializedName("final_amount") val finalAmount: Long = 0,
    val principal: Long = 0,
    @SerializedName("saving_interest") val savingInterest: Long = 0,
    @SerializedName("deposit_interest") val depositInterest: Long = 0,
    @SerializedName("no_interest_months") val noInterestMonths: Int? = null,
    val slots: List<AiSlot>? = null,
    val deposit: AiDeposit? = null,
    @SerializedName("deposit_rate") val depositRate: Double = 0.0,
    val cycles: List<AiCycle>? = null,
    @SerializedName("withdrawal_notes") val withdrawalNotes: List<String>? = null
)

data class AiSlot(
    val code: String? = null,
    val bank: String? = null,
    val name: String? = null,
    val rate: Double = 0.0,
    val cap: Long? = null,
    val monthly: Long = 0,
    @SerializedName("interest_per_cycle") val interestPerCycle: Long = 0
)

data class AiDeposit(val code: String? = null, val bank: String? = null, val name: String? = null, val rate: Double = 0.0)

data class AiCycle(val number: Int = 0, val start: Int = 0, val end: Int = 0, val completed: Boolean = false)
