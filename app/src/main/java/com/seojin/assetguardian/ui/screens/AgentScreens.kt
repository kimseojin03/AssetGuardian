@file:OptIn(ExperimentalMaterial3Api::class)

package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.domain.GoalPlanner
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.components.LabelValue
import com.seojin.assetguardian.ui.components.Pill
import com.seojin.assetguardian.ui.components.SectionTitle
import com.seojin.assetguardian.ui.components.SoftCard
import com.seojin.assetguardian.ui.components.pct
import com.seojin.assetguardian.ui.components.won
import com.seojin.assetguardian.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun Frame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg)
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
            Gap(24)
        }
    }
}

@Composable
private fun ServerOff() {
    SoftCard(color = Palette.AmberSoft) {
        Text("PC에서 run_server.bat을 켜야 볼 수 있어요", color = Palette.Amber, fontWeight = FontWeight.Bold)
        Text("이 기능은 에이전트 서버의 기억 DB를 사용해요.", style = MaterialTheme.typography.bodyMedium)
    }
}

private val dateFmt = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)

// ===================================================================== 1) 우대조건 체크리스트
@Composable
fun TasksScreen(vm: MainViewModel, onBack: () -> Unit) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val mine by vm.myProducts.collectAsStateWithLifecycle()
    val ok by vm.serverOk.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadAgentData() }

    Frame("우대조건 체크리스트", onBack) {
        SoftCard(color = Palette.MintSoft) {
            Text("우대금리를 놓치지 않게 에이전트가 챙겨요", fontWeight = FontWeight.Bold, color = Palette.MintDeep)
            Text("적금을 등록하면 AI가 우대조건 문장을 읽고 할 일로 바꿔요. 기한 하루 전에 알림이 오고, '했어요'를 누르면 다음 달로 넘어가요.",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (ok == false) ServerOff()
        if (tasks.isEmpty() && ok != false) {
            Text("아직 할 일이 없어요. 상품 탐색에서 고른 상품을 '내 적금으로 등록'하면 자동으로 만들어져요.",
                color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        tasks.groupBy { it.product ?: "" }.forEach { (product, list) ->
            SectionTitle(product)
            list.forEach { t ->
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Pill(when (t.cadence) { "monthly" -> "매달"; "once" -> "한 번"; else -> "만기까지" },
                            Palette.SkySoft, Palette.Ink)
                    }
                    Text(t.detail ?: "", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                    Gap(6)
                    Text("다음 기한: " + dateFmt.format(Date((t.nextDue * 1000).toLong())) +
                        (if (t.doneCount > 0) " · ${t.doneCount}번 완료" else "") + (if (t.source == "ai") " · AI가 정리" else ""),
                        style = MaterialTheme.typography.labelMedium, color = Palette.MintDeep)
                    Gap(8)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.taskDone(t.id) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)) { Text("했어요") }
                        OutlinedButton(onClick = { vm.deleteTask(t.id) }) { Text("필요 없어요") }
                    }
                }
            }
        }
        if (mine.isNotEmpty()) {
            SectionTitle("체크리스트 다시 만들기")
            mine.forEach { p ->
                OutlinedButton(onClick = { vm.createChecklist(p) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${p.bank} ${p.name}", maxLines = 1)
                }
            }
            Text("금감원 상품에서 등록한 적금만 우대조건 원문이 있어서 만들 수 있어요.", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
    }
}

// ===================================================================== 2) 목표 추적 + 재계획
@Composable
fun GoalTrackScreen(vm: MainViewModel, onBack: () -> Unit, onAiPlan: () -> Unit, onGoalTab: () -> Unit) {
    val g by vm.goal.collectAsStateWithLifecycle()
    val ok by vm.serverOk.collectAsStateWithLifecycle()
    val aiLoading by vm.aiLoading.collectAsStateWithLifecycle()
    var month by remember(g?.nextRecordMonth) { mutableStateOf(g?.nextRecordMonth ?: "") }
    var amountMan by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadAgentData() }

    Frame("목표 진행", onBack) {
        if (ok == false) { ServerOff(); return@Frame }
        val s = g
        if (s == null || !s.active) {
            SoftCard {
                Text("추적 중인 목표가 없어요", fontWeight = FontWeight.Bold)
                Text(s?.message ?: "목표 탭에서 AI에게 루트를 짜달라고 하면, 그 루트를 목표로 매달 추적해요.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                Gap(8)
                Button(onClick = onGoalTab, colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)) { Text("목표 루트 짜러 가기") }
            }
            return@Frame
        }
        val (label, color, bg) = when (s.status) {
            "behind" -> Triple("계획보다 뒤처짐", Palette.Loss, Palette.LossSoft)
            "ahead" -> Triple("계획보다 앞서감", Palette.Gain, Palette.GainSoft)
            "on_track" -> Triple("계획대로 진행 중", Palette.MintDeep, Palette.MintSoft)
            else -> Triple("기록을 기다리는 중", Palette.Muted, Palette.SkySoft)
        }
        SoftCard(color = bg) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("목표 ${won(s.target)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Pill(label, androidx.compose.ui.graphics.Color.White, color)
            }
            Gap(8)
            LinearProgressIndicator(
                progress = { (s.progressPct / 100).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)),
                color = color, trackColor = androidx.compose.ui.graphics.Color.White
            )
            Gap(6)
            Text("${won(s.actualTotal)} 모음 (${s.progressPct}%)", fontWeight = FontWeight.Bold)
            if (s.carried > 0) LabelValue("재계획 전까지 모은 돈", won(s.carried))
            LabelValue("계획상 지금까지", won(s.plannedTotal))
            LabelValue("차이", (if (s.gap >= 0) "+" else "") + won(s.gap), if (s.gap >= 0) Palette.Gain else Palette.Loss, true)
            LabelValue("계획 월 저축 / 최근 실제", "${won(s.monthlyPlan)} / ${won(s.pace)}")
            s.monthsLeftNow?.let { LabelValue("지금 속도면 남은 기간", GoalPlanner.monthsText(it)) }
        }

        SectionTitle("이번 달 저축 기록하기")
        SoftCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(month, { month = it }, label = { Text("월 (YYYY-MM)") }, singleLine = true,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(amountMan, { v -> amountMan = v.filter { it.isDigit() } }, label = { Text("저축액 (만원)") },
                    singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Gap(8)
            Button(
                onClick = { vm.recordProgress(month.trim(), (amountMan.toLongOrNull() ?: 0) * 10_000); amountMan = "" },
                enabled = Regex("""\d{4}-\d{2}""").matches(month.trim()) && amountMan.isNotBlank(),
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)
            ) { Text("기록하고 루트 점검하기") }
            Text("AI 상담 탭에서 \"지난달 50만원 넣었어\"라고 말해도 기록돼요.", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }

        val opts = s.options ?: emptyList()
        if (opts.isNotEmpty()) {
            SectionTitle("에이전트가 계산한 선택지")
            opts.forEach { o ->
                SoftCard(color = if (o.kind == "replan") Palette.MintSoft else Palette.Surface) {
                    Text(o.title ?: "", style = MaterialTheme.typography.titleMedium)
                    Text(o.text ?: "", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                    if (o.kind == "replan") {
                        Gap(8)
                        Button(onClick = { vm.replan(onAiPlan) }, enabled = !aiLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)) {
                            Text(if (aiLoading) "AI가 다시 짜는 중..." else "AI에게 다시 짜달라기")
                        }
                    }
                }
            }
        }

        val rec = s.records ?: emptyList()
        if (rec.isNotEmpty()) {
            SectionTitle("월별 기록")
            SoftCard {
                rec.reversed().forEach { r ->
                    val diff = r.actual - s.monthlyPlan
                    LabelValue(r.month ?: "", won(r.actual) + "  (" + (if (diff >= 0) "+" else "") + won(diff) + ")",
                        if (diff >= 0) Palette.Gain else Palette.Loss)
                }
            }
        }
    }
}

