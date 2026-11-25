package com.seojin.assetguardian.domain

import com.seojin.assetguardian.data.EarlyRule
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 갈아타기 손익 계산기 (앱의 "정확한 숫자"는 전부 여기서 나온다).
 *
 * 적금 이자는 단리: 매달 넣은 돈이 은행에 머문 개월 수만큼만 이자가 붙는다.
 *   k번째 달에 넣은 돈은 (만기 - k + 1)개월 동안 이자를 받는다.
 *
 * 해지 손실 = 유지했으면 지금까지 넣은 돈이 받을 이자
 *            - (중도해지 이자 + 해지한 원금을 파킹통장에 남은 기간 둔 이자)
 * 전환 이익 = 남은 납입분을 새 금리로 굴릴 때 더 받는 이자
 *            (새 상품 월 납입 한도가 작으면 한도까지만 새 금리, 넘는 돈은 파킹통장 금리)
 * 순이익   = (전환 이익 - 해지 손실), 모두 세후
 */
object SwitchCalculator {

    data class Input(
        val monthly: Long,
        val myRate: Double,        // 연 %
        val myBaseRate: Double,    // 연 %
        val termMonths: Int,
        val elapsed: Int,          // 지금까지 납입한 개월 수
        val newRate: Double,       // 연 %
        val parkingRate: Double,   // 연 %
        val taxRate: Double,       // %
        val earlyRule: String = EarlyRule.BANK_TABLE,
        val customEarlyRate: Double = 0.0,
        val newMonthlyCap: Long? = null    // 새 상품 월 납입 한도 (null = 한도 없음)
    )

    data class Result(
        val loss: Double,          // 세후 해지 손실 (원)
        val gain: Double,          // 세후 전환 이익 (원)
        val net: Double,           // 세후 순이익 (원)
        val breakEven: Double?,    // 손익분기 금리 (연 %), 남은 기간이 없으면 null
        val earlyRate: Double,     // 적용된 중도해지이율 (연 %)
        val remaining: Int,
        val newMonthly: Long = 0,  // 새 상품에 실제로 넣을 수 있는 월 금액
        val capped: Boolean = false // 월 한도 때문에 일부를 파킹통장에 둔다고 계산했는지
    )

    /** 시중은행 적금 상품설명서 방식의 중도해지이율 (연 %). 예: KB국민은행 적금 */
    fun earlyRate(baseRate: Double, elapsed: Int, term: Int, rule: String, custom: Double): Double {
        if (rule == EarlyRule.CUSTOM) return custom
        if (elapsed < 1) return 0.1
        val ratio = when {
            elapsed < 6 -> 0.5
            elapsed < 8 -> 0.6
            elapsed < 10 -> 0.7
            elapsed < 11 -> 0.8
            else -> 0.9
        }
        val floor = if (elapsed < 6) 0.1 else 0.2
        return maxOf(baseRate * ratio * elapsed / term, floor)
    }

    /** 월 납입 적금을 만기까지 유지했을 때 세후 이자 (원) */
    fun maturityInterest(monthly: Long, rate: Double, term: Int, taxRate: Double): Double =
        monthly * (rate / 100) * (term * (term + 1) / 2.0) / 12 * (1 - taxRate / 100)

    fun analyze(i: Input): Result {
        val el = i.elapsed.coerceIn(0, i.termMonths)
        val remaining = i.termMonths - el
        val tax = 1 - i.taxRate / 100
        val m = i.monthly.toDouble()

        // 유지 시: 지금까지 넣은 el번의 돈이 만기까지 받을 이자
        var keepMonths = 0
        for (k in 1..el) keepMonths += (i.termMonths - k + 1)
        val keepExisting = m * (i.myRate / 100) * keepMonths / 12

        // 해지 시: 중도해지이율로 지금까지의 이자 + 원금을 남은 기간 파킹
        val er = earlyRate(i.myBaseRate, el, i.termMonths, i.earlyRule, i.customEarlyRate)
        var earlyMonths = 0
        for (k in 1..el) earlyMonths += (el - k + 1)
        val earlyInterest = m * (er / 100) * earlyMonths / 12
        val parkingInterest = m * el * (i.parkingRate / 100) * remaining / 12

        val loss = (keepExisting - earlyInterest - parkingInterest) * tax

        // 남은 납입분: 새 상품 한도까지는 새 금리, 넘는 돈은 파킹 금리 vs 원래 적금 금리
        val cap = i.newMonthlyCap?.takeIf { it > 0 }
        val c = if (cap != null) minOf(m, cap.toDouble()) else m
        val remainSum = remaining * (remaining + 1) / 2.0
        val k = remainSum / 12 * tax
        val gain = (c * i.newRate + (m - c) * i.parkingRate - m * i.myRate) / 100 * k

        // 손익분기 금리: gain == loss 가 되는 새 상품 금리
        val breakEven = if (remaining <= 0 || c <= 0) null
        else (loss * 100 / k - (m - c) * i.parkingRate + m * i.myRate) / c

        return Result(loss, gain, gain - loss, breakEven, er, remaining, c.toLong(), c < m)
    }

    /** 가입일부터 오늘까지 지난 개월 수 (= 납입 횟수로 사용), 0 ~ 계약기간 */
    fun elapsedMonths(joinDate: String, today: LocalDate, term: Int): Int = try {
        val j = LocalDate.parse(joinDate)
        ChronoUnit.MONTHS.between(j, today).toInt().coerceIn(0, term)
    } catch (e: Exception) { 0 }

    fun maturityDate(joinDate: String, term: Int): LocalDate? = try {
        LocalDate.parse(joinDate).plusMonths(term.toLong())
    } catch (e: Exception) { null }
}
