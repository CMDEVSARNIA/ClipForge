package com.clipforge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipforge.R
import com.clipforge.analysis.ModelPricing
import com.clipforge.captions.SpeechModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val st by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Worker connection") {
                Text(
                    "ClipForge never holds your Anthropic API key. It talks to your own Cloudflare Worker, which does. See the README for deployment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = st.workerUrl,
                    onValueChange = vm::setUrl,
                    label = { Text("Worker URL") },
                    placeholder = { Text("https://clipforge-worker.you.workers.dev") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = st.inviteCode,
                    onValueChange = vm::setInvite,
                    label = { Text(if (st.connected) "Invite code (only to reconnect)" else "Invite code") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::connect, enabled = !st.busy) { Text(if (st.connected) "Reconnect" else "Connect") }
                    if (st.connected) {
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(onClick = vm::disconnect, enabled = !st.busy) { Text("Disconnect") }
                    }
                    if (st.busy) {
                        Spacer(Modifier.size(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                val status = st.status ?: if (st.connected) "Connected." else "Not connected."
                Text(
                    status,
                    color = if (st.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (st.workerModels.isNotEmpty()) {
                    Text("Worker allows: ${st.workerModels.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("Models") {
                ModelField("Frame analysis (cheap, many requests)", st.analysisModel, vm::setAnalysisModel, st.workerModels,
                    listOf("claude-haiku-5-5", "claude-sonnet-5-5"))
                ModelField("Ranking, titles & captions", st.writingModel, vm::setWritingModel, st.workerModels,
                    listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"))
                Button(onClick = vm::saveModels, enabled = !st.modelsSaved) { Text(if (st.modelsSaved) "Saved" else "Save models") }
            }

            Section("Export") {
                Text("1080×1920 · H.264 High · AAC 48 kHz · faststart. Saved to Movies/ClipForge.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Frame rate", modifier = Modifier.weight(1f))
                    listOf(30, 60).forEach { f -> FilterChip(selected = st.exportFps == f, onClick = { vm.setExportFps(f) }, label = { Text("$f fps") }) }
                }
                Text("60 fps keeps gameplay smooth; a 30 fps source stays at 30.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("Speech captions") {
                Text("Captions are transcribed on your phone with whisper.cpp; audio never leaves the device. Claude only sees the text, to polish it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SpeechModel.entries.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = st.speechModel == m, onClick = { vm.setSpeechModel(m) })
                        Text(m.label, modifier = Modifier.weight(1f))
                        if (st.modelReady[m] == true) {
                            Text("downloaded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            androidx.compose.material3.TextButton(onClick = { vm.deleteModel(m) }) { Text("Delete") }
                        }
                    }
                }
                if (st.modelReady[st.speechModel] != true) {
                    val dl = st.modelDownload
                    if (dl != null) {
                        androidx.compose.material3.LinearProgressIndicator(progress = { dl }, modifier = Modifier.fillMaxWidth())
                    } else {
                        OutlinedButton(onClick = vm::downloadModel) { Text("Download now (otherwise on first use)") }
                    }
                }
                st.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Language", modifier = Modifier.weight(1f))
                    listOf("auto" to "Auto-detect", "en" to "English").forEach { (code, label) ->
                        FilterChip(selected = st.captionLanguage == code, onClick = { vm.setCaptionLanguage(code) }, label = { Text(label) })
                    }
                }
            }

            Section("Trends") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Rank against live YouTube Shorts trends", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (st.workerTrends) {
                                true -> "The Worker has a YouTube API key. Uses the past week's most-viewed Shorts for the game."
                                false -> "The Worker has no YOUTUBE_API_KEY, so ranking falls back to Claude's built-in knowledge."
                                null -> "Uses the past week's most-viewed Shorts for the game when the Worker has a YouTube key."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = st.useTrends, onCheckedChange = vm::setUseTrends)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ModelField(label: String, value: String, onChange: (String) -> Unit, allowed: List<String>, presets: List<String>) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                val notes = buildList {
                    if (allowed.isNotEmpty() && value !in allowed) add("Not in the Worker's ALLOWED_MODELS")
                    if (!ModelPricing.isKnown(value)) add("unknown price: estimates use Sonnet rates")
                }
                if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = MaterialTheme.colorScheme.error)
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { p -> FilterChip(selected = value == p, onClick = { onChange(p) }, label = { Text(p.removePrefix("claude-")) }) }
        }
        Spacer(Modifier.height(4.dp))
    }
}
