package com.clipforge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import com.clipforge.export.WatermarkPosition
import com.clipforge.export.WatermarkSize
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
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
        var advanced by rememberSaveable { mutableStateOf(false) }
        var editServer by rememberSaveable { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Server") {
                if (st.connected && !editServer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Connected", style = MaterialTheme.typography.bodyLarge)
                            Text(st.workerUrl.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        androidx.compose.material3.TextButton(onClick = { editServer = true }) { Text("Change") }
                    }
                } else {
                    ConnectionFields(st, vm)
                    if (st.connected) androidx.compose.material3.TextButton(onClick = { editServer = false }) { Text("Done") }
                }
            }
            Section("Defaults & autopilot") { DefaultsSettings(st, vm) }
            Section("Watermark") { WatermarkSettings(st, vm) }
            Section("Captions") {
                CaptionLanguage(st, vm)
                Text(
                    if (st.modelReady[st.speechModel] == true) "Speech model downloaded (${st.speechModel.label.substringBefore(" ·")})."
                    else "The speech model (${st.speechModel.label.substringAfter("· ").substringBefore(" ·")}) downloads the first time you make captions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Section("Export") { ExportSettings(st, vm) }

            // Technical options, folded away by default.
            Surface(
                onClick = { advanced = !advanced },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Advanced", style = MaterialTheme.typography.titleMedium)
                        Text("Models, live trends, speech model", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (advanced) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (advanced) {
                Section("Claude models") { ModelSettings(st, vm) }
                Section("Live trends") { TrendSettings(st, vm) }
                Section("Speech model") { SpeechModelSettings(st, vm) }
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

@Composable
private fun WatermarkSettings(st: SettingsViewModel.UiState, vm: SettingsViewModel) {
    val pickLogo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::setWmLogo) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Add a watermark to exports", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Credits you when clips get reposted. Moving mode hops between corners, so one crop can't remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = st.wmEnabled, onCheckedChange = vm::setWmEnabled)
    }
    if (!st.wmEnabled) return
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Live preview, drawn by the same renderer the export uses.
        androidx.compose.foundation.layout.Box(
            Modifier.size(width = 108.dp, height = 192.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF3A7BD5), androidx.compose.ui.graphics.Color(0xFF1B3D1C)))),
        ) {
            st.wmPreview?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.matchParentSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = st.wmText, onValueChange = vm::setWmText, label = { Text("Text") },
                placeholder = { Text("@yourhandle") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    pickLogo.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (st.wmHasLogo) "Change logo" else "Add logo") }
                if (st.wmHasLogo) androidx.compose.material3.TextButton(onClick = vm::clearWmLogo) { Text("Remove") }
            }
        }
    }
    Text("Position", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WatermarkPosition.entries.forEach { p -> FilterChip(selected = st.wmPosition == p, onClick = { vm.setWmPosition(p) }, label = { Text(p.label) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        WatermarkSize.entries.forEach { s -> FilterChip(selected = st.wmSize == s, onClick = { vm.setWmSize(s) }, label = { Text(s.label) }, modifier = Modifier.padding(start = 6.dp)) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Opacity ${(st.wmOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.size(width = 110.dp, height = 20.dp))
        androidx.compose.material3.Slider(value = st.wmOpacity, onValueChange = vm::setWmOpacity, valueRange = 0.2f..1f, modifier = Modifier.weight(1f))
    }
    Text(
        "Placed clear of TikTok's buttons and captions; titles move down to make room. Shown in the editor's 9:16 preview and burned into every export.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HorizontalDividerThin() = androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun ConnectionFields(st: SettingsViewModel.UiState, vm: SettingsViewModel) {
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

@Composable
private fun ModelSettings(st: SettingsViewModel.UiState, vm: SettingsViewModel) {
    ModelField("Frame analysis (cheap, many requests)", st.analysisModel, vm::setAnalysisModel, st.workerModels,
        listOf("claude-haiku-5-5", "claude-sonnet-5-5"))
    ModelField("Ranking, titles & captions", st.writingModel, vm::setWritingModel, st.workerModels,
        listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"))
    Button(onClick = vm::saveModels, enabled = !st.modelsSaved) { Text(if (st.modelsSaved) "Saved" else "Save models") }
}

@Composable
private fun ExportSettings(st: SettingsViewModel.UiState, vm: SettingsViewModel) {
    Text("1080×1920 · H.264 High · AAC 48 kHz · faststart. Saved to Movies/ClipForge.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Frame rate", modifier = Modifier.weight(1f))
        listOf(30, 60).forEach { f -> FilterChip(selected = st.exportFps == f, onClick = { vm.setExportFps(f) }, label = { Text("$f fps") }) }
    }
    Text("60 fps keeps gameplay smooth; a 30 fps source stays at 30.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DefaultsSettings(st: SettingsViewModel.UiState, vm: SettingsViewModel) {
    Text("New clips start with", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.clipforge.export.CaptionStyle.entries.forEach { s ->
            FilterChip(selected = st.defCaptionStyle == s, onClick = { vm.setDefCaptionStyle(s) }, label = { Text(s.label) })
        }
    }
    ToggleRow("Hook teaser on new clips", "Flash the payoff before the clip starts.", st.defHookTeaser, vm::setDefHookTeaser)
    HorizontalDividerThin()
    Text("Autopilot exports", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("S" to "S only", "A" to "S + A", "B" to "S + A + B").forEach { (t, label) ->
            FilterChip(selected = st.apMinTier == t, onClick = { vm.setApMinTier(t) }, label = { Text(label) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Up to", style = MaterialTheme.typography.bodyMedium)
        listOf(3, 5, 10).forEach { n -> FilterChip(selected = st.apMax == n, onClick = { vm.setApMax(n) }, label = { Text("$n clips") }) }
    }
    ToggleRow("Captions & titles", "Transcribe and write titles before exporting.", st.apCaptions, vm::setApCaptions)
    ToggleRow("Run autopilot after every import", "Import a video and get a notification when its clips are ready.", st.apAfterImport, vm::setApAfterImport)
}

@Composable
private fun SpeechModelSettings(st: SettingsViewModel.UiState, vm: SettingsViewModel) {
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
}

@Composable
private fun CaptionLanguage(st: SettingsViewModel.UiState, vm: SettingsViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Language", modifier = Modifier.weight(1f))
        listOf("auto" to "Auto-detect", "en" to "English").forEach { (code, label) ->
            FilterChip(selected = st.captionLanguage == code, onClick = { vm.setCaptionLanguage(code) }, label = { Text(label) })
        }
    }
}

@Composable
private fun TrendSettings(st: SettingsViewModel.UiState, vm: SettingsViewModel) {
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
