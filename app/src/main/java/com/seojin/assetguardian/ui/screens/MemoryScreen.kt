@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.seojin.assetguardian.data.SavingProfile
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

/**
 * "AI가 기억하는 나" — 에이전트의 장기 기억을 그대로 보여주는 화면.
 * 아는 사실 / 관심 상품 / 최근 본 적금 / 성향 / 내 적금 / 지난 AI 루트
 */
@Composable
fun MemoryScreen(vm: MainViewModel, onBack: () -> Unit, onOpenProduct: (MarketProduct) -> Unit) {
    val mem by vm.memory.collectAsStateWithLifecycle()
    val mine by vm.myProducts.collectAsStateWithLifecycle()
    val recs by vm.recs.collectAsStateWithLifecycle()
    val recLoading by vm.recLoading.collectAsStateWithLifecycle()
    var newFact by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val df = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }

    LaunchedEffect(Unit) { vm.loadMemory() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("AI가 기억하는 나") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg)
        )
        val m = mem
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SoftCard(color = if (m?.fromServer == true) Palette.MintSoft else Palette.AmberSoft) {
                Text(
                    if (m?.fromServer == true) "에이전트 서버의 기억 저장소(DB)와 연결됨"
                    else "서버가 꺼져 있어서 휴대폰에 쌓인 기록만 보여요",
                    fontWeight = FontWeight.Bold,
                    color = if (m?.fromServer == true) Palette.MintDeep else Palette.Amber
                )
                Text("AI는 대화가 끝나면 아무것도 기억하지 못해요. 그래서 내 행동과 AI가 알아낸 사실을 DB에 쌓아두고, AI가 루트를 짤 때 도구로 꺼내 봐요.",
                    style = MaterialTheme.typography.bodyMedium)
            }

            // 0. 저축 성향 (행동 기록을 코드로 분석)
            SectionTitle("내 저축 성향")
            ProfileCard(m?.profile, m?.fromServer == true, onStated = { vm.setStated(it) })

            // 성향 맞춤 추천
            SoftCard(color = Palette.MintSoft) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("성향 맞춤 추천", fontWeight = FontWeight.Bold, color = Palette.MintDeep, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.loadRecs() }, enabled = !recLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)) {
                        Text(if (recLoading) "계산 중..." else if (recs == null) "추천 받기" else "다시 계산")
                    }
                }
                Text("상품마다 내 성향 점수로 '적합도'를 계산해서 순위를 매겨요. 금리순이 아니라 나한테 맞는 순서예요.",
                    style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                (recs?.learning ?: emptyList()).takeIf { it.isNotEmpty() }?.let { notes ->
                    Gap(8)
                    Text("내 반응으로 배운 것", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    notes.forEach { Text("• $it", style = MaterialTheme.typography.labelMedium, color = Palette.Muted) }
                }
                recs?.aiComment?.let {
                    Gap(8)
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Surface).padding(10.dp)) {
                        Text("AI: $it", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                (recs?.items ?: emptyList()).forEachIndexed { idx, r ->
                    val p = vm.findProduct("적금", r.code, r.term)
                    Gap(8)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface)
                        .clickable(enabled = p != null) { vm.logRecClick(r); p?.let(onOpenProduct) }.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${idx + 1}. ${r.name}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                            Pill("적합도 ${r.fit.toInt()}", Palette.MintSoft, Palette.MintDeep)
                        }
                        Text("${(r.bank ?: "").removePrefix("주식회사 ")} · ${r.term}개월 · 기본 ${pct(r.baseRate)} / 최고 ${pct(r.maxRate)}",
                            style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        val reasons = r.reasons ?: emptyList()
                        if (reasons.isNotEmpty()) {
                            Gap(4)
                            Text(reasons.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Palette.MintDeep)
                        }
                    }
                }
            }

            // 1. AI가 아는 사실
            SectionTitle("AI가 알고 있는 나에 대한 사실")
            SoftCard {
                val facts = m?.facts ?: emptyList()
                if (facts.isEmpty()) {
                    Text(if (m?.fromServer == true) "아직 없어요. 목표 탭에서 AI에게 상황을 말하면 AI가 스스로 저장해요."
                        else "서버가 켜져 있을 때 볼 수 있어요.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                facts.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.text ?: "", style = MaterialTheme.typography.bodyLarge)
                            Text((if (f.source == "user") "내가 추가" else "AI가 대화에서 알아냄") +
                                " · " + df.format(Date((f.createdAt * 1000).toLong())),
                                style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        }
                        IconButton(onClick = { vm.deleteFact(f.id) }) { Icon(Icons.Filled.Close, "삭제", tint = Palette.Muted) }
                    }
                    Gap(4)
                }
                if (m?.fromServer == true) {
                    Gap(6)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newFact, { newFact = it }, placeholder = { Text("직접 알려주기 (예: 급여이체 가능)") },
                            singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { vm.addFact(newFact.trim()); newFact = "" }, enabled = newFact.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)) { Text("저장") }
                    }
                }
            }

            // 2. 관심 상품
            SectionTitle("관심 있어 보이는 상품")
            SoftCard {
                val list = m?.interests ?: emptyList()
                if (list.isEmpty()) Text("상품을 열어보거나 비교하면 여기에 쌓여요.", color = Palette.Muted,
                    style = MaterialTheme.typography.bodyMedium)
                val maxScore = (list.maxOfOrNull { it.score } ?: 1.0).coerceAtLeast(0.1)
                list.forEach { i ->
                    val p = vm.findProduct(i.kind, i.code, i.term)
                    Column(Modifier.fillMaxWidth().clickable(enabled = p != null) { p?.let(onOpenProduct) }.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i.name}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("관심도 ${"%.1f".format(i.score)}", style = MaterialTheme.typography.labelMedium, color = Palette.MintDeep)
                        }
                        Text("${(i.bank ?: "").removePrefix("주식회사 ")} · ${i.term}개월 · 기본 ${pct(i.rate)} · 조회 ${i.views}번 · 링크 ${i.links}번",
                            style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        Gap(4)
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Palette.MintSoft)) {
                            Box(Modifier.fillMaxWidth((i.score / maxScore).toFloat().coerceIn(0.03f, 1f)).fillMaxHeight()
                                .background(Palette.Mint, RoundedCornerShape(50)))
                        }
                    }
                }
                Gap(4)
                Text("관심도 = 조회 1점, 비교 2점, 홈페이지 클릭 3점, 등록 5점 (30일마다 절반으로 줄어요)",
                    style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            }

            // 3. 최근 본 적금
            SectionTitle("최근 확인한 적금")
            SoftCard {
                val list = m?.recent ?: emptyList()
                if (list.isEmpty()) Text("아직 본 상품이 없어요.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                list.forEach { r ->
                    val p = vm.findProduct(r.kind, r.code, r.term)
                    Row(Modifier.fillMaxWidth().clickable(enabled = p != null) { p?.let(onOpenProduct) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name ?: "", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text("${(r.bank ?: "").removePrefix("주식회사 ")} · ${r.term}개월 · 기본 ${pct(r.rate)}",
                                style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        }
                        Text(df.format(Date((r.at * 1000).toLong())), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    }
                }
            }

            // 4. 성향
            SectionTitle("행동으로 본 내 성향")
            SoftCard {
                val t = m?.taste
                if (t == null || t.events == 0) {
                    Text("기록이 더 쌓이면 보여요.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                } else {
                    LabelValue("가장 많이 본 기간", t.favoriteTerm?.let { "${it}개월 적금" } ?: "-", bold = true)
                    LabelValue("저축은행 상품 비중", t.savingsBankShare?.let { "${(it * 100).toInt()}%" } ?: "-")
                    LabelValue("관심 금리대 (평균)", t.avgRateViewed?.let { "연 ${pct(it)}" } ?: "-")
                    LabelValue("알림 반응", "확인 ${t.alertAccept}번 · 관심 없음 ${t.alertIgnore}번")
                    LabelValue("쌓인 행동 기록", "${t.events}개")
                }
            }

            // 5. 내 적금
            SectionTitle("AI가 알고 있는 내 적금")
            SoftCard {
                if (mine.isEmpty()) Text("등록한 적금이 없어요.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                mine.forEach { p ->
                    LabelValue("${p.bank} ${p.name}", "월 ${won(p.monthly)} · 연 ${pct(p.finalRate)}")
                }
            }

            // 6. 지난 AI 루트
            SectionTitle("지난 AI 루트 기록")
            SoftCard {
                val plans = m?.plans ?: emptyList()
                if (plans.isEmpty()) Text(if (m?.fromServer == true) "아직 없어요." else "서버가 켜져 있을 때 볼 수 있어요.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                plans.forEach { p ->
                    Text(df.format(Date((p.createdAt * 1000).toLong())) + " · 목표 " + won(p.target ?: 0) +
                        (p.monthsNeeded?.let { " · ${GoalPlanner.monthsText(it)}" } ?: ""), fontWeight = FontWeight.Bold)
                    if (!p.message.isNullOrBlank()) Text("\"${p.message}\"", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, maxLines = 2)
                    Text((p.products ?: emptyList()).joinToString(" + ") { "${it.name}" } + " (${p.term ?: 0}개월)",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Gap(8)
                }
                if (plans.size >= 2) {
                    val a = plans[0].monthsNeeded; val b = plans[1].monthsNeeded
                    if (a != null && b != null && a != b) {
                        Pill(if (a < b) "지난번보다 ${b - a}개월 빨라졌어요" else "지난번보다 ${a - b}개월 늘었어요",
                            if (a < b) Palette.GainSoft else Palette.LossSoft, if (a < b) Palette.Gain else Palette.Loss)
                    }
                }
            }

            if (!confirmClear) {
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("기록 모두 지우기") }
            } else {
                SoftCard(color = Palette.LossSoft) {
                    Text("행동 기록, AI가 아는 사실, 루트 기록이 모두 지워지고 되돌릴 수 없어요.", style = MaterialTheme.typography.bodyMedium)
                    Gap(8)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.clearMemory(); confirmClear = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Loss)) { Text("모두 지우기") }
                        OutlinedButton(onClick = { confirmClear = false }) { Text("취소") }
                    }
                }
            }
            Gap(24)
        }
    }
}


