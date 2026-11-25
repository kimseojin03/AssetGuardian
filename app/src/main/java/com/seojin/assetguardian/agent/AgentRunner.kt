package com.seojin.assetguardian.agent

import android.content.Context
import com.seojin.assetguardian.data.Repository
import com.seojin.assetguardian.data.SampleData
import com.seojin.assetguardian.domain.GoalPlanner
import com.seojin.assetguardian.domain.MemoryAnalyzer
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * 에이전트 1회 실행 (매일 아침 Worker가 호출, 홈 화면 "지금 점검" 버튼도 호출).
 * 인식 → 판단 → 행동(알림) → 기록까지 한 번에.
 */
object AgentRunner {

    data class Outcome(
        val checked: Int,          // 점검한 내 적금 수
        val marketSize: Int,       // 비교한 시장 상품 수
        val newFound: Int,         // 새로 나오거나 금리가 오른 상품 수
        val notified: Int,         // 보낸 알림 수
        val kept: Int,             // "유지 권장"으로 막아준 수
        val demo: Boolean,
        val error: String? = null
    )

    suspend fun runOnce(context: Context): Outcome {
        val repo = Repository.get(context)
        val settings = repo.settings.first()
        val mine = repo.myProducts.first()
        val history = repo.decisions.first()

        // ① 인식: 오늘 시장 상품 수집
        val (market, demo) = try {
            repo.fetchMarket(settings.apiKey)
        } catch (e: Exception) {
            return Outcome(mine.size, 0, 0, 0, 0, false, e.message ?: "네트워크 오류")
        }
        val yesterday = repo.snapshot.first().ifEmpty { if (demo) SampleData.marketYesterday else emptyList() }

        // ② 판단
        val now = System.currentTimeMillis()
        val report = GuardianBrain.run(mine, market, yesterday, settings, LocalDate.now(), now)
        val fresh = GuardianBrain.dedupe(report.decisions, history, now)

        // ③ 행동: 이득/조건부/만기만 알림, 손해(KEEP)는 기록만
        val toNotify = fresh.filter { it.verdict != com.seojin.assetguardian.data.Verdict.KEEP }
        toNotify.forEach { Notifier.notifyDecision(context, it) }

        // 기억 활용: 내가 관심 있게 본 상품의 금리가 오르거나 새로 나오면 알려주기
        var interestAlerts = 0
        val events = repo.events.first()
        val top = MemoryAnalyzer.interests(events, now, 5).map { "${it.kind}|${it.code}|${it.term}" }.toSet()
        if (top.isNotEmpty()) {
            val prev = yesterday.associateBy { it.key }
            val changed = (report.perception.raisedKeys + report.perception.newKeys)
            market.filter { it.key in changed && "${it.kind}|${it.code}|${it.termMonths}" in top }
                .distinctBy { "${it.code}|${it.termMonths}" }
                .take(2)
                .forEach { p ->
                    val old = prev[p.key]
                    val text = if (old == null) "관심 있게 본 ${p.bank} ${p.name}(${p.termMonths}개월)이 새로 공시됐어요. 기본 ${p.baseRate}% / 최고 ${p.maxRate}%"
                    else "관심 있게 본 ${p.bank} ${p.name}(${p.termMonths}개월) 금리가 올랐어요. 최고 ${old.maxRate}% → ${p.maxRate}%"
                    Notifier.notifyText(context, "관심 상품 금리 변동", text)
                    interestAlerts++
                }
        }
        // 서버 기억과 동기화 (서버가 꺼져 있으면 다음에)
        try {
            val pending = events.filter { !it.synced }
            MemoryClient.postEvents(settings, pending)
            repo.markSynced(pending.map { it.clientId }.toSet())
            MemoryClient.postHoldings(settings, mine)
        } catch (e: Exception) { }

        // ---- 에이전트 기능: 우대조건 기한 / 목표 진행 / 주간 리포트 (PC 서버가 켜져 있을 때) ----
        var s2 = settings
        try {
            // 1) 내일까지 챙겨야 할 우대조건 (지난 건 반복해서 울리지 않음)
            FeaturesClient.dueTasks(settings, 1.0).tasks.orEmpty()
                .filter { it.nextDue * 1000 >= now }
                .take(3)
                .forEach { t -> Notifier.notifyText(context, "우대조건 챙기기: ${t.title}", "${t.product} · ${t.detail}", "tasks") }

            // 2) 목표 진행: 매달 초에 지난달 저축액 묻기, 늦어지고 있으면 대안 알림
            val g = FeaturesClient.goal(settings)
            val today = LocalDate.now()
            val ym = "%04d-%02d".format(today.year, today.monthValue)
            if (g.active) {
                if (today.dayOfMonth <= 3 && settings.lastProgressAskMonth != ym) {
                    Notifier.notifyText(context, "지난달 얼마 저축했어요?", "기록하면 에이전트가 목표 루트가 계획대로 가는지 점검해요.", "goaltrack")
                    s2 = s2.copy(lastProgressAskMonth = ym)
                }
                val delay = g.delayMonths ?: 0
                if (g.status == "behind" && delay > 0 && settings.lastGoalAlertMonth != ym) {
                    val catchUp = g.options.orEmpty().firstOrNull { it.kind == "catch_up" }
                    Notifier.notifyText(context, "목표 달성이 ${delay}개월 늦어지고 있어요",
                        catchUp?.text ?: "목표 진행 화면에서 대안을 확인하세요.", "goaltrack")
                    s2 = s2.copy(lastGoalAlertMonth = ym)
                }
            }

            // 3) 주간 리포트: 7일마다 자동 생성
            if (now - settings.lastWeeklyAt >= 7L * 24 * 3600 * 1000 - 3600_000) {
                val w = FeaturesClient.weekly(settings, market)
                Notifier.notifyText(context, "이번 주 저축 리포트", w.aiSummary ?: w.summaryLines.orEmpty().joinToString(" · "), "weekly")
                s2 = s2.copy(lastWeeklyAt = now)
            }
        } catch (e: Exception) { /* 서버가 꺼져 있으면 다음 점검 때 */ }

        // 목표 루트 다시 계산 → 금리가 바뀌어 더 빨리 도달할 수 있으면 알림
        var planMonths = settings.lastPlanMonths
        if (settings.goalAmount > 0 && settings.monthlySaving > 0) {
            val plan = GoalPlanner.plan(settings.goalAmount, settings.monthlySaving, market,
                settings.taxRate, settings.parkingRate, settings.useMaxRate)
            val m = plan?.monthsNeeded
            if (plan != null && m != null) {
                if (settings.lastPlanMonths > 0 && m < settings.lastPlanMonths) {
                    Notifier.notifyText(context, "목표 달성이 ${settings.lastPlanMonths - m}개월 빨라지는 루트를 찾았어요",
                        "${plan.slots.first().product.bank} ${plan.slots.first().product.name} 중심으로 " +
                            "${GoalPlanner.monthsText(m)} 만에 ${"%,d".format(settings.goalAmount)}원 달성 가능해요.")
                }
                planMonths = m
            }
        }

        repo.saveDecisions(fresh + history)
        repo.saveSnapshot(market)
        repo.saveSettings(s2.copy(lastCheck = now, demoMode = demo, lastPlanMonths = planMonths))

        return Outcome(
            checked = mine.size,
            marketSize = market.size,
            newFound = report.perception.newKeys.size + report.perception.raisedKeys.size,
            notified = toNotify.size + interestAlerts,
            kept = fresh.size - toNotify.size,
            demo = demo
        )
    }
}
