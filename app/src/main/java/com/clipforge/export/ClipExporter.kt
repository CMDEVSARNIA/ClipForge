package com.clipforge.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.clipforge.analysis.FrameSampler
import com.clipforge.data.db.ClipEntity
import com.clipforge.data.db.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExportFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One clip → one 1080×1920 H.264 High / AAC 48 kHz MP4 with faststart, saved to Movies/ClipForge. */
@UnstableApi
class ClipExporter(private val context: Context) {

    data class Result(val uri: Uri, val displayName: String, val warnings: List<String>)

    /** Segments the clip will be rendered with: explicit ones if the user edited them, else the auto rule / clip mode. */
    fun resolveSegments(clip: ClipEntity, cutsMs: List<Long>, specialMs: List<Long>): List<Segment> {
        SegmentPlanner.decode(clip.segmentsJson)?.let { return SegmentPlanner.normalize(it, clip.startMs, clip.endMs) }
        val mode = CropMode.of(clip.cropMode)
        return if (mode == CropMode.AUTO) {
            SegmentPlanner.auto(clip.startMs, clip.endMs, cutsMs, specialMs)
        } else {
            listOf(Segment(clip.startMs, clip.endMs, mode, clip.panX, NRect.fromCsv(clip.zoomRect)))
        }
    }

    suspend fun export(
        project: ProjectEntity,
        clip: ClipEntity,
        segments: List<Segment>,
        fileIndex: Int,
        targetFps: Int,
        cam: CamSpec?,
        watermark: Watermark?,
        onProgress: (Float) -> Unit,
    ): Result {
        val warnings = ArrayList<String>()
        val source = project.sourceUri.toUri()

        // Trailing black frames (fade-to-black, loading screens): trim them off the end.
        val endMs = withContext(Dispatchers.IO) { trimBlackEnd(source, clip.startMs, clip.endMs) }
        if (endMs < clip.endMs) warnings += "Trimmed ${(clip.endMs - endMs)} ms of black frames at the end."
        val segs = SegmentPlanner.normalize(segments, clip.startMs, endMs)

        val fps = ExportMath.outputFps(project.frameRate, targetFps)
        val enc = ExportMath.encodeSettings(fps)
        val text = TextConfig.forClip(clip, clip.startMs, endMs, cam, watermark = watermark).takeIf { !it.isEmpty }
        val items = segs.mapIndexed { i, s -> buildItem(source, s, cam, text, i == 0, i == segs.lastIndex, project.frameRate, fps, project.hasAudio) }

        val tmp = File(context.cacheDir, "export/${clip.id}_${System.currentTimeMillis()}.mp4").also { it.parentFile?.mkdirs() }
        val fixed = File(tmp.path.removeSuffix(".mp4") + "_fs.mp4")
        try {
            runTransformer(items, tmp.path, enc, endMs - clip.startMs, onProgress, warnings)
            // +faststart: the muxer only *attempts* it, so verify and relocate moov if needed.
            val output = withContext(Dispatchers.IO) {
                if (Mp4FastStart.ensure(tmp, fixed)) tmp else fixed
            }
            val name = ExportMath.fileName(fileIndex, clip.title)
            val uri = withContext(Dispatchers.IO) { saveToGallery(output, name) }
            return Result(uri, name, warnings)
        } finally {
            tmp.delete()
            fixed.delete()
        }
    }

    private fun buildItem(
        source: Uri,
        seg: Segment,
        cam: CamSpec?,
        text: TextConfig?,
        first: Boolean,
        last: Boolean,
        sourceFps: Float,
        fps: Int,
        hasAudio: Boolean,
    ): EditedMediaItem {
        val media = MediaItem.Builder()
            .setUri(source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(seg.startMs)
                    .setEndPositionMs(seg.endMs)
                    .build()
            )
            .build()
        val video = ArrayList<Effect>()
        if (sourceFps > fps + 0.5f) video += FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat())
        video += LayoutEffect.constant(CropMath.OUT_W, CropMath.OUT_H, seg.request(cam))
        if (text != null) {
            val clock = SegmentClock(seg.startMs)
            video += OverlayEffect(listOf(TextBitmapOverlay(CropMath.OUT_W, CropMath.OUT_H, { text }, clock::toSourceMs)))
        }

