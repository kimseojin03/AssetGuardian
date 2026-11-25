@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.components.Pill
import com.seojin.assetguardian.ui.components.SectionTitle
import com.seojin.assetguardian.ui.components.SoftCard
import com.seojin.assetguardian.ui.components.pct
import com.seojin.assetguardian.ui.components.won
import com.seojin.assetguardian.ui.theme.Palette

/**
 * 은행에서 제안받은 VIP·영업점 특판처럼 금감원 공시에 없는 상품을 직접 넣는 화면.
 * 넣은 상품은 상품 탐색, 갈아타기 손익 계산, 매일 아침 점검, AI 목표 루트에 공시 상품과 똑같이 쓰인다.
 */
@Composable
fun CustomProductScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (MarketProduct) -> Unit) {
    val customs by vm.customProducts.collectAsStateWithLifecycle()

    var bank by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var term by rememberSaveable { mutableIntStateOf(12) }
    var base by rememberSaveable { mutableStateOf("") }
    var max by rememberSaveable { mutableStateOf("") }
    var capMan by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }

    val baseRate = base.replace(",", ".").toDoubleOrNull()
    val maxRate = max.replace(",", ".").toDoubleOrNull()
    val valid = bank.isNotBlank() && name.isNotBlank() && baseRate != null && baseRate in 0.01..30.0 &&
        (maxRate == null || maxRate in 0.01..30.0)
    val rateField = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("직접 입력 상품") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg)
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SoftCard(color = Palette.MintSoft) {
                Text("공시에 없는 상품도 똑같이 비교해요", fontWeight = FontWeight.Bold, color = Palette.MintDeep)
                Text("은행에서 제안받은 VIP 전용 상품이나 영업점 특판은 금감원 API에 나오지 않아요. " +
                    "금리·기간·월 한도를 넣으면 갈아타기 손익 계산, 매일 아침 점검, AI 목표 루트에 함께 써요.",
                    style = MaterialTheme.typography.bodyMedium)
            }

            SoftCard {
                SectionTitle("상품 정보")
                OutlinedTextField(bank, { bank = it }, label = { Text("은행 이름") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Gap(6)
                OutlinedTextField(name, { name = it }, label = { Text("상품 이름 (예: VIP 특판 적금)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Gap(8)
                Text("기간", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24, 36).forEach { t ->
                        FilterChip(selected = term == t, onClick = { term = t }, label = { Text("${t}개월") })
                    }
                }
                Gap(6)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(base, { base = it }, label = { Text("기본 금리 (%)") }, singleLine = true,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), keyboardOptions = rateField)
                    OutlinedTextField(max, { max = it }, label = { Text("최고 금리 (선택)") }, singleLine = true,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), keyboardOptions = rateField)
                }
                Gap(6)
                OutlinedTextField(capMan, { v -> capMan = v.filter { it.isDigit() } }, label = { Text("월 납입 한도 (만원, 선택)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Gap(6)
                OutlinedTextField(memo, { memo = it }, label = { Text("우대조건 메모 (선택)") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), minLines = 2)
                Text("최고 금리를 비우면 기본 금리와 같다고 봐요. 월 한도를 넣으면 한도를 넘는 돈은 파킹통장 금리로 계산해요.",
                    style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                Gap(8)
                Button(
                    onClick = {
                        vm.addCustomProduct(bank, name, term, baseRate ?: 0.0, maxRate ?: (baseRate ?: 0.0),
                            capMan.toLongOrNull()?.let { it * 10_000 }, memo)
                        name = ""; base = ""; max = ""; capMan = ""; memo = ""
                    },
                    enabled = valid, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)
                ) { Text("비교 후보로 추가") }
            }

            if (customs.isNotEmpty()) {
                SectionTitle("내가 넣은 상품")
                customs.forEach { p ->
                    SoftCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.bank, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                            }
                            Pill("${p.termMonths}개월", Palette.SkySoft, Palette.Ink)
                        }
                        Gap(4)
                        Text("기본 ${pct(p.baseRate)} · 최고 ${pct(p.maxRate)}" +
                            (p.monthlyCap?.let { " · 월 ${won(it)} 한도" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, color = Palette.MintDeep)
                        if (p.specialCondition.isNotBlank()) {
                            Text(p.specialCondition, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        }
                        Gap(8)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpen(p) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)) {
                                Text("내 적금과 비교")
                            }
                            OutlinedButton(onClick = { vm.deleteCustomProduct(p.code) }) { Text("삭제") }
                        }
                    }
                }
            }
            Gap(24)
        }
    }
}
