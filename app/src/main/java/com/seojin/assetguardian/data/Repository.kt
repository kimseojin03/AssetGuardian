package com.seojin.assetguardian.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.seojin.assetguardian.BuildConfig
import com.seojin.assetguardian.agent.AiPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "guardian")

/**
 * 앱 데이터 저장소. 서버 DB 대신 휴대폰 안(DataStore)에 JSON으로 저장한다.
 * 저장하는 것: 내 적금 목록, 에이전트 판단 기록, 설정, 어제 시장 스냅샷
 */
class Repository(private val context: Context) {

    private val gson = Gson()
    private val api by lazy { FinlifeApi.create() }

    private object Keys {
        val MY = stringPreferencesKey("my_products")
        val DECISIONS = stringPreferencesKey("decisions")
        val SETTINGS = stringPreferencesKey("settings")
        val AI_PLAN = stringPreferencesKey("ai_plan")
        val EVENTS = stringPreferencesKey("memory_events")
        val CUSTOM = stringPreferencesKey("custom_products")   // 직접 입력한 비교 후보 (VIP·특판)
        val SNAPSHOT = stringPreferencesKey("market_snapshot_v2")  // v2: 예금·홈페이지·월한도 포함
    }

    private inline fun <reified T> fromJson(json: String?, default: T): T =
        if (json.isNullOrBlank()) default
        else try { gson.fromJson<T>(json, object : TypeToken<T>() {}.type) ?: default } catch (e: Exception) { default }

    // ---------- 읽기 (화면이 자동으로 갱신되도록 Flow) ----------
    val myProducts: Flow<List<MyProduct>> =
        context.dataStore.data.map { fromJson(it[Keys.MY], emptyList<MyProduct>()) }

    val decisions: Flow<List<Decision>> =
        context.dataStore.data.map { fromJson(it[Keys.DECISIONS], emptyList<Decision>()) }

    val settings: Flow<AppSettings> =
        context.dataStore.data.map { prefs ->
            val s = fromJson(prefs[Keys.SETTINGS], AppSettings())
            // 설정에 키가 없으면 local.properties 에 적어둔 기본 키 사용
            if (s.apiKey.isBlank() && BuildConfig.FINLIFE_API_KEY.isNotBlank()) s.copy(apiKey = BuildConfig.FINLIFE_API_KEY) else s
        }

    val snapshot: Flow<List<MarketProduct>> =
        context.dataStore.data.map { fromJson(it[Keys.SNAPSHOT], emptyList<MarketProduct>()) }

    /** 사용자가 직접 입력한 상품 (공시에 없는 VIP·영업점 특판) */
    val customProducts: Flow<List<MarketProduct>> =
        context.dataStore.data.map { fromJson(it[Keys.CUSTOM], emptyList<MarketProduct>()) }

    suspend fun saveCustomProducts(list: List<MarketProduct>) =
        context.dataStore.edit { it[Keys.CUSTOM] = gson.toJson(list) }

    val aiPlan: Flow<AiPlan?> =
        context.dataStore.data.map { prefs ->
            val j = prefs[Keys.AI_PLAN]
            if (j.isNullOrBlank()) null else try { gson.fromJson(j, AiPlan::class.java) } catch (e: Exception) { null }
        }

    suspend fun saveAiPlan(p: AiPlan?) =
        context.dataStore.edit { if (p == null) it.remove(Keys.AI_PLAN) else it[Keys.AI_PLAN] = gson.toJson(p) }

    val events: Flow<List<MemoryEvent>> =
        context.dataStore.data.map { fromJson(it[Keys.EVENTS], emptyList<MemoryEvent>()) }

    /** 행동 기록 추가 (최근 400개까지 휴대폰에 보관) */
    suspend fun addEvent(e: MemoryEvent) = context.dataStore.edit {
        val list = fromJson(it[Keys.EVENTS], emptyList<MemoryEvent>())
        it[Keys.EVENTS] = gson.toJson((list + e).takeLast(400))
    }

    suspend fun markSynced(ids: Set<String>) = context.dataStore.edit {
        val list = fromJson(it[Keys.EVENTS], emptyList<MemoryEvent>())
        it[Keys.EVENTS] = gson.toJson(list.map { e -> if (e.clientId in ids) e.copy(synced = true) else e })
    }

    suspend fun clearEvents() = context.dataStore.edit { it.remove(Keys.EVENTS) }

    // ---------- 쓰기 ----------
    suspend fun saveMyProducts(list: List<MyProduct>) =
        context.dataStore.edit { it[Keys.MY] = gson.toJson(list) }

    suspend fun addMyProduct(p: MyProduct) = saveMyProducts(myProducts.first() + p)

    suspend fun deleteMyProduct(id: String) = saveMyProducts(myProducts.first().filterNot { it.id == id })

    suspend fun saveDecisions(list: List<Decision>) =
        context.dataStore.edit { it[Keys.DECISIONS] = gson.toJson(list.take(100)) }

    suspend fun saveSettings(s: AppSettings) =
        context.dataStore.edit { it[Keys.SETTINGS] = gson.toJson(s) }

    suspend fun saveSnapshot(list: List<MarketProduct>) =
        context.dataStore.edit { it[Keys.SNAPSHOT] = gson.toJson(list) }

    // ---------- 금감원 API ----------
    /**
     * 은행 + 저축은행의 적금·예금 전체와 은행 홈페이지 주소를 가져온다. 키가 없으면 데모 데이터.
     * @return (상품 목록, 데모 여부)
     */
    suspend fun fetchMarket(apiKey: String): Pair<List<MarketProduct>, Boolean> {
        val custom = customProducts.first()   // 직접 입력 상품도 시장 상품과 똑같이 비교 후보로
        if (apiKey.isBlank()) return (SampleData.market + custom) to true
        val groups = listOf("020000", "030300")

        // 1) 금융회사 → 홈페이지 주소 (실패해도 상품 목록은 계속 가져옴)
        val homepages = mutableMapOf<String, String>()
        for (g in groups) {
            try {
                val res = api.companies(apiKey, g, 1).result
                res?.baseList?.forEach { c ->
                    if (c.finCoNo != null && !c.homepage.isNullOrBlank()) homepages[c.finCoNo] = c.homepage
                }
            } catch (e: Exception) { /* 홈페이지 없이 진행 */ }
        }

        // 2) 적금 + 예금
        val all = mutableListOf<MarketProduct>()
        for (kind in listOf(ProductKind.SAVING, ProductKind.DEPOSIT)) {
            for (g in groups) {
                var page = 1
                var maxPage: Int
                do {
                    val res = (if (kind == ProductKind.SAVING) api.savings(apiKey, g, page) else api.deposits(apiKey, g, page)).result
                        ?: throw FinlifeException("금감원 API 응답이 비어 있어요")
                    if (res.errCd != "000") {
                        throw FinlifeException("금감원 API 오류(${res.errCd}): ${res.errMsg ?: "알 수 없음"}")
                    }
                    all += res.toMarketProducts(kind, homepages)
                    maxPage = res.maxPageNo ?: 1
                    page++
                } while (page <= maxPage && page <= 10)
            }
        }
        return (all + custom) to false
    }

    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context.applicationContext).also { instance = it }
            }
    }
}