// ===================================================================== 5) 주간 리포트
@Composable
fun WeeklyScreen(vm: MainViewModel, onBack: () -> Unit, onOpenProduct: (MarketProduct) -> Unit) {
    val w by vm.weekly.collectAsStateWithLifecycle()
    val ok by vm.serverOk.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadAgentData() }
    val df = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)

    Frame("주간 리포트", onBack) {
        if (ok == false) ServerOff()
        SoftCard(color = Palette.MintSoft) {
            Text("매주 에이전트가 알아서 정리해 알림으로 보내요", fontWeight = FontWeight.Bold, color = Palette.MintDeep)
            Text("목표 진행, 새로 나온 맞춤 상품, 이번 주 챙길 우대조건, 다가오는 만기, 성향 변화를 한 번에 봐요.",
                style = MaterialTheme.typography.bodyMedium)
            Gap(8)
            Button(onClick = { vm.makeWeekly() }, enabled = !loading, colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)) {
                Text(if (loading) "만드는 중..." else "지금 리포트 만들기")
            }
        }
        val r = w ?: run {
            Text("아직 리포트가 없어요.", color = Palette.Muted)
            return@Frame
        }
        Text(df.format(Date((r.at * 1000).toLong())) + " 리포트", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        r.aiSummary?.let { SoftCard(color = Palette.SkySoft) { Text("AI 요약", fontWeight = FontWeight.Bold); Text(it) } }
        SoftCard {
            (r.summaryLines ?: emptyList()).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        r.goal?.let { g ->
            SectionTitle("목표 진행")
            SoftCard {
                LinearProgressIndicator(progress = { (g.progressPct / 100).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)), color = Palette.Mint, trackColor = Palette.MintSoft)
                Gap(6)
                LabelValue("모은 돈", "${won(g.actualTotal)} / ${won(g.target)}", bold = true)
                LabelValue("계획 대비", (if (g.gap >= 0) "+" else "") + won(g.gap), if (g.gap >= 0) Palette.Gain else Palette.Loss)
            }
        }
        SectionTitle("새로 나온 내 성향 맞춤 상품")
        SoftCard {
            val np = r.newProducts ?: emptyList()
            if (r.firstRun) Text("첫 리포트라 기준을 저장했어요. 다음 주부터 새 상품을 찾아드려요.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            else if (np.isEmpty()) Text("이번 주 새 상품 ${r.newCount}개 중 성향에 맞는 건 없었어요.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            np.forEach { p ->
                val mp = vm.findProduct("적금", p.code, p.term)
                Column(Modifier.fillMaxWidth().clickable(enabled = mp != null) { mp?.let(onOpenProduct) }.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name ?: "", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Pill("적합도 ${p.fit.toInt()}", Palette.MintSoft, Palette.MintDeep)
                    }
                    Text("${p.bank} · ${p.term}개월 · 기본 ${pct(p.baseRate)}", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                }
            }
        }
        val ts = r.tasks ?: emptyList()
        if (ts.isNotEmpty()) {
            SectionTitle("이번 주 챙길 우대조건")
            SoftCard { ts.forEach { LabelValue(it.title ?: "", dateFmt.format(Date((it.nextDue * 1000).toLong()))) } }
        }
        val ms = r.maturities ?: emptyList()
        if (ms.isNotEmpty()) {
            SectionTitle("30일 안에 만기")
            SoftCard { ms.forEach { LabelValue(it.name ?: "", "${it.days}일 남음") } }
        }
        r.profile?.let { p ->
            SectionTitle("저축 성향")
            SoftCard {
                Text("${p.label} (신뢰도 ${p.confidence})", fontWeight = FontWeight.Bold)
                p.changedFrom?.let { Text("지난주 '$it'에서 바뀌었어요", color = Palette.Amber) }
            }
        }
    }
}

