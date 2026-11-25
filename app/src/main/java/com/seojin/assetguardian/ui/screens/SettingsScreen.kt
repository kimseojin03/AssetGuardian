@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.theme.Palette

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()

    var key by remember { mutableStateOf("") }
    var threshold by remember { mutableStateOf("") }
    var parking by remember { mutableStateOf("") }
    var tax by remember { mutableStateOf(15.4) }
    var server by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(s) {
        if (!loaded) {
            key = s.apiKey; threshold = s.thresholdWon.toString(); parking = s.parkingRate.toString()
            tax = s.taxRate; server = s.serverUrl; profile = s.profile; loaded = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("설정") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg)
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("금감원 금융상품 인증키", style = MaterialTheme.typography.labelLarge)
            Input("인증키 (finlife.fss.or.kr에서 발급)", key) { key = it }
            Text("금융상품통합비교공시(finlife) 오픈API 키예요. 전자공시(DART) 키와는 달라요. 비워두면 데모 데이터로 동작해요.",
                style = MaterialTheme.typography.labelMedium, color = Palette.Muted)

            Gap(4)
            Text("에이전트 판단 기준", style = MaterialTheme.typography.labelLarge)
            Input("알림 기준 (세후 순이익, 원)", threshold, KeyboardType.Number) { threshold = it }
            Input("파킹통장 금리 (연 %)", parking, KeyboardType.Decimal) { parking = it }
            Text("이자 과세", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15.4 to "일반 15.4%", 9.5 to "세금우대 9.5%", 0.0 to "비과세 0%").forEach { (v, label) ->
                    FilterChip(selected = tax == v, onClick = { tax = v }, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Palette.MintSoft, selectedLabelColor = Palette.MintDeep))
                }
            }

            Gap(4)
            Text("AI 에이전트 서버 (PC에서 run_server.bat 실행)", style = MaterialTheme.typography.labelLarge)
            Input("서버 주소 (비우면 에뮬레이터용 http://10.0.2.2:8000)", server) { server = it }
            Text("실제 휴대폰으로 테스트할 때만 http://PC의IP:8000 으로 바꾸세요. AI 키는 앱이 아니라 PC의 server/.env 파일에 있어요.",
                style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
            OutlinedTextField(
                value = profile, onValueChange = { profile = it },
                label = { Text("내 상황 (예: 대학생, 급여이체 없음, 체크카드 월 20만원)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(14.dp)
            )

            Button(
                onClick = {
                    vm.saveSettings(
                        s.copy(
                            apiKey = key.trim(),
                            thresholdWon = threshold.toLongOrNull()?.coerceAtLeast(0) ?: s.thresholdWon,
                            parkingRate = parking.toDoubleOrNull() ?: s.parkingRate,
                            taxRate = tax,
                            serverUrl = server.trim(),
                            profile = profile.trim()
                        )
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)
            ) { Text("저장") }
            Gap(24)
        }
    }
}

@Composable
private fun Input(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard)
    )
}
