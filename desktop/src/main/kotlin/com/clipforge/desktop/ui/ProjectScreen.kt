package com.clipforge.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipforge.desktop.DesktopApp
import com.clipforge.desktop.data.DesktopProject
import com.clipforge.desktop.media.FfmpegPlayer
import com.clipforge.media.VideoFormat
import com.clipforge.ui.components.ScrubMath
import com.clipforge.util.TimeFormat
import java.awt.Desktop

@Composable
fun ProjectScreen(app: DesktopApp, project: DesktopProject, onBack: () -> Unit) {
    val tools = app.tools
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Your videos") }
            Spacer(Modifier.width(8.dp))
            Text(project.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        when {
            project.missing -> MissingFile(project, onRemove = { app.library.remove(project.id); onBack() })
            tools == null -> Text("FFmpeg wasn't found, so this video can't be previewed. See Settings.", color = MaterialTheme.colorScheme.error)
            else -> {
                val scope = rememberCoroutineScope()
                val player = remember(project.id) { FfmpegPlayer(tools, project.file, project.info, scope) }
                DisposableEffect(player) { onDispose { player.close() } }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PlayerPane(player, Modifier.weight(1f))
                    InfoPane(project, Modifier.width(300.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun PlayerPane(player: FfmpegPlayer, modifier: Modifier) {
    val frame by player.frame.collectAsState()
    val position by player.position.collectAsState()
    val playing by player.playing.collectAsState()
    val error by player.error.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier.focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Spacebar, Key.K -> { player.togglePlay(); true }
                Key.DirectionLeft, Key.J -> { player.skip(-5_000); true }
                Key.DirectionRight, Key.L -> { player.skip(5_000); true }
                Key.MoveHome -> { player.seek(0); true }
                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val aspect = player.info.aspectRatio
            val boxAspect = maxWidth / maxHeight
            val m = if (aspect >= boxAspect) Modifier.fillMaxWidth().aspectRatio(aspect) else Modifier.fillMaxHeight().aspectRatio(aspect)
            Box(m.clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                frame?.let { Image(it, contentDescription = "Preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Timeline(player, position)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { player.skip(-5_000) }) { Text("−5 s") }
            FilledIconButton(
                onClick = player::togglePlay, modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Coral),
            ) { PlayPauseGlyph(playing) }
            OutlinedButton(onClick = { player.skip(5_000) }) { Text("+5 s") }
            Spacer(Modifier.width(8.dp))
            Text(
                "${TimeFormat.format(position, tenths = true)} / ${TimeFormat.format(player.info.durationMs)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.weight(1f))
            Text("Space play/pause · ← → 5 s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Seek bar. Drag away from it vertically to scrub more finely (ScrubMath, shared with the phone app). */
@Composable
private fun Timeline(player: FfmpegPlayer, position: Long) {
    val dur = player.info.durationMs
    var widthPx by remember { mutableFloatStateOf(1f) }
    var dragPos by remember { mutableStateOf<Double?>(null) }
    var wasPlaying by remember { mutableStateOf(false) }
    var factorLabel by remember { mutableStateOf<String?>(null) }
    val shown = dragPos?.toLong() ?: position
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(dur) {
                    detectTapGestures { o -> player.seek(ScrubMath.positionForX(o.x, size.width.toFloat(), dur)) }
                }
                .pointerInput(dur) {
                    detectDragGestures(
                        onDragStart = { o ->
                            wasPlaying = player.playing.value
                            player.pause()
                            dragPos = ScrubMath.positionForX(o.x, size.width.toFloat(), dur).toDouble()
                        },
                        onDragEnd = {
                            dragPos?.let { player.seek(it.toLong()) }
                            dragPos = null; factorLabel = null
                            if (wasPlaying) player.play()
                        },
                        onDragCancel = { dragPos = null; factorLabel = null },
                    ) { change, drag ->
                        val outside = ScrubMath.distanceOutside(change.position.y, size.height.toFloat())
                        val f = ScrubMath.fineFactor(outside, density)
                        factorLabel = ScrubMath.factorLabel(f)
                        val next = ScrubMath.applyDrag(dragPos ?: 0.0, drag.x, size.width.toFloat(), dur, f)
                        dragPos = next
                        player.seek(next.toLong())
                    }
                },
        ) {
            widthPx = size.width
            val y = size.height / 2
            val trackH = 6.dp.toPx()
            drawRoundRect(Line, Offset(0f, y - trackH / 2), Size(size.width, trackH), CornerRadius(trackH / 2))
            val x = ScrubMath.xForPosition(shown, size.width, dur)
            drawRoundRect(Coral, Offset(0f, y - trackH / 2), Size(x, trackH), CornerRadius(trackH / 2))
            drawCircle(Snow, 8.dp.toPx(), Offset(x, y))
        }
        factorLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Coral) }
    }
}

@Composable
private fun PlayPauseGlyph(playing: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val c = Color(0xFF1E1D29)
        if (playing) {
            val w = size.width * 0.28f
            drawRoundRect(c, Offset(size.width * 0.12f, 0f), Size(w, size.height), CornerRadius(2f))
            drawRoundRect(c, Offset(size.width * 0.60f, 0f), Size(w, size.height), CornerRadius(2f))
        } else {
            val p = Path().apply {
                moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.2f, size.height); close()
            }
            drawPath(p, c)
        }
    }
}

@Composable
private fun InfoPane(project: DesktopProject, modifier: Modifier) {
    val i = project.info
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Video", style = MaterialTheme.typography.titleMedium)
                LabeledValue("Length", TimeFormat.format(i.durationMs))
                LabeledValue("Resolution", i.resolutionLabel)
                LabeledValue("Frame rate", i.frameRateLabel)
                LabeledValue("Codec", i.videoMime?.substringAfter('/')?.uppercase() ?: "Unknown")
                LabeledValue("Bitrate", VideoFormat.bitrateLabel(i.bitrate))
                LabeledValue("Size", VideoFormat.bytesLabel(i.sizeBytes))
                LabeledValue("Audio", if (i.hasAudio) "Yes" else "No audio track")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("File", style = MaterialTheme.typography.titleMedium)
                Text(project.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { openFolder(project) }) { Text("Show in folder") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Gold))
                    Text("Coming next", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Finding the best moments with Claude, vertical clip export and captions arrive in the next desktop updates.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
            }
        }
    }
}

@Composable
private fun MissingFile(project: DesktopProject, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This file was moved or deleted", style = MaterialTheme.typography.titleMedium)
            Text(project.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Import it again from its new location, or remove it from the list.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onRemove) { Text("Remove from list") }
        }
    }
}

private fun openFolder(project: DesktopProject) {
    runCatching {
        val d = Desktop.getDesktop()
        if (d.isSupported(Desktop.Action.BROWSE_FILE_DIR)) d.browseFileDirectory(project.file)
        else d.open(project.file.parentFile)
    }
}
