package com.clipforge.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Extracts small preview frames. Frames are decoded at sync points and downscaled immediately, so
 * memory stays tiny no matter how long the source is.
 */
class ThumbnailLoader(private val context: Context) {

    // ~4 MB of small thumbnails (key = "uri@ms@maxW").
    private val cache = object : LruCache<String, ImageBitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    suspend fun frameAt(uri: Uri, timeMs: Long, maxWidth: Int, maxHeight: Int): ImageBitmap? {
        val key = "$uri@$timeMs@$maxWidth"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                r.frame(timeMs, maxWidth, maxHeight)?.also { cache.put(key, it) }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
    }

    /** Emits (index, frame) for [count] evenly spaced frames, reusing one retriever. */
    fun strip(uri: Uri, durationMs: Long, count: Int, maxWidth: Int, maxHeight: Int): Flow<Pair<Int, ImageBitmap>> =
        stripRange(uri, 0, durationMs, count, maxWidth, maxHeight)

    /** Like [strip] but over [startMs, endMs), e.g. the window around a clip in the trim editor. */
    fun stripRange(uri: Uri, startMs: Long, endMs: Long, count: Int, maxWidth: Int, maxHeight: Int): Flow<Pair<Int, ImageBitmap>> = flow {
        val span = (endMs - startMs).coerceAtLeast(1)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            for (i in 0 until count) {
                currentCoroutineContext().ensureActive()
                val t = startMs + (span * (i + 0.5) / count).toLong()
                val key = "$uri@$t@$maxWidth"
                val bmp = cache.get(key) ?: r.frame(t, maxWidth, maxHeight)?.also { cache.put(key, it) }
                if (bmp != null) emit(i to bmp)
            }
        } finally {
            runCatching { r.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun MediaMetadataRetriever.frame(timeMs: Long, maxW: Int, maxH: Int): ImageBitmap? {
        val us = timeMs * 1000
        val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            getScaledFrameAtTime(us, option, maxW, maxH)
        } else {
            getFrameAtTime(us, option)?.let { full ->
                val scale = minOf(maxW.toFloat() / full.width, maxH.toFloat() / full.height, 1f)
                val scaled = full.scale((full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1))
                if (scaled !== full) full.recycle()
                scaled
            }
        } ?: return null
        return bmp.asImageBitmap()
    }
}
