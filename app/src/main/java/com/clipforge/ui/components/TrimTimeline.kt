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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Trim strip: filmstrip of the window around a clip with draggable start/end handles, scene-cut
 * markers (handles snap to them) and a playhead. Touching away from the handles seeks.
 */
@Composable
fun TrimTimeline(
    windowStartMs: Long,
    windowEndMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long,
    cutsMs: List<Long>,
    thumbnails: List<ImageBitmap?>,
    onHandleDragStart: (TrimMath.Handle) -> Unit,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onDragEnd: (TrimMath.Handle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by rememberUpdatedState(startMs)
    val e by rememberUpdatedState(endMs)
    val onStart by rememberUpdatedState(onStartChange)
    val onEnd by rememberUpdatedState(onEndChange)
    val seek by rememberUpdatedState(onSeek)
    val dragStart by rememberUpdatedState(onHandleDragStart)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .pointerInput(windowStartMs, windowEndMs) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val w = size.width.toFloat()
                    val handle = TrimMath.pick(
                        down.position.x,
                        TrimMath.xFor(s, windowStartMs, windowEndMs, w),
                        TrimMath.xFor(e, windowStartMs, windowEndMs, w),
                        28.dp.toPx(),
                    )
                    // Keep the grab offset so a handle doesn't jump under the finger.
                    val grabOffset = when (handle) {
                        TrimMath.Handle.START -> TrimMath.xFor(s, windowStartMs, windowEndMs, w) - down.position.x
                        TrimMath.Handle.END -> TrimMath.xFor(e, windowStartMs, windowEndMs, w) - down.position.x
                        TrimMath.Handle.NONE -> 0f
                    }
                    dragStart(handle)
                    fun apply(x: Float) {
                        val ms = TrimMath.msFor(x + grabOffset, windowStartMs, windowEndMs, w)
                        when (handle) {
                            TrimMath.Handle.START -> onStart(ms)
                            TrimMath.Handle.END -> onEnd(ms)
                            TrimMath.Handle.NONE -> seek(ms)
                        }
                    }
                    apply(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        apply(change.position.x)
                        change.consume()
                    }
                    dragEnd(handle)
                }
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(vertical = 8.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            val n = thumbnails.size.coerceAtLeast(1)
            for (i in 0 until n) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    thumbnails.getOrNull(i)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val stripTop = 8.dp.toPx()
            val stripH = size.height - stripTop * 2
            val sx = TrimMath.xFor(startMs, windowStartMs, windowEndMs, w)
            val ex = TrimMath.xFor(endMs, windowStartMs, windowEndMs, w)
            val dim = Color.Black.copy(alpha = 0.6f)
            drawRect(dim, Offset(0f, stripTop), Size(sx, stripH))
            drawRect(dim, Offset(ex, stripTop), Size(w - ex, stripH))
            // Selection frame
            val bw = 3.dp.toPx()
            drawRect(accent, Offset(sx, stripTop - bw / 2), Size(ex - sx, bw))
            drawRect(accent, Offset(sx, stripTop + stripH - bw / 2), Size(ex - sx, bw))
            // Handles
            val hw = 12.dp.toPx()
            for ((x, left) in listOf(sx to true, ex to false)) {
                val hx = if (left) x - hw else x
                drawRoundRect(accent, Offset(hx, stripTop - bw), Size(hw, stripH + bw * 2), CornerRadius(4.dp.toPx()))
                val gx = hx + hw / 2
                drawLine(Color.Black.copy(alpha = 0.6f), Offset(gx, stripTop + stripH * 0.35f), Offset(gx, stripTop + stripH * 0.65f), 2.dp.toPx())
            }
            // Scene cut markers (small triangles above the strip)
            val tri = 5.dp.toPx()
            for (c in cutsMs) {
                if (c < windowStartMs || c > windowEndMs) continue
                val cx = TrimMath.xFor(c, windowStartMs, windowEndMs, w)
                val path = Path().apply {
                    moveTo(cx - tri, 0f); lineTo(cx + tri, 0f); lineTo(cx, tri * 1.4f); close()
                }
                drawPath(path, Color.White.copy(alpha = 0.8f))
            }
            // Playhead
            val px = TrimMath.xFor(playheadMs, windowStartMs, windowEndMs, w)
            drawLine(Color.White, Offset(px, stripTop - 2.dp.toPx()), Offset(px, stripTop + stripH + 2.dp.toPx()), 2.dp.toPx())
        }
    }
}
