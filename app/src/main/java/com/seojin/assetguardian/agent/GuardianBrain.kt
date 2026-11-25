package com.seojin.assetguardian.agent

import com.seojin.assetguardian.data.AppSettings
import com.seojin.assetguardian.data.Decision
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.data.MyProduct
import com.seojin.assetguardian.data.ProductKind
import com.seojin.assetguardian.data.Verdict
import com.seojin.assetguardian.domain.SwitchCalculator
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * 에이전트의 "두뇌" — 안드로이드와 무관한 순수 로직이라 테스트하기 쉽다.
 *
 *  ① 인식: 오늘 시장 상품을 어제 스냅샷과 비교해 신규/금리 인상 상품을 찾는다
 *  ② 판단: 내 적금마다 같은 기간 후보를 골라 갈아타기 손익을 계산한다
 *  ③ 행동: 알림을 보낼 판단(SWITCH/CONDITIONAL/MATURITY)과 기록만 할 판단(KEEP)을 나눈다
 *  ④ 학습: 사용자의 수락/무시 반응으로 알림 기준 금액을 조정한다 (adjustThreshold)
 */
object GuardianBrain {

    /** 이 정도 금리 차이도 안 나면 계산할 가치도 없다고 보고 건너뜀 (연 %p) */
    const val MIN_RATE_GAP = 0.3

    data class Perception(val newKeys: Set<String>, val raisedKeys: Set<String>)

    data class Report(
        val perception: Perception,
        val decisions: List<Decision>
    ) {
        val toNotify: List<Decision>
            get() = decisions.filter { it.verdict != Verdict.KEEP }
    }

    fun perceive(today: List<MarketProduct>, yesterday: List<MarketProduct>): Perception {
        if (yesterday.isEmpty()) return Perception(emptySet(), emptySet())
        val prev = yesterday.associateBy { it.key }
        val newKeys = today.filter { it.key !in prev }.map { it.key }.toSet()
        val raised = today.filter { p ->
            val old = prev[p.key] ?: return@filter false
            p.maxRate > old.maxRate + 0.001 || p.baseRate > old.baseRate + 0.001
        }.map { it.key }.toSet()
        return Perception(newKeys, raised)
    }

    fun run(
        mine: List<MyProduct>,
        market: List<MarketProduct>,
        yesterday: List<MarketProduct>,
        settings: AppSettings,
        today: LocalDate,
        now: Long
    ): Report {
        val perception = perceive(market, yesterday)
        val decisions = mutableListOf<Decision>()

        for (my in mine) {
            val elapsed = SwitchCalculator.elapsedMonths(my.joinDate, today, my.termMonths)
            val maturity = SwitchCalculator.maturityDate(my.joinDate, my.termMonths)
            val sameTerm = market.filter {
                it.kind == ProductKind.SAVING && it.termMonths == my.termMonths && it.code != my.productCode
            }

            // 만기 2주 이내 → 갈아타기 대신 재예치 안내
            if (maturity != null) {
                val days = ChronoUnit.DAYS.between(today, maturity)
                if (days in 0..14) {
                    val best = sameTerm.maxByOrNull { it.baseRate }
                    if (best != null) decisions += maturityDecision(my, best, days, now)
                    continue
                }
                if (days < 0) continue  // 이미 만기
            }

            // 금리 차이가 충분한 후보만 계산
            val candidates = sameTerm.filter { it.maxRate - my.finalRate >= MIN_RATE_GAP }
            if (candidates.isEmpty()) continue

            val evaluated = candidates.map { c -> c to evaluate(my, c, elapsed, settings) }
            // 기본금리만으로 확실히 이득인 후보가 있으면 그중 최고, 없으면 우대 포함 최고
            val sureOnes = evaluated.filter { it.second.netSure >= settings.thresholdWon }
            val (best, eval) = (if (sureOnes.isNotEmpty()) sureOnes.maxByOrNull { it.second.netSure }
                else evaluated.maxByOrNull { it.second.netMax }) ?: continue

            val raw = when {
                eval.netSure >= settings.thresholdWon -> Verdict.SWITCH
                eval.netMax >= settings.thresholdWon -> Verdict.CONDITIONAL
                else -> Verdict.KEEP
            }
            // 정책 상품은 계산에 없는 손실(비과세 추징·정부기여금)이 있어 갈아타기를 권하지 않음
            val policy = com.seojin.assetguardian.data.ProductRules.isPolicy(my.name)
            val verdict = if (policy) Verdict.KEEP else raw
            val isNew = best.key in perception.newKeys || best.key in perception.raisedKeys
            decisions += Decision(
                id = "${my.id}-${best.key}-$now",
                createdAt = now,
                myId = my.id,
                myName = "${my.bank} ${my.name}",
                candidateKey = best.key,
                candidateBank = best.bank,
                candidateName = best.name,
                candidateBaseRate = best.baseRate,
                candidateMaxRate = best.maxRate,
                verdict = verdict,
                netSure = eval.netSure,
                netMax = eval.netMax,
                loss = eval.loss,
                gainSure = eval.gainSure,
                breakEven = eval.breakEven,
                elapsed = elapsed,
                message = if (policy) policyMessage(raw, best, eval) else explain(verdict, my, best, eval, elapsed, settings),
                isNewProduct = isNew
            )
        }
        return Report(perception, decisions)
    }

    data class Eval(
        val netSure: Long, val netMax: Long, val loss: Long,
        val gainSure: Long, val breakEven: Double?,
        val cap: Long? = null,       // 새 상품 월 한도 때문에 줄어든 납입액 (한도에 안 걸리면 null)
        val monthly: Long = 0
    )

