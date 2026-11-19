package com.clipforge.ui.clip

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.clipforge.R
import com.clipforge.ui.components.SubScores
import com.clipforge.ui.components.TierBadge
import com.clipforge.ui.components.TrimTimeline
import com.clipforge.util.TimeFormat

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipEditorScreen(onBack: () -> Unit, vm: ClipEditorViewModel = viewModel(factory = ClipEditorViewModel.Factory)) {
    val st by vm.state.collectAsStateWithLifecycle()
    val strip by vm.strip.collectAsStateWithLifecycle()
    val sourceFrame by vm.sourceFrame.collectAsStateWithLifecycle()
    val exportRun by vm.exportRun.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.export()
    }
    fun export() {
        val needsAsk = Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.export()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(st.clip?.let { "Clip #${it.rank}" } ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") } },
                actions = {
                    TextButton(onClick = vm::resetToSuggested, enabled = st.clip != null) { Text("Reset") }
                    TextButton(onClick = { vm.save() }, enabled = st.dirty) { Text(if (st.dirty) "Save" else "Saved") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val clip = st.clip
        val project = st.project
        if (clip == null || project == null) return@Scaffold
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val maxPlayer = maxHeight * 0.45f
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                    val aspect = if (st.preview916) 9f / 16f else project.toVideoInfo().aspectRatio
                    ClipPlayer(vm, Modifier.heightIn(max = maxPlayer).aspectRatio(aspect), st.isPlaying)
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TrimTimeline(
                        windowStartMs = st.windowStartMs,
                        windowEndMs = st.windowEndMs,
                        startMs = st.startMs,
                        endMs = st.endMs,
                        playheadMs = st.playheadMs,
                        cutsMs = st.cutsMs,
                        thumbnails = strip,
                        onHandleDragStart = vm::onHandleDragStart,
                        onStartChange = vm::setStart,
                        onEndChange = vm::setEnd,
                        onSeek = vm::seek,
                        onDragEnd = vm::onDragEnd,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        EdgeControl("Start", st.startMs, onMinus = { vm.nudgeStart(-100) }, onPlus = { vm.nudgeStart(100) })
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            val len = st.lengthMs / 1000.0
                            Text(String.format(java.util.Locale.US, "%.1fs", len), style = MaterialTheme.typography.titleMedium)
                            val note = when {
                                len < 8 -> "short (8–30s is best)"
                                len > 30 -> "long (8–30s is best)"
                                else -> "good length"
                            }
                            Text(note, style = MaterialTheme.typography.labelSmall, color = if (len in 8.0..30.0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary)
                        }
                        EdgeControl("End", st.endMs, onMinus = { vm.nudgeEnd(-100) }, onPlus = { vm.nudgeEnd(100) })
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                        FilledIconButton(
                            onClick = vm::togglePlay,
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Icon(painterResource(if (st.isPlaying) R.drawable.ic_pause else R.drawable.ic_play), if (st.isPlaying) "Pause" else "Play clip", Modifier.size(30.dp))
                        }
                    }
                    Text(
                        "Drag the orange handles to trim. Handles snap to scene cuts (white markers). Playback loops the clip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                FramingSection(st, sourceFrame, project.toVideoInfo().aspectRatio, vm)

                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            exportRun?.let { "Exporting… $it" } ?: clip.exportedAt?.let { "Exported · saved in Movies/ClipForge" } ?: "1080×1920 · H.264 · AAC 48 kHz",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = ::export, enabled = exportRun == null) { Text(if (clip.exportedAt != null) "Export again" else "Export") }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TierBadge(clip.tier)
                            Spacer(Modifier.width(8.dp))
                            Text("${clip.score}/100 · ranked #${clip.rank}", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(clip.why, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.size(12.dp))
                        SubScores(clip.hook, clip.payoff, clip.clarity, clip.emotion, clip.trendFit)
                        Spacer(Modifier.size(12.dp))
                        OutlinedTextField(
                            value = st.title,
                            onValueChange = vm::setTitle,
                            label = { Text("Title") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (clip.hashtags.isNotBlank()) {
                            Spacer(Modifier.size(8.dp))
                            Text(clip.hashtags, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Suggested: ${TimeFormat.format(clip.suggestedStartMs, true)}–${TimeFormat.format(clip.suggestedEndMs, true)}" +
                                if (clip.loudnessAboveMedianDb > 0) " · peak +${clip.loudnessAboveMedianDb.toInt()} dB" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EdgeControl(label: String, ms: Long, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(TimeFormat.format(ms, tenths = true), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
        Row {
            TextButton(onClick = onMinus) { Text("−0.1") }
            TextButton(onClick = onPlus) { Text("+0.1") }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ClipPlayer(vm: ClipEditorViewModel, modifier: Modifier, isPlaying: Boolean) {
    Box(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = vm::togglePlay),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = vm.player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (!isPlaying) {
            Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_play), null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}
