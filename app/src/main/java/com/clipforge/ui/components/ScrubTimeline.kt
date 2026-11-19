package com.clipforge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column

/**
 * Filmstrip timeline with a draggable playhead. Tap jumps, drag scrubs, and dragging the finger
 * up/down away from the strip slows scrubbing down for precise positioning (see [ScrubMath.fineFactor]).
 */
@Composable
fun ScrubTimeline(
    durationMs: Long,
    positionMs: Long,
    thumbnails: List<ImageBitmap?>,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 60.dp,
) {
    val start by rememberUpdatedState(onScrubStart)
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    val currentPosition by rememberUpdatedState(positionMs)
    var fine by remember { mutableFloatStateOf(1f) }
    val accent = MaterialTheme.colorScheme.primary

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
            ScrubMath.factorLabel(fine)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = accent)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(durationMs) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val w = size.width.toFloat()
                        var pos = ScrubMath.positionForX(down.position.x, w, durationMs).toDouble()
                        start()
                        scrub(pos.toLong())
                        var lastX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            val factor = ScrubMath.fineFactor(
                                ScrubMath.distanceOutside(change.position.y, size.height.toFloat()), density
                            )
                            fine = factor
                            pos = ScrubMath.applyDrag(pos, dx, w, durationMs, factor)
                            scrub(pos.toLong())
                            change.consume()
                        }
                        fine = 1f
                        end()
                    }
                },
        ) {
            // Filmstrip
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                val n = thumbnails.size.coerceAtLeast(1)
                for (i in 0 until n) {
                    val bmp = thumbnails.getOrNull(i)
                    Box(Modifier.weight(1f).fillMaxHeight().padding(end = if (i < n - 1) 1.dp else 0.dp)) {
                        if (bmp != null) {
                            Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            // Played region tint + playhead
            Canvas(Modifier.fillMaxSize()) {
                val x = ScrubMath.xForPosition(currentPosition, size.width, durationMs)
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.35f),
                    topLeft = Offset(x, 0f),
                    size = Size(size.width - x, size.height),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
                val barW = 4.dp.toPx()
                val overhang = 4.dp.toPx()
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.5f),
                    topLeft = Offset(x - barW / 2 - 1.dp.toPx(), -overhang),
                    size = Size(barW + 2.dp.toPx(), size.height + overhang * 2),
                    cornerRadius = CornerRadius(barW),
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(x - barW / 2, -overhang),
                    size = Size(barW, size.height + overhang * 2),
                    cornerRadius = CornerRadius(barW),
                )
                drawCircle(accent, radius = 6.dp.toPx(), center = Offset(x, -overhang))
            }
        }
    }
}
