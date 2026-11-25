@file:OptIn(ExperimentalMaterial3Api::class)

package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.agent.GuardianBrain
import com.seojin.assetguardian.data.Verdict
import com.seojin.assetguardian.data.EventType
import androidx.compose.runtime.LaunchedEffect
import com.seojin.assetguardian.domain.SwitchCalculator
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.components.LabelValue
import com.seojin.assetguardian.ui.components.Pill
import com.seojin.assetguardian.ui.components.ProductLinks
import com.seojin.assetguardian.ui.components.SectionTitle
import com.seojin.assetguardian.ui.components.SoftCard
import com.seojin.assetguardian.ui.components.pct
import com.seojin.assetguardian.ui.components.signedWon
import com.seojin.assetguardian.ui.components.verdictStyle
import com.seojin.assetguardian.ui.components.won
import com.seojin.assetguardian.ui.theme.Palette
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun AnalysisScreen(
    vm: MainViewModel, myId: String, key: String,
    onBack: () -> Unit, onRegister: (String) -> Unit
) {
    val market by vm.market.collectAsStateWithLifecycle()
    val mine by vm.myProducts.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val c = market.firstOrNull { it.key == key }
    val my = mine.firstOrNull { it.id == myId }

    DisposableEffect(key, myId) { onDispose { vm.clearReview() } }
    // 기억: 이 상품을 열어봤다 (내 적금과 비교 중이면 COMPARE)
    LaunchedEffect(c?.key, my?.id) {
        if (c != null) vm.log(if (my != null) EventType.COMPARE else EventType.VIEW, c, my?.name ?: "")
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("상품 분석") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg)
        )
        if (c == null) {
            Text("상품 정보를 찾을 수 없어요", Modifier.padding(16.dp)); return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SoftCard {
                Text(c.bank, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                Text(c.name, style = MaterialTheme.typography.titleLarge)
                if (c.isCustom) {
                    Text("직접 입력한 상품이라 공시 정보가 없어요. 금리·한도는 은행 안내를 기준으로 확인하세요.",
                        style = MaterialTheme.typography.labelMedium, color = Palette.Amber)
                }
                Gap(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("${c.termMonths}개월", Palette.SkySoft, Palette.Ink)
                    Pill("기본 ${pct(c.baseRate)}", Palette.MintSoft, Palette.MintDeep)
                    Pill("최고 ${pct(c.maxRate)}", Palette.GainSoft, Palette.Gain)
                }
                if (c.specialCondition.isNotBlank()) {
                    Gap(10)
                    Text("우대조건", fontWeight = FontWeight.Bold)
                    Text(c.specialCondition, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
                if (c.joinMember.isNotBlank()) {
                    Gap(6)
                    Text("가입대상: ${c.joinMember}", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
                c.monthlyCap?.let {
                    Text("월 납입 한도: ${won(it)}", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
                Gap(10)
                ProductLinks(c, onClick = { vm.log(EventType.LINK, c) })
            }

            if (my == null) {
                SoftCard(color = Palette.SkySoft) {
                    Text("내 적금과 비교하려면 상품 탐색에서 '비교 기준'을 골라주세요.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val elapsed = SwitchCalculator.elapsedMonths(my.joinDate, LocalDate.now(), my.termMonths)
                val e = GuardianBrain.evaluate(my, c, elapsed, s)
                val raw = when {
                    e.netSure >= s.thresholdWon -> Verdict.SWITCH
                    e.netMax >= s.thresholdWon -> Verdict.CONDITIONAL
                    else -> Verdict.KEEP
                }
                val policy = com.seojin.assetguardian.data.ProductRules.isPolicy(my.name)
                val verdict = if (policy) Verdict.KEEP else raw
                val st = verdictStyle(verdict)

                SoftCard(color = st.bg) {
                    Pill(st.label, Color.White, st.fg)
                    Gap(8)
                    Text(
                        "지금 갈아타면 ${signedWon(e.netSure)}",
                        style = MaterialTheme.typography.titleLarge, color = st.fg
                    )
                    Text(if (policy) GuardianBrain.policyMessage(raw, c, e) else GuardianBrain.explain(verdict, my, c, e, elapsed, s),
                        style = MaterialTheme.typography.bodyMedium)
                }

                SoftCard {
                    SectionTitle("${my.name} → ${c.name} (${elapsed}개월 차)")
                    LabelValue("해지 손실 (세후)", "-${won(e.loss)}", Palette.Loss)
                    LabelValue("전환 이익 · 기본금리 (세후)", "+${won(e.gainSure)}", Palette.Gain)
                    LabelValue("순이익 · 기본금리", signedWon(e.netSure), if (e.netSure >= 0) Palette.Gain else Palette.Loss, true)
                    LabelValue("순이익 · 우대 모두 충족", signedWon(e.netMax), if (e.netMax >= 0) Palette.Gain else Palette.Loss, true)
                    e.cap?.let { LabelValue("새 상품에 넣을 수 있는 금액", "월 ${won(it)} (나머지는 파킹통장)", Palette.Amber) }
                    LabelValue("갈아탈 가치가 생기는 금리", e.breakEven?.let { "연 ${pct(it)} 이상" } ?: "-", Palette.MintDeep, true)
                    Gap(4)
                    Text("알림 기준: 세후 ${won(s.thresholdWon)} 이상 이득 · 파킹통장 ${pct(s.parkingRate)} · 세율 ${pct(s.taxRate)}",
                        style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                }

                TimingCard(my, c, elapsed, s)

                SoftCard(color = Palette.MintSoft) {
                    Text("AI 에이전트에게 우대조건 판단 맡기기", fontWeight = FontWeight.Bold, color = Palette.MintDeep)
                    Text("내 상황(설정의 '내 상황')을 보고 우대금리를 실제로 받을 수 있는지 AI 에이전트가 판단해 다시 계산해요.",
                        style = MaterialTheme.typography.bodyMedium)
                    Gap(8)
                    Button(
                        onClick = { vm.askAgentServer(my, c) }, enabled = !loading,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)
                    ) { Text(if (loading) "판단 중..." else "에이전트에게 묻기") }
                    review?.let { r ->
                        Gap(10)
                        Text("판단: ${verdictStyle(r.verdict).label} · 예상 금리 ${pct(r.expectedRate)} · ${signedWon(r.netWon)}",
                            fontWeight = FontWeight.Bold)
                        Text(r.reasoning, style = MaterialTheme.typography.bodyMedium)
                        val calls = r.toolCalls ?: emptyList()
                        if (calls.isNotEmpty()) {
                            Gap(4)
                            Text("에이전트가 호출한 도구: " + calls.joinToString(" → "),
                                style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        }
                        if (r.mode.isNotBlank()) Text("(${r.mode})", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    }
                }
            }

            OutlinedButton(onClick = { onRegister(c.key) }, modifier = Modifier.fillMaxWidth()) {
                Text("이 상품에 가입했다면 내 적금으로 등록")
            }
            Gap(24)
        }
    }
}

/** 같은 상품이라도 몇 개월 차에 갈아타느냐에 따라 이득이 손해로 바뀌는 걸 막대로 보여준다 */
@Composable
private fun TimingCard(
    my: com.seojin.assetguardian.data.MyProduct,
    c: com.seojin.assetguardian.data.MarketProduct,
    elapsedNow: Int,
    s: com.seojin.assetguardian.data.AppSettings
) {
    val points = (1 until my.termMonths).filter { it == elapsedNow || it % maxOf(1, my.termMonths / 6) == 0 }.distinct().sorted()
    val values = points.map { el ->
        el to SwitchCalculator.analyze(
            SwitchCalculator.Input(my.monthly, my.finalRate, my.baseRate, my.termMonths, el, c.baseRate,
                s.parkingRate, s.taxRate, my.earlyRule, my.customEarlyRate, newMonthlyCap = c.monthlyCap)
        ).net.roundToLong()
    }
    val maxAbs = (values.maxOfOrNull { abs(it.second) } ?: 1L).coerceAtLeast(1L)

    SoftCard {
        SectionTitle("언제 갈아타느냐가 중요해요")
        Text("기본금리 ${pct(c.baseRate)} 기준, 가입 후 개월 차별 세후 순이익", style = MaterialTheme.typography.labelMedium,
            color = Palette.Muted)
        Gap(8)
        values.forEach { (el, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${el}개월" + if (el == elapsedNow) " (지금)" else "",
                    Modifier.width(84.dp), style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (el == elapsedNow) FontWeight.Bold else FontWeight.Normal
                )
                Box(Modifier.weight(1f).height(18.dp)) {
                    val frac = (abs(v).toFloat() / maxAbs).coerceIn(0.02f, 1f)
                    Box(
                        Modifier.fillMaxWidth(frac).fillMaxHeight()
                            .background(if (v >= 0) Palette.Gain else Palette.Loss, RoundedCornerShape(6.dp))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(signedWon(v), Modifier.width(88.dp), style = MaterialTheme.typography.labelMedium,
                    color = if (v >= 0) Palette.Gain else Palette.Loss)
            }
        }
    }
}
