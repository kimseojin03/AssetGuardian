package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.seojin.assetguardian.data.AppSettings
import com.seojin.assetguardian.data.Decision
import com.seojin.assetguardian.data.MyProduct
import com.seojin.assetguardian.agent.AiPlan
import com.seojin.assetguardian.data.MemorySummary
import androidx.compose.runtime.LaunchedEffect
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.domain.GoalPlanner
import androidx.compose.runtime.remember
import com.seojin.assetguardian.domain.SwitchCalculator
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.components.LabelValue
import com.seojin.assetguardian.ui.components.Pill
import com.seojin.assetguardian.ui.components.SoftCard
import com.seojin.assetguardian.ui.components.pct
import com.seojin.assetguardian.ui.components.verdictStyle
import com.seojin.assetguardian.ui.components.won
import com.seojin.assetguardian.ui.theme.Palette
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onAdd: () -> Unit,
    onCompare: (String) -> Unit,
    onSettings: () -> Unit,
    onGoal: () -> Unit,
    onAiPlan: () -> Unit,
    onMemory: () -> Unit,
    onAgentScreen: (String) -> Unit,
    onPlan: (target: Long, saving: Long, useMax: Boolean) -> Unit
) {
    val mine by vm.myProducts.collectAsStateWithLifecycle()
    val decisions by vm.decisions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val demo by vm.demo.collectAsStateWithLifecycle()
    val market by vm.market.collectAsStateWithLifecycle()
    val aiPlan by vm.aiPlan.collectAsStateWithLifecycle()
    val memory by vm.memory.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadMemory() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("자산지킴이", style = MaterialTheme.typography.headlineSmall, color = Palette.Ink)
                        Text("내 적금을 매일 대신 지켜보고 있어요", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "설정", tint = Palette.Muted) }
                }
            }
            item { HeroCard(mine, settings, loading) { vm.runAgentNow() } }
            item {
                val ap = aiPlan
                if (ap?.plan != null) AiGoalCard(ap, onAiPlan) else GoalCard(settings, market, onGoal, onPlan)
            }
            item { AgentCard(vm, onAgentScreen) }
            item { MemoryCard(memory, onMemory) }
            if (demo || settings.apiKey.isBlank()) {
                item {
                    SoftCard(color = Palette.AmberSoft) {
                        Text("데모 데이터로 보는 중이에요", fontWeight = FontWeight.Bold, color = Palette.Amber)
                        Text("설정에서 금감원 금융상품 인증키를 넣으면 실제 은행 적금으로 감시해요.",
                            style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
                    }
                }
            }
            if (mine.isEmpty()) {
                item { EmptyCard(onAdd) }
            } else {
                items(mine, key = { it.id }) { p ->
                    val latest = decisions.firstOrNull { it.myId == p.id }
                    MyProductCard(p, settings, latest, onClick = { onCompare(p.id) }, onDelete = { vm.deleteProduct(p.id) })
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            containerColor = Palette.Mint, contentColor = Color.White,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("적금 등록") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
}

@Composable
private fun HeroCard(mine: List<MyProduct>, s: AppSettings, loading: Boolean, onRun: () -> Unit) {
    val totalMonthly = mine.sumOf { it.monthly }
    val totalInterest = mine.sumOf {
        SwitchCalculator.maturityInterest(it.monthly, it.finalRate, it.termMonths, s.taxRate)
    }
    val last = if (s.lastCheck == 0L) "아직 점검 전"
    else SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(s.lastCheck)) + " 점검"

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.Hero).padding(20.dp)
    ) {
        Text("오늘의 지킴이 리포트", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelLarge)
        Gap(6)
        Text("만기 세후 이자 약 ${won(totalInterest)}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("적금 ${mine.size}개 · 매달 ${won(totalMonthly)} 저축 중", color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodyMedium)
        Gap(14)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$last · 매일 오전 8시 자동", color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Button(
                onClick = onRun, enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Palette.MintDeep)
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.MintDeep)
                else Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("지금 점검")
            }
        }
    }
}