        val audio = ArrayList<AudioProcessor>()
        if (hasAudio) {
            audio += SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }
            val inUs = if (first) ExportMath.FADE_IN_US else 0L
            val outUs = if (last) ExportMath.FADE_OUT_US else 0L
            if (inUs > 0 || outUs > 0) audio += FadeAudioProcessor(seg.lengthMs * 1000, inUs, outUs)
        }
        return EditedMediaItem.Builder(media).setEffects(Effects(audio, video)).build()
    }

    private suspend fun runTransformer(
        items: List<EditedMediaItem>,
        path: String,
        enc: ExportMath.EncodeSettings,
        durationMs: Long,
        onProgress: (Float) -> Unit,
        warnings: MutableList<String>,
    ) = withContext(Dispatchers.Main) {
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(enc.bitrate)
                    .setEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, enc.avcLevel)
                    .setiFrameIntervalSeconds(1f)
                    .build()
            )
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(192_000).build())
            .setEnableFallback(true)
            .build()
        val muxerFactory = InAppMp4Muxer.Factory()
            .setVideoDurationUs(durationMs * 1000)
            .setAttemptStreamableOutputEnabled(true)

        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()

        coroutineScope {
            suspendCancellableCoroutine { cont ->
                lateinit var transformer: Transformer
                transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setPortraitEncodingEnabled(EncoderCaps.portrait1080x1920Supported())
                    .setEncoderFactory(encoderFactory)
                    .setMuxerFactory(muxerFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(ExportFailedException("Export failed: ${exportException.errorCodeName}", exportException))
                        }

                        override fun onFallbackApplied(
                            composition: Composition,
                            originalTransformationRequest: androidx.media3.transformer.TransformationRequest,
                            fallbackTransformationRequest: androidx.media3.transformer.TransformationRequest,
                        ) {
                            warnings += "The encoder needed a fallback configuration on this device."
                        }
                    })
                    .build()
                transformer.start(composition, path)
                val poller = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                        delay(400)
                    }
                }
                // Only one cancellation handler is allowed per continuation.
                cont.invokeOnCancellation {
                    poller.cancel()
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }
            }
            coroutineContext.cancelChildren()
        }
    }

    private fun trimBlackEnd(uri: Uri, startMs: Long, endMs: Long): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            ExportMath.trimTrailingBlack(startMs, endMs, isBlackAt = { t ->
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 128, 128)
                } else {
                    r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                bmp?.let { try { FrameSampler.signatureOf(it).isBlack } finally { it.recycle() } } ?: false
            })
        } catch (_: Exception) {
            endMs
        } finally {
            runCatching { r.release() }
        }
    }

    private fun saveToGallery(file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.TITLE, displayName.removeSuffix(".mp4"))
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ClipForge")
            values.put(MediaStore.Video.Media.IS_PENDING, 1)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ClipForge").also { it.mkdirs() }
            var target = File(dir, displayName)
            var n = 1
            while (target.exists()) target = File(dir, displayName.removeSuffix(".mp4") + " ($n).mp4").also { n++ }
            @Suppress("DEPRECATION")
            values.put(MediaStore.Video.Media.DATA, target.absolutePath)
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: throw ExportFailedException("Couldn't create the gallery entry.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: throw ExportFailedException("Couldn't write to the gallery.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }
}

object EncoderCaps {
    @Volatile private var cached: Boolean? = null

    /**
     * Whether the preferred H.264 encoder takes 1080×1920 directly. If not, Transformer encodes
     * 1920×1080 plus a 90° rotation flag, which every platform player honors.
     */
    fun portrait1080x1920Supported(): Boolean = cached ?: run {
        val ok = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MimeTypes.VIDEO_H264, ignoreCase = true) } }
                .sortedByDescending { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it.isHardwareAccelerated }
                .firstOrNull()
                ?.getCapabilitiesForType(MimeTypes.VIDEO_H264)
                ?.videoCapabilities
                ?.isSizeSupported(1080, 1920) == true
        }.getOrDefault(false)
        cached = ok
        ok
    }
}
