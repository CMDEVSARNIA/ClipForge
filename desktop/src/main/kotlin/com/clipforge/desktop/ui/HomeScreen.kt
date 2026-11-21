package com.clipforge.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipforge.desktop.DesktopApp
import com.clipforge.desktop.data.DesktopProject
import com.clipforge.media.VideoFormat
import com.clipforge.util.TimeFormat

@Composable
fun HomeScreen(app: DesktopApp, onImport: () -> Unit, onOpen: (DesktopProject) -> Unit) {
    val projects by app.library.projects.collectAsState()
    val import by app.import.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your videos", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Import a gameplay recording or stream VOD. Files stay where they are.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (import.busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
            }
            Button(onClick = onImport, enabled = !import.busy, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Import video")
            }
        }
        import.message?.let { msg ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (import.isError) Color(0xFF3A1820) else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        color = if (import.isError) Color(0xFFFFC2CD) else MaterialTheme.colorScheme.onSurface)
                    if (!import.busy) TextButton(onClick = app::clearImportMessage) { Text("Dismiss") }
                }
            }
        }
        if (projects.isEmpty()) {
            EmptyDropZone(onImport)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(250.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(projects, key = { it.id }) { p ->
                    ProjectCard(p, onOpen = { onOpen(p) }, onRemove = { app.library.remove(p.id) })
                }
            }
        }
    }
}

@Composable
private fun EmptyDropZone(onImport: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(20.dp))
            .clickable(onClick = onImport),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LogoMark(64.dp)
            Spacer(Modifier.height(4.dp))
            Text("Drop a recording here", style = MaterialTheme.typography.headlineSmall)
            Text(
                "or click to choose a file. MP4, MKV, MOV, WebM and FLV, up to 6 hours.\nOBS recordings work as they are.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProjectCard(p: DesktopProject, onOpen: () -> Unit, onRemove: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val thumb = remember(p.thumbPath) { loadImage(p.thumbPath) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.hoverable(hover).clickable(onClick = onOpen),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                if (thumb != null) {
                    Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Surface(
                    shape = RoundedCornerShape(6.dp), color = Color(0xCC111117),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                ) {
                    Text(TimeFormat.format(p.info.durationMs), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(VideoFormat.resolutionLabel(p.info.displayWidth, p.info.displayHeight).substringAfter("· "))
                    Chip(VideoFormat.frameRateLabel(p.info.frameRate))
                    if (p.missing) Chip("File moved")
                    Spacer(Modifier.weight(1f))
                    if (hovered) {
                        Text("Remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onRemove).padding(4.dp))
                    }
                }
            }
        }
    }
}

/** Shown over the whole window while a file is dragged in. */
@Composable
fun DropOverlay() {
    Box(Modifier.fillMaxSize().background(Color(0xD9111117)).padding(24.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().border(BorderStroke(2.dp, Coral), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LogoMark(72.dp)
                Text("Drop to import", style = MaterialTheme.typography.headlineSmall)
                Text("ClipForge reads the file where it is; nothing is copied.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 420.dp), textAlign = TextAlign.Center)
            }
        }
    }
}
