package com.clipforge.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clipforge.desktop.AppDirs
import com.clipforge.desktop.DesktopApp
import com.clipforge.desktop.settings.DesktopSettings
import com.clipforge.net.WorkerException
import kotlinx.coroutines.launch
import java.awt.Desktop

@Composable
fun SettingsScreen(app: DesktopApp) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ConnectionCard(app)
            FfmpegCard(app)
            AboutCard()
        }
    }
}

@Composable
private fun ConnectionCard(app: DesktopApp) {
    val prefs by app.settings.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.workerUrl) }
    var invite by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(prefs.isConnected) {
        if (prefs.isConnected) runCatching { app.worker.health(prefs.workerUrl) }
            .onSuccess { status = "Connected. Models: ${it.models.joinToString { m -> m.removePrefix("claude-") }}${if (it.trends) " · trends on" else ""}" to false }
            .onFailure { status = "Saved, but the Worker didn't answer: ${it.message}" to true }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Connect to your Worker", style = MaterialTheme.typography.titleMedium)
            Text(
                "The same Worker URL and invite code as the phone app. Your Anthropic key stays in the Worker; this app never sees it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(url, { url = it }, label = { Text("Worker URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            if (!prefs.isConnected) {
                OutlinedTextField(invite, { invite = it }, label = { Text("Invite code") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (prefs.isConnected) {
                    OutlinedButton(onClick = { app.settings.disconnect(); status = null }) { Text("Disconnect") }
                } else {
                    Button(
                        enabled = !busy && url.isNotBlank() && invite.isNotBlank(),
                        onClick = {
                            busy = true; status = null
                            scope.launch {
                                val u = DesktopSettings.normalizeUrl(url)
                                try {
                                    app.worker.health(u)
                                    val token = app.worker.register(u, invite)
                                    app.settings.connect(u, token)
                                    url = u; invite = ""
                                } catch (e: WorkerException) {
                                    status = (if (e.status == 401 || e.status == 403) "That invite code wasn't accepted." else e.message ?: "Worker error") to true
                                } catch (e: Exception) {
                                    status = "Couldn't reach the Worker: ${e.message}" to true
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(if (busy) "Connecting…" else "Connect") }
                }
            }
            status?.let { (msg, isError) ->
                Text(msg, style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun FfmpegCard(app: DesktopApp) {
    var version by remember { mutableStateOf("") }
    LaunchedEffect(app.ffmpeg) { version = app.ffmpeg?.version().orEmpty() }
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Video engine", style = MaterialTheme.typography.titleMedium)
            val ff = app.ffmpeg
            if (ff == null) {
                Text("FFmpeg wasn't found. The installer includes it; if you're running from source, put ffmpeg and ffprobe on your PATH.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else {
                Text(version.ifBlank { "FFmpeg" }, style = MaterialTheme.typography.bodyMedium)
                Text(ff.ffmpeg.parentFile.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("ClipForge for desktop ${DesktopApp.VERSION} (preview)", style = MaterialTheme.typography.bodyMedium)
            Text("Library and settings: ${AppDirs.data.path}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Uses FFmpeg (LGPL) and the Outfit typeface (SIL Open Font License).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(AppDirs.data) } }) { Text("Open data folder") }
        }
    }
}
