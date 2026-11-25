package com.seojin.assetguardian.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalUriHandler
import com.seojin.assetguardian.data.MarketProduct
import com.seojin.assetguardian.data.ProductRules
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.seojin.assetguardian.data.Verdict
import com.seojin.assetguardian.ui.theme.Palette

fun won(v: Long): String = "%,d원".format(v)
fun won(v: Double): String = "%,d원".format(Math.round(v))
fun signedWon(v: Long): String = (if (v > 0) "+" else "") + "%,d원".format(v)
fun pct(v: Double): String = "%.2f%%".format(v)

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    color: Color = Palette.Surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium, color = Palette.Ink
    )
}

@Composable
fun LabelValue(label: String, value: String, valueColor: Color = Palette.Ink, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Text(
            value, color = valueColor, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

data class VerdictStyle(val label: String, val bg: Color, val fg: Color)

fun verdictStyle(v: String): VerdictStyle = when (v) {
    Verdict.SWITCH -> VerdictStyle("갈아타면 이득", Palette.GainSoft, Palette.Gain)
    Verdict.CONDITIONAL -> VerdictStyle("조건부 이득", Palette.AmberSoft, Palette.Amber)
    Verdict.KEEP -> VerdictStyle("유지 권장", Palette.LossSoft, Palette.Loss)
    Verdict.MATURITY -> VerdictStyle("만기 임박", Palette.SkySoft, Color(0xFF0369A1))
    else -> VerdictStyle(v, Palette.Line, Palette.Muted)
}

@Composable
fun Gap(h: Int = 12) = Spacer(Modifier.height(h.dp))

/** 은행 홈페이지 / 상품 검색으로 바로 이동하는 버튼 두 개 */
@Composable
fun ProductLinks(p: MarketProduct, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val uri = LocalUriHandler.current
    val home = ProductRules.homepageUrl(p)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (home != null) {
            Button(
                onClick = { onClick(); runCatching { uri.openUri(home) } },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Mint)
            ) { Text("${p.bank.removePrefix("주식회사 ")} 홈페이지", maxLines = 1) }
        }
        OutlinedButton(
            onClick = { onClick(); runCatching { uri.openUri(ProductRules.searchUrl(p)) } },
            modifier = Modifier.weight(1f)
        ) { Text("상품 페이지 찾기", maxLines = 1) }
    }
}
