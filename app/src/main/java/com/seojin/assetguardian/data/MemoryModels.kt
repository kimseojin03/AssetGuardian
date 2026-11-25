package com.seojin.assetguardian.data

import com.google.gson.annotations.SerializedName

/** 서버 /memory/summary 응답 (서버가 꺼져 있으면 휴대폰 기록으로 같은 모양을 만든다) */
data class MemorySummary(
    val facts: List<MemFact>? = null,
    val interests: List<MemInterest>? = null,
    val recent: List<MemRecent>? = null,
    val taste: MemTaste? = null,
    val plans: List<MemPlan>? = null,
    val counts: Map<String, Int>? = null,
    val profile: SavingProfile? = null,
    val fromServer: Boolean = true
)

/** 서버가 행동 기록을 코드로 분석한 저축 성향 (saving_profile.py) */
data class SavingProfile(
    val ready: Boolean = false,
    val n: Int = 0,
    val need: Int = 0,
    val message: String? = null,
    val type: String? = null,             // STABLE / YIELD / PERK / SHORT
    val label: String? = null,            // 안정형 ...
    val description: String? = null,
    val scores: Map<String, Double>? = null,
    val evidence: List<String>? = null,
    val confidence: Double = 0.0,
    @SerializedName("confidence_label") val confidenceLabel: String? = null,
    val stated: String? = null,
    @SerializedName("stated_label") val statedLabel: String? = null,
    val conflict: String? = null,
    val history: List<ProfileSnap>? = null,
    val types: Map<String, String>? = null
)

data class ProfileSnap(val at: Double = 0.0, val type: String? = null, val label: String? = null)

/** 성향 맞춤 추천 (서버 /agent/recommend) */
data class RecResponse(
    val profile: SavingProfile? = null,
    val items: List<RecItem>? = null,
    @SerializedName("ai_comment") val aiComment: String? = null,
    val learning: List<String>? = null      // 추천에 대한 내 반응으로 점수를 보정한 내역
)

data class RecItem(
    val code: String? = null,
    val bank: String? = null,
    val name: String? = null,
    val term: Int = 0,
    @SerializedName("base_rate") val baseRate: Double = 0.0,
    @SerializedName("max_rate") val maxRate: Double = 0.0,
    @SerializedName("monthly_cap") val monthlyCap: Long? = null,
    val fit: Double = 0.0,
    val reasons: List<String>? = null
)

data class MemFact(
    val id: Long = 0,
    val text: String? = null,
    val category: String? = null,
    val source: String? = null,
    @SerializedName("created_at") val createdAt: Double = 0.0
)

data class MemInterest(
    val code: String? = null,
    val kind: String? = null,
    val term: Int = 0,
    val bank: String? = null,
    val name: String? = null,
    val rate: Double = 0.0,
    val score: Double = 0.0,
    val views: Int = 0,
    val links: Int = 0,
    @SerializedName("last_at") val lastAt: Double = 0.0
)

data class MemRecent(
    val code: String? = null,
    val kind: String? = null,
    val term: Int = 0,
    val bank: String? = null,
    val name: String? = null,
    val rate: Double = 0.0,
    val at: Double = 0.0
)

data class MemTaste(
    val events: Int = 0,
    @SerializedName("favorite_term") val favoriteTerm: Int? = null,
    @SerializedName("savings_bank_share") val savingsBankShare: Double? = null,
    @SerializedName("avg_rate_viewed") val avgRateViewed: Double? = null,
    @SerializedName("alert_accept") val alertAccept: Int = 0,
    @SerializedName("alert_ignore") val alertIgnore: Int = 0
)

data class MemPlan(
    val id: Long = 0,
    @SerializedName("created_at") val createdAt: Double = 0.0,
    val message: String? = null,
    val target: Long? = null,
    val monthly: Long? = null,
    val term: Int? = null,
    @SerializedName("months_needed") val monthsNeeded: Int? = null,
    @SerializedName("final_amount") val finalAmount: Long? = null,
    val products: List<MemPlanProduct>? = null,
    val summary: String? = null,
    val mode: String? = null
)

data class MemPlanProduct(val name: String? = null, val bank: String? = null, val monthly: Long? = null, val rate: Double? = null)
