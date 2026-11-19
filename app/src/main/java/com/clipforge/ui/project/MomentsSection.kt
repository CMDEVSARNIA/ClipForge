package com.clipforge.ui.project

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.clipforge.R
import com.clipforge.analysis.CostEstimator
import com.clipforge.data.db.AnalysisEntity
import com.clipforge.data.db.ClipEntity
import com.clipforge.media.ThumbnailLoader
import com.clipforge.settings.AppSettings
import com.clipforge.ui.components.TierBadge
import com.clipforge.util.TimeFormat
import java.util.Locale

fun usd(v: Double): String = when {
    v < 0.01 -> String.format(Locale.US, "$%.4f", v)
    v < 1 -> String.format(Locale.US, "$%.3f", v)
    else -> String.format(Locale.US, "$%.2f", v)
}

@Composable
fun AnalyzeCard(
    settings: AppSettings?,
    run: ProjectViewModel.AnalysisRun?,
    analysis: AnalysisEntity?,
    clipCount: Int,
    onFindMoments: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                run?.running == true -> {
                    Text("Finding moments…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { run.fraction }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(run.step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    Text("You can leave this screen; progress continues in the notification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                settings != null && !settings.isConnected -> {
                    Text("Find the best moments", style = MaterialTheme.typography.titleMedium)
                    Text("Connect to your ClipForge Worker first. It holds the API key so the app never does.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onOpenSettings) { Text("Open Settings") }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (clipCount > 0) "Moments" else "Find the best moments", style = MaterialTheme.typography.titleMedium)
                            analysis?.let { a -> AnalysisSummary(a) }
                        }
                        Button(onClick = onFindMoments, enabled = settings != null) {
                            Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (clipCount > 0) "Re-run" else "Find moments")
                        }
                    }
                    analysis?.trendSummary?.takeIf { analysis.status == "DONE" }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    analysis?.warning?.takeIf { analysis.status == "DONE" }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    val err = run?.failedMessage ?: analysis?.error?.takeIf { analysis.status == "FAILED" }
                    if (err != null) Text("Last run failed: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AnalysisSummary(a: AnalysisEntity) {
    val parts = buildList {
        a.game?.let { add(if (a.gameDetected) "$it (detected)" else it) }
        a.trendNote?.let { add(it) }
        if (a.status == "DONE" || a.actualCostUsd > 0) add("spent ${usd(a.actualCostUsd)}")
    }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzeSheet(
    vm: ProjectViewModel,
    initialGame: String,
    onDismiss: () -> Unit,
) {
    val settings = vm.settings.value ?: return
    var game by rememberSaveable { mutableStateOf(initialGame) }
    val estimate: CostEstimator.Estimate? = remember(game.isBlank(), settings) { vm.estimate(game) }
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.startAnalysis(game)
        onDismiss()
    }
    fun start() {
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else {
            vm.startAnalysis(game)
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Find moments", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = game,
                onValueChange = { game = it },
                label = { Text("Game") },
                placeholder = { Text("Leave blank to auto-detect") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Used to look up what's trending for this game.") },
            )
            if (estimate != null) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Estimated API cost", style = MaterialTheme.typography.labelLarge)
                        EstimateRow("${estimate.frames} frames in ${estimate.batches} requests · ${settings.analysisModel}", usd(estimate.analysisUsd))
                        EstimateRow("Ranking & titles · ${settings.writingModel}", usd(estimate.rankUsd))
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        EstimateRow("Total (approx.)", usd(estimate.totalUsd), bold = true)
                        Text(
                            "Frames are 512 px wide (~${estimate.tokensPerFrame} tokens each). Actual cost is shown after the run; the Worker enforces its daily budget.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                if (settings.useTrends) "Trend data: past week's most-viewed Shorts for the game (falls back to built-in knowledge if unavailable)."
                else "Trend data: off (built-in knowledge only). Change in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = ::start, modifier = Modifier.fillMaxWidth()) { Text("Start analysis") }
        }
    }
}

@Composable
private fun EstimateRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else null)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else null)
    }
}

@Composable
fun ClipRow(
    clip: ClipEntity,
    sourceUri: String,
    thumbnails: ThumbnailLoader,
    onClick: () -> Unit,
    onPreview: () -> Unit,
    selected: Boolean? = null,
    onToggle: () -> Unit = {},
) {
    val thumb by produceState<ImageBitmap?>(null, clip.id, clip.thumbMs) {
        value = thumbnails.frameAt(sourceUri.toUri(), clip.thumbMs, 320, 320)
    }
    Surface(
        onClick = if (selected != null) onToggle else onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected == true) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, modifier = Modifier.size(32.dp))
            } else {
                Text("${clip.rank}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(26.dp))
            }
            Box(Modifier.width(100.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
                thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                TierBadge(clip.tier, Modifier.align(Alignment.TopStart).padding(4.dp))
                Surface(
                    onClick = onPreview,
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(26.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_play), "Preview in player", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(clip.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${TimeFormat.format(clip.startMs)}–${TimeFormat.format(clip.endMs)} · ${clip.lengthMs / 1000}s · ${clip.score}/100" +
                        (if (clip.edited) " · edited" else "") + (if (clip.exportedAt != null) " · exported" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(clip.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ExportCard(run: ProjectViewModel.ExportRun, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (run.running) {
                Text("Exporting…", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { run.fraction }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(run.step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            } else if (run.message != null) {
                Text(run.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun MomentsHeader(count: Int, selection: Set<Long>?, onSelect: () -> Unit, onSelectAll: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (selection == null) "$count moments, best first" else "${selection.size} selected",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        if (selection == null) {
            TextButton(onClick = onSelect) { Text("Select to export") }
        } else {
            TextButton(onClick = onSelectAll) { Text("All") }
            TextButton(onClick = onDone) { Text("Cancel") }
        }
    }
}
