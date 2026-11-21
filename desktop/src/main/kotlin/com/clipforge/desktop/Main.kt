package com.clipforge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.clipforge.desktop.data.Library
import com.clipforge.desktop.media.Ffmpeg
import com.clipforge.desktop.ui.BrandLockup
import com.clipforge.desktop.ui.ClipForgeTheme
import com.clipforge.desktop.ui.DropOverlay
import com.clipforge.desktop.ui.HomeScreen
import com.clipforge.desktop.ui.ProjectScreen
import com.clipforge.desktop.ui.SettingsScreen
import com.clipforge.desktop.ui.logoPainter
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Project(val id: Long) : Screen
}

fun main() {
    val app = DesktopApp(AppDirs.data, Ffmpeg.locate())
    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        Window(onCloseRequest = ::exitApplication, state = state, title = "ClipForge", icon = remember { logoPainter() }) {
            window.minimumSize = Dimension(960, 640)
            ClipForgeTheme { AppRoot(app, pickFiles = { pickVideos(window) }) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(app: DesktopApp, pickFiles: () -> List<File>, initial: Screen = Screen.Home) {
    var screen by remember { mutableStateOf(initial) }
    val dragging by app.dragging.collectAsState()
    val projects by app.library.projects.collectAsState()
    val doImport = { files: List<File> -> app.importFiles(files) { p -> if (p != null) screen = Screen.Project(p.id) } }
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { app.dragging.value = true }
            override fun onExited(event: DragAndDropEvent) { app.dragging.value = false }
            override fun onEnded(event: DragAndDropEvent) { app.dragging.value = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                app.dragging.value = false
                val data = event.dragData() as? DragData.FilesList ?: return false
                doImport(data.readFiles().map(::fileFromDrop))
                return true
            }
        }
    }
    // Surface sets the content color, so plain Text is light-on-dark everywhere below it.
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable { screen = Screen.Home }.padding(4.dp)) { BrandLockup() }
                Spacer(Modifier.weight(1f))
                NavItem("Videos", screen !is Screen.Settings) { screen = Screen.Home }
                NavItem("Settings", screen is Screen.Settings) { screen = Screen.Settings }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (val s = screen) {
                Screen.Home -> HomeScreen(app, onImport = { doImport(pickFiles()) }, onOpen = { screen = Screen.Project(it.id) })
                Screen.Settings -> SettingsScreen(app)
                is Screen.Project -> {
                    val p = projects.firstOrNull { it.id == s.id }
                    if (p == null) screen = Screen.Home else ProjectScreen(app, p, onBack = { screen = Screen.Home })
                }
            }
        }
        if (dragging) DropOverlay()
    }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) c.surfaceContainerHigh else c.background)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.onSurface else c.onSurfaceVariant)
    }
    Spacer(Modifier.height(0.dp))
}

/** Drag data lists files as URIs ("file:/C:/…") on some platforms and plain paths on others. */
fun fileFromDrop(s: String): File =
    if (s.startsWith("file:")) runCatching { File(URI(s)) }.getOrElse { File(s.removePrefix("file:")) } else File(s)

/** Native open dialog. Windows ignores filename filters, so the file pattern carries the extensions there. */
private fun pickVideos(owner: Frame): List<File> {
    val d = FileDialog(owner, "Import a video", FileDialog.LOAD)
    d.isMultipleMode = true
    d.setFilenameFilter { _, name -> Library.isVideo(File(name)) }
    if (AppDirs.os() == AppDirs.Os.WINDOWS) d.file = Library.VIDEO_EXTENSIONS.joinToString(";") { "*.$it" }
    d.isVisible = true
    return d.files?.toList().orEmpty()
}
