package com.seojin.assetguardian.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seojin.assetguardian.data.Decision
import com.seojin.assetguardian.data.Verdict
import com.seojin.assetguardian.ui.MainViewModel
import com.seojin.assetguardian.ui.components.Gap
import com.seojin.assetguardian.ui.components.LabelValue
import com.seojin.assetguardian.ui.components.Pill
import com.seojin.assetguardian.ui.components.SoftCard
import com.seojin.assetguardian.ui.components.pct
import com.seojin.assetguardian.ui.components.signedWon
import com.seojin.assetguardian.ui.components.verdictStyle
import com.seojin.assetguardian.ui.components.won
import com.seojin.assetguardian.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InboxScreen(vm: MainViewModel) {
    val decisions by vm.decisions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val prevented = decisions.count { it.verdict == Verdict.KEEP }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("알림함", style = MaterialTheme.typography.headlineSmall)
            Text("에이전트가 내린 판단 기록이에요", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            SoftCard(color = Palette.SkySoft) {
                Text("지금 알림 기준: 세후 ${won(settings.thresholdWon)} 이상 이득", fontWeight = FontWeight.Bold)
                Text("'관심 없어요'를 누르면 기준이 올라가고, '확인했어요'를 누르면 내려가요. 에이전트가 내 반응을 보고 알림 빈도를 스스로 맞춰요.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                if (prevented > 0) {
                    Gap(6)
                    Text("손해 보는 갈아타기를 ${prevented}번 막았어요", color = Palette.MintDeep, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (decisions.isEmpty()) {
            item {
                Text("아직 판단 기록이 없어요. 홈에서 적금을 등록하고 '지금 점검'을 눌러보세요.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(decisions, key = { it.id }) { d -> DecisionCard(d) { fb -> vm.feedback(d.id, fb) } }
    }
}

@Composable
private fun DecisionCard(d: Decision, onFeedback: (String) -> Unit) {
    val st = verdictStyle(d.verdict)
    val time = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(d.createdAt))
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(st.label, st.bg, st.fg)
            if (d.isNewProduct) { Spacer(Modifier.width(6.dp)); Pill("신규·금리인상", Palette.SkySoft, Palette.Ink) }
            Spacer(Modifier.weight(1f))
            Text(time, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
        Gap(8)
        Text("${d.myName} → ${d.candidateBank} ${d.candidateName}", style = MaterialTheme.typography.titleMedium)
        Gap(4)
        Text(d.message, style = MaterialTheme.typography.bodyMedium)
        if (d.verdict != Verdict.MATURITY) {
            Gap(8)
            LabelValue("해지 손실", "-${won(d.loss)}", Palette.Loss)
            LabelValue("순이익 (기본 ${pct(d.candidateBaseRate)})", signedWon(d.netSure),
                if (d.netSure >= 0) Palette.Gain else Palette.Loss, true)
            LabelValue("순이익 (최고 ${pct(d.candidateMaxRate)})", signedWon(d.netMax),
                if (d.netMax >= 0) Palette.Gain else Palette.Loss)
        }
        Gap(10)
        when (d.feedback) {
            "" -> if (d.verdict != Verdict.KEEP) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onFeedback("ACCEPT") },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)) { Text("확인했어요") }
                OutlinedButton(onClick = { onFeedback("IGNORE") }) { Text("관심 없어요") }
            }
            "ACCEPT" -> Text("확인한 알림이에요", color = Palette.Gain, style = MaterialTheme.typography.labelMedium)
            else -> Text("관심 없음으로 표시했어요", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        }
    }
}
