package com.clipforge.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.clipforge.ClipForgeApp
import com.clipforge.net.WorkerClient
import com.clipforge.net.WorkerException
import com.clipforge.settings.AppSettings
import com.clipforge.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

class SettingsViewModel(private val repo: SettingsRepository, private val worker: WorkerClient) : ViewModel() {

    data class UiState(
        val loaded: Boolean = false,
        val workerUrl: String = "",
        val inviteCode: String = "",
        val connected: Boolean = false,
        val busy: Boolean = false,
        val status: String? = null,
        val statusIsError: Boolean = false,
        val workerModels: List<String> = emptyList(),
        val workerTrends: Boolean? = null,
        val analysisModel: String = AppSettings.DEFAULT_ANALYSIS_MODEL,
        val writingModel: String = AppSettings.DEFAULT_WRITING_MODEL,
        val useTrends: Boolean = true,
        val modelsSaved: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = repo.current()
            _state.update {
                it.copy(
                    loaded = true, workerUrl = s.workerUrl, connected = s.isConnected,
                    analysisModel = s.analysisModel, writingModel = s.writingModel, useTrends = s.useTrends,
                )
            }
            if (s.isConnected) refreshHealth(s.workerUrl)
        }
    }

    fun setUrl(v: String) = _state.update { it.copy(workerUrl = v) }
    fun setInvite(v: String) = _state.update { it.copy(inviteCode = v) }
    fun setAnalysisModel(v: String) = _state.update { it.copy(analysisModel = v, modelsSaved = false) }
    fun setWritingModel(v: String) = _state.update { it.copy(writingModel = v, modelsSaved = false) }

    fun setUseTrends(v: Boolean) {
        _state.update { it.copy(useTrends = v) }
        viewModelScope.launch { repo.setUseTrends(v) }
    }

    fun saveModels() {
        val st = _state.value
        viewModelScope.launch {
            repo.saveModels(st.analysisModel, st.writingModel)
            _state.update { it.copy(modelsSaved = true) }
        }
    }

    private suspend fun refreshHealth(url: String) {
        try {
            val h = worker.health(url)
            _state.update { it.copy(workerModels = h.models, workerTrends = h.trends) }
        } catch (_: Exception) {
            // Status line stays as is; connect() reports errors explicitly.
        }
    }

    fun connect() {
        val st = _state.value
        val url = worker.normalizeUrl(st.workerUrl)
        if (url == null) {
            _state.update { it.copy(status = "That doesn't look like a URL.", statusIsError = true) }
            return
        }
        if (st.inviteCode.isBlank()) {
            _state.update { it.copy(status = "Enter the invite code you set on the Worker.", statusIsError = true) }
            return
        }
        _state.update { it.copy(busy = true, status = "Connecting…", statusIsError = false) }
        viewModelScope.launch {
            try {
                val health = worker.health(url)
                val token = worker.register(url, st.inviteCode)
                repo.saveConnection(url, token)
                _state.update {
                    it.copy(
                        busy = false, connected = true, workerUrl = url, inviteCode = "",
                        workerModels = health.models, workerTrends = health.trends,
                        status = "Connected.", statusIsError = false,
                    )
                }
            } catch (e: WorkerException) {
                _state.update { it.copy(busy = false, status = e.message, statusIsError = true) }
            } catch (e: IOException) {
                _state.update { it.copy(busy = false, status = "Couldn't reach the Worker: ${e.message}", statusIsError = true) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, status = "Unexpected response from that URL. Is it the ClipForge Worker?", statusIsError = true) }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repo.disconnect()
            _state.update { it.copy(connected = false, status = "Disconnected.", statusIsError = false) }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ClipForgeApp
                SettingsViewModel(app.container.settings, app.container.worker)
            }
        }
    }
}
