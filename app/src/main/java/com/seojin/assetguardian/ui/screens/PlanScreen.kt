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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.domain.GoalPlanner
import com.seojin.assetguardian.data.EventType
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

/** 목표 루트 상세: 어떤 적금에 얼마씩 → 만기 목돈은 어느 예금으로 → 몇 회차에 목표 달성 */
@Composable
fun PlanScreen(vm: MainViewModel, target: Long, saving: Long, useMax: Boolean, onBack: () -> Unit) {
    val market by vm.market.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val plan = remember(market, target, saving, useMax, s.taxRate, s.parkingRate) {
        GoalPlanner.plan(target, saving, market, s.taxRate, s.parkingRate, useMax)
    }
    val ym = DateTimeFormatter.ofPattern("yyyy년 M월")
    val start = LocalDate.now().withDayOfMonth(1)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("목표 루트") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg)
        )
        if (plan == null) {
            Text("이 조건으로는 루트를 만들 수 없어요. 목표 금액과 저축액을 확인해 주세요.", Modifier.padding(16.dp))
            return@Column
        }
        val months = plan.monthsNeeded
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 요약
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.Hero).padding(20.dp)) {
                Text("목표 ${won(plan.target)}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelLarge)
                Gap(4)
                Text(
                    if (months == null) "20년 안에 어려워요" else "${GoalPlanner.monthsText(months)} 만에 달성",
                    color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold
                )
                if (months != null) {
                    Text("${start.plusMonths(months.toLong() - 1).format(ym)} 예상 · 매달 ${won(plan.monthly)} 저축",
                        color = Color.White.copy(alpha = 0.9f))
                    val saved = (plan.noInterestMonths ?: months) - months
                    if (saved > 0) Text("이자 없이 모을 때보다 ${saved}개월 빨라요", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            // ① 적금 나눠 넣기
            SectionTitle("① 매달 ${won(plan.monthly)}을 이렇게 나눠 넣어요")
            Text("금리가 높은 적금부터 월 납입 한도까지 채우고, 남는 돈은 다음 적금으로 넘겼어요." +
                if (plan.useMaxRate) " (우대금리 포함 가정)" else " (조건 없이 받는 기본금리 기준)",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            plan.slots.forEachIndexed { i, slot ->
                val p = slot.product
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StepDot("${i + 1}")
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.bank, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                        }
                        Pill("매달 ${won(slot.monthly)}", Palette.MintSoft, Palette.MintDeep)
                    }
                    Gap(8)
                    if (p.code == "PARKING") {
                        Text("적금 한도를 다 채우고 남은 돈이에요. 아무 은행 파킹통장(연 ${pct(slot.rate)} 가정)에 모아두면 돼요.",
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LabelValue("적용 금리", "연 ${pct(slot.rate)} (기본 ${pct(p.baseRate)} / 최고 ${pct(p.maxRate)})")
                        LabelValue("기간", "${p.termMonths}개월 · ${p.rsrvType.ifBlank { "적립식" }}")
                        LabelValue("월 납입 한도", p.monthlyCap?.let { won(it) } ?: "정보 없음 (한도 확인 필요)")
                        LabelValue("만기마다 받는 세후 이자", "+" + won(slot.interestPerCycle), Palette.Gain, true)
                        if (p.specialCondition.isNotBlank() && plan.useMaxRate) {
                            Gap(4)
                            Text("우대조건: " + p.specialCondition.take(120), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        }
                        Gap(10)
                        ProductLinks(p, onClick = { vm.log(EventType.LINK, p) })
                    }
                }
            }

            // ② 만기 목돈 굴리기
            SectionTitle("② ${plan.termMonths}개월마다 만기 목돈은 예금으로")
            SoftCard(color = Palette.SkySoft) {
                val d = plan.deposit
                if (d == null) {
                    Text("파킹통장 (연 ${pct(plan.depositRate)})", style = MaterialTheme.typography.titleMedium)
                    Text("지금은 파킹통장보다 나은 1년 예금이 없어서 파킹통장에 두는 걸로 계산했어요.",
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(d.bank, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                    Text(d.name, style = MaterialTheme.typography.titleMedium)
                    LabelValue("1년 예금 금리", "연 ${pct(d.baseRate)}")
                    Text("적금이 끝날 때마다 받은 목돈(원금+이자)을 여기에 넣어 이자를 한 번 더 붙여요.",
                        style = MaterialTheme.typography.bodyMedium)
                    Gap(10)
                    ProductLinks(d, onClick = { vm.log(EventType.LINK, d) })
                }
            }

            // ③ 타임라인
            SectionTitle("③ 진행 순서")
            SoftCard {
                plan.cycles.forEach { c ->
                    val from = start.plusMonths(c.startMonth.toLong() - 1).format(ym)
                    val to = start.plusMonths(c.endMonth.toLong() - 1).format(ym)
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        StepDot("${c.number}", if (c.completed) Palette.Mint else Palette.Sky)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${c.number}회차 · $from ~ $to", fontWeight = FontWeight.Bold)
                            Text(
                                if (c.completed) "적금 만기 → 목돈을 ${plan.deposit?.name ?: "파킹통장"}(으)로 옮기기"
                                else "새 적금 진행 중 ${c.endMonth - c.startMonth + 1}개월 차에 목표 금액 도달",
                                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted
                            )
                        }
                    }
                }
                Gap(6)
                LabelValue("넣은 원금", won(plan.principal))
                LabelValue("적금 이자 (세후)", "+" + won(plan.savingInterest), Palette.Gain)
                LabelValue("예금 이자 (세후)", "+" + won(plan.depositInterest), Palette.Gain)
                LabelValue("목표 시점 예상 금액", won(plan.finalAmount), bold = true)
            }

            // 왜 이 루트?
            SectionTitle("왜 ${plan.termMonths}개월 적금 루트인가요?")
            SoftCard {
                Text("적금 기간별로 같은 방식을 모두 계산해서 가장 빨리 도달하는 걸 골랐어요. 같으면 최종 금액이 많은 쪽.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                Gap(8)
                plan.options.forEach { o ->
                    val chosen = o.termMonths == plan.termMonths
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${o.termMonths}개월", Modifier.width(56.dp),
                            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                            color = if (chosen) Palette.MintDeep else Palette.Ink)
                        Column(Modifier.weight(1f)) {
                            Text(o.monthsNeeded?.let { GoalPlanner.monthsText(it) + " 만에 달성" } ?: "20년 초과",
                                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                            Text(o.firstSlot, style = MaterialTheme.typography.labelMedium, color = Palette.Muted, maxLines = 1)
                        }
                        Text(won(o.finalAmount), style = MaterialTheme.typography.labelMedium)
                        if (chosen) { Spacer(Modifier.width(6.dp)); Pill("선택", Palette.MintSoft, Palette.MintDeep) }
                    }
                }
                Gap(8)
                Text("· 어린이·장병·서민전용처럼 아무나 가입 못 하는 상품은 뺐어요\n· 지금 금리가 그대로라고 가정했어요. 금리가 바뀌면 에이전트가 매일 다시 계산해서 더 빠른 루트가 생기면 알려드려요",
                    style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            }
            Gap(24)
        }
    }
}

@Composable
private fun StepDot(text: String, color: Color = Palette.Mint) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
    }
}