/** 홈의 "AI가 기억하는 나" 요약 — 누르면 기억 화면 */
/** 에이전트가 계속 챙기고 있는 것: 목표 진행 · 우대조건 할 일 · 주간 리포트 */
@Composable
private fun AgentCard(vm: MainViewModel, open: (String) -> Unit) {
    val goal by vm.goal.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val weekly by vm.weekly.collectAsStateWithLifecycle()
    val ok by vm.serverOk.collectAsStateWithLifecycle()
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("에이전트가 챙기는 중", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (ok == false) Pill("PC 서버 꺼짐", Palette.AmberSoft, Palette.Amber)
        }
        Gap(6)
        val g = goal
        AgentRow("목표 진행",
            if (g?.active == true) "${g.progressPct}% · " + when (g.status) {
                "behind" -> "계획보다 ${g.delayMonths ?: 0}개월 늦어지는 중"; "ahead" -> "계획보다 앞서는 중"
                "on_track" -> "계획대로"; else -> "이번 달 저축을 기록해 주세요"
            } else "AI 루트를 짜면 매달 추적해요",
            if (g?.status == "behind") Palette.Loss else Palette.MintDeep) { open("goaltrack") }
        val next = tasks.minByOrNull { it.nextDue }
        AgentRow("우대조건 할 일",
            if (tasks.isEmpty()) "적금을 등록하면 자동으로 만들어요"
            else "${tasks.size}개 · 다음: ${next?.title} (" + SimpleDateFormat("M/d", Locale.KOREA).format(Date(((next?.nextDue ?: 0.0) * 1000).toLong())) + ")",
            Palette.MintDeep) { open("tasks") }
        AgentRow("주간 리포트",
            weekly?.summaryLines?.firstOrNull() ?: "매주 자동으로 정리해서 알려드려요", Palette.MintDeep) { open("weekly") }
    }
}

