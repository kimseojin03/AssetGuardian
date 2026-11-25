package com.seojin.assetguardian.agent

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.seojin.assetguardian.data.AppSettings
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.data.MyProduct
import com.seojin.assetguardian.data.ProductKind
import com.seojin.assetguardian.domain.SwitchCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * (선택 기능) Claude 에이전트 서버에 "이 우대조건, 나한테 해당돼?"를 물어본다.
 * 서버(server/ 폴더)가 Claude Tool Use로 우대조건을 해석하고 계산 도구를 직접 호출해 판단한다.
 */
object AgentServerClient {

    data class Review(
        val verdict: String = "",
        @SerializedName("expected_rate") val expectedRate: Double = 0.0,
        @SerializedName("net_won") val netWon: Long = 0,
        val reasoning: String = "",
        @SerializedName("tool_calls") val toolCalls: List<String>? = null,
        val mode: String = ""
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)   // AI가 도구를 여러 번 부르면 30초~1분 걸릴 수 있음
        .build()
    private val gson = Gson()

    suspend fun review(s: AppSettings, my: MyProduct, c: MarketProduct): Review = withContext(Dispatchers.IO) {
        val elapsed = SwitchCalculator.elapsedMonths(my.joinDate, LocalDate.now(), my.termMonths)
        val body = mapOf(
            "my_product" to mapOf(
                "name" to "${my.bank} ${my.name}", "final_rate" to my.finalRate, "base_rate" to my.baseRate,
                "monthly" to my.monthly, "term_months" to my.termMonths, "elapsed" to elapsed,
                "early_rule" to my.earlyRule, "custom_early_rate" to my.customEarlyRate
            ),
            "candidate" to mapOf(
                "name" to "${c.bank} ${c.name}", "base_rate" to c.baseRate, "max_rate" to c.maxRate,
                "special_condition" to c.specialCondition, "join_member" to c.joinMember, "monthly_cap" to c.monthlyCap
            ),
            "user_profile" to s.profile,
            "parking_rate" to s.parkingRate,
            "tax_rate" to s.taxRate,
            "threshold_won" to s.thresholdWon
        )
        val req = Request.Builder()
            .url(s.agentUrl.trimEnd('/') + "/agent/review")
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw Exception("서버 오류 ${res.code}")
            gson.fromJson(res.body?.string() ?: "{}", Review::class.java)
        }
    }

    /**
     * 목표 루트 에이전트: 사용자의 말 + 시장 상품을 보내면
     * AI가 상품 검색 → 조건 판단 → 루트 시뮬레이션을 반복해 최종 루트를 돌려준다.
     */
    suspend fun plan(
        s: AppSettings, message: String, market: List<MarketProduct>,
        target: Long, income: Long, living: Long, mine: List<MyProduct>,
        replan: Boolean = false
    ): AiPlan = withContext(Dispatchers.IO) {
        val body = mapOf(
            "message" to message,
            "holdings" to MemoryClient.holdings(mine),
            "profile" to s.profile,
            "defaults" to mapOf("target_won" to target, "income_won" to income, "living_won" to living),
            "tax_rate" to s.taxRate,
            "parking_rate" to s.parkingRate,
            "replan" to replan,
            "products" to pickForAgent(market).map { p ->
                mapOf(
                    "code" to p.code, "kind" to p.kind, "bank" to p.bank, "name" to p.name, "term" to p.termMonths,
                    "base_rate" to p.baseRate, "max_rate" to p.maxRate, "monthly_cap" to p.monthlyCap,
                    "join_member" to p.joinMember.take(200), "conditions" to p.specialCondition.take(400),
                    "note" to p.etcNote.take(200), "rsrv" to p.rsrvType, "restricted" to p.restricted
                )
            }
        )
        val req = Request.Builder()
            .url(s.agentUrl.trimEnd('/') + "/agent/plan")
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw Exception("서버 오류 ${res.code}")
            gson.fromJson(res.body?.string() ?: "{}", AiPlan::class.java)
        }
    }

    /** 전체(2,000개 넘음)를 다 보내지 않고, 기간별로 기본금리·최고금리 상위 상품만 추려서 보낸다 */
    fun pickForAgent(market: List<MarketProduct>): List<MarketProduct> {
        val out = LinkedHashMap<String, MarketProduct>()
        for (t in listOf(6, 12, 24, 36)) {
            val sv = market.filter { it.kind == ProductKind.SAVING && it.termMonths == t }
            sv.sortedByDescending { it.baseRate }.take(25).forEach { out[it.key] = it }
            sv.sortedByDescending { it.maxRate }.take(15).forEach { out[it.key] = it }
        }
        market.filter { it.kind == ProductKind.DEPOSIT && it.termMonths == 12 }
            .sortedByDescending { it.baseRate }.take(8).forEach { out[it.key] = it }
        return out.values.toList()
    }
}
