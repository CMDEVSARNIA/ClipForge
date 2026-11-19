package com.clipforge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.clipforge.R

// ---- Brand (sampled from the logo) ----
val Ink = Color(0xFF2E2D3D)
val Coral = Color(0xFFFE624C)

// ---- Dark UI built on ink: tinted, not flat grey ----
val Night = Color(0xFF111117)        // app background
val NightLow = Color(0xFF16151E)
val NightCard = Color(0xFF1C1B26)    // cards
val NightRaised = Color(0xFF24232F)  // inputs, chips, raised cards
val NightTop = Ink                   // highest surface
val Snow = Color(0xFFF3F3F6)
val Mist = Color(0xFFA6A5B6)
val Line = Color(0xFF34333F)
val Gold = Color(0xFFFFC94A)
val Teal = Color(0xFF3DD6C6)
val Amber = Color(0xFFFFB86B)

/** Kept for code that predates the rebrand. */
val Forge = Coral

private val Scheme = darkColorScheme(
    primary = Coral,
    // Dark ink on coral reads at ~5:1, where white would be only ~3:1.
    onPrimary = Color(0xFF1E1D29),
    primaryContainer = Color(0xFF3B1F1C),
    onPrimaryContainer = Color(0xFFFFD9D2),
    inversePrimary = Color(0xFFB13A28),
    secondary = Amber,
    onSecondary = Color(0xFF2A1A05),
    secondaryContainer = NightRaised,
    onSecondaryContainer = Snow,
    tertiary = Teal,
    onTertiary = Color(0xFF00201D),
    background = Night,
    onBackground = Snow,
    surface = Night,
    onSurface = Snow,
    surfaceVariant = NightRaised,
    onSurfaceVariant = Mist,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF0C0C11),
    surfaceContainerLow = NightLow,
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightRaised,
    surfaceContainerHighest = NightTop,
    surfaceBright = NightTop,
    surfaceDim = Night,
    outline = Line,
    outlineVariant = Color(0xFF272632),
    error = Color(0xFFFF5C7A),
    onError = Color(0xFF2B0710),
    scrim = Color(0xCC000000),
)

/** Outfit (OFL), a geometric sans that matches the wordmark; one variable font file, many weights. */
private fun outfit(weight: Int) = Font(
    R.font.outfit,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Outfit = FontFamily(outfit(400), outfit(500), outfit(600), outfit(700), outfit(800))

private fun style(size: Int, weight: Int, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Outfit,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

private val AppTypography = Typography(
    displaySmall = style(34, 700, 40, -0.02),
    headlineLarge = style(30, 700, 36, -0.02),
    headlineMedium = style(26, 700, 32, -0.015),
    headlineSmall = style(22, 700, 28, -0.01),
    titleLarge = style(20, 700, 26, -0.01),
    titleMedium = style(16, 600, 22),
    titleSmall = style(14, 600, 20),
    bodyLarge = style(16, 400, 24),
    bodyMedium = style(14, 400, 20),
    bodySmall = style(12.5.toInt(), 400, 17),
    labelLarge = style(14, 600, 18, 0.01),
    labelMedium = style(12, 600, 16, 0.02),
    labelSmall = style(11, 600, 14, 0.03),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** ClipForge is dark-only: video reads best on a dark canvas, and the brand coral pops on ink. */
@Composable
fun ClipForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