    fun evaluate(my: MyProduct, c: MarketProduct, elapsed: Int, s: AppSettings): Eval {
        fun calc(rate: Double) = SwitchCalculator.analyze(
            SwitchCalculator.Input(
                monthly = my.monthly, myRate = my.finalRate, myBaseRate = my.baseRate,
                termMonths = my.termMonths, elapsed = elapsed, newRate = rate,
                parkingRate = s.parkingRate, taxRate = s.taxRate,
                earlyRule = my.earlyRule, customEarlyRate = my.customEarlyRate,
                newMonthlyCap = c.monthlyCap
            )
        )
        val sure = calc(c.baseRate)
        val max = calc(c.maxRate)
        return Eval(
            netSure = sure.net.roundToLong(),
            netMax = max.net.roundToLong(),
            loss = sure.loss.roundToLong(),
            gainSure = sure.gain.roundToLong(),
            breakEven = sure.breakEven,
            cap = if (sure.capped) sure.newMonthly else null,
            monthly = my.monthly
        )
    }

    /** 새 상품 월 한도에 걸릴 때 덧붙이는 설명 */
    fun capNote(e: Eval): String = e.cap?.let {
        " (이 상품은 월 ${won(it)}까지만 넣을 수 있어서, 나머지 월 ${won(e.monthly - it)}은 파킹통장 금리로 계산했어요)"
    } ?: ""

    /** 정책 상품(청년도약계좌 등)을 갈아타려 할 때의 안내 */
    fun policyMessage(raw: String, c: MarketProduct, e: Eval): String =
        "정부 지원·비과세 상품이라 중도해지하면 비과세 혜택이 사라지거나 정부기여금을 못 받을 수 있어요. " +
        "이 손실은 계산에 들어 있지 않아요. " +
        (if (raw != Verdict.KEEP) "계산상으로는 ${c.bank} ${c.name}이 세후 ${won(maxOf(e.netSure, e.netMax))} 이득이지만, 그보다 혜택 손실이 클 수 있으니 유지를 권해요. "
         else "계산상으로도 갈아타면 손해예요. ") +
        "특별중도해지 사유(취업·결혼 등)에 해당하는지 먼저 약관을 확인하세요."

    private fun won(v: Long): String = "%,d원".format(v)
    private fun pct(v: Double?): String = if (v == null) "-" else "연 %.2f%%".format(v)

    fun explain(v: String, my: MyProduct, c: MarketProduct, e: Eval, elapsed: Int, s: AppSettings): String =
        when (v) {
            Verdict.SWITCH ->
                "${c.bank} ${c.name}(기본 ${"%.2f".format(c.baseRate)}%)로 갈아타면 세후 ${won(e.netSure)} 이득이에요. " +
                "가입 ${elapsed}개월 차라 해지 손실이 ${won(e.loss)}로 아직 작아요." + capNote(e)
            Verdict.CONDITIONAL ->
                "기본금리만으로는 ${won(e.netSure)}지만, 우대조건을 채워 최고 ${"%.2f".format(c.maxRate)}%를 받으면 " +
                "${won(e.netMax)} 이득이에요. 우대조건을 확인해 보세요." + capNote(e)
            Verdict.KEEP ->
                "${c.bank} ${c.name}이 최고 ${"%.2f".format(c.maxRate)}%로 높아 보이지만, 지금(${elapsed}개월 차) 해지하면 " +
                "손실 ${won(e.loss)} 때문에 오히려 ${won(-e.netMax)} 손해예요. 유지하세요. " +
                "(갈아탈 가치가 생기는 금리: ${pct(e.breakEven)} 이상)" + capNote(e)
            else -> ""
        }

    private fun maturityDecision(my: MyProduct, best: MarketProduct, days: Long, now: Long): Decision =
        Decision(
            id = "${my.id}-maturity-$now",
            createdAt = now,
            myId = my.id,
            myName = "${my.bank} ${my.name}",
            candidateKey = best.key,
            candidateBank = best.bank,
            candidateName = best.name,
            candidateBaseRate = best.baseRate,
            candidateMaxRate = best.maxRate,
            verdict = Verdict.MATURITY,
            netSure = 0, netMax = 0, loss = 0, gainSure = 0, breakEven = null,
            elapsed = my.termMonths,
            message = "만기가 ${days}일 남았어요. 지금 같은 기간 상품 중 기본금리가 가장 높은 건 " +
                "${best.bank} ${best.name}(기본 ${"%.2f".format(best.baseRate)}%)이에요."
        )

    /**
     * 같은 판단을 일주일 안에 다시 알리지 않기 (알림 피로 방지).
     * 같은 내 상품 + 같은 후보 + 같은 판단이면 중복.
     */
    fun dedupe(fresh: List<Decision>, history: List<Decision>, now: Long): List<Decision> {
        val week = 7L * 24 * 60 * 60 * 1000
        return fresh.filter { d ->
            history.none { h ->
                h.myId == d.myId && h.candidateKey == d.candidateKey &&
                    h.verdict == d.verdict && now - h.createdAt < week
            }
        }
    }

    /** ④ 학습: 알림을 무시하면 기준을 올리고(덜 귀찮게), 수락하면 조금 내린다(더 자주) */
    fun adjustThreshold(current: Long, feedback: String): Long = when (feedback) {
        "IGNORE" -> (current * 1.5).roundToLong().coerceAtMost(50_000)
        "ACCEPT" -> (current * 0.8).roundToLong().coerceAtLeast(1_000)
        else -> current
    }
}
