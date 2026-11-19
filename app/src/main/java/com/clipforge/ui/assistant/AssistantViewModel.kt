package com.clipforge.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.clipforge.ClipForgeApp
import com.clipforge.assistant.ChatEngine
import com.clipforge.assistant.ChatItem
import com.clipforge.assistant.ChatProtocol
import com.clipforge.assistant.ChatStore
import com.clipforge.assistant.ProjectState
import com.clipforge.net.ModelRefusedException
import com.clipforge.net.WorkerException
import com.clipforge.ui.nav.AssistantRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

class AssistantViewModel(app: ClipForgeApp, private val projectId: Long) : ViewModel() {
    private val c = app.container
    private val store = ChatStore(app, projectId)
    private var engine: ChatEngine? = null

    data class UiState(
        val projectName: String = "",
        val items: List<ChatItem> = emptyList(),
        val sending: Boolean = false,
        val error: String? = null,
        val canUndo: Boolean = false,
        val costUsd: Double = 0.0,
        val connected: Boolean = true,
        val model: String = "",
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val project = c.database.projectDao().get(projectId) ?: return@launch
            val analysis = c.database.analysisDao().latest(projectId)
            val s = c.settings.current()
            store.load()
            engine = ChatEngine(
                call = { req -> c.worker.messagesRaw(req) },
                store = store,
                model = s.writingModel,
                system = ChatProtocol.system(project.displayName, analysis?.game, analysis?.trendSummary),
                state = {
                    val p = c.database.projectDao().get(projectId) ?: project
                    ProjectState(c.database.clipDao().getAll(clipIds()), p.durationMs, p.camPresetId != null)
                },
                apply = { changed -> changed.forEach { c.database.clipDao().update(it) } },
            )
            _ui.update { it.copy(projectName = project.displayName, connected = s.isConnected, model = s.writingModel) }
            refresh()
        }
    }

    private suspend fun clipIds(): List<Long> = c.database.clipDao().idsForProject(projectId)

    private fun refresh() = _ui.update {
        it.copy(items = ChatProtocol.display(store.messages), costUsd = store.costUsd, canUndo = engine?.lastChanges?.isNotEmpty() == true)
    }

    fun send(text: String) {
        val e = engine ?: return
        val t = text.trim()
        if (t.isEmpty() || _ui.value.sending) return
        _ui.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                when (val o = e.send(t) { refresh() }) {
                    is ChatEngine.Outcome.Stopped -> _ui.update { it.copy(error = o.note) }
                    ChatEngine.Outcome.Done -> Unit
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: WorkerException) {
                _ui.update { it.copy(error = ex.message) }
            } catch (ex: ModelRefusedException) {
                _ui.update { it.copy(error = ex.message) }
            } catch (ex: IOException) {
                _ui.update { it.copy(error = "Network error: ${ex.message}") }
            } finally {
                _ui.update { it.copy(sending = false) }
                refresh()
            }
        }
    }

    /** Restores every clip the last request changed. */
    fun undo() {
        val e = engine ?: return
        val originals = e.lastChanges.values.toList()
        if (originals.isEmpty()) return
        viewModelScope.launch {
            originals.forEach { c.database.clipDao().update(it) }
            e.clearUndo()
            _ui.update { it.copy(error = "Undid ${originals.size} change${if (originals.size == 1) "" else "s"}.") }
            refresh()
        }
    }

    fun newChat() {
        viewModelScope.launch {
            store.clear()
            engine?.clearUndo()
            _ui.update { it.copy(error = null) }
            refresh()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ClipForgeApp
                AssistantViewModel(app, createSavedStateHandle().toRoute<AssistantRoute>().projectId)
            }
        }
    }
}
