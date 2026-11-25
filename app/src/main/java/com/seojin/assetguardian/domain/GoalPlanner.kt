package com.seojin.assetguardian.domain

import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.data.ProductKind
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * 목표 금액까지 가장 빠른 루트를 찾는 플래너.
 *
 * 루트 = "적금 사이클"을 반복하는 구조
 *   ① 매달 저축액을 금리 높은 적금부터 월 한도만큼 나눠 넣는다 (최대 3개 통장)
 *   ② 적금이 만기되면 받은 목돈(원금+세후이자)을 금리가 가장 높은 정기예금에 넣어 굴린다
 *   ③ 같은 적금 조합으로 다음 사이클을 다시 시작한다
 * 적금 기간(6·12·24·36개월)마다 이 과정을 한 달씩 시뮬레이션해서, 목표에 가장 빨리 닿는 기간을 고른다.
 *
 * 보수적으로 계산: 진행 중인 적금은 이자 없이 원금만 인정, 금리는 지금 금리가 그대로 유지된다고 가정.
 */
object GoalPlanner {

    const val MAX_ACCOUNTS = 3

    data class Slot(
        val product: MarketProduct,
        val monthly: Long,
        val rate: Double,
        val interestPerCycle: Long   // 한 사이클 만기 시 세후 이자
    )

    data class Cycle(val number: Int, val startMonth: Int, val endMonth: Int, val completed: Boolean)

    data class Option(val termMonths: Int, val monthsNeeded: Int?, val finalAmount: Long, val firstSlot: String)

    data class Plan(
        val target: Long,
        val monthly: Long,
        val termMonths: Int,
        val slots: List<Slot>,
        val deposit: MarketProduct?,       // 만기 목돈을 굴릴 예금 (없으면 파킹통장)
        val depositRate: Double,
        val cycles: List<Cycle>,
        val monthsNeeded: Int?,            // null = 20년 안에 불가
        val finalAmount: Long,
        val principal: Long,
        val savingInterest: Long,
        val depositInterest: Long,
        val noInterestMonths: Int?,        // 이자 없이 그냥 모을 때 걸리는 개월
        val options: List<Option>,         // 기간별 비교 (왜 이 기간을 골랐는지)
        val useMaxRate: Boolean
    )

    private fun rateOf(p: MarketProduct, useMax: Boolean) = if (useMax) p.maxRate else p.baseRate

    /** 매달 저축액을 금리 높은 순으로 월 한도까지 채워 넣는다 */
    fun allocate(
        monthly: Long, candidates: List<MarketProduct>, useMax: Boolean, parking: MarketProduct
    ): List<Pair<MarketProduct, Long>> {
        val sorted = candidates.sortedByDescending { rateOf(it, useMax) }.distinctBy { it.code }
        val result = mutableListOf<Pair<MarketProduct, Long>>()
        var left = monthly
        for (p in sorted) {
            if (left <= 0) break
            val isLastSlot = result.size == MAX_ACCOUNTS - 1
            val cap = p.monthlyCap
            if (isLastSlot && cap != null && cap < left) {
                // 마지막 통장인데 한도가 작으면, 한도 없는 상품을 찾아 나머지를 다 넣는 편이 낫다
                val unlimited = sorted.firstOrNull { it.monthlyCap == null && result.none { r -> r.first.code == it.code } }
                if (unlimited != null && rateOf(unlimited, useMax) > parking.baseRate) {
                    result += unlimited to left; left = 0; break
                }
            }
            val put = if (cap == null) left else minOf(cap, left)
            if (put <= 0) continue
            result += p to put
            left -= put
            if (result.size == MAX_ACCOUNTS) break
        }
        if (left > 0) result += parking to left   // 한도가 다 차면 남는 돈은 파킹통장
        return result
    }

