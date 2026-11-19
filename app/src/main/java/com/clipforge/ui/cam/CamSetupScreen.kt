package com.clipforge.ui.cam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipforge.R
import com.clipforge.ui.clip.RectEditor
import com.clipforge.util.TimeFormat
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamSetupScreen(onBack: () -> Unit, vm: CamSetupViewModel = viewModel(factory = CamSetupViewModel.Factory)) {
    val st by vm.state.collectAsStateWithLifecycle()
    val presets by vm.all.collectAsStateWithLifecycle()
    val project = st.project

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Facecam") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (project == null) return@Scaffold
        val aspect = project.toVideoInfo().aspectRatio
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Find a frame where your webcam is visible, then drag the box over it. Corners resize. Saved presets work in every project.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RectEditor(frame = st.frame, aspect = aspect, rect = st.rect, onChange = vm::setRect)
            val boxAspect = st.rect.w * aspect / st.rect.h
            Text(
                String.format(java.util.Locale.US, "Box shape %.2f:1. ", boxAspect) +
                    if (boxAspect < 1.3f) "Split shows the cam at 1.6:1, so a box this square loses some of its top and bottom there. PiP keeps the shape."
                    else "Good fit for Split (1.6:1) and PiP.",
                style = MaterialTheme.typography.bodySmall,
                color = if (boxAspect < 1.3f) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(TimeFormat.format(st.frameMs), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = st.frameMs.toFloat(),
                    onValueChange = { vm.setFrameMs(it.toLong()) },
                    valueRange = 0f..project.durationMs.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
            OutlinedTextField(value = st.name, onValueChange = vm::setName, label = { Text("Preset name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::saveAndUse) { Text(if (st.editingId == null) "Save preset & use" else "Update & use") }
                if (st.editingId != null) OutlinedButton(onClick = vm::newPreset) { Text("Save as new…") }
            }
            if (st.saved) Text("Saved. Pick Split or Picture-in-picture on the project or in a clip.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)

            if (presets.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Presets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp))
                        presets.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = project.camPresetId == p.id || st.editingId == p.id, onClick = { vm.pick(p) })
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                    if (abs(p.sourceAspect - aspect) > 0.05f) {
                                        Text("Drawn on a differently shaped video; check the box position.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                                TextButton(onClick = { vm.delete(p) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
            if (project.camPresetId != null) {
                TextButton(onClick = vm::removeFromProject) { Text("This video has no facecam") }
            }
        }
    }
}
