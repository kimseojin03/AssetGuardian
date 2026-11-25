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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.data.EarlyRule
import com.seojin.assetguardian.data.MyProduct
import com.seojin.assetguardian.domain.SwitchCalculator
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.components.SoftCard
import com.seojin.assetguardian.ui.components.pct
import com.seojin.assetguardian.ui.components.won
import com.seojin.assetguardian.ui.theme.Palette
import java.time.LocalDate
import java.util.UUID

@Composable
fun AddProductScreen(vm: MainViewModel, prefillKey: String, onDone: () -> Unit) {
    val market by vm.market.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pre = market.firstOrNull { it.key == prefillKey }

    var bank by rememberSaveable { mutableStateOf(pre?.bank ?: "") }
    var name by rememberSaveable { mutableStateOf(pre?.name ?: "") }
    var finalRate by rememberSaveable { mutableStateOf(pre?.maxRate?.toString() ?: "") }
    var baseRate by rememberSaveable { mutableStateOf(pre?.baseRate?.toString() ?: "") }
    var monthlyMan by rememberSaveable { mutableStateOf("50") }
    var term by rememberSaveable { mutableIntStateOf(pre?.termMonths ?: 12) }
    var join by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var rule by rememberSaveable { mutableStateOf(EarlyRule.BANK_TABLE) }
    var custom by rememberSaveable { mutableStateOf("") }

    val fr = finalRate.toDoubleOrNull()
    val br = baseRate.toDoubleOrNull() ?: fr
    val monthly = (monthlyMan.toDoubleOrNull() ?: 0.0).times(10_000).toLong()
    val joinOk = runCatching { LocalDate.parse(join) }.isSuccess
    val valid = bank.isNotBlank() && name.isNotBlank() && fr != null && br != null && monthly > 0 && joinOk &&
        (rule == EarlyRule.BANK_TABLE || custom.toDoubleOrNull() != null)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("내 적금 등록") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg)
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Field("은행", bank) { bank = it }
            Field("상품명", name) { name = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("최종 금리 (연 %)", finalRate, Modifier.weight(1f), KeyboardType.Decimal) { finalRate = it }
                Field("기본 금리 (연 %)", baseRate, Modifier.weight(1f), KeyboardType.Decimal) { baseRate = it }
            }
            Text("최종 금리 = 우대까지 받는 실제 금리, 기본 금리 = 중도해지 계산에 쓰여요",
                style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            Field("월 납입액 (만원)", monthlyMan, keyboard = KeyboardType.Number) { monthlyMan = it }

            Text("계약 기간", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6, 12, 24, 36).forEach { t -> Chip(term == t, "${t}개월") { term = t } }
            }

            Field("가입일 (예: 2026-07-15)", join) { join = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0L to "오늘", 1L to "1개월 전", 3L to "3개월 전", 6L to "6개월 전").forEach { (m, label) ->
                    Chip(join == LocalDate.now().minusMonths(m).toString(), label) {
                        join = LocalDate.now().minusMonths(m).toString()
                    }
                }
            }

            Text("중도해지이율", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(rule == EarlyRule.BANK_TABLE, "은행 기본 규칙 (보수적)") { rule = EarlyRule.BANK_TABLE }
                Chip(rule == EarlyRule.CUSTOM, "상품설명서 보고 직접 입력") { rule = EarlyRule.CUSTOM }
            }
            if (rule == EarlyRule.CUSTOM) {
                Field("중도해지이율 (연 %)", custom, keyboard = KeyboardType.Decimal) { custom = it }
            } else {
                Text("기본이율 × 50~90% × 경과월/계약월 (시중은행 적금 상품설명서 방식)",
                    style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            }

            if (valid) {
                val maturity = SwitchCalculator.maturityInterest(monthly, fr!!, term, settings.taxRate)
                SoftCard(color = Palette.MintSoft) {
                    Text("만기까지 유지하면 세후 이자 약 ${won(maturity)}", color = Palette.MintDeep,
                        style = MaterialTheme.typography.titleMedium)
                    Text("원금 ${won(monthly * term)} · 연 ${pct(fr!!)} · ${term}개월", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(
                onClick = {
                    vm.addProduct(
                        MyProduct(
                            id = UUID.randomUUID().toString(), bank = bank.trim(), name = name.trim(),
                            finalRate = fr!!, baseRate = br!!, monthly = monthly, termMonths = term,
                            joinDate = join, earlyRule = rule, customEarlyRate = custom.toDoubleOrNull() ?: 0.0,
                            productCode = pre?.code ?: ""
                        )
                    )
                    onDone()
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)
            ) { Text("등록하기") }
            Gap(24)
        }
    }
}

@Composable
private fun Field(
    label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = modifier, shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard)
    )
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
