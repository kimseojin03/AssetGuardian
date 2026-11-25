package com.seojin.assetguardian.data

import java.net.URLEncoder

/** 금감원 데이터의 문장(가입대상·기타사항)에서 쓸모 있는 규칙을 뽑아내는 도우미 */
object ProductRules {

    private val restrictedWords = listOf(
        "키즈", "어린이", "아이적금", "우리아이", "아동", "자녀", "청소년", "미성년", "주니어",
        "장병", "군인", "병사", "시니어", "외국인전용",
        "기초생활", "수급자", "차상위", "한부모", "장애인", "새터민", "반려동물", "출산"
    )
    private val policyWords = listOf("청년도약", "청년희망", "청년내일", "청년미래", "장병내일", "주택청약", "청약", "ISA", "비과세")

    /** 정부 지원·비과세 정책 상품: 중도해지하면 비과세 혜택·정부기여금을 잃을 수 있음 */
    fun isPolicy(name: String): Boolean = policyWords.any { name.contains(it, ignoreCase = true) }

    private val underAge = Regex("""만\s*(\d{1,2})\s*세\s*(이하|미만)""")

    /** 어린이·장병·서민전용처럼 일반 성인이 가입하기 어려운 상품이면 true (목표 루트에서 제외) */
    fun isRestricted(name: String, joinMember: String, joinDeny: String): Boolean {
        if (joinDeny == "2") return true  // 서민전용
        val text = "$name $joinMember"
        if (restrictedWords.any { text.contains(it) }) return true
        return underAge.findAll(joinMember).any { (it.groupValues[1].toIntOrNull() ?: 99) <= 18 }
    }

    private val amount = Regex("""(\d[\d,]*)\s*(천|백)?\s*만\s*원""")

    /**
     * 월 납입 한도(원). max_limit 값이 있으면 그걸 쓰고,
     * 없으면 기타사항의 "월 50만원 이내", "월 5만원 이상 1백만원 이하" 같은 문장에서 가장 큰 금액을 한도로 본다.
     */
    fun monthlyCap(maxLimit: Long?, etcNote: String): Long? {
        if (maxLimit != null && maxLimit in 1..50_000_000) return maxLimit
        for (line in etcNote.split('\n')) {
            if (!line.contains("월")) continue
            if (!(line.contains("이하") || line.contains("이내") || line.contains("까지"))) continue
            val values = amount.findAll(line).mapNotNull { m ->
                val n = m.groupValues[1].replace(",", "").toLongOrNull() ?: return@mapNotNull null
                val mul = when (m.groupValues[2]) { "천" -> 1000L; "백" -> 100L; else -> 1L }
                n * mul * 10_000
            }.toList()
            if (values.isNotEmpty()) return values.max()
        }
        return null
    }

    /** 상품 페이지를 바로 찾을 수 있는 네이버 검색 주소 (금감원 API에는 상품별 페이지 주소가 없음) */
    fun searchUrl(p: MarketProduct): String =
        "https://search.naver.com/search.naver?query=" + URLEncoder.encode("${p.bank} ${p.name}", "UTF-8")

    fun homepageUrl(p: MarketProduct): String? {
        val h = p.homepage.trim()
        if (h.isBlank()) return null
        return if (h.startsWith("http")) h else "https://$h"
    }
}