    fun plan(
        target: Long,
        monthly: Long,
        market: List<MarketProduct>,
        taxRate: Double,
        parkingRate: Double,
        useMaxRate: Boolean,
        horizon: Int = 240
    ): Plan? {
        if (target <= 0 || monthly <= 0) return null
        val tax = 1 - taxRate / 100
        val parking = MarketProduct("PARKING", "파킹통장", "수시입출금 통장", 1, parkingRate, parkingRate,
            kind = ProductKind.DEPOSIT)
        val savings = market.filter { it.kind == ProductKind.SAVING && !it.restricted }
        val deposit = market.filter { it.kind == ProductKind.DEPOSIT && !it.restricted && it.termMonths == 12 }
            .maxByOrNull { it.baseRate }   // 예금은 우대조건 없이 받을 수 있는 기본금리로
        val depRate = maxOf(deposit?.baseRate ?: 0.0, parkingRate)
        val depositOrNull = if (deposit != null && deposit.baseRate >= parkingRate) deposit else null

        fun simulate(term: Int): Plan? {
            val cands = savings.filter { it.termMonths == term }
            if (cands.isEmpty()) return null
            val alloc = allocate(monthly, cands, useMaxRate, parking)
            val slots = alloc.map { (p, m) ->
                val r = if (p.code == "PARKING") parkingRate else rateOf(p, useMaxRate)
                val i = if (p.code == "PARKING") m * (r / 100) * term * (term - 1) / 2.0 / 12 * tax  // 파킹: 매달 쌓이는 돈에 이자
                        else SwitchCalculator.maturityInterest(m, r, term, taxRate)
                Slot(p, m, r, i.roundToLong())
            }
            val cycleInterest = slots.sumOf { it.interestPerCycle }

            var depositBal = 0.0       // 예금에 굴리는 목돈 (원금+이미 받은 이자)
            var depInterest = 0.0
            var savInterest = 0.0
            var cyclePrincipal = 0L
            val cycles = mutableListOf<Cycle>()
            var cycleStart = 1
            var reached: Int? = null
            for (m in 1..horizon) {
                val di = depositBal * (depRate / 100) / 12 * tax
                depositBal += di; depInterest += di
                cyclePrincipal += monthly
                if ((m - cycleStart + 1) == term) {
                    depositBal += cyclePrincipal + cycleInterest
                    savInterest += cycleInterest
                    cycles += Cycle(cycles.size + 1, cycleStart, m, true)
                    cyclePrincipal = 0; cycleStart = m + 1
                }
                if (depositBal + cyclePrincipal >= target) { reached = m; break }
            }
            if (reached != null && cyclePrincipal > 0) cycles += Cycle(cycles.size + 1, cycleStart, reached, false)
            val months = reached ?: horizon
            return Plan(
                target = target, monthly = monthly, termMonths = term, slots = slots,
                deposit = depositOrNull, depositRate = depRate, cycles = cycles,
                monthsNeeded = reached,
                finalAmount = (depositBal + cyclePrincipal).roundToLong(),
                principal = monthly * months,
                savingInterest = savInterest.roundToLong(),
                depositInterest = depInterest.roundToLong(),
                noInterestMonths = ceil(target.toDouble() / monthly).toInt(),
                options = emptyList(),
                useMaxRate = useMaxRate
            )
        }

        val all = listOf(6, 12, 24, 36).mapNotNull { simulate(it) }
        if (all.isEmpty()) return null
        val best = all.minWith(compareBy<Plan>({ it.monthsNeeded ?: Int.MAX_VALUE }, { -it.finalAmount }))
        return best.copy(options = all.map { o ->
            Option(o.termMonths, o.monthsNeeded, o.finalAmount, o.slots.firstOrNull()?.let { "${it.product.bank} ${it.product.name}" } ?: "-")
        })
    }

    fun monthsText(m: Int): String {
        val y = m / 12; val r = m % 12
        return when {
            y == 0 -> "${r}개월"
            r == 0 -> "${y}년"
            else -> "${y}년 ${r}개월"
        }
    }
}