/** 저축 성향 카드: 주 성향 · 신뢰도 · 4가지 점수 막대 · 근거 숫자 · 내가 고른 성향 · 변화 기록 */
@Composable
private fun ProfileCard(p: SavingProfile?, connected: Boolean, onStated: (String?) -> Unit) {
    val types = linkedMapOf("STABLE" to "안정형", "YIELD" to "수익추구형", "PERK" to "우대조건 활용형", "SHORT" to "단기 유동형")
    SoftCard {
        if (!connected || p == null) {
            Text("성향 분석은 PC에서 서버가 켜져 있을 때 계산돼요.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            return@SoftCard
        }
        if (!p.ready) {
            Text("아직 분석 전이에요", fontWeight = FontWeight.Bold)
            Text(p.message ?: "", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.label ?: "", style = MaterialTheme.typography.headlineSmall, color = Palette.MintDeep, modifier = Modifier.weight(1f))
                Pill("신뢰도 ${p.confidenceLabel ?: "-"}", Palette.SkySoft, Palette.Ink)
            }
            Text(p.description ?: "", style = MaterialTheme.typography.bodyMedium)
            Gap(10)
            types.forEach { (k, label) ->
                val v = p.scores?.get(k) ?: 0.0
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.width(104.dp), style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (k == p.type) FontWeight.Bold else FontWeight.Normal)
                    Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(50)).background(Palette.Line)) {
                        Box(Modifier.fillMaxWidth((v / 100.0).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight()
                            .background(if (k == p.type) Palette.Mint else Palette.Sky, RoundedCornerShape(50)))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${v.toInt()}", Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Gap(8)
            Text("이렇게 판단한 근거 (내 행동 ${p.n}건 분석)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            (p.evidence ?: emptyList()).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted) }
        }
        Gap(10)
        Text("내가 생각하는 내 성향", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { (k, label) ->
                FilterChip(selected = p.stated == k, onClick = { onStated(if (p.stated == k) null else k) }, label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.MintSoft, selectedLabelColor = Palette.MintDeep))
            }
        }
        p.conflict?.let {
            Gap(6)
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.AmberSoft).padding(10.dp)) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Amber)
            }
        }
        val hist = p.history ?: emptyList()
        if (hist.size >= 2) {
            Gap(6)
            val df = SimpleDateFormat("M/d", Locale.KOREA)
            Text("변화: " + hist.reversed().joinToString(" → ") { "${df.format(Date((it.at * 1000).toLong()))} ${it.label}" },
                style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
        Gap(4)
        Text("성향 점수는 AI가 추측한 게 아니라, 본 상품의 저축은행 비중·시장 대비 금리 수준·우대조건 의존도·기간을 코드로 계산한 값이에요.",
            style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}
