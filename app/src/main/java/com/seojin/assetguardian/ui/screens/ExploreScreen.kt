@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.agent.GuardianBrain
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.data.ProductKind
import com.seojin.assetguardian.domain.SwitchCalculator
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.components.Pill
import com.seojin.assetguardian.ui.components.SoftCard
import com.seojin.assetguardian.ui.components.pct
import com.seojin.assetguardian.ui.components.signedWon
import com.seojin.assetguardian.ui.components.won
import com.seojin.assetguardian.ui.theme.Palette
import java.time.LocalDate

@Composable
fun ExploreScreen(vm: MainViewModel, initialMyId: String, onOpen: (myId: String, key: String) -> Unit, onCustom: () -> Unit = {}) {
    val market by vm.market.collectAsStateWithLifecycle()
    val mine by vm.myProducts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    var myId by rememberSaveable { mutableStateOf(initialMyId) }
    val base = mine.firstOrNull { it.id == myId }
    var term by rememberSaveable { mutableIntStateOf(base?.termMonths ?: 12) }
    var sortByBase by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val elapsed = base?.let { SwitchCalculator.elapsedMonths(it.joinDate, LocalDate.now(), it.termMonths) } ?: 0

    val monthlyForPreview = base?.monthly ?: 500_000L
    val list = remember(market, term, sortByBase, query, base) {
        market.filter { it.kind == ProductKind.SAVING && it.termMonths == term }
            .filter { query.isBlank() || it.name.contains(query) || it.bank.contains(query) }
            .distinctBy { it.key }
            .sortedByDescending { if (sortByBase) it.baseRate else it.maxRate }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("상품 탐색", style = MaterialTheme.typography.headlineSmall)
            Text("금리가 아니라 실제로 받는 세후 이자로 비교해요", color = Palette.Muted,
                style = MaterialTheme.typography.bodyMedium)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Mint)
        }
        item {
            SoftCard(Modifier.clickable { onCustom() }, color = Palette.MintSoft) {
                Text("은행에서 제안받은 상품 직접 넣기", fontWeight = FontWeight.Bold, color = Palette.MintDeep)
                Text("VIP 전용·영업점 특판처럼 공시에 없는 상품도 금리만 넣으면 똑같이 비교해요 →",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("은행·상품명 검색") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
            )
        }
        item {
            Text("기간", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6, 12, 24, 36).forEach { t ->
                    Chip(selected = term == t, label = "${t}개월") { term = t }
                }
                Chip(selected = !sortByBase, label = "최고금리순") { sortByBase = false }
                Chip(selected = sortByBase, label = "기본금리순") { sortByBase = true }
            }
        }
        if (mine.isNotEmpty()) {
            item {
                Text("비교 기준 (내 적금)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(selected = base == null, label = "비교 안 함") { myId = "" }
                    mine.forEach { p ->
                        Chip(selected = p.id == myId, label = p.name) { myId = p.id; term = p.termMonths }
                    }
                }
            }
        }
        if (base != null) {
            item {
                SoftCard(color = Palette.SkySoft) {
                    Text("${base.name} · ${elapsed}개월 차 기준", fontWeight = FontWeight.Bold)
                    Text("각 상품으로 지금 갈아탔을 때 세후 순이익을 보여줘요 (해지 손실 포함)",
                        style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
            }
        }
        if (list.isEmpty()) {
            item { Text("조건에 맞는 상품이 없어요", color = Palette.Muted) }
        }
        items(list, key = { it.key }) { p ->
            val eval = base?.let { GuardianBrain.evaluate(it, p, elapsed, settings) }
            ProductRow(p, monthlyForPreview, settings.taxRate, eval) { onOpen(myId, p.key) }
        }
    }
}

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Palette.MintSoft, selectedLabelColor = Palette.MintDeep
        )
    )
}

@Composable
private fun ProductRow(
    p: MarketProduct, monthly: Long, tax: Double,
    eval: GuardianBrain.Eval?, onClick: () -> Unit
) {
    val interest = SwitchCalculator.maturityInterest(monthly, p.baseRate, p.termMonths, tax)
    SoftCard(Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.bank, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                    if (p.isCustom) { Spacer(Modifier.width(6.dp)); Pill("직접 입력", Palette.AmberSoft, Palette.Amber) }
                }
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("최고 ${pct(p.maxRate)}", color = Palette.MintDeep, fontWeight = FontWeight.Bold)
                Text("기본 ${pct(p.baseRate)}", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        Gap(6)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("월 ${won(monthly)} · 기본금리 만기 세후 이자 ${won(interest)}",
                style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.weight(1f))
            if (p.rsrvType.isNotBlank()) Pill(p.rsrvType, Palette.SkySoft, Palette.Ink)
        }
        if (eval != null) {
            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val good = eval.netSure > 0
                Pill(
                    "지금 갈아타면 ${signedWon(eval.netSure)}",
                    if (good) Palette.GainSoft else Palette.LossSoft,
                    if (good) Palette.Gain else Palette.Loss
                )
                Spacer(Modifier.width(6.dp))
                if (eval.netMax != eval.netSure)
                    Text("우대 다 받으면 ${signedWon(eval.netMax)}", style = MaterialTheme.typography.labelMedium,
                        color = Palette.Muted)
            }
        }
    }
}
