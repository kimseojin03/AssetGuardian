package com.seojin.assetguardian.domain

import com.seojin.assetguardian.data.EventType
import com.seojin.assetguardian.data.MemInterest
import com.seojin.assetguardian.data.MemRecent
import com.seojin.assetguardian.data.MemTaste
import com.seojin.assetguardian.data.MemoryEvent
import com.seojin.assetguardian.data.MemorySummary
import kotlin.math.pow

/**
 * 휴대폰에 쌓인 행동 기록으로 관심 상품·최근 본 상품·성향을 계산한다.
 * (서버 memory.py 와 같은 규칙 — 서버가 꺼져 있어도 기억 화면과 매일 점검이 동작하도록)
 */
object MemoryAnalyzer {

    private val weights = mapOf(
        EventType.VIEW to 1.0, EventType.COMPARE to 2.0, EventType.LINK to 3.0,
        EventType.REGISTER to 5.0, EventType.ALERT_ACCEPT to 3.0, EventType.ALERT_IGNORE to -2.0
    )
    private const val HALF_LIFE_DAYS = 30.0

    fun interests(events: List<MemoryEvent>, now: Long, limit: Int = 10): List<MemInterest> {
        data class Acc(var e: MemoryEvent, var score: Double = 0.0, var views: Int = 0, var links: Int = 0, var last: Long = 0)
        val acc = LinkedHashMap<String, Acc>()
        for (e in events) {
            if (e.code.isBlank()) continue
            val w = (weights[e.type] ?: 0.0) * 0.5.pow((now - e.at) / 86_400_000.0 / HALF_LIFE_DAYS)
            val a = acc.getOrPut("${e.kind}|${e.code}|${e.term}") { Acc(e) }
            a.score += w
            if (e.type == EventType.VIEW) a.views++
            if (e.type == EventType.LINK) a.links++
            if (e.at >= a.last) { a.last = e.at; a.e = e }
        }
        return acc.values.filter { it.score > 0.3 }.sortedByDescending { it.score }.take(limit).map {
            MemInterest(it.e.code, it.e.kind, it.e.term, it.e.bank, it.e.name, it.e.rate,
                Math.round(it.score * 100) / 100.0, it.views, it.links, it.last / 1000.0)
        }
    }

    fun recent(events: List<MemoryEvent>, limit: Int = 15): List<MemRecent> =
        events.filter { it.code.isNotBlank() && it.type in setOf(EventType.VIEW, EventType.COMPARE, EventType.LINK) }
            .sortedByDescending { it.at }
            .distinctBy { "${it.kind}|${it.code}|${it.term}" }
            .take(limit)
            .map { MemRecent(it.code, it.kind, it.term, it.bank, it.name, it.rate, it.at / 1000.0) }

    fun taste(events: List<MemoryEvent>): MemTaste {
        val ev = events.filter { it.type in setOf(EventType.VIEW, EventType.COMPARE, EventType.LINK, EventType.REGISTER) }
        val acc = events.count { it.type == EventType.ALERT_ACCEPT }
        val ign = events.count { it.type == EventType.ALERT_IGNORE }
        if (ev.isEmpty()) return MemTaste(0, alertAccept = acc, alertIgnore = ign)
        val terms = ev.filter { it.term > 0 }.groupingBy { it.term }.eachCount()
        val rates = ev.map { it.rate }.filter { it > 0 }
        return MemTaste(
            events = ev.size,
            favoriteTerm = terms.maxByOrNull { it.value }?.key,
            savingsBankShare = Math.round(ev.count { it.bank.contains("저축은행") } * 100.0 / ev.size) / 100.0,
            avgRateViewed = if (rates.isEmpty()) null else Math.round(rates.average() * 100) / 100.0,
            alertAccept = acc, alertIgnore = ign
        )
    }

    /** 서버 없이 만드는 요약 (AI가 기억한 사실·루트 기록은 서버에만 있으므로 비어 있음) */
    fun localSummary(events: List<MemoryEvent>, now: Long): MemorySummary =
        MemorySummary(
            facts = emptyList(),
            interests = interests(events, now),
            recent = recent(events),
            taste = taste(events),
            plans = emptyList(),
            counts = events.groupingBy { it.type }.eachCount(),
            fromServer = false
        )
}
