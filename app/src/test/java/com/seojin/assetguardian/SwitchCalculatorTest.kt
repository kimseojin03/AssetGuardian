package com.seojin.assetguardian

import com.seojin.assetguardian.agent.GuardianBrain
import com.seojin.assetguardian.data.AppSettings
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.data.MyProduct
import com.seojin.assetguardian.data.Verdict
import com.seojin.assetguardian.domain.SwitchCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * 발표 자료의 예시 숫자와 같은지 확인하는 테스트.
 * 기존: 1년 적금, 월 50만원, 최종 3.5%(기본 3.0%) / 신규: 5.0% / 파킹 2.5% / 세율 15.4%
 * Android Studio에서 이 파일 왼쪽 ▶ 버튼으로 실행.
 */
class SwitchCalculatorTest {

    private fun net(elapsed: Int) = SwitchCalculator.analyze(
        SwitchCalculator.Input(500_000, 3.5, 3.0, 12, elapsed, 5.0, 2.5, 15.4)
    )

    @Test fun threeMonthsIsGain() {
        val r = net(3)
        assertEquals(16_127, r.loss.roundToLong())
        assertEquals(7_667, r.net.roundToLong())
        assertEquals(4.52, r.breakEven!!, 0.01)
    }

    @Test fun sixMonthsIsLoss() {
        val r = net(6)
        assertEquals(-20_833, r.net.roundToLong())
        assertEquals(7.81, r.breakEven!!, 0.01)
    }

    @Test fun agentKeepsWhenSwitchingLoses() {
        val today = LocalDate.of(2026, 10, 29)
        val my = MyProduct("a", "은행", "적금", 3.5, 3.0, 500_000, 12, today.minusMonths(6).toString())
        val market = listOf(MarketProduct("p", "은행B", "특판", 12, 5.0, 5.0))
        val report = GuardianBrain.run(listOf(my), market, emptyList(), AppSettings(), today, 1L)
        assertEquals(Verdict.KEEP, report.decisions.single().verdict)
        assertTrue(report.toNotify.isEmpty())
    }

    @Test fun learningRaisesThresholdWhenIgnored() {
        assertEquals(7_500, GuardianBrain.adjustThreshold(5_000, "IGNORE"))
        assertEquals(4_000, GuardianBrain.adjustThreshold(5_000, "ACCEPT"))
    }
}
