package com.clipforge.ui.cam

import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.clipforge.ClipForgeApp
import com.clipforge.data.db.CamPresetDao
import com.clipforge.data.db.CamPresetEntity
import com.clipforge.data.db.ProjectDao
import com.clipforge.data.db.ProjectEntity
import com.clipforge.export.CropMath
import com.clipforge.export.NRect
import com.clipforge.media.ThumbnailLoader
import com.clipforge.ui.nav.CamSetupRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CamSetupViewModel(
    private val projectId: Long,
    startMs: Long,
    private val projects: ProjectDao,
    private val presets: CamPresetDao,
    private val thumbnails: ThumbnailLoader,
) : ViewModel() {

    data class State(
        val project: ProjectEntity? = null,
        val frameMs: Long = 0,
        val frame: ImageBitmap? = null,
        val rect: NRect = DEFAULT_RECT,
        /** Preset being edited, or null when drawing a new one. */
        val editingId: Long? = null,
        val name: String = "My facecam",
        val saved: Boolean = false,
    )

    val all: StateFlow<List<CamPresetEntity>> = presets.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(State(frameMs = startMs))
    val state: StateFlow<State> = _state.asStateFlow()
    private var frameJob: Job? = null

    init {
        viewModelScope.launch {
            val p = projects.get(projectId) ?: return@launch
            val current = p.camPresetId?.let { presets.get(it) }
            _state.update {
                it.copy(
                    project = p,
                    frameMs = it.frameMs.coerceIn(0, p.durationMs),
                    rect = current?.let { c -> NRect.fromCsv(c.rect) } ?: DEFAULT_RECT,
                    editingId = current?.id,
                    name = current?.name ?: "My facecam",
                )
            }
            loadFrame(0)
        }
    }

    fun setFrameMs(ms: Long) {
        _state.update { it.copy(frameMs = ms) }
        loadFrame(120)
    }

    private fun loadFrame(debounceMs: Long) {
        val p = _state.value.project ?: return
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            delay(debounceMs)
            thumbnails.frameAt(p.sourceUri.toUri(), _state.value.frameMs, 960, 960)?.let { f -> _state.update { it.copy(frame = f) } }
        }
    }

    fun setRect(r: NRect) = _state.update { it.copy(rect = CropMath.clampRect(r), saved = false) }
    fun setName(n: String) = _state.update { it.copy(name = n.take(40), saved = false) }

    fun pick(preset: CamPresetEntity) = _state.update {
        it.copy(rect = NRect.fromCsv(preset.rect) ?: DEFAULT_RECT, editingId = preset.id, name = preset.name, saved = false)
    }

    fun newPreset() = _state.update { it.copy(editingId = null, name = "Facecam ${all.value.size + 1}", saved = false) }

    /** Saves (creates or updates) the preset and assigns it to this project. */
    fun saveAndUse() {
        val st = _state.value
        val p = st.project ?: return
        viewModelScope.launch {
            val aspect = p.toVideoInfo().aspectRatio
            val id = st.editingId?.let { id ->
                presets.get(id)?.let { presets.update(it.copy(name = st.name.ifBlank { it.name }, rect = st.rect.toCsv(), sourceAspect = aspect)) }
                id
            } ?: presets.insert(CamPresetEntity(name = st.name.ifBlank { "Facecam" }, rect = st.rect.toCsv(), sourceAspect = aspect, createdAt = System.currentTimeMillis()))
            projects.setCamPreset(projectId, id)
            _state.update { it.copy(editingId = id, saved = true) }
        }
    }

    fun delete(preset: CamPresetEntity) {
        viewModelScope.launch {
            presets.delete(preset.id)
            if (_state.value.project?.camPresetId == preset.id) projects.setCamPreset(projectId, null)
            if (_state.value.editingId == preset.id) _state.update { it.copy(editingId = null) }
        }
    }

    fun removeFromProject() {
        viewModelScope.launch { projects.setCamPreset(projectId, null) }
    }

    companion object {
        /** Typical streamer cam spot: bottom-right corner. */
        val DEFAULT_RECT = NRect(0.72f, 0.62f, 0.98f, 0.97f)

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ClipForgeApp
                val route = createSavedStateHandle().toRoute<CamSetupRoute>()
                val db = app.container.database
                CamSetupViewModel(route.projectId, route.atMs, db.projectDao(), db.camPresetDao(), app.container.thumbnails)
            }
        }
    }
}
