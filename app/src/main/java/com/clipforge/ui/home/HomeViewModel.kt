package com.clipforge.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.clipforge.ClipForgeApp
import com.clipforge.data.ProjectRepository
import com.clipforge.data.db.ProjectEntity
import com.clipforge.media.NotAVideoException
import com.clipforge.media.ThumbnailLoader
import com.clipforge.media.VideoFormat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repo: ProjectRepository,
    val thumbnails: ThumbnailLoader,
) : ViewModel() {

    val projects: StateFlow<List<ProjectEntity>?> =
        repo.observeProjects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    sealed interface Event {
        data class Open(val projectId: Long) : Event
        data class Message(val text: String) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun import(uri: Uri) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                val result = repo.importVideo(uri)
                if (result.info.durationMs > VideoFormat.MAX_SUPPORTED_DURATION_MS) {
                    _events.send(Event.Message("Heads up: videos over 60 min may be slow to analyze."))
                } else if (result.alreadyImported) {
                    _events.send(Event.Message("Already imported, opening the existing project."))
                }
                _events.send(Event.Open(result.projectId))
            } catch (e: NotAVideoException) {
                _events.send(Event.Message(e.message ?: "Not a playable video."))
            } catch (e: Exception) {
                _events.send(Event.Message("Import failed: ${e.message ?: e::class.simpleName}"))
            } finally {
                _importing.value = false
            }
        }
    }

    fun delete(project: ProjectEntity) {
        viewModelScope.launch { repo.delete(project) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ClipForgeApp
                HomeViewModel(app.container.projects, app.container.thumbnails)
            }
        }
    }
}
