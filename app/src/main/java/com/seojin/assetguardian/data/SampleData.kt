package com.seojin.assetguardian.data

/**
 * 인증키가 없을 때(또는 발표 시연용) 쓰는 데모 데이터.
 * 실제 은행 상품이 아니라 화면·계산 흐름을 보여주기 위한 가상의 값이다.
 */
object SampleData {
    val market: List<MarketProduct> = listOf(
        MarketProduct("DEMO01", "데모은행 A", "봄맞이 특판 적금", 12, 4.6, 5.0, "자유적립식",
            "1. 급여이체 실적 0.2%p\n2. 카드 사용 월 30만원 이상 0.2%p", "제한없음", "스마트폰", monthlyCap = 300_000),
        MarketProduct("DEMO02", "데모은행 B", "청년 응원 적금", 12, 4.0, 5.5, "정액적립식",
            "1. 만 19~34세 1.0%p\n2. 첫 거래 고객 0.5%p", "만 19~34세", "스마트폰", monthlyCap = 500_000),
        MarketProduct("DEMO03", "데모은행 C", "든든 정기적금", 12, 3.4, 3.8, "정액적립식",
            "1. 자동이체 0.4%p", "제한없음", "영업점,인터넷,스마트폰"),
        MarketProduct("DEMO04", "데모저축은행", "하이 적금", 12, 4.2, 4.5, "자유적립식",
            "1. 마케팅 동의 0.3%p", "제한없음", "스마트폰"),
        MarketProduct("DEMO05", "데모은행 A", "봄맞이 특판 적금", 24, 4.3, 4.7, "자유적립식",
            "1. 급여이체 실적 0.2%p\n2. 카드 사용 월 30만원 이상 0.2%p", "제한없음", "스마트폰", monthlyCap = 300_000),
        MarketProduct("DEMO06", "데모은행 D", "6개월 단기 적금", 6, 3.9, 4.4, "자유적립식",
            "1. 앱 첫 가입 0.5%p", "제한없음", "스마트폰", monthlyCap = 1_000_000),
        MarketProduct("DEMO07", "데모저축은행", "하이 정기예금", 12, 3.8, 3.8, "",
            "", "제한없음", "스마트폰", kind = ProductKind.DEPOSIT),
    )

    /** 데모 모드에서 "어제" 데이터 — 오늘과 비교해 신규/금리인상 감지를 보여주기 위함 */
    val marketYesterday: List<MarketProduct> = market
        .filter { it.code != "DEMO02" }  // DEMO02는 오늘 새로 나온 상품
        .map { if (it.code == "DEMO01" && it.termMonths == 12) it.copy(baseRate = 4.2, maxRate = 4.6) else it }
}
