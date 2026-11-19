package com.clipforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clipforge.ui.theme.Forge

fun tierColor(tier: String): Color = when (tier) {
    "S" -> Color(0xFFFFC94A)
    "A" -> Forge
    "B" -> Color(0xFF3DD6C6)
    "C" -> Color(0xFF8E8E9C)
    else -> Color(0xFF55555F)
}

@Composable
fun TierBadge(tier: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(tierColor(tier), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(tier, color = Color.Black, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
    }
}

/** Horizontal 0–10 bar with a label, for hook / payoff / clarity / emotion / trend fit. */
@Composable
fun SubScoreBar(label: String, value: Int?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(76.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp))) {
            if (value != null) {
                Box(
                    Modifier.fillMaxWidth(value.coerceIn(0, 10) / 10f).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                )
            }
        }
        Text(value?.toString() ?: "–", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(28.dp).padding(start = 8.dp))
    }
}

@Composable
fun SubScores(hook: Int?, payoff: Int?, clarity: Int?, emotion: Int?, trendFit: Int?) {
    Column {
        SubScoreBar("Hook", hook)
        SubScoreBar("Payoff", payoff)
        SubScoreBar("Clarity", clarity)
        SubScoreBar("Emotion", emotion)
        SubScoreBar("Trend fit", trendFit)
    }
}
