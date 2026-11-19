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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.navigation.toRoute
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.clipforge.ClipForgeApp
import com.clipforge.analysis.SignalsStore
import com.clipforge.data.db.ClipDao
import com.clipforge.data.db.ClipEntity
import com.clipforge.data.db.ProjectDao
import com.clipforge.data.db.ProjectEntity
import com.clipforge.export.CamLayout
import com.clipforge.export.CamSpec
import com.clipforge.export.CropMath
import com.clipforge.export.TextBitmapOverlay
import com.clipforge.export.TextConfig
import com.clipforge.export.CaptionStyle
import com.clipforge.export.HookTeaser
import com.clipforge.export.TitleStyle
import com.clipforge.captions.CaptionMath
import com.clipforge.captions.CaptionWord
import com.clipforge.captions.CaptionWorker
import androidx.media3.effect.OverlayEffect
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.clipforge.export.PipCorner
import com.clipforge.data.db.CamPresetDao
import com.clipforge.export.CropMode
import com.clipforge.export.ExportWorker
import com.clipforge.export.LayoutEffect
import com.clipforge.export.LayoutRequest
import com.clipforge.export.NRect
import com.clipforge.export.Segment
import com.clipforge.export.SegmentPlanner
import com.clipforge.media.ThumbnailLoader
import com.clipforge.ui.components.TrimMath
import com.clipforge.ui.nav.ClipEditorRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class ClipEditorViewModel(
    private val app: Application,
    private val clipId: Long,
    private val clipDao: ClipDao,
    private val projectDao: ProjectDao,
    private val signals: SignalsStore,
    private val thumbnails: ThumbnailLoader,
    private val camPresets: CamPresetDao,
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
        val specialMs: List<Long> = emptyList(),
        val playheadMs: Long = 0,
        val isPlaying: Boolean = false,
        /** Clip-level mode; AUTO derives segments from the vision flags. */
        val cropMode: CropMode = CropMode.AUTO,
        val panX: Float = 0f,
        val zoomRect: NRect? = null,
        /** Non-null when the user split the clip into custom segments. */
        val customSegments: List<Segment>? = null,
        val selectedSegment: Int = 0,
        val preview916: Boolean = true,
        val previewError: String? = null,
        /** Facecam rect from the project's preset; null = no facecam set up. */
        val camRect: NRect? = null,
        val camLayout: CamLayout = CamLayout.NONE,
        val pipCorner: PipCorner = PipCorner.TR,
        val showTitle: Boolean = true,
        val titleStyle: TitleStyle = TitleStyle.PUNCH,
        val showCaptions: Boolean = true,
        // Written by the captions job (refreshed live from the database):
        val titleOptions: List<String> = emptyList(),
        val captionWords: List<CaptionWord> = emptyList(),
        val captionStatus: String = "NONE",
        val hashtags: String = "",
        /** From Settings; applies to every export. */
        val watermark: com.clipforge.export.Watermark? = null,
        val captionStyle: CaptionStyle = CaptionStyle.CLASSIC,
        val hookTeaser: Boolean = false,
        val teaserStartMs: Long? = null,
        val loudnessDb: List<Float> = emptyList(),
        val loudnessWindowS: Double = 0.5,
        /** True while "Preview teaser" plays the teaser before jumping to the clip start. */
        val teaserPlaying: Boolean = false,
    ) {
        val teaserRange: Pair<Long, Long>?
            get() = HookTeaser.range(startMs, endMs, teaserStartMs, loudnessDb, loudnessWindowS)

        /** Inputs of the text overlay; a new value means the preview must redraw. */
        val textKey: Any get() = listOf(title, showTitle, titleStyle, showCaptions, captionWords, startMs, endMs, cam, watermark, captionStyle, teaserPlaying)

        val cam: CamSpec? get() = camRect?.takeIf { camLayout != CamLayout.NONE }?.let { CamSpec(it, camLayout, pipCorner) }

        val lengthMs: Long get() = endMs - startMs

        /** Segments as they will export. */
        val segments: List<Segment>
            get() = when {
                customSegments != null -> SegmentPlanner.normalize(customSegments, startMs, endMs)
                cropMode == CropMode.AUTO -> SegmentPlanner.auto(startMs, endMs, cutsMs, specialMs)
                else -> listOf(Segment(startMs, endMs, cropMode, panX, zoomRect))
            }

        val dirty: Boolean
            get() = clip != null && (
                startMs != clip.startMs || endMs != clip.endMs || title != clip.title ||
                    cropMode.name != clip.cropMode || panX != clip.panX || zoomRect?.toCsv() != clip.zoomRect ||
                    camLayout.name != clip.camLayout || pipCorner.name != clip.pipCorner ||
                    showTitle != clip.showTitle || titleStyle.name != clip.titleStyle || showCaptions != clip.showCaptions ||
                    captionStyle.name != clip.captionStyle || hookTeaser != clip.hookTeaser || teaserStartMs != clip.teaserStartMs ||
                    customSegments?.let { SegmentPlanner.encode(SegmentPlanner.normalize(it, startMs, endMs)) } != clip.segmentsJson
                )

        /** The segment whose settings the mode chips / pan / zoom box currently edit. */
        val editing: Segment? get() = segments.getOrNull(selectedSegment.coerceIn(0, (segments.size - 1).coerceAtLeast(0)))
    }

    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    /** Read by the GL thread every frame for the live 9:16 preview. */
    @Volatile private var previewRequest = LayoutRequest(CropMode.CENTER)
    @Volatile private var previewText: TextConfig? = null
    @Volatile private var previewPosMs: Long = 0
    private var lastTextKey: Any? = null

    private fun previewEffects() = listOf(
        LayoutEffect(PREVIEW_W, PREVIEW_H) { previewRequest },
        OverlayEffect(listOf(TextBitmapOverlay(PREVIEW_W, PREVIEW_H, { previewText }, { previewPosMs }))),
    )

    val captionRun: StateFlow<String?> = WorkManager.getInstance(app)
        .getWorkInfosByTagFlow(CaptionWorker.TAG)
        .map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            running?.progress?.getString(CaptionWorker.KEY_STEP) ?: running?.let { "Queued" }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _strip = MutableStateFlow<List<ImageBitmap?>>(List(STRIP) { null })
    val strip: StateFlow<List<ImageBitmap?>> = _strip.asStateFlow()

    private val _sourceFrame = MutableStateFlow<ImageBitmap?>(null)
    val sourceFrame: StateFlow<ImageBitmap?> = _sourceFrame.asStateFlow()

    val exportRun: StateFlow<String?> = WorkManager.getInstance(app)
        .getWorkInfosByTagFlow("export")
        .map { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            running?.progress?.getString(ExportWorker.KEY_STEP) ?: running?.let { "Queued" }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var dragging = TrimMath.Handle.NONE
    private var wasPlaying = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (_state.value.preview916) {
                    // Fall back to the plain preview if the effects pipeline fails on this device.
                    _state.update { it.copy(preview916 = false, previewError = "9:16 preview isn't available on this device (${error.errorCodeName}); export is unaffected.") }
                    player.setVideoEffects(emptyList())
                    player.prepare()
                }
            }
        })
        viewModelScope.launch { load() }
        viewModelScope.launch {
            while (isActive) {
                val st = _state.value
                val teaser = st.teaserRange
                if (st.teaserPlaying && teaser != null && player.currentPosition >= teaser.second) {
                    // Teaser done: cut to the real start, like the export does.
                    player.seekTo(st.startMs)
                    _state.update { it.copy(teaserPlaying = false) }
                } else if (!st.teaserPlaying && dragging == TrimMath.Handle.NONE && player.isPlaying && player.currentPosition >= st.endMs) {
                    player.seekTo(st.startMs) // loop the clip
                }
                val pos = if (dragging == TrimMath.Handle.NONE) player.currentPosition else st.playheadMs
                val segs = st.segments
                if (segs.isNotEmpty()) previewRequest = segs[SegmentPlanner.indexAt(segs, pos)].request(st.cam)
                previewPosMs = pos
                val tk = st.textKey
                if (tk != lastTextKey && st.clip != null) {
                    lastTextKey = tk
                    previewText = if (st.teaserPlaying) {
                        TextConfig.forClip(st.clip, st.startMs, st.endMs, st.cam, title = HookTeaser.LABEL, showTitle = true,
                            style = st.titleStyle, showCaptions = false, watermark = st.watermark)
                    } else TextConfig.forClip(
                        st.clip, st.startMs, st.endMs, st.cam, title = st.title, showTitle = st.showTitle,
                        style = st.titleStyle, showCaptions = st.showCaptions, words = st.captionWords, watermark = st.watermark,
                        captionStyle = st.captionStyle,
                    ).takeIf { !it.isEmpty }
                }
                _state.update { it.copy(playheadMs = pos, isPlaying = player.isPlaying) }
                delay(if (player.isPlaying) 33 else 150)
            }
        }
    }

    private suspend fun load() {
        val clip = clipDao.get(clipId) ?: return
        val project = projectDao.get(clip.projectId) ?: return
        val sig = withContext(Dispatchers.IO) { signals.read(project.id) }
        val cuts = sig?.cuts.orEmpty().map { (it * 1000).toLong() }
        val special = sig?.specialFrames.orEmpty().map { (it.tS * 1000).toLong() }
        val (ws, we) = TrimMath.window(clip.suggestedStartMs, clip.suggestedEndMs, clip.startMs, clip.endMs, project.durationMs)
        val camRect = project.camPresetId?.let { camPresets.get(it) }?.let { NRect.fromCsv(it.rect) }
        _state.value = State(
            clip = clip, project = project, startMs = clip.startMs, endMs = clip.endMs, title = clip.title,
            windowStartMs = ws, windowEndMs = we, cutsMs = cuts, specialMs = special, playheadMs = clip.startMs,
            cropMode = CropMode.of(clip.cropMode), panX = clip.panX, zoomRect = NRect.fromCsv(clip.zoomRect),
            customSegments = SegmentPlanner.decode(clip.segmentsJson),
            camRect = camRect, camLayout = CamLayout.of(clip.camLayout), pipCorner = PipCorner.of(clip.pipCorner),
            showTitle = clip.showTitle, titleStyle = TitleStyle.of(clip.titleStyle), showCaptions = clip.showCaptions,
            titleOptions = decodeOptions(clip.titleOptions), captionWords = CaptionMath.decode(clip.captionsJson),
            captionStatus = clip.captionStatus, hashtags = clip.hashtags,
            captionStyle = CaptionStyle.of(clip.captionStyle), hookTeaser = clip.hookTeaser, teaserStartMs = clip.teaserStartMs,
            loudnessDb = sig?.loudnessDb.orEmpty(), loudnessWindowS = sig?.loudnessWindowS ?: 0.5,
            watermark = withContext(Dispatchers.IO) { com.clipforge.export.WatermarkStore(app).build((app as ClipForgeApp).container.settings.current()) },
        )
        // The captions job writes to this clip; pick up its fields without touching unsaved edits.
        viewModelScope.launch {
            clipDao.observe(clipId).collect { c ->
                if (c == null) return@collect
                _state.update {
                    it.copy(
                        titleOptions = decodeOptions(c.titleOptions), captionWords = CaptionMath.decode(c.captionsJson),
                        captionStatus = c.captionStatus, hashtags = c.hashtags,
                        clip = it.clip?.copy(titleOptions = c.titleOptions, captionsJson = c.captionsJson, captionStatus = c.captionStatus, hashtags = c.hashtags, exportedAt = c.exportedAt, exportedUri = c.exportedUri),
                    )
                }
            }
        }
        // Effects must be set before prepare(); they can be swapped afterwards.
        player.setVideoEffects(previewEffects())
        player.setMediaItem(MediaItem.fromUri(project.sourceUri.toUri()))
        player.setSeekParameters(SeekParameters.EXACT)
        player.prepare()
        player.seekTo(clip.startMs)
        refreshSourceFrame()
        thumbnails.stripRange(project.sourceUri.toUri(), ws, we, STRIP, 160, 160).collect { (i, bmp) ->
            _strip.value = _strip.value.toMutableList().also { it[i] = bmp }
        }
    }

    // ---- playback & trim ----

    fun togglePlay() {
        val st = _state.value
        if (player.isPlaying) {
            player.pause()
            refreshSourceFrame()
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
        refreshSourceFrame()
    }

    fun setStart(raw: Long) {
        val st = _state.value
        val v = TrimMath.clampStart(TrimMath.magnet(raw, st.cutsMs), st.endMs, st.windowStartMs)
        _state.update { it.copy(startMs = v, playheadMs = v) }
        player.seekTo(v)
    }

    fun setEnd(raw: Long) {
        val st = _state.value
        val v = TrimMath.clampEnd(TrimMath.magnet(raw, st.cutsMs), st.startMs, st.windowEndMs)
        _state.update { it.copy(endMs = v, playheadMs = v) }
        player.seekTo(v)
    }

    fun seek(ms: Long) {
        _state.update { it.copy(playheadMs = ms) }
        player.seekTo(ms)
    }

    fun nudgeStart(delta: Long) {
        val st = _state.value
        val v = TrimMath.clampStart(st.startMs + delta, st.endMs, st.windowStartMs)
        _state.update { it.copy(startMs = v) }
        player.pause(); player.seekTo(v)
    }

    fun nudgeEnd(delta: Long) {
        val st = _state.value
        val v = TrimMath.clampEnd(st.endMs + delta, st.startMs, st.windowEndMs)
        _state.update { it.copy(endMs = v) }
        player.pause(); player.seekTo(v)
    }

    fun setTitle(t: String) = _state.update { it.copy(title = t.take(100)) }

    fun resetToSuggested() {
        val c = _state.value.clip ?: return
        _state.update { it.copy(startMs = c.suggestedStartMs, endMs = c.suggestedEndMs) }
        player.seekTo(c.suggestedStartMs)
    }

    // ---- framing ----

    fun togglePreview916() {
        val on = !_state.value.preview916
        _state.update { it.copy(preview916 = on, previewError = null) }
        player.setVideoEffects(if (on) previewEffects() else emptyList())
    }

    /**
     * Mode chips: AUTO resets to the auto rule. Any other mode edits the selected segment when the clip
     * has custom segments; otherwise it applies to the whole clip.
     */
    fun setMode(mode: CropMode) = _state.update { st ->
        when {
            mode == CropMode.AUTO -> st.copy(cropMode = CropMode.AUTO, customSegments = null, selectedSegment = 0)
            st.customSegments != null -> st.copy(customSegments = st.segments.mapIndexed { i, s ->
                if (i == st.selectedSegment) s.copy(mode = mode, zoomRect = s.zoomRect ?: if (mode == CropMode.ZOOM_FIT) CropMath.defaultZoomRect() else null) else s
            })
            else -> st.copy(cropMode = mode, zoomRect = st.zoomRect ?: if (mode == CropMode.ZOOM_FIT) CropMath.defaultZoomRect() else null)
        }
    }

    fun setCaptionStyle(s: CaptionStyle) = _state.update { it.copy(captionStyle = s) }
    fun setHookTeaser(v: Boolean) = _state.update { it.copy(hookTeaser = v) }

    /** Teaser starts a little before the playhead, so the moment under the playhead is its payoff. */
    fun setTeaserHere() = _state.update { it.copy(teaserStartMs = (it.playheadMs - HookTeaser.LEAD_MS).coerceAtLeast(it.startMs), hookTeaser = true) }
    fun resetTeaser() = _state.update { it.copy(teaserStartMs = null) }

    fun previewTeaser() {
        val range = _state.value.teaserRange ?: return
        _state.update { it.copy(teaserPlaying = true) }
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(range.first)
        player.play()
    }

    fun setShowTitle(v: Boolean) = _state.update { it.copy(showTitle = v) }
    fun setTitleStyle(s: TitleStyle) = _state.update { it.copy(titleStyle = s) }
    fun setShowCaptions(v: Boolean) = _state.update { it.copy(showCaptions = v) }

    fun generateCaptions() {
        val c = _state.value.clip ?: return
        save { CaptionWorker.enqueue(app, c.projectId, listOf(c.id)) }
    }

    private fun decodeOptions(s: String?): List<String> =
        s?.let { runCatching { Json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()

    fun setCamLayout(l: CamLayout) = _state.update { it.copy(camLayout = l) }
    fun setPipCorner(c: PipCorner) = _state.update { it.copy(pipCorner = c, camLayout = CamLayout.PIP) }

    fun setPan(pan: Float) = editSelected { it.copy(panX = pan.coerceIn(-1f, 1f)) }

    fun setZoomRect(r: NRect) = editSelected { it.copy(zoomRect = CropMath.clampRect(r)) }

    private fun editSelected(f: (Segment) -> Segment) = _state.update { st ->
        if (st.customSegments != null) {
            st.copy(customSegments = st.segments.mapIndexed { i, s -> if (i == st.selectedSegment) f(s) else s })
        } else {
            val single = f(Segment(st.startMs, st.endMs, st.cropMode, st.panX, st.zoomRect))
            st.copy(panX = single.panX, zoomRect = single.zoomRect)
        }
    }

    fun selectSegment(i: Int) {
        val st = _state.value
        val s = st.segments.getOrNull(i) ?: return
        _state.update { it.copy(selectedSegment = i) }
        seek(s.startMs)
        refreshSourceFrame()
    }

    fun splitAtPlayhead() = _state.update { st ->
        val split = SegmentPlanner.splitAt(st.segments, st.playheadMs)
        st.copy(customSegments = split, selectedSegment = SegmentPlanner.indexAt(split, st.playheadMs))
    }

    fun mergeSelectedWithNext() = _state.update { st ->
        st.copy(customSegments = SegmentPlanner.mergeWithNext(st.segments, st.selectedSegment))
    }

    private fun refreshSourceFrame() {
        val p = _state.value.project ?: return
        val t = player.currentPosition
        viewModelScope.launch { _sourceFrame.value = thumbnails.frameAt(p.sourceUri.toUri(), t, 720, 720) ?: _sourceFrame.value }
    }

    // ---- persistence & export ----

    fun save(then: (() -> Unit)? = null) {
        val st = _state.value
        val c = st.clip ?: return
        viewModelScope.launch {
            // Start from the database row, not the editor's copy: other parts of the app (export,
            // captions, assistant) write fields the editor doesn't own, and a stale copy would erase them.
            val fresh = clipDao.get(c.id) ?: c
            val updated = fresh.copy(
                startMs = st.startMs, endMs = st.endMs, title = st.title.trim().ifBlank { c.title },
                edited = c.edited || st.startMs != c.startMs || st.endMs != c.endMs || st.title != c.title,
                cropMode = st.cropMode.name, panX = st.panX, zoomRect = st.zoomRect?.toCsv(),
                segmentsJson = st.customSegments?.let { SegmentPlanner.encode(SegmentPlanner.normalize(it, st.startMs, st.endMs)) },
                camLayout = st.camLayout.name, pipCorner = st.pipCorner.name,
                showTitle = st.showTitle, titleStyle = st.titleStyle.name, showCaptions = st.showCaptions,
                captionStyle = st.captionStyle.name, hookTeaser = st.hookTeaser, teaserStartMs = st.teaserStartMs,
            )
            clipDao.update(updated)
            _state.update { it.copy(clip = updated, title = updated.title) }
            then?.invoke()
        }
    }

    fun export() {
        val c = _state.value.clip ?: return
        save { ExportWorker.enqueue(app, c.projectId, listOf(c.id)) }
    }

    override fun onCleared() = player.release()

    companion object {
        const val STRIP = 10
        const val PREVIEW_W = 540
        const val PREVIEW_H = 960

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ClipForgeApp
                val route = createSavedStateHandle().toRoute<ClipEditorRoute>()
                val c = app.container
                ClipEditorViewModel(app, route.clipId, c.database.clipDao(), c.database.projectDao(), c.signals, c.thumbnails, c.database.camPresetDao())
            }
        }
    }
}
