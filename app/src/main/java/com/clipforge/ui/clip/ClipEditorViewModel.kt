package com.clipforge.ui.clip

import android.app.Application
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.navigation.toRoute
import com.clipforge.ClipForgeApp
import com.clipforge.analysis.SignalsStore
import com.clipforge.data.db.ClipDao
import com.clipforge.data.db.ClipEntity
import com.clipforge.data.db.ProjectDao
import com.clipforge.data.db.ProjectEntity
import com.clipforge.media.ThumbnailLoader
import com.clipforge.ui.components.TrimMath
import com.clipforge.ui.nav.ClipEditorRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class ClipEditorViewModel(
    app: Application,
    private val clipId: Long,
    private val clipDao: ClipDao,
    private val projectDao: ProjectDao,
    private val signals: SignalsStore,
    private val thumbnails: ThumbnailLoader,
) : ViewModel() {

    data class State(
        val clip: ClipEntity? = null,
        val project: ProjectEntity? = null,
        val startMs: Long = 0,
        val endMs: Long = 0,
        val title: String = "",
        val windowStartMs: Long = 0,
        val windowEndMs: Long = 1,
        val cutsMs: List<Long> = emptyList(),
        val playheadMs: Long = 0,
        val isPlaying: Boolean = false,
        val saved: Boolean = true,
    ) {
        val lengthMs: Long get() = endMs - startMs
        val dirty: Boolean get() = clip != null && (startMs != clip.startMs || endMs != clip.endMs || title != clip.title)
    }

    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _strip = MutableStateFlow<List<ImageBitmap?>>(List(STRIP) { null })
    val strip: StateFlow<List<ImageBitmap?>> = _strip.asStateFlow()

    private var dragging = TrimMath.Handle.NONE
    private var wasPlaying = false

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            while (isActive) {
                val st = _state.value
                if (dragging == TrimMath.Handle.NONE && player.isPlaying && player.currentPosition >= st.endMs) {
                    player.seekTo(st.startMs) // loop the clip
                }
                _state.update { it.copy(playheadMs = if (dragging == TrimMath.Handle.NONE) player.currentPosition else it.playheadMs, isPlaying = player.isPlaying) }
                delay(if (player.isPlaying) 33 else 150)
            }
        }
    }

    private suspend fun load() {
        val clip = clipDao.get(clipId) ?: return
        val project = projectDao.get(clip.projectId) ?: return
        val cuts = withContext(Dispatchers.IO) { signals.read(project.id)?.cuts.orEmpty().map { (it * 1000).toLong() } }
        val (ws, we) = TrimMath.window(clip.suggestedStartMs, clip.suggestedEndMs, clip.startMs, clip.endMs, project.durationMs)
        _state.value = State(
            clip = clip, project = project, startMs = clip.startMs, endMs = clip.endMs, title = clip.title,
            windowStartMs = ws, windowEndMs = we, cutsMs = cuts, playheadMs = clip.startMs,
        )
        player.setMediaItem(MediaItem.fromUri(project.sourceUri.toUri()))
        player.setSeekParameters(SeekParameters.EXACT)
        player.prepare()
        player.seekTo(clip.startMs)
        thumbnails.stripRange(project.sourceUri.toUri(), ws, we, STRIP, 160, 160).collect { (i, bmp) ->
            _strip.value = _strip.value.toMutableList().also { it[i] = bmp }
        }
    }

    fun togglePlay() {
        val st = _state.value
        if (player.isPlaying) {
            player.pause()
        } else {
            val pos = player.currentPosition
            if (pos < st.startMs || pos >= st.endMs - 100) player.seekTo(st.startMs)
            player.play()
        }
    }

    fun pause() = player.pause()

    fun onHandleDragStart(h: TrimMath.Handle) {
        dragging = h
        wasPlaying = player.isPlaying
        if (h != TrimMath.Handle.NONE) {
            player.pause()
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    fun onDragEnd(h: TrimMath.Handle) {
        dragging = TrimMath.Handle.NONE
        player.setSeekParameters(SeekParameters.EXACT)
        val st = _state.value
        when (h) {
            TrimMath.Handle.START -> player.seekTo(st.startMs)
            TrimMath.Handle.END -> player.seekTo((st.endMs - 500).coerceAtLeast(st.startMs))
            TrimMath.Handle.NONE -> Unit
        }
        if (h != TrimMath.Handle.NONE && wasPlaying) player.play()
    }

    fun setStart(raw: Long) {
        val st = _state.value
        val v = TrimMath.clampStart(TrimMath.magnet(raw, st.cutsMs), st.endMs, st.windowStartMs)
        _state.update { it.copy(startMs = v, playheadMs = v, saved = false) }
        player.seekTo(v)
    }

    fun setEnd(raw: Long) {
        val st = _state.value
        val v = TrimMath.clampEnd(TrimMath.magnet(raw, st.cutsMs), st.startMs, st.windowEndMs)
        _state.update { it.copy(endMs = v, playheadMs = v, saved = false) }
        player.seekTo(v)
    }

    fun seek(ms: Long) {
        _state.update { it.copy(playheadMs = ms) }
        player.seekTo(ms)
    }

    fun nudgeStart(delta: Long) {
        val st = _state.value
        val v = TrimMath.clampStart(st.startMs + delta, st.endMs, st.windowStartMs)
        _state.update { it.copy(startMs = v, saved = false) }
        player.pause(); player.seekTo(v)
    }

    fun nudgeEnd(delta: Long) {
        val st = _state.value
        val v = TrimMath.clampEnd(st.endMs + delta, st.startMs, st.windowEndMs)
        _state.update { it.copy(endMs = v, saved = false) }
        player.pause(); player.seekTo(v)
    }

    fun setTitle(t: String) = _state.update { it.copy(title = t.take(100), saved = false) }

    fun resetToSuggested() {
        val c = _state.value.clip ?: return
        _state.update { it.copy(startMs = c.suggestedStartMs, endMs = c.suggestedEndMs, saved = false) }
        player.seekTo(c.suggestedStartMs)
    }

    fun save() {
        val st = _state.value
        val c = st.clip ?: return
        viewModelScope.launch {
            val updated = c.copy(startMs = st.startMs, endMs = st.endMs, title = st.title.trim().ifBlank { c.title }, edited = true)
            clipDao.update(updated)
            _state.update { it.copy(clip = updated, title = updated.title, saved = true) }
        }
    }

    override fun onCleared() = player.release()

    companion object {
        const val STRIP = 10

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ClipForgeApp
                val route = createSavedStateHandle().toRoute<ClipEditorRoute>()
                val c = app.container
                ClipEditorViewModel(app, route.clipId, c.database.clipDao(), c.database.projectDao(), c.signals, c.thumbnails)
            }
        }
    }
}
