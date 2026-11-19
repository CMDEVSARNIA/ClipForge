package com.clipforge.ui.clip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.clipforge.export.CropMath
import com.clipforge.export.CropMode
import com.clipforge.export.NRect
import com.clipforge.util.TimeFormat

@Composable
fun FramingSection(
    st: ClipEditorViewModel.State,
    sourceFrame: ImageBitmap?,
    sourceAspect: Float,
    vm: ClipEditorViewModel,
) {
    val segs = st.segments
    val editing = st.editing
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vertical framing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("9:16 preview", style = MaterialTheme.typography.labelMedium)
                Switch(checked = st.preview916, onCheckedChange = { vm.togglePreview916() }, modifier = Modifier.padding(start = 8.dp))
            }
            st.previewError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            val activeMode = if (st.customSegments == null && st.cropMode == CropMode.AUTO) CropMode.AUTO else editing?.mode
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CropMode.entries.forEach { m ->
                    FilterChip(selected = activeMode == m, onClick = { vm.setMode(m) }, label = { Text(m.label) })
                }
            }
            Text(
                when (activeMode) {
                    CropMode.AUTO -> "Auto: text cards and wide shots the AI flagged use Fit + blur, everything else Center crop."
                    CropMode.CENTER -> "Fills the frame with the middle of the shot."
                    CropMode.FIT_BLUR -> "Shows the whole frame over a blurred copy of itself."
                    CropMode.ZOOM_FIT -> "Shows the box you draw below over a blurred background."
                    CropMode.PAN -> "Fills the frame; slide to choose which part of the shot."
                    null -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (segs.size > 1 || st.customSegments != null) {
                Text("Segments", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    segs.forEachIndexed { i, s ->
                        FilterChip(
                            selected = i == st.selectedSegment,
                            onClick = { vm.selectSegment(i) },
                            label = { Text("${i + 1} · ${TimeFormat.format(s.startMs - st.startMs)} · ${s.mode.label}") },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::splitAtPlayhead) { Text("Split at playhead") }
                if (st.customSegments != null && st.selectedSegment < segs.lastIndex) {
                    OutlinedButton(onClick = vm::mergeSelectedWithNext) { Text("Merge with next") }
                }
            }

            when (editing?.mode) {
                CropMode.PAN -> {
                    Text("Pan", style = MaterialTheme.typography.labelLarge)
                    Slider(value = editing.panX, onValueChange = vm::setPan, valueRange = -1f..1f)
                }
                CropMode.ZOOM_FIT -> {
                    Text("Zoom box: drag to move, drag a corner to resize", style = MaterialTheme.typography.labelLarge)
                    RectEditor(
                        frame = sourceFrame,
                        aspect = sourceAspect,
                        rect = editing.zoomRect ?: CropMath.defaultZoomRect(),
                        onChange = vm::setZoomRect,
                    )
                }
                else -> Unit
            }
        }
    }
}

/** Source frame with a draggable / corner-resizable normalized rectangle. */
@Composable
fun RectEditor(frame: ImageBitmap?, aspect: Float, rect: NRect, onChange: (NRect) -> Unit, accent: Color = MaterialTheme.colorScheme.primary) {
    val current by rememberUpdatedState(rect)
    val change by rememberUpdatedState(onChange)
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(8.dp)).background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val handle = CropMath.pickHandle(current, down.position.x / w, down.position.y / h, 0.07f) ?: return@awaitEachGesture
                    down.consume()
                    var last = down.position
                    var r = current
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        r = CropMath.drag(r, handle, (c.position.x - last.x) / w, (c.position.y - last.y) / h)
                        last = c.position
                        change(r)
                        c.consume()
                    }
                }
            },
    ) {
        frame?.let { Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        Canvas(Modifier.fillMaxSize()) {
            val l = rect.l * size.width
            val t = rect.t * size.height
            val rw = rect.w * size.width
            val rh = rect.h * size.height
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset.Zero, Size(size.width, t))
            drawRect(dim, Offset(0f, t + rh), Size(size.width, size.height - t - rh))
            drawRect(dim, Offset(0f, t), Size(l, rh))
            drawRect(dim, Offset(l + rw, t), Size(size.width - l - rw, rh))
            drawRect(accent, Offset(l, t), Size(rw, rh), style = Stroke(2.dp.toPx()))
            val k = 10.dp.toPx()
            for ((x, y) in listOf(l to t, l + rw to t, l to t + rh, l + rw to t + rh)) {
                drawRect(accent, Offset(x - k / 2, y - k / 2), Size(k, k))
            }
        }
    }
}
