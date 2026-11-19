package com.clipforge.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotAVideoException(message: String) : Exception(message)

/** Reads container metadata without decoding frames (cheap even for 60-minute files). */
class VideoProbe(private val context: Context) {

    suspend fun probe(uri: Uri): VideoInfo = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var durationMs: Long
        var width: Int
        var height: Int
        val rotation: Int
        val bitrate: Int?
        val hasAudio: Boolean
        var frameCount: Int? = null
        try {
            try {
                retriever.setDataSource(context, uri)
            } catch (e: RuntimeException) {
                throw NotAVideoException("Couldn't open this file as a video.")
            }
            fun meta(key: Int) = retriever.extractMetadata(key)
            if (meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                throw NotAVideoException("This file has no video track.")
            }
            durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            bitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
            hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                frameCount = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            }
        } finally {
            runCatching { retriever.release() }
        }

        var frameRate = 0f
        var mime: String? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val m = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!m.startsWith("video/")) continue
                mime = m
                frameRate = format.frameRateOrZero()
                if (width == 0 && format.containsKey(MediaFormat.KEY_WIDTH)) width = format.getInteger(MediaFormat.KEY_WIDTH)
                if (height == 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (durationMs == 0L && format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
                }
                break
            }
        } catch (_: Exception) {
            // Extractor can fail on exotic containers; metadata from the retriever is still useful.
        } finally {
            extractor.release()
        }
        if (frameRate <= 0f && frameCount != null && durationMs > 0) {
            frameRate = frameCount * 1000f / durationMs
        }
        if (durationMs <= 0L) throw NotAVideoException("Couldn't read the video's duration.")

        val (name, size) = queryNameAndSize(uri)
        VideoInfo(
            durationMs = durationMs,
            width = width,
            height = height,
            rotationDegrees = rotation,
            frameRate = frameRate,
            bitrate = bitrate,
            videoMime = mime,
            hasAudio = hasAudio,
            sizeBytes = size,
            displayName = name,
        )
    }

    private fun MediaFormat.frameRateOrZero(): Float {
        if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return 0f
        // Stored as Integer by most muxers, Float by some; the getter throws on a type mismatch.
        return runCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
            .recoverCatching { getFloat(MediaFormat.KEY_FRAME_RATE) }
            .getOrDefault(0f)
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long?> = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
            name to size
        } ?: (null to null)
    }.getOrDefault(null to null)
}
