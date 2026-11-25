package com.seojin.assetguardian.agent

import com.google.gson.Gson
import com.seojin.assetguardian.data.AppSettings
import com.seojin.assetguardian.data.ChatHistory
import com.seojin.assetguardian.data.ChatReply
import com.seojin.assetguardian.data.GoalStatus
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.data.MyProduct
import com.seojin.assetguardian.data.TasksResponse
import com.seojin.assetguardian.data.WeeklyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 에이전트 기능 5종(체크리스트·목표 추적·상담·주간 리포트) 서버 통신 */
object FeaturesClient {
    private val fast = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val slow = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val json = "application/json".toMediaType()

    private fun url(s: AppSettings, path: String) = s.agentUrl.trimEnd('/') + path

    private fun call(c: OkHttpClient, req: Request): String = c.newCall(req).execute().use { res ->
        if (!res.isSuccessful) throw Exception("서버 오류 ${res.code}")
        res.body?.string() ?: "{}"
    }

    private fun get(s: AppSettings, path: String, c: OkHttpClient = fast) =
        call(c, Request.Builder().url(url(s, path)).get().build())

    private fun post(s: AppSettings, path: String, body: Any, c: OkHttpClient = fast) =
        call(c, Request.Builder().url(url(s, path)).post(gson.toJson(body).toRequestBody(json)).build())

    private fun compact(market: List<MarketProduct>) = AgentServerClient.pickForAgent(market).map { p ->
        mapOf("code" to p.code, "kind" to p.kind, "bank" to p.bank, "name" to p.name, "term" to p.termMonths,
            "base_rate" to p.baseRate, "max_rate" to p.maxRate, "monthly_cap" to p.monthlyCap,
            "join_member" to p.joinMember.take(200), "conditions" to p.specialCondition.take(400),
            "note" to p.etcNote.take(200), "rsrv" to p.rsrvType, "restricted" to p.restricted)
    }

    // ---- 1) 우대조건 체크리스트
    suspend fun checklist(s: AppSettings, my: MyProduct, conditions: String): TasksResponse = withContext(Dispatchers.IO) {
        val body = mapOf(
            "holding" to mapOf("id" to my.id, "bank" to my.bank, "name" to my.name, "term" to my.termMonths, "join_date" to my.joinDate),
            "conditions" to conditions, "profile" to s.profile
        )
        gson.fromJson(post(s, "/agent/checklist", body, slow), TasksResponse::class.java)
    }

    suspend fun tasks(s: AppSettings): TasksResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get(s, "/tasks"), TasksResponse::class.java)
    }

    suspend fun dueTasks(s: AppSettings, days: Double = 1.0): TasksResponse = withContext(Dispatchers.IO) {
        gson.fromJson(get(s, "/tasks/due?days=$days"), TasksResponse::class.java)
    }

    suspend fun taskDone(s: AppSettings, id: Long) = withContext(Dispatchers.IO) { post(s, "/tasks/$id/done", emptyMap<String, Any>()) }

    suspend fun deleteTask(s: AppSettings, id: Long) = withContext(Dispatchers.IO) {
        call(fast, Request.Builder().url(url(s, "/tasks/$id")).delete().build())
    }

    // ---- 2) 목표 추적
    suspend fun goal(s: AppSettings): GoalStatus = withContext(Dispatchers.IO) {
        gson.fromJson(get(s, "/goal/status", slow), GoalStatus::class.java)
    }

    suspend fun recordProgress(s: AppSettings, month: String, actual: Long): GoalStatus = withContext(Dispatchers.IO) {
        gson.fromJson(post(s, "/goal/progress", mapOf("month" to month, "actual_won" to actual), slow), GoalStatus::class.java)
    }

    // ---- 3) 상담
    suspend fun chat(s: AppSettings, message: String, market: List<MarketProduct>, mine: List<MyProduct>): ChatReply =
        withContext(Dispatchers.IO) {
            val body = mapOf("message" to message, "products" to compact(market), "holdings" to MemoryClient.holdings(mine),
                "tax_rate" to s.taxRate, "parking_rate" to s.parkingRate)
            gson.fromJson(post(s, "/agent/chat", body, slow), ChatReply::class.java)
        }

    suspend fun chatHistory(s: AppSettings): ChatHistory = withContext(Dispatchers.IO) {
        gson.fromJson(get(s, "/agent/chat/history"), ChatHistory::class.java)
    }

    suspend fun clearChat(s: AppSettings) = withContext(Dispatchers.IO) {
        call(fast, Request.Builder().url(url(s, "/agent/chat")).delete().build())
    }

    // ---- 5) 주간 리포트
    suspend fun weekly(s: AppSettings, market: List<MarketProduct>): WeeklyReport = withContext(Dispatchers.IO) {
        gson.fromJson(post(s, "/agent/weekly", mapOf("products" to compact(market)), slow), WeeklyReport::class.java)
    }

    suspend fun latestWeekly(s: AppSettings): WeeklyReport? = withContext(Dispatchers.IO) {
        val t = get(s, "/agent/weekly/latest")
        if (t.trim() == "{}") null else gson.fromJson(t, WeeklyReport::class.java)
    }
}
