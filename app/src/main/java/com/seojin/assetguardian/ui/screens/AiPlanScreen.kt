@file:OptIn(ExperimentalMaterial3Api::class)

package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.agent.AiSlot
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.data.EventType
import com.seojin.assetguardian.data.ProductKind
import com.seojin.assetguardian.domain.GoalPlanner
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.components.LabelValue
import com.seojin.assetguardian.ui.components.Pill
import com.seojin.assetguardian.ui.components.ProductLinks
import com.seojin.assetguardian.ui.components.SectionTitle
import com.seojin.assetguardian.ui.components.SoftCard
import com.seojin.assetguardian.ui.components.pct
import com.seojin.assetguardian.ui.components.won
import com.seojin.assetguardian.ui.theme.Palette
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** AI 에이전트가 짠 목표 루트: AI가 한 일 → 고른 적금과 이유(가입 링크) → 예금 → 진행 순서 → 뺀 상품 → 주의 */
@Composable
fun AiPlanScreen(vm: MainViewModel, onBack: () -> Unit, onRetry: () -> Unit) {
    val ai by vm.aiPlan.collectAsStateWithLifecycle()
    val market by vm.market.collectAsStateWithLifecycle()
    val ym = DateTimeFormatter.ofPattern("yyyy년 M월")

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("AI가 짠 목표 루트") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg)
        )
        val a = ai
        val plan = a?.plan
        if (a == null || plan == null) {
            Text("아직 AI 루트가 없어요. 목표 탭에서 AI에게 맡겨보세요.", Modifier.padding(16.dp))
            return@Column
        }
        val start = LocalDate.ofEpochDay(a.createdAt / 86_400_000L).withDayOfMonth(1)
        val months = plan.monthsNeeded
        val term = plan.termMonths

        fun findProduct(code: String?, kind: String, t: Int): MarketProduct? =
            market.firstOrNull { it.kind == kind && it.code == code && (kind == ProductKind.DEPOSIT || it.termMonths == t) }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 요약
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.Hero).padding(20.dp)) {
                Text("목표 ${won(a.targetWon)} · 매달 ${won(a.monthlyWon)}", color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelLarge)
                Gap(4)
                Text(if (months == null) "20년 안에 어려워요" else "${GoalPlanner.monthsText(months)} 만에 달성",
                    color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (months != null) {
                    Text("${start.plusMonths(months.toLong() - 1).format(ym)} 예상", color = Color.White.copy(alpha = 0.9f))
                    val saved = (plan.noInterestMonths ?: months) - months
                    if (saved > 0) Text("이자 없이 모을 때보다 ${saved}개월 빨라요", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Gap(6)
                Text(a.mode ?: "", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
            }

            if (!a.message.isNullOrBlank()) {
                SoftCard(color = Palette.SkySoft) {
                    Text("내가 한 말", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                    Text("\"${a.message}\"", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // AI가 한 일 (에이전트 과정)
            SectionTitle("AI 에이전트가 한 일")
            SoftCard {
                (a.trace ?: emptyList()).forEachIndexed { i, t ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Dot("${i + 1}", Palette.Sky)
                        Spacer(Modifier.width(10.dp))
                        Text(t, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Gap(4)
                Text("검색·계산은 도구(코드)가 하고, 어떤 상품을 넣을지·받을 금리·몇 번 비교할지는 AI가 정했어요.",
                    style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            }

            if (!a.summary.isNullOrBlank()) {
                SoftCard(color = Palette.MintSoft) {
                    Text("AI 설명", fontWeight = FontWeight.Bold, color = Palette.MintDeep)
                    Text(a.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // ① 적금
            SectionTitle("① 매달 ${won(a.monthlyWon)}을 이렇게 나눠 넣어요 (${term}개월 적금)")
            (plan.slots ?: emptyList()).forEachIndexed { i, slot ->
                SlotCard(i + 1, slot, a.reasons?.get(slot.code), findProduct(slot.code, ProductKind.SAVING, term), term) { vm.log(EventType.LINK, it) }
            }

            // ② 예금
            SectionTitle("② ${term}개월마다 만기 목돈은 예금으로")
            SoftCard(color = Palette.SkySoft) {
                val d = plan.deposit
                if (d == null) {
                    Text("파킹통장 (연 ${pct(plan.depositRate)})", style = MaterialTheme.typography.titleMedium)
                    Text("지금은 파킹통장보다 나은 1년 예금이 없어서 파킹통장으로 계산했어요.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(d.bank ?: "", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                    Text(d.name ?: "", style = MaterialTheme.typography.titleMedium)
                    LabelValue("1년 예금 금리", "연 ${pct(d.rate)}")
                    Text("적금이 끝날 때마다 받은 목돈을 여기에 넣어 이자를 한 번 더 붙여요.", style = MaterialTheme.typography.bodyMedium)
                    Gap(10)
                    val depProduct = findProduct(d.code, ProductKind.DEPOSIT, 12)
                        ?: MarketProduct(d.code ?: "", d.bank ?: "", d.name ?: "", 12, d.rate, d.rate, kind = ProductKind.DEPOSIT)
                    ProductLinks(depProduct, onClick = { vm.log(EventType.LINK, depProduct) })
                }
            }

            // ③ 진행 순서
            SectionTitle("③ 진행 순서")
            SoftCard {
                (plan.cycles ?: emptyList()).forEach { c ->
                    val from = start.plusMonths(c.start.toLong() - 1).format(ym)
                    val to = start.plusMonths(c.end.toLong() - 1).format(ym)
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Dot("${c.number}", if (c.completed) Palette.Mint else Palette.Sky)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${c.number}회차 · $from ~ $to", fontWeight = FontWeight.Bold)
                            Text(if (c.completed) "적금 만기 → 목돈을 ${plan.deposit?.name ?: "파킹통장"}(으)로"
                                else "진행 중 ${c.end - c.start + 1}개월 차에 목표 도달",
                                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                        }
                    }
                }
                (plan.withdrawalNotes ?: emptyList()).forEach { n ->
                    Gap(4)
                    Text("• $n", style = MaterialTheme.typography.bodyMedium, color = Palette.Amber)
                }
                Gap(6)
                LabelValue("넣은 원금", won(plan.principal))
                LabelValue("적금 이자 (세후)", "+" + won(plan.savingInterest), Palette.Gain)
                LabelValue("예금 이자 (세후)", "+" + won(plan.depositInterest), Palette.Gain)
                LabelValue("목표 시점 예상 금액", won(plan.finalAmount), bold = true)
            }

            // 뺀 상품
            val ex = a.excluded ?: emptyList()
            if (ex.isNotEmpty()) {
                SectionTitle("금리는 높지만 AI가 뺀 상품")
                SoftCard {
                    ex.forEach { e ->
                        Text(e.name ?: "", fontWeight = FontWeight.Bold)
                        Text(e.reason ?: "", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                        Gap(6)
                    }
                }
            }

            val cautions = a.cautions ?: emptyList()
            if (cautions.isNotEmpty()) {
                SoftCard(color = Palette.AmberSoft) {
                    Text("주의할 점", fontWeight = FontWeight.Bold, color = Palette.Amber)
                    cautions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }

            Text("정보 제공용 계산이에요. 금리·조건은 가입 전에 은행 페이지에서 꼭 다시 확인하세요.",
                style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("조건 바꿔서 다시 맡기기") }
            Gap(24)
        }
    }
}

@Composable
private fun SlotCard(n: Int, slot: AiSlot, reason: String?, product: MarketProduct?, term: Int, onLink: (MarketProduct) -> Unit) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot("$n", Palette.Mint)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(slot.bank ?: "", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                Text(slot.name ?: "", style = MaterialTheme.typography.titleMedium)
            }
            Pill("매달 ${won(slot.monthly)}", Palette.MintSoft, Palette.MintDeep)
        }
        Gap(8)
        if (slot.code == "PARKING") {
            Text("적금 한도를 다 채우고 남은 돈이에요. 아무 은행 파킹통장(연 ${pct(slot.rate)} 가정)에 두면 돼요.",
                style = MaterialTheme.typography.bodyMedium)
            return@SoftCard
        }
        LabelValue("AI가 판단한 적용 금리", "연 ${pct(slot.rate)}" +
            (product?.let { " (기본 ${pct(it.baseRate)} / 최고 ${pct(it.maxRate)})" } ?: ""))
        LabelValue("월 납입 한도", slot.cap?.let { won(it) } ?: "정보 없음")
        LabelValue("만기마다 받는 세후 이자", "+" + won(slot.interestPerCycle), Palette.Gain, true)
        if (!reason.isNullOrBlank()) {
            Gap(6)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.SkySoft).padding(10.dp)) {
                Text("AI: $reason", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Gap(10)
        val target = product ?: MarketProduct(slot.code ?: "", slot.bank ?: "", slot.name ?: "", term, slot.rate, slot.rate)
        ProductLinks(target, onClick = { onLink(target) })
    }
}

@Composable
private fun Dot(text: String, color: Color) {
    Box(Modifier.size(26.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
    }
}
