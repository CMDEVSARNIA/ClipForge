package com.clipforge.ui.project

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.navigation.toRoute
import com.clipforge.ClipForgeApp
import com.clipforge.data.ProjectRepository
import com.clipforge.data.db.ProjectEntity
import com.clipforge.media.ThumbnailLoader
import com.clipforge.ui.nav.ProjectRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.clipforge.analysis.AnalysisWorker
import com.clipforge.analysis.CostEstimator
import com.clipforge.data.db.AnalysisDao
import com.clipforge.data.db.AnalysisEntity
import com.clipforge.data.db.ClipDao
import com.clipforge.data.db.ClipEntity
import com.clipforge.settings.AppSettings
import com.clipforge.settings.SettingsRepository
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class ProjectViewModel(
    private val app: Application,
    private val projectId: Long,
    private val repo: ProjectRepository,
    val thumbnailLoader: ThumbnailLoader,
    settingsRepo: SettingsRepository,
    analysisDao: AnalysisDao,
    clipDao: ClipDao,
) : ViewModel() {

    data class AnalysisRun(val running: Boolean, val fraction: Float, val step: String, val failedMessage: String?)

    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val clips: StateFlow<List<ClipEntity>> = clipDao.observeForProject(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val analysis: StateFlow<AnalysisEntity?> = analysisDao.observeLatest(projectId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live state of the background job (null when none has run in this process lifetime of WorkManager's history). */
    val run: StateFlow<AnalysisRun?> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(AnalysisWorker.uniqueName(projectId))
        .map { infos ->
            val info = infos.firstOrNull() ?: return@map null
            val running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED
            AnalysisRun(
                running = running,
                fraction = info.progress.getFloat(AnalysisWorker.KEY_FRACTION, 0f),
                step = info.progress.getString(AnalysisWorker.KEY_STEP) ?: if (running) "Starting…" else "",
                failedMessage = if (info.state == WorkInfo.State.FAILED) info.outputData.getString(AnalysisWorker.KEY_ERROR) else null,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun estimate(game: String): CostEstimator.Estimate? {
        val p = _project.value ?: return null
        val s = settings.value ?: return null
        val info = p.toVideoInfo()
        return CostEstimator.estimate(info.durationMs / 1000.0, info.displayWidth, info.displayHeight, s.analysisModel, s.writingModel, game.isBlank())
    }

    fun startAnalysis(game: String) {
        player.pause()
        AnalysisWorker.enqueue(app, projectId, game.trim().ifBlank { null })
    }

    fun cancelAnalysis() = AnalysisWorker.cancel(app, projectId)

    fun preview(clip: ClipEntity) {
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(clip.startMs)
        player.play()
    }

    data class Playback(
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val scrubbing: Boolean = false,
    )

    /** Owned by the ViewModel so playback survives rotation; created on the main thread. */
    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setSeekBackIncrementMs(5_000)
        .setSeekForwardIncrementMs(5_000)
        .build()

    private val _project = MutableStateFlow<ProjectEntity?>(null)
    val project: StateFlow<ProjectEntity?> = _project.asStateFlow()

    private val _playback = MutableStateFlow(Playback())
    val playback: StateFlow<Playback> = _playback.asStateFlow()

    private val _thumbnails = MutableStateFlow<List<ImageBitmap?>>(List(THUMB_COUNT) { null })
    val thumbnails: StateFlow<List<ImageBitmap?>> = _thumbnails.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var scrubPositionMs: Long? = null
    private var resumeAfterScrub = false
    private var lastScrubSeekAt = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
            override fun onPlayerError(error: PlaybackException) {
                _error.value = "Playback failed (${error.errorCodeName}). The file may be corrupt or use an unsupported codec."
            }
        })
        viewModelScope.launch { load() }
        viewModelScope.launch {
            while (isActive) {
                publish()
                delay(if (player.isPlaying) 33 else 200)
            }
        }
    }

    private suspend fun load() {
        val p = repo.observeProject(projectId).first()
        if (p == null) {
            _error.value = "This project no longer exists."
            return
        }
        _project.value = p
        repo.touch(p.id)
        val uri = p.sourceUri.toUri()
        if (!canRead(uri)) {
            _error.value = "ClipForge lost access to this video (it may have been moved or deleted). Remove the project and import it again."
            return
        }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()

        thumbnailLoader.strip(uri, p.durationMs, THUMB_COUNT, 192, 192).collect { (i, bmp) ->
            _thumbnails.value = _thumbnails.value.toMutableList().also { it[i] = bmp }
        }
    }

    private suspend fun canRead(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { app.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
    }

    private fun publish() {
        val duration = player.duration.takeIf { it > 0 } ?: _project.value?.durationMs ?: 0L
        _playback.value = Playback(
            positionMs = scrubPositionMs ?: player.currentPosition,
            durationMs = duration,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            scrubbing = scrubPositionMs != null,
        )
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun pause() = player.pause()

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceIn(0, _playback.value.durationMs)
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(target)
        publish()
    }

    fun stepFrame(direction: Int) {
        player.pause()
        val fps = _project.value?.frameRate?.takeIf { it > 1f } ?: 30f
        seekBy((direction * 1000f / fps).toLong().let { if (it == 0L) direction.toLong() else it })
    }

    fun scrubStart() {
        resumeAfterScrub = player.isPlaying
        player.pause()
        // Keyframe seeks while dragging keep scrubbing responsive on long files; exact seek on release.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        scrubPositionMs = player.currentPosition
    }

    fun scrubTo(positionMs: Long) {
        scrubPositionMs = positionMs
        val now = SystemClock.uptimeMillis()
        if (now - lastScrubSeekAt >= 80) {
            lastScrubSeekAt = now
            player.seekTo(positionMs)
        }
        publish()
    }

    fun scrubEnd() {
        val target = scrubPositionMs ?: return
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(target)
        scrubPositionMs = null
        if (resumeAfterScrub) player.play()
        publish()
    }

    override fun onCleared() {
        player.release()
    }

    companion object {
        const val THUMB_COUNT = 12

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ClipForgeApp
                val route = createSavedStateHandle().toRoute<ProjectRoute>()
                ProjectViewModel(
                    app, route.projectId, app.container.projects, app.container.thumbnails,
                    app.container.settings, app.container.database.analysisDao(), app.container.database.clipDao(),
                )
            }
        }
    }
}