@Composable
private fun AgentRow(title: String, sub: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(sub, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Text("›", color = Palette.Muted, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun MemoryCard(m: MemorySummary?, onOpen: () -> Unit) {
    SoftCard(Modifier.clickable { onOpen() }, color = Palette.SkySoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI가 기억하는 나", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(if (m?.fromServer == true) "기억 DB 연결됨" else "휴대폰 기록", Color.White, Palette.MintDeep)
        }
        Gap(4)
        val facts = m?.facts?.size ?: 0
        val interests = m?.interests ?: emptyList()
        val recent = m?.recent?.size ?: 0
        Text("아는 사실 ${facts}개 · 관심 상품 ${interests.size}개 · 최근 본 적금 ${recent}개",
            style = MaterialTheme.typography.bodyMedium)
        interests.firstOrNull()?.let {
            Text("가장 관심: ${it.name} (${it.term}개월, 기본 ${pct(it.rate)})", style = MaterialTheme.typography.bodyMedium,
                color = Palette.MintDeep, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        val pf = m?.profile
        if (pf?.ready == true) {
            Text("저축 성향: ${pf.label} (신뢰도 ${pf.confidenceLabel})", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold)
        } else if (pf != null) {
            Text(pf.message ?: "", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
        m?.taste?.favoriteTerm?.let {
            Text("주로 ${it}개월 적금을 살펴봐요", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
        Gap(4)
        Text("눌러서 저축 성향 · 맞춤 추천 · 관심 상품 · 지난 루트 보기 →", color = Palette.Sky, style = MaterialTheme.typography.labelMedium)
    }
}

/** 홈의 목표 요약 — AI 에이전트가 짠 루트가 있으면 이걸 보여줌 */
@Composable
private fun AiGoalCard(a: AiPlan, onOpen: () -> Unit) {
    val p = a.plan ?: return
    SoftCard(Modifier.clickable { onOpen() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("내 목표", color = Palette.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Pill("AI 에이전트 루트", Palette.Mint, Color.White)
        }
        Text(won(a.targetWon), style = MaterialTheme.typography.titleLarge)
        val m = p.monthsNeeded
        if (m != null) {
            val date = LocalDate.ofEpochDay(a.createdAt / 86_400_000L).withDayOfMonth(1).plusMonths(m.toLong() - 1)
            Text("${GoalPlanner.monthsText(m)} 뒤 (${date.year}년 ${date.monthValue}월) 달성 예상 · 매달 ${won(a.monthlyWon)}",
                color = Palette.MintDeep, fontWeight = FontWeight.Bold)
        }
        Gap(6)
        (p.slots ?: emptyList()).forEach { sl ->
            Text("· ${(sl.bank ?: "").removePrefix("주식회사 ")} ${sl.name} 매달 ${won(sl.monthly)} (연 ${pct(sl.rate)})",
                style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        Text("· ${p.termMonths}개월마다 만기 목돈 → ${p.deposit?.name ?: "파킹통장"}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        if (!a.summary.isNullOrBlank()) {
            Gap(4)
            Text(a.summary, style = MaterialTheme.typography.labelMedium, color = Palette.Muted, maxLines = 2)
        }
        Gap(6)
        Text("눌러서 AI가 한 일 · 고른 이유 · 가입 페이지 보기 →", color = Palette.Sky, style = MaterialTheme.typography.labelMedium)
    }
}

/** 홈의 목표 요약: 목표 금액 · 달성 시점 · 루트 한 줄. 누르면 상세 루트 */
@Composable
private fun GoalCard(
    s: AppSettings, market: List<MarketProduct>, onGoal: () -> Unit,
    onPlan: (Long, Long, Boolean) -> Unit
) {
    if (s.goalAmount <= 0 || s.monthlySaving <= 0) {
        SoftCard(Modifier.clickable { onGoal() }, color = Palette.SkySoft) {
            Text("목표 금액을 정해보세요", style = MaterialTheme.typography.titleMedium)
            Text("예: 5,000만원 — 실제 은행 상품으로 가장 빠른 루트를 짜드려요 →",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        return
    }
    val plan = remember(s, market) {
        GoalPlanner.plan(s.goalAmount, s.monthlySaving, market, s.taxRate, s.parkingRate, s.useMaxRate)
    }
    SoftCard(Modifier.clickable { onPlan(s.goalAmount, s.monthlySaving, s.useMaxRate) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("내 목표", color = Palette.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Pill("매달 ${won(s.monthlySaving)}", Palette.MintSoft, Palette.MintDeep)
        }
        Text(won(s.goalAmount), style = MaterialTheme.typography.titleLarge)
        val m = plan?.monthsNeeded
        if (plan == null || m == null) {
            Text(if (market.isEmpty()) "상품을 불러오는 중이에요" else "지금 저축액으로는 20년 안에 어려워요",
                color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            val date = LocalDate.now().withDayOfMonth(1).plusMonths(m.toLong() - 1)
            Text("${GoalPlanner.monthsText(m)} 뒤 (${date.year}년 ${date.monthValue}월) 달성 예상",
                color = Palette.MintDeep, fontWeight = FontWeight.Bold)
            Gap(6)
            plan.slots.forEach { sl ->
                Text("· ${sl.product.bank.removePrefix("주식회사 ")} ${sl.product.name} 매달 ${won(sl.monthly)} (연 ${pct(sl.rate)})",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            Text("· ${plan.termMonths}개월마다 만기 목돈 → ${plan.deposit?.name ?: "파킹통장"} (연 ${pct(plan.depositRate)})",
                style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        Gap(6)
        Text("눌러서 루트 자세히 보기 · 가입 페이지 바로가기 →", color = Palette.Sky, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EmptyCard(onAdd: () -> Unit) {
    SoftCard(color = Palette.MintSoft) {
        Text("아직 등록한 적금이 없어요", style = MaterialTheme.typography.titleMedium, color = Palette.MintDeep)
        Gap(4)
        Text("가입한 적금을 등록하면, 더 좋은 상품이 나왔을 때 갈아타는 게 이득인지 매일 계산해서 알려드려요.",
            style = MaterialTheme.typography.bodyMedium)
        Gap(10)
        Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)) { Text("첫 적금 등록하기") }
    }
}

@Composable
private fun MyProductCard(p: MyProduct, s: AppSettings, latest: Decision?, onClick: () -> Unit, onDelete: () -> Unit) {
    val elapsed = SwitchCalculator.elapsedMonths(p.joinDate, LocalDate.now(), p.termMonths)
    val maturity = SwitchCalculator.maturityInterest(p.monthly, p.finalRate, p.termMonths, s.taxRate)
    val be = SwitchCalculator.analyze(
        SwitchCalculator.Input(p.monthly, p.finalRate, p.baseRate, p.termMonths, elapsed, p.finalRate,
            s.parkingRate, s.taxRate, p.earlyRule, p.customEarlyRate)
    ).breakEven

    SoftCard(Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.bank, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                Text(p.name, style = MaterialTheme.typography.titleMedium)
            }
            Pill("연 ${pct(p.finalRate)}", Palette.MintSoft, Palette.MintDeep)
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "삭제", tint = Palette.Muted) }
        }
        Gap(8)
        LinearProgressIndicator(
            progress = { elapsed / p.termMonths.toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
            color = Palette.Mint, trackColor = Palette.MintSoft
        )
        Gap(4)
        Text("${elapsed}개월 / ${p.termMonths}개월 · 매달 ${won(p.monthly)}", color = Palette.Muted,
            style = MaterialTheme.typography.labelMedium)
        Gap(8)
        LabelValue("만기 세후 이자", "약 ${won(maturity)}", bold = true)
        LabelValue(
            "갈아탈 가치가 생기는 금리",
            if (be == null) "만기 도달" else "연 ${pct(be)} 이상",
            valueColor = Palette.MintDeep, bold = true
        )
        if (latest != null) {
            Gap(8)
            val st = verdictStyle(latest.verdict)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(st.label, st.bg, st.fg)
                Spacer(Modifier.width(8.dp))
                Text(latest.candidateBank + " " + latest.candidateName, style = MaterialTheme.typography.labelMedium,
                    color = Palette.Muted, maxLines = 1)
            }
        }
        Gap(6)
        Text("눌러서 시장 상품과 비교하기 →", color = Palette.Sky, style = MaterialTheme.typography.labelMedium)
    }
}
