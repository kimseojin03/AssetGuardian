package com.seojin.assetguardian.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 밝고 산뜻한 민트 + 하늘색 팔레트 */
object Palette {
    val Bg = Color(0xFFF4FBF9)
    val Surface = Color(0xFFFFFFFF)
    val Mint = Color(0xFF14B8A6)
    val MintDeep = Color(0xFF0F766E)
    val MintSoft = Color(0xFFCCFBF1)
    val Sky = Color(0xFF38BDF8)
    val SkySoft = Color(0xFFE0F2FE)
    val Gain = Color(0xFF16A34A)
    val GainSoft = Color(0xFFDCFCE7)
    val Loss = Color(0xFFEF4444)
    val LossSoft = Color(0xFFFEE2E2)
    val Amber = Color(0xFFD97706)
    val AmberSoft = Color(0xFFFEF3C7)
    val Ink = Color(0xFF0F172A)
    val Muted = Color(0xFF64748B)
    val Line = Color(0xFFE2E8F0)

    val Hero = Brush.linearGradient(listOf(Color(0xFF2DD4BF), Color(0xFF38BDF8)))
}

private val Colors = lightColorScheme(
    primary = Palette.Mint,
    onPrimary = Color.White,
    primaryContainer = Palette.MintSoft,
    onPrimaryContainer = Palette.MintDeep,
    secondary = Palette.Sky,
    onSecondary = Color.White,
    secondaryContainer = Palette.SkySoft,
    onSecondaryContainer = Color(0xFF075985),
    background = Palette.Bg,
    onBackground = Palette.Ink,
    surface = Palette.Surface,
    onSurface = Palette.Ink,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Palette.Muted,
    outline = Palette.Line,
    error = Palette.Loss
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun AssetGuardianTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
