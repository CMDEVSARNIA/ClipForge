package com.clipforge.ui.project

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.clipforge.data.db.ProjectEntity
import com.clipforge.media.VideoFormat
import com.clipforge.ui.components.ScrubTimeline
import com.clipforge.util.TimeFormat

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    onBack: () -> Unit,
    onOpenClip: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCamSetup: (projectId: Long, atMs: Long) -> Unit,
    vm: ProjectViewModel = viewModel(factory = ProjectViewModel.Factory),
) {
    val project by vm.project.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val clips by vm.clips.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val exportRun by vm.exportRun.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val camPreset by vm.camPreset.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.exportSelected() }
    fun export() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) vm.exportSelected() else permissions.launch(needed.toTypedArray())
    }
    var showSheet by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(project?.displayName ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            val sel = selection
            if (sel != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Button(
                        onClick = ::export,
                        enabled = sel.isNotEmpty() && exportRun?.running != true,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    ) { Text("Export ${sel.size} clip${if (sel.size == 1) "" else "s"} (1080×1920)") }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val maxPlayerHeight = maxHeight * 0.38f
            Column(Modifier.fillMaxSize()) {
                val p = project
                Box(
                    Modifier.fillMaxWidth().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (p != null && error == null) {
                        VideoSurface(
                            vm = vm,
                            modifier = Modifier.heightIn(max = maxPlayerHeight).aspectRatio(p.toVideoInfo().aspectRatio),
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().heightIn(max = maxPlayerHeight).aspectRatio(16f / 9f))
                    }
                }
                if (p != null && error == null) Controls(vm)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    error?.let { e -> item { ErrorBanner(e) } }
                    if (p != null && error == null) {
                        item(key = "analyze") {
                            AnalyzeCard(
                                settings = settings,
                                run = run,
                                analysis = analysis,
                                clipCount = clips.size,
                                onFindMoments = { showSheet = true },
                                onCancel = vm::cancelAnalysis,
                                onOpenSettings = onOpenSettings,
                            )
                        }
                        item(key = "facecam") {
                            FacecamCard(
                                preset = camPreset,
                                hasClips = clips.isNotEmpty(),
                                onSetup = { vm.pause(); onOpenCamSetup(p.id, vm.currentPositionMs()) },
                                onApply = vm::applyLayoutToAll,
                            )
                        }
                        exportRun?.takeIf { it.running || it.message != null }?.let { r ->
                            item(key = "export") { ExportCard(r, vm::cancelExport) }
                        }
                        if (clips.isNotEmpty()) {
                            item(key = "header") {
                                MomentsHeader(clips.size, selection, vm::startSelection, vm::selectAll, vm::endSelection)
                            }
                        }
                        items(clips, key = { it.id }) { c ->
                            ClipRow(
                                c, p.sourceUri, vm.thumbnailLoader,
                                onClick = { onOpenClip(c.id) },
                                onPreview = { vm.preview(c) },
                                selected = selection?.let { c.id in it },
                                onToggle = { vm.toggle(c.id) },
                            )
                        }
                    }
                    if (p != null) item(key = "info") { InfoCard(p) }
                }
            }
        }
    }

    if (showSheet) {
        AnalyzeSheet(vm = vm, initialGame = analysis?.game.orEmpty(), onDismiss = { showSheet = false })
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(vm: ProjectViewModel, modifier: Modifier) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    Box(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = vm::togglePlay,
        ),
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
        if (playback.isBuffering && !playback.scrubbing) {
            CircularProgressIndicator(Modifier.size(40.dp), color = Color.White)
        } else if (!playback.isPlaying && !playback.scrubbing) {
            Box(
                Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_play), "Play", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
private fun Controls(vm: ProjectViewModel) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val thumbs by vm.thumbnails.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        ScrubTimeline(
            durationMs = playback.durationMs,
            positionMs = playback.positionMs,
            thumbnails = thumbs,
            onScrubStart = vm::scrubStart,
            onScrub = vm::scrubTo,
            onScrubEnd = vm::scrubEnd,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                TimeFormat.format(playback.positionMs, tenths = true),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = if (playback.scrubbing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                TimeFormat.format(playback.durationMs),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.stepFrame(-1) }) { Text("−1f") }
            IconButton(onClick = { vm.seekBy(-5_000) }) { Icon(painterResource(R.drawable.ic_replay5), "Back 5 seconds") }
            FilledIconButton(
                onClick = vm::togglePlay,
                modifier = Modifier.size(60.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    painterResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    if (playback.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = { vm.seekBy(5_000) }) { Icon(painterResource(R.drawable.ic_forward5), "Forward 5 seconds") }
            TextButton(onClick = { vm.stepFrame(1) }) { Text("+1f") }
        }
    }
}

@Composable
private fun InfoCard(p: ProjectEntity) {
    val info = p.toVideoInfo()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Source video", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            InfoRow("Duration", TimeFormat.format(info.durationMs))
            InfoRow("Resolution", info.resolutionLabel)
            InfoRow("Frame rate", info.frameRateLabel)
            InfoRow("Codec", VideoFormat.codecLabel(info.videoMime))
            InfoRow("Bitrate", VideoFormat.bitrateLabel(info.bitrate))
            InfoRow("Audio", if (info.hasAudio) "Yes" else "No audio track")
            InfoRow("File size", VideoFormat.bytesLabel(info.sizeBytes))
            if (info.rotationDegrees != 0) InfoRow("Rotation", "${info.rotationDegrees}°")

            val warnings = buildList {
                if (info.durationMs > VideoFormat.MAX_SUPPORTED_DURATION_MS) add("Longer than 60 min: analysis may be slow and cost more.")
                if (!info.hasAudio) add("No audio: loudness-based moment detection will be skipped.")
                if (!p.persistedPermission) add("Your picker didn't grant long-term access; this project may stop opening after a reboot.")
            }
            warnings.forEach {
                Spacer(Modifier.height(8.dp))
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
