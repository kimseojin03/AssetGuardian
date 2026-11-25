package com.seojin.assetguardian.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seojin.assetguardian.agent.AgentRunner
import com.seojin.assetguardian.agent.AgentServerClient
import com.seojin.assetguardian.agent.AiPlan
import com.seojin.assetguardian.agent.GuardianBrain
import com.seojin.assetguardian.data.AppSettings
import com.seojin.assetguardian.data.Decision
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.data.MyProduct
import com.seojin.assetguardian.data.Repository
import com.seojin.assetguardian.data.EventType
import com.seojin.assetguardian.data.MemoryEvent
import com.seojin.assetguardian.data.MemorySummary
import com.seojin.assetguardian.data.RecResponse
import com.seojin.assetguardian.data.RecItem
import com.seojin.assetguardian.data.TaskItem
import com.seojin.assetguardian.data.GoalStatus
import com.seojin.assetguardian.data.ChatMsg
import com.seojin.assetguardian.data.WeeklyReport
import com.seojin.assetguardian.agent.FeaturesClient
import com.seojin.assetguardian.data.ProductKind
import com.seojin.assetguardian.agent.MemoryClient
import com.seojin.assetguardian.domain.MemoryAnalyzer
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository.get(app)

    val myProducts: StateFlow<List<MyProduct>> =
        repo.myProducts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val decisions: StateFlow<List<Decision>> =
        repo.decisions.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _market = MutableStateFlow<List<MarketProduct>>(emptyList())
    val market: StateFlow<List<MarketProduct>> = _market.asStateFlow()

    private val _demo = MutableStateFlow(false)
    val demo: StateFlow<Boolean> = _demo.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val aiPlan: StateFlow<AiPlan?> =
        repo.aiPlan.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading.asStateFlow()

    /** 목표 탭 "AI에게 말로 맡기기" — 성공하면 저장하고 onDone (AI 루트 화면으로 이동) */
    fun askAiPlan(message: String, target: Long, income: Long, living: Long, onDone: () -> Unit, replan: Boolean = false) = viewModelScope.launch {
        if (_market.value.isEmpty()) { _message.value = "상품 데이터를 아직 못 불러왔어요"; return@launch }
        _aiLoading.value = true
        try {
            log(EventType.AI_PLAN, null, message.take(300))
            val p = AgentServerClient.plan(settings.value, message, _market.value, target, income, living, myProducts.value, replan)
            if (p.plan == null) {
                _message.value = p.summary ?: "루트를 만들지 못했어요"
            } else {
                repo.saveAiPlan(p.copy(message = message, createdAt = System.currentTimeMillis()))
                loadMemory()
                if (replan) _goal.value = try { FeaturesClient.goal(settings.value) } catch (e: Exception) { _goal.value }
                onDone()
            }
        } catch (e: Exception) {
            _message.value = "에이전트 서버에 연결하지 못했어요. PC에서 run_server.bat을 켰는지 확인해 주세요 (${e.message})"
        } finally {
            _aiLoading.value = false
        }
    }

    fun clearAiPlan() = viewModelScope.launch { repo.saveAiPlan(null) }

    private val _review = MutableStateFlow<AgentServerClient.Review?>(null)
    val review: StateFlow<AgentServerClient.Review?> = _review.asStateFlow()

    // ---------------- 장기 기억 ----------------
    val events: StateFlow<List<MemoryEvent>> =
        repo.events.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _memory = MutableStateFlow<MemorySummary?>(null)
    /** 서버 DB의 기억 요약 (서버가 꺼져 있으면 휴대폰 기록으로 만든 요약) */
    val memory: StateFlow<MemorySummary?> = _memory.asStateFlow()

    /** 행동 기록 남기기 → 휴대폰에 저장하고 서버 기억으로 보냄 (서버가 꺼져 있으면 나중에 다시 보냄) */
    fun log(type: String, p: MarketProduct?, detail: String = "") = viewModelScope.launch {
        repo.addEvent(
            MemoryEvent(
                clientId = UUID.randomUUID().toString(), type = type,
                code = p?.code ?: "", kind = p?.kind ?: ProductKind.SAVING, term = p?.termMonths ?: 0,
                bank = p?.bank ?: "", name = p?.name ?: "", rate = p?.baseRate ?: 0.0, maxRate = p?.maxRate ?: 0.0, detail = detail
            )
        )
        syncMemory()
    }

    private suspend fun syncMemory(): Boolean = try {
        val pending = repo.events.first().filter { !it.synced }
        MemoryClient.postEvents(settings.value, pending)
        repo.markSynced(pending.map { it.clientId }.toSet())
        MemoryClient.postHoldings(settings.value, repo.myProducts.first())
        if (!marketSent && _market.value.isNotEmpty()) {
            MemoryClient.postMarket(settings.value, _market.value); marketSent = true
        }
        true
    } catch (e: Exception) { false }

    fun loadMemory() = viewModelScope.launch {
        syncMemory()
        _memory.value = try {
            MemoryClient.summary(settings.value)
        } catch (e: Exception) {
            MemoryAnalyzer.localSummary(repo.events.first(), System.currentTimeMillis())
        }
    }

    private var marketSent = false

    private val _recs = MutableStateFlow<RecResponse?>(null)
    /** 성향 맞춤 추천 결과 */
    val recs: StateFlow<RecResponse?> = _recs.asStateFlow()
    private val _recLoading = MutableStateFlow(false)
    val recLoading: StateFlow<Boolean> = _recLoading.asStateFlow()

    fun loadRecs() = viewModelScope.launch {
        if (_market.value.isEmpty()) return@launch
        _recLoading.value = true
        try {
            syncMemory()
            val r = MemoryClient.recommend(settings.value, _market.value)
            _recs.value = r
            // 추천 피드백 학습: 무엇을 보여줬는지 기록 (나중에 눌렀는지와 비교)
            (r.items ?: emptyList()).forEach { it2 ->
                repo.addEvent(MemoryEvent(UUID.randomUUID().toString(), EventType.REC_SHOWN, it2.code ?: "", ProductKind.SAVING,
                    it2.term, it2.bank ?: "", it2.name ?: "", it2.baseRate, it2.maxRate))
            }
            syncMemory()
        } catch (e: Exception) {
            _message.value = "추천은 PC에서 서버(run_server.bat)가 켜져 있을 때 볼 수 있어요"
        } finally {
            _recLoading.value = false
        }
    }

    fun logRecClick(r: RecItem) = viewModelScope.launch {
        repo.addEvent(MemoryEvent(UUID.randomUUID().toString(), EventType.REC_CLICK, r.code ?: "", ProductKind.SAVING,
            r.term, r.bank ?: "", r.name ?: "", r.baseRate, r.maxRate))
        syncMemory()
    }

    // ================= 에이전트 기능: 체크리스트 / 목표 추적 / 상담 / 주간 리포트 =================
    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()
    private val _goal = MutableStateFlow<GoalStatus?>(null)
    val goal: StateFlow<GoalStatus?> = _goal.asStateFlow()
    private val _chat = MutableStateFlow<List<ChatMsg>>(emptyList())
    val chat: StateFlow<List<ChatMsg>> = _chat.asStateFlow()
    private val _chatLoading = MutableStateFlow(false)
    val chatLoading: StateFlow<Boolean> = _chatLoading.asStateFlow()
    private val _weekly = MutableStateFlow<WeeklyReport?>(null)
    val weekly: StateFlow<WeeklyReport?> = _weekly.asStateFlow()
    private val _serverOk = MutableStateFlow<Boolean?>(null)
    /** null = 아직 확인 전, false = PC 서버 꺼짐 */
    val serverOk: StateFlow<Boolean?> = _serverOk.asStateFlow()

    fun loadAgentData() = viewModelScope.launch {
        val s = repo.settings.first()   // 앱 시작 직후에도 저장된 서버 주소를 쓰도록
        try {
            _tasks.value = FeaturesClient.tasks(s).tasks ?: emptyList()
            _goal.value = FeaturesClient.goal(s)
            _weekly.value = FeaturesClient.latestWeekly(s)
            _serverOk.value = true
        } catch (e: Exception) { _serverOk.value = false }
    }

    /** 적금 등록 직후: 우대조건 문장을 AI가 할 일로 바꿔 체크리스트 생성 */
    fun createChecklist(my: MyProduct) = viewModelScope.launch {
        val mp = _market.value.firstOrNull { it.kind == ProductKind.SAVING && it.code == my.productCode && it.termMonths == my.termMonths }
        val cond = mp?.specialCondition.orEmpty()
        if (cond.isBlank()) { _message.value = "이 상품은 우대조건 정보가 없어서 체크리스트를 만들 게 없어요"; return@launch }
        try {
            val r = FeaturesClient.checklist(settings.value, my, cond)
            _message.value = "우대조건 할 일 ${r.tasks?.size ?: 0}개를 만들었어요" + if (r.source == "ai") " (AI가 정리)" else " (규칙)"
            _tasks.value = FeaturesClient.tasks(settings.value).tasks ?: emptyList()
        } catch (e: Exception) { _message.value = "체크리스트는 PC 서버가 켜져 있을 때 만들 수 있어요" }
    }

    fun taskDone(id: Long) = viewModelScope.launch {
        try { FeaturesClient.taskDone(settings.value, id); _tasks.value = FeaturesClient.tasks(settings.value).tasks ?: emptyList()
            _message.value = "완료! 다음 기한으로 넘겼어요" } catch (e: Exception) { _message.value = "서버에 연결하지 못했어요" }
    }

    fun deleteTask(id: Long) = viewModelScope.launch {
        try { FeaturesClient.deleteTask(settings.value, id); _tasks.value = FeaturesClient.tasks(settings.value).tasks ?: emptyList() }
        catch (e: Exception) { _message.value = "서버에 연결하지 못했어요" }
    }

    fun recordProgress(month: String, actual: Long) = viewModelScope.launch {
        try {
            val g = FeaturesClient.recordProgress(settings.value, month, actual)
            _goal.value = g
            _message.value = when (g.status) {
                "behind" -> "기록했어요. 계획보다 뒤처져서 대안을 계산했어요"
                "ahead" -> "기록했어요. 계획보다 앞서가고 있어요!"
                else -> "기록했어요. 계획대로 가고 있어요"
            }
        } catch (e: Exception) { _message.value = "서버에 연결하지 못했어요" }
    }

    /** 목표 진행 → "AI에게 루트 다시 짜달라기": 남은 금액·현재 저축 속도로 재계획 */
    fun replan(onDone: () -> Unit) {
        val g = _goal.value ?: return
        val remaining = (g.target - g.actualTotal).coerceAtLeast(0)
        val msg = "목표 ${"%,d".format(g.target)}원 중 지금까지 ${"%,d".format(g.actualTotal)}원을 모았어. " +
            "최근 실제 저축은 매달 ${"%,d".format(g.pace)}원 정도야. 남은 ${"%,d".format(remaining)}원을 모을 루트를 다시 짜줘."
        askAiPlan(msg, remaining, g.pace, 0, onDone, replan = true)
    }

    fun loadChat() = viewModelScope.launch {
        try { _chat.value = FeaturesClient.chatHistory(settings.value).messages ?: emptyList(); _serverOk.value = true }
        catch (e: Exception) { _serverOk.value = false }
    }

    fun sendChat(text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        _chat.value = _chat.value + ChatMsg(role = "user", text = text, at = System.currentTimeMillis() / 1000.0)
        _chatLoading.value = true
        try {
            syncMemory()
            val r = FeaturesClient.chat(settings.value, text, _market.value, myProducts.value)
            _chat.value = _chat.value + ChatMsg(role = "assistant", text = r.reply, trace = r.trace, at = System.currentTimeMillis() / 1000.0)
            _goal.value = try { FeaturesClient.goal(settings.value) } catch (e: Exception) { _goal.value }
            loadMemory()
        } catch (e: Exception) {
            _chat.value = _chat.value + ChatMsg(role = "assistant", text = "PC의 에이전트 서버에 연결하지 못했어요. run_server.bat이 켜져 있는지 확인해 주세요.")
        } finally { _chatLoading.value = false }
    }

    fun clearChat() = viewModelScope.launch {
        try { FeaturesClient.clearChat(settings.value) } catch (e: Exception) { }
        _chat.value = emptyList()
    }

    fun makeWeekly() = viewModelScope.launch {
        _loading.value = true
        try {
            syncMemory()
            _weekly.value = FeaturesClient.weekly(settings.value, _market.value)
            repo.saveSettings(settings.value.copy(lastWeeklyAt = System.currentTimeMillis()))
        } catch (e: Exception) { _message.value = "주간 리포트는 PC 서버가 켜져 있을 때 만들 수 있어요" }
        finally { _loading.value = false }
    }

    /** 내가 생각하는 내 성향 (null = 선택 해제) */
    fun setStated(type: String?) = viewModelScope.launch {
        try { MemoryClient.setStated(settings.value, type); loadMemory(); _recs.value = null }
        catch (e: Exception) { _message.value = "서버에 연결하지 못했어요" }
    }

    fun addFact(text: String) = viewModelScope.launch {
        try { MemoryClient.addFact(settings.value, text); loadMemory() }
        catch (e: Exception) { _message.value = "사실 저장은 서버가 켜져 있을 때 할 수 있어요" }
    }

    fun deleteFact(id: Long) = viewModelScope.launch {
        try { MemoryClient.deleteFact(settings.value, id); loadMemory() }
        catch (e: Exception) { _message.value = "서버에 연결하지 못했어요" }
    }

    fun clearMemory() = viewModelScope.launch {
        repo.clearEvents()
        try { MemoryClient.clearAll(settings.value) } catch (e: Exception) { }
        loadMemory()
        _message.value = "기록을 모두 지웠어요"
    }

    // ---------------- 직접 입력 상품 (VIP·영업점 특판) ----------------
    val customProducts: StateFlow<List<MarketProduct>> =
        repo.customProducts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 은행에서 제안받은 상품을 비교 후보로 추가 → 상품 탐색·갈아타기 판단·매일 점검·AI 루트에 함께 쓰임 */
    fun addCustomProduct(bank: String, name: String, term: Int, base: Double, max: Double, capWon: Long?, memo: String) =
        viewModelScope.launch {
            val p = MarketProduct(
                code = MarketProduct.CUSTOM_PREFIX + UUID.randomUUID().toString().take(8),
                bank = bank.trim(), name = name.trim(), termMonths = term,
                baseRate = base, maxRate = maxOf(base, max),
                specialCondition = memo.trim(), monthlyCap = capWon?.takeIf { it > 0 },
                etcNote = "직접 입력한 상품 (금감원 공시 없음)"
            )
            val list = repo.customProducts.first() + p
            repo.saveCustomProducts(list)
            _market.value = _market.value.filterNot { it.isCustom } + list
            log(EventType.VIEW, p, "직접 입력 상품 추가")
            _message.value = "${p.name}을(를) 비교 후보로 추가했어요"
        }

    fun deleteCustomProduct(code: String) = viewModelScope.launch {
        val list = repo.customProducts.first().filterNot { it.code == code }
        repo.saveCustomProducts(list)
        _market.value = _market.value.filterNot { it.isCustom } + list
    }

    /** 상품 key 로 시장 상품 찾기 (기억 화면 → 상품 분석으로 이동할 때) */
    fun findProduct(kind: String?, code: String?, term: Int): MarketProduct? =
        _market.value.firstOrNull { it.kind == (kind ?: ProductKind.SAVING) && it.code == code && it.termMonths == term }

    init {
        viewModelScope.launch {
            val cached = repo.snapshot.first().filterNot { it.isCustom } + repo.customProducts.first()
            if (cached.isNotEmpty()) _market.value = cached
            refreshMarket(silent = cached.isNotEmpty())
        }
        loadMemory()
        loadAgentData()
    }

    fun consumeMessage() { _message.value = null }

    fun refreshMarket(silent: Boolean = false) = viewModelScope.launch {
        _loading.value = true
        try {
            val key = repo.settings.first().apiKey
            val (list, isDemo) = repo.fetchMarket(key)
            _market.value = list
            _demo.value = isDemo
            if (!silent) _message.value =
                if (isDemo) "데모 데이터 ${list.size}개를 불러왔어요" else "금감원 적금 상품 ${list.size}개를 불러왔어요"
        } catch (e: Exception) {
            _message.value = e.message ?: "상품을 불러오지 못했어요"
        } finally {
            _loading.value = false
        }
    }

    /** 홈 화면 "지금 점검하기" — 매일 아침 자동으로 도는 것과 같은 에이전트 1회 실행 */
    fun runAgentNow() = viewModelScope.launch {
        _loading.value = true
        val o = AgentRunner.runOnce(getApplication())
        _loading.value = false
        _message.value = o.error ?: buildString {
            append("적금 ${o.checked}개를 상품 ${o.marketSize}개와 비교했어요. ")
            if (o.newFound > 0) append("신규·금리인상 ${o.newFound}건 감지. ")
            append("알림 ${o.notified}건")
            if (o.kept > 0) append(", 손해라서 막은 갈아타기 ${o.kept}건")
            if (o.demo) append(" (데모)")
        }
        if (o.error == null) _market.value = repo.snapshot.first()
    }

    fun addProduct(p: MyProduct) = viewModelScope.launch {
        repo.addMyProduct(p)
        val mp = _market.value.firstOrNull { it.kind == ProductKind.SAVING && it.code == p.productCode && it.termMonths == p.termMonths }
        log(EventType.REGISTER, mp ?: MarketProduct(p.productCode, p.bank, p.name, p.termMonths, p.baseRate, p.finalRate), "내 적금 등록")
        if (mp != null && mp.specialCondition.isNotBlank()) createChecklist(p)   // 우대조건 → 할 일 자동 생성
        _message.value = "${p.name}을(를) 등록했어요"
    }

    fun deleteProduct(id: String) = viewModelScope.launch {
        repo.deleteMyProduct(id)
        syncMemory()
    }

    /** ④ 학습: 사용자 반응 → 알림 기준 금액 조정 */
    fun feedback(decisionId: String, fb: String) = viewModelScope.launch {
        val list = repo.decisions.first().map { if (it.id == decisionId) it.copy(feedback = fb) else it }
        repo.saveDecisions(list)
        list.firstOrNull { it.id == decisionId }?.let { d ->
            val p = _market.value.firstOrNull { it.key == d.candidateKey }
                ?: MarketProduct("", d.candidateBank, d.candidateName, 0, d.candidateBaseRate, d.candidateMaxRate)
            log(if (fb == "ACCEPT") EventType.ALERT_ACCEPT else EventType.ALERT_IGNORE, p, d.verdict)
        }
        val s = repo.settings.first()
        val newT = GuardianBrain.adjustThreshold(s.thresholdWon, fb)
        repo.saveSettings(s.copy(thresholdWon = newT))
        _message.value = "알림 기준을 세후 %,d원 이상으로 조정했어요".format(newT)
    }

    fun saveSettings(s: AppSettings) = viewModelScope.launch {
        val keyChanged = s.apiKey != repo.settings.first().apiKey
        repo.saveSettings(s)
        if (keyChanged) {
            repo.saveSnapshot(emptyList())
            refreshMarket()
        } else {
            _message.value = "설정을 저장했어요"
        }
    }

    /** 목표 탭에서 "이 목표로 설정" — 홈 화면 목표 카드와 매일 에이전트 루트 점검에 쓰인다 */
    fun saveGoal(goal: Long, income: Long, living: Long, useMax: Boolean) = viewModelScope.launch {
        val s = repo.settings.first()
        repo.saveSettings(s.copy(goalAmount = goal, income = income, living = living, useMaxRate = useMax, lastPlanMonths = 0))
        _message.value = "목표 %,d원을 설정했어요. 홈에서 루트를 확인하세요".format(goal)
    }

    fun askAgentServer(my: MyProduct, c: MarketProduct) = viewModelScope.launch {
        val s = settings.value
        _loading.value = true
        _review.value = null
        try {
            _review.value = AgentServerClient.review(s, my, c)
        } catch (e: Exception) {
            _message.value = "에이전트 서버 연결 실패: ${e.message}"
        } finally {
            _loading.value = false
        }
    }

    fun clearReview() { _review.value = null }
}
