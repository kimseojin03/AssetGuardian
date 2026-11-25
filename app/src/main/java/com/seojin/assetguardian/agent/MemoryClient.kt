package com.seojin.assetguardian.agent

import com.google.gson.Gson
import com.seojin.assetguardian.data.AppSettings
import com.seojin.assetguardian.data.MemoryEvent
import com.seojin.assetguardian.data.MemorySummary
import com.seojin.assetguardian.data.MyProduct
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.data.ProductKind
import com.seojin.assetguardian.data.RecResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 서버의 장기 기억(SQLite)과 주고받는 통신. 서버가 꺼져 있으면 예외 → 호출한 쪽이 휴대폰 기록으로 대체 */
object MemoryClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    private fun url(s: AppSettings, path: String) = s.agentUrl.trimEnd('/') + path

    private fun send(req: Request): String = client.newCall(req).execute().use { res ->
        if (!res.isSuccessful) throw Exception("서버 오류 ${res.code}")
        res.body?.string() ?: "{}"
    }

    suspend fun postEvents(s: AppSettings, events: List<MemoryEvent>) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        val body = mapOf("events" to events.map {
            mapOf("client_id" to it.clientId, "type" to it.type, "code" to it.code, "kind" to it.kind, "term" to it.term,
                "bank" to it.bank, "name" to it.name, "rate" to it.rate, "max_rate" to it.maxRate, "detail" to it.detail, "at" to it.at / 1000.0)
        })
        send(Request.Builder().url(url(s, "/memory/events")).post(gson.toJson(body).toRequestBody(json)).build())
    }

    suspend fun postHoldings(s: AppSettings, mine: List<MyProduct>) = withContext(Dispatchers.IO) {
        send(Request.Builder().url(url(s, "/memory/holdings")).post(gson.toJson(mapOf("holdings" to holdings(mine))).toRequestBody(json)).build())
    }

    fun holdings(mine: List<MyProduct>): List<Map<String, Any>> = mine.map {
        mapOf("id" to it.id, "bank" to it.bank, "name" to it.name, "final_rate" to it.finalRate, "base_rate" to it.baseRate,
            "monthly" to it.monthly, "term" to it.termMonths, "join_date" to it.joinDate, "code" to it.productCode)
    }

    suspend fun summary(s: AppSettings): MemorySummary = withContext(Dispatchers.IO) {
        gson.fromJson(send(Request.Builder().url(url(s, "/memory/summary")).get().build()), MemorySummary::class.java)
            .copy(fromServer = true)
    }

    /** 성향 분석용: 시장 기간별 기본금리 분포 (적금만, 가볍게) */
    suspend fun postMarket(s: AppSettings, market: List<MarketProduct>) = withContext(Dispatchers.IO) {
        val list = market.filter { it.kind == ProductKind.SAVING }.map {
            mapOf("kind" to it.kind, "term" to it.termMonths, "base_rate" to it.baseRate)
        }
        if (list.isEmpty()) return@withContext
        send(Request.Builder().url(url(s, "/memory/market")).post(gson.toJson(mapOf("products" to list)).toRequestBody(json)).build())
    }

    suspend fun setStated(s: AppSettings, type: String?) = withContext(Dispatchers.IO) {
        send(Request.Builder().url(url(s, "/memory/profile/stated")).post(gson.toJson(mapOf("type" to type)).toRequestBody(json)).build())
    }

    /** 성향 맞춤 추천 — 적합도·이유는 서버 코드가 계산, AI는 설명 한 줄 */
    suspend fun recommend(s: AppSettings, market: List<MarketProduct>, limit: Int = 8): RecResponse = withContext(Dispatchers.IO) {
        val products = AgentServerClient.pickForAgent(market).map { p ->
            mapOf("code" to p.code, "kind" to p.kind, "bank" to p.bank, "name" to p.name, "term" to p.termMonths,
                "base_rate" to p.baseRate, "max_rate" to p.maxRate, "monthly_cap" to p.monthlyCap,
                "rsrv" to p.rsrvType, "restricted" to p.restricted)
        }
        val body = gson.toJson(mapOf("products" to products, "limit" to limit))
        val slow = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        slow.newCall(Request.Builder().url(url(s, "/agent/recommend")).post(body.toRequestBody(json)).build()).execute().use { res ->
            if (!res.isSuccessful) throw Exception("서버 오류 ${res.code}")
            gson.fromJson(res.body?.string() ?: "{}", RecResponse::class.java)
        }
    }

    suspend fun addFact(s: AppSettings, text: String) = withContext(Dispatchers.IO) {
        send(Request.Builder().url(url(s, "/memory/facts")).post(gson.toJson(mapOf("text" to text)).toRequestBody(json)).build())
    }

    suspend fun deleteFact(s: AppSettings, id: Long) = withContext(Dispatchers.IO) {
        send(Request.Builder().url(url(s, "/memory/facts/$id")).delete().build())
    }

    suspend fun clearAll(s: AppSettings) = withContext(Dispatchers.IO) {
        send(Request.Builder().url(url(s, "/memory/all")).delete().build())
    }
}
