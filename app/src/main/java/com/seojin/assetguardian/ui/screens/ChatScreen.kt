@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.data.ChatMsg
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.components.SoftCard
import com.seojin.assetguardian.ui.theme.Palette

private val suggestions = listOf(
    "나한테 맞는 적금 추천해줘",
    "목표 잘 가고 있어?",
    "지난달 50만원 넣었어",
    "지금 가진 적금 갈아타는 게 나아?",
    "이번 달 챙길 우대조건 알려줘",
    "2년 안에 1000만원 모으려면?"
)

/** AI 상담: 질문하면 AI가 기억·성향·추천·계산·목표·할 일 도구 중 필요한 걸 골라 쓰고 답한다 */
@Composable
fun ChatScreen(vm: MainViewModel) {
    val msgs by vm.chat.collectAsStateWithLifecycle()
    val loading by vm.chatLoading.collectAsStateWithLifecycle()
    val serverOk by vm.serverOk.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.loadChat() }
    LaunchedEffect(msgs.size, loading) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1 + if (loading) 1 else 0) }

    fun send(t: String) { vm.sendChat(t.trim()); input = "" }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 상담", style = MaterialTheme.typography.headlineSmall)
                Text("내 적금·목표·성향을 기억하고 도구로 계산해서 답해요", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (msgs.isNotEmpty()) IconButton(onClick = { vm.clearChat() }) { Icon(Icons.Filled.Delete, "대화 지우기", tint = Palette.Muted) }
        }
        if (serverOk == false) {
            SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Palette.AmberSoft) {
                Text("PC에서 run_server.bat을 켜야 상담할 수 있어요", color = Palette.Amber, fontWeight = FontWeight.Bold)
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (msgs.isEmpty()) {
                item {
                    SoftCard(color = Palette.MintSoft) {
                        Text("이렇게 물어보세요", fontWeight = FontWeight.Bold, color = Palette.MintDeep)
                        Gap(6)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            suggestions.forEach { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }) }
                        }
                    }
                }
            }
            itemsIndexed(msgs) { _, m -> Bubble(m) }
            if (loading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Mint)
                        Spacer(Modifier.width(8.dp))
                        Text("AI가 필요한 도구를 고르고 계산하는 중...", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, placeholder = { Text("궁금한 걸 물어보세요") },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp), maxLines = 4
            )
            IconButton(onClick = { send(input) }, enabled = input.isNotBlank() && !loading) {
                Icon(Icons.AutoMirrored.Filled.Send, "보내기", tint = if (input.isNotBlank()) Palette.Mint else Palette.Muted)
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    val mine = m.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Box(
            Modifier.widthIn(max = 310.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (mine) Palette.Mint else Palette.Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(m.text ?: "", color = if (mine) androidx.compose.ui.graphics.Color.White else Palette.Ink,
                style = MaterialTheme.typography.bodyLarge)
        }
        val trace = m.trace ?: emptyList()
        if (!mine && trace.isNotEmpty()) {
            Gap(4)
            Column(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SkySoft).padding(8.dp)) {
                Text("AI가 사용한 도구", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                trace.forEachIndexed { i, t ->
                    Text("${i + 1}. $t", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                }
            }
        }
    }
}
