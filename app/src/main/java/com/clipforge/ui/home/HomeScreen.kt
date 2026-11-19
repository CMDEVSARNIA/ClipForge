package com.clipforge.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipforge.R
import com.clipforge.data.db.ProjectEntity
import com.clipforge.media.ThumbnailLoader
import com.clipforge.ui.components.BrandLockup
import com.clipforge.ui.components.LogoMark
import com.clipforge.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenProject: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.import(uri)
    }
    val pickVideo = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is HomeViewModel.Event.Open -> onOpenProject(e.projectId)
                is HomeViewModel.Event.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }

    HomeContent(
        projects = projects,
        importing = importing,
        thumbnails = vm.thumbnails,
        snackbar = snackbar,
        onImport = pickVideo,
        onOpenProject = onOpenProject,
        onOpenSettings = onOpenSettings,
        onDelete = { pendingDelete = it },
    )

    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove project?") },
            text = { Text("\"${p.displayName}\" will be removed from ClipForge. The original video stays in your gallery.") },
            confirmButton = { TextButton(onClick = { vm.delete(p); pendingDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Stateless home layout (also used by the screenshot tests). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    projects: List<ProjectEntity>?,
    importing: Boolean,
    thumbnails: ThumbnailLoader?,
    snackbar: SnackbarHostState,
    onImport: () -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onDelete: (ProjectEntity) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { BrandLockup() },
                actions = { IconButton(onClick = onOpenSettings) { Icon(painterResource(R.drawable.ic_settings), "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (!projects.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { if (!importing) onImport() },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text("New project") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(50),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                projects == null -> Unit
                projects.isEmpty() -> EmptyState(onImport = onImport, enabled = !importing)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Projects",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(projects, key = { it.id }) { p ->
                        ProjectTile(p, thumbnails, onClick = { onOpenProject(p.id) }, onDelete = { onDelete(p) })
                    }
                }
            }
            if (importing) ImportingOverlay()
        }
    }
}

@Composable
private fun EmptyState(onImport: () -> Unit, enabled: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) { LogoMark(64.dp) }
        Spacer(Modifier.height(28.dp))
        Text("Long gameplay in.\nShorts out.", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Import a recording. ClipForge finds the moments worth posting, frames them vertically and captions them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onImport,
            enabled = enabled,
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Import a video", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ProjectTile(project: ProjectEntity, thumbnails: ThumbnailLoader?, onClick: () -> Unit, onDelete: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, project.sourceUri) {
        value = thumbnails?.frameAt(project.sourceUri.toUri(), minOf(5_000L, project.durationMs / 2), 480, 480)
    }
    val info = project.toVideoInfo()
    Column(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) {
                Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painterResource(R.drawable.ic_movie), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            Text(
                TimeFormat.format(project.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(project.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${info.resolutionLabel.substringAfter("· ")} · ${info.frameRateLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(painterResource(R.drawable.ic_delete), "Remove project", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ImportingOverlay() {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text("Reading video…", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
