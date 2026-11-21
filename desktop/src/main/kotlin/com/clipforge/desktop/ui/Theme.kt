package com.clipforge.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

// Brand tokens, same values as the Android app (app/.../ui/theme/Theme.kt).
val Ink = Color(0xFF2E2D3D)
val Coral = Color(0xFFFE624C)
val Night = Color(0xFF111117)
val NightLow = Color(0xFF16151E)
val NightCard = Color(0xFF1C1B26)
val NightRaised = Color(0xFF24232F)
val Snow = Color(0xFFF3F3F6)
val Mist = Color(0xFFA6A5B6)
val Line = Color(0xFF34333F)
val Gold = Color(0xFFFFC94A)
val Teal = Color(0xFF3DD6C6)
val ErrorRed = Color(0xFFFF5C7A)

val Outfit = FontFamily(
    Font("fonts/Outfit-Regular.ttf", FontWeight.Normal),
    Font("fonts/Outfit-SemiBold.ttf", FontWeight.SemiBold),
    Font("fonts/Outfit-Bold.ttf", FontWeight.Bold),
)

private val scheme = darkColorScheme(
    primary = Coral,
    onPrimary = Color(0xFF1E1D29),
    primaryContainer = Color(0xFF3B1F1C),
    onPrimaryContainer = Color(0xFFFFD9D2),
    secondary = Gold,
    tertiary = Teal,
    background = Night,
    onBackground = Snow,
    surface = Night,
    onSurface = Snow,
    surfaceVariant = NightRaised,
    onSurfaceVariant = Mist,
    surfaceContainerLowest = Color(0xFF0C0C11),
    surfaceContainerLow = NightLow,
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightRaised,
    surfaceContainerHighest = Color(0xFF2B2A37),
    outline = Line,
    outlineVariant = Color(0xFF272632),
    error = ErrorRed,
)

private fun t(size: Int, weight: FontWeight, line: Int, spacing: Float = 0f) =
    TextStyle(fontFamily = Outfit, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

private val type = Typography(
    displaySmall = t(34, FontWeight.Bold, 40),
    headlineMedium = t(26, FontWeight.Bold, 32),
    headlineSmall = t(22, FontWeight.SemiBold, 28),
    titleLarge = t(19, FontWeight.SemiBold, 25),
    titleMedium = t(15, FontWeight.SemiBold, 21),
    titleSmall = t(13, FontWeight.SemiBold, 18),
    bodyLarge = t(15, FontWeight.Normal, 22),
    bodyMedium = t(14, FontWeight.Normal, 20),
    bodySmall = t(12, FontWeight.Normal, 17),
    labelLarge = t(14, FontWeight.SemiBold, 18),
    labelMedium = t(12, FontWeight.SemiBold, 16),
    labelSmall = t(11, FontWeight.SemiBold, 14, 0.3f),
)

@Composable
fun ClipForgeTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type, content = content)
