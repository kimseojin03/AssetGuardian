@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.domain.GoalPlanner
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.components.LabelValue
import com.seojin.assetguardian.ui.components.Pill
import com.seojin.assetguardian.ui.components.SoftCard
import com.seojin.assetguardian.ui.components.won
import com.seojin.assetguardian.ui.theme.Palette
import kotlin.math.roundToInt

/**
 * 목표 탭: 목표 금액 + 월 수입 - 생활비(±10만원) → 실제 은행 상품으로 짠 가장 빠른 루트 3가지.
 * 결과 카드를 누르면 어떤 적금에 얼마씩, 어떤 순서로 넣는지 상세 루트 화면으로 간다.
 */
@Composable
fun GoalScreen(
    vm: MainViewModel,
    onOpenPlan: (target: Long, saving: Long, useMax: Boolean) -> Unit,
    onOpenAiPlan: () -> Unit
) {
    val market by vm.market.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val aiLoading by vm.aiLoading.collectAsStateWithLifecycle()
    val lastAi by vm.aiPlan.collectAsStateWithLifecycle()
    var talk by rememberSaveable { mutableStateOf("") }

    var goalMan by rememberSaveable { mutableStateOf("5000") }
    var incomeMan by rememberSaveable { mutableStateOf("180") }
    var living by rememberSaveable { mutableFloatStateOf(70f) }
    var useMax by rememberSaveable { mutableStateOf(false) }
    var loaded by rememberSaveable { mutableStateOf(false) }

    // 이미 저장한 목표가 있으면 그 값으로 시작
    LaunchedEffect(s) {
        if (!loaded && s.goalAmount > 0) {
            goalMan = (s.goalAmount / 10_000).toString()
            incomeMan = (s.income / 10_000).toString()
            living = (s.living / 10_000).toFloat()
            useMax = s.useMaxRate
        }
        loaded = true
    }

    val goal = (goalMan.toLongOrNull() ?: 0L) * 10_000
    val income = (incomeMan.toLongOrNull() ?: 0L) * 10_000
    val livingMan = living.roundToInt()

    val scenarios = remember(market, goal, income, livingMan, useMax, s.taxRate, s.parkingRate) {
        listOf(-10 to "생활비 아낀 달 (-10만원)", 0 to "기준", 10 to "생활비 많이 쓴 달 (+10만원)").map { (d, title) ->
            val saving = (income - (livingMan + d) * 10_000L).coerceAtLeast(0)
            Triple(title, saving, GoalPlanner.plan(goal, saving, market, s.taxRate, s.parkingRate, useMax))
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("목표 루트 찾기", style = MaterialTheme.typography.headlineSmall)
        Text("실제 은행 적금·예금으로 목표까지 가장 빠른 길을 찾아요", color = Palette.Muted,
            style = MaterialTheme.typography.bodyMedium)

        // ---- AI 에이전트에게 말로 맡기기 (핵심) ----
        SoftCard(color = Palette.MintSoft) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI 에이전트에게 말로 맡기기", fontWeight = FontWeight.Bold, color = Palette.MintDeep,
                    modifier = Modifier.weight(1f))
                Pill("AI", Palette.Mint, Color.White)
            }
            Text("상황을 편하게 적으면 AI가 상품을 찾아보고, 가입조건을 내 상황과 비교하고, 루트를 여러 번 계산해본 뒤 가장 좋은 걸 골라줘요.",
                style = MaterialTheme.typography.bodyMedium)
            Gap(8)
            OutlinedTextField(
                value = talk, onValueChange = { talk = it },
                placeholder = { Text("예: 23살 대학생이고 알바로 월 180 벌어. 생활비는 70 정도 쓰고, 2년 안에 2천만원 모으고 싶어. 1년 뒤에 등록금 300 필요해.") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(14.dp)
            )
            Gap(8)
            Button(
                onClick = {
                    vm.askAiPlan(talk.trim(), (goalMan.toLongOrNull() ?: 0L) * 10_000,
                        (incomeMan.toLongOrNull() ?: 0L) * 10_000, living.roundToInt() * 10_000L, onOpenAiPlan)
                },
                enabled = !aiLoading && (talk.isNotBlank() || goalMan.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)
            ) {
                if (aiLoading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("AI가 상품을 비교하는 중... (30초~1분)")
                } else Text("AI에게 루트 짜달라고 하기")
            }
            if (lastAi?.plan != null) {
                Gap(4)
                Text("지난번 AI 루트 다시 보기 →", color = Palette.Sky, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { onOpenAiPlan() }.padding(vertical = 4.dp))
            }
            Text("말에 없는 값은 아래 칸(목표·수입·생활비)을 기본값으로 써요. PC에서 server/run_server.bat이 켜져 있어야 해요.",
                style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }

        Text("직접 숫자로 계산해보기 (AI 없이)", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("목표 금액 (만원)", goalMan, Modifier.weight(1f)) { goalMan = it }
            NumberField("월 수입 (만원)", incomeMan, Modifier.weight(1f)) { incomeMan = it }
        }

        SoftCard {
            Text("생활비 기준: ${livingMan}만원", fontWeight = FontWeight.Bold)
            Slider(
                value = living, onValueChange = { living = it },
                valueRange = 10f..maxOf(20f, (income / 10_000).toFloat() - 10f),
                colors = SliderDefaults.colors(thumbColor = Palette.Mint, activeTrackColor = Palette.Mint)
            )
            Text("매달 다를 수 있으니 ${livingMan - 10}만원 ~ ${livingMan + 10}만원 범위로 보여드려요",
                color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("우대금리까지 받는다고 가정", style = MaterialTheme.typography.bodyMedium)
                    Text("끄면 조건 없이 받는 기본금리로만 계산 (추천)", color = Palette.Muted,
                        style = MaterialTheme.typography.labelMedium)
                }
                Switch(checked = useMax, onCheckedChange = { useMax = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.Mint))
            }
        }

        Button(
            onClick = { vm.saveGoal(goal, income, livingMan * 10_000L, useMax) },
            enabled = goal > 0 && income > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)
        ) { Text("이 목표로 설정 (홈 화면에 표시 + 매일 루트 점검)") }

        if (market.isEmpty()) {
            Text("상품 데이터를 불러오는 중이에요...", color = Palette.Muted)
        }

        scenarios.forEach { (title, saving, plan) ->
            val color = when {
                title.startsWith("생활비 아낀") -> Palette.Gain
                title == "기준" -> Palette.MintDeep
                else -> Palette.Loss
            }
            SoftCard(Modifier.clickable(enabled = plan != null) { onOpenPlan(goal, saving, useMax) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Pill("매달 ${won(saving)}", Palette.MintSoft, Palette.MintDeep)
                }
                Gap(6)
                val m = plan?.monthsNeeded
                if (plan == null || m == null) {
                    Text("이 저축액으로는 20년 안에 어려워요", color = Palette.Muted)
                } else {
                    Text("${GoalPlanner.monthsText(m)} 만에 달성", style = MaterialTheme.typography.titleLarge)
                    val saved = (plan.noInterestMonths ?: m) - m
                    Text(
                        if (saved > 0) "그냥 모으면 ${GoalPlanner.monthsText(plan.noInterestMonths ?: m)} → ${saved}개월 단축"
                        else "그냥 모으는 것과 같은 기간 (이자가 목표 앞당기기엔 부족)",
                        color = Palette.Muted, style = MaterialTheme.typography.labelMedium
                    )
                    Gap(6)
                    LabelValue("달성 시점 예상 금액", won(plan.finalAmount), bold = true)
                    LabelValue("받는 이자 (세후)", "+" + won(plan.savingInterest + plan.depositInterest), Palette.Gain)
                    Gap(4)
                    Text(
                        "루트: " + plan.slots.joinToString(" + ") { "${it.product.name}(${it.monthly / 10_000}만)" } +
                            " → ${plan.termMonths}개월마다 " + (plan.deposit?.name ?: "파킹통장") + "로 굴리기",
                        style = MaterialTheme.typography.labelMedium, color = Palette.Ink, maxLines = 2
                    )
                    Gap(4)
                    Text("눌러서 루트 자세히 보기 →", color = Palette.Sky, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("* 세후(15.4%) 기준, 지금 금리가 유지된다고 가정한 계산이에요. 진행 중인 적금은 이자 없이 원금만 셌어요(보수적).",
            style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        Gap(24)
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() }) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

