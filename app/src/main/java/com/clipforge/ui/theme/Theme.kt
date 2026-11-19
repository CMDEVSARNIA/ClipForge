package com.clipforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

val Forge = Color(0xFFFF6A2B)
val ForgeDim = Color(0xFF8A3A18)
val Ember = Color(0xFFFFB37A)
val Ink = Color(0xFF0F0F12)
val Slate = Color(0xFF16161B)
val SlateHigh = Color(0xFF202027)
val SlateHighest = Color(0xFF2A2A33)
val Mist = Color(0xFFA6A6B3)

// Video editors read best on a dark canvas, so the app is always dark.
private val Scheme = darkColorScheme(
    primary = Forge,
    onPrimary = Color.Black,
    primaryContainer = ForgeDim,
    onPrimaryContainer = Color(0xFFFFE3D6),
    secondary = Ember,
    onSecondary = Color.Black,
    tertiary = Color(0xFF3DD6C6),
    background = Ink,
    onBackground = Color(0xFFECECF1),
    surface = Slate,
    onSurface = Color(0xFFECECF1),
    surfaceVariant = SlateHigh,
    onSurfaceVariant = Mist,
    surfaceContainer = SlateHigh,
    surfaceContainerHigh = SlateHighest,
    surfaceContainerLow = Slate,
    outline = Color(0xFF3A3A44),
    outlineVariant = Color(0xFF2C2C35),
    error = Color(0xFFFF5470),
)

private val Base = Typography()
private val AppTypography = Base.copy(
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Black),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Bold),
)

@Composable
fun ClipForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
