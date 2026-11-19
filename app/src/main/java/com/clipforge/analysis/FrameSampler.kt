package com.clipforge.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Samples one frame every [CostEstimator.SAMPLE_INTERVAL_S], writes each as a 512 px-wide JPEG to
 * [outDir] and keeps only a tiny [FrameSignature] in memory. Frames already on disk from an
 * interrupted run are reused.
 */
class FrameSampler(private val context: Context) {

    data class Sample(val timeS: Double, val file: File, val sig: FrameSignature)

    suspend fun sample(
        uri: Uri,
        durationMs: Long,
        displayW: Int,
        displayH: Int,
        outDir: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<Sample> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val (fw, fh) = CostEstimator.frameSize(displayW, displayH)
        val durationS = durationMs / 1000.0
        val times = generateSequence(0.0) { it + CostEstimator.SAMPLE_INTERVAL_S }
            .takeWhile { it < durationS - 0.1 }
            .toList()
            .ifEmpty { listOf(0.0) }

        val retriever = MediaMetadataRetriever()
        val syncProbe = SyncProbe(context, uri)
        val samples = ArrayList<Sample>(times.size)
        try {
            retriever.setDataSource(context, uri)
            times.forEachIndexed { i, t ->
                ensureActive()
                val file = File(outDir, "f_${(t * 1000).toLong()}.jpg")
                val sig = if (file.length() > 0) signatureFromJpeg(file) else null
                if (sig != null) {
                    samples += Sample(t, file, sig)
                } else {
                    // Keyframe seeks are much faster; use one when a keyframe sits right at t.
                    val syncT = syncProbe.previousSyncS(t)
                    val useSync = syncT != null && t - syncT <= 0.3
                    val option = if (useSync) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
                    val bmp = retriever.scaledFrame((t * 1_000_000).toLong(), option, fw, fh)
                    if (bmp != null) {
                        try {
                            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 72, it) }
                            samples += Sample(t, file, signatureOf(bmp))
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
                onProgress(i + 1, times.size)
            }
        } finally {
            runCatching { retriever.release() }
            syncProbe.release()
        }
        samples
    }

    private fun MediaMetadataRetriever.scaledFrame(us: Long, option: Int, w: Int, h: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            getScaledFrameAtTime(us, option, w, h)
        } else {
            getFrameAtTime(us, option)?.let { full ->
                val s = full.scale(w, h)
                if (s !== full) full.recycle()
                s
            }
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        fun signatureOf(bmp: Bitmap): FrameSignature {
            val tiny = bmp.scale(FrameSignature.W, FrameSignature.H)
            val px = IntArray(FrameSignature.W * FrameSignature.H)
            tiny.getPixels(px, 0, FrameSignature.W, 0, 0, FrameSignature.W, FrameSignature.H)
            if (tiny !== bmp) tiny.recycle()
            return FrameSignature.fromArgb(px)
        }

        fun signatureFromJpeg(file: File): FrameSignature? {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
            return try { signatureOf(bmp) } finally { bmp.recycle() }
        }
    }
}

/** Finds the keyframe at or before a time without decoding anything. */
internal class SyncProbe(context: Context, uri: Uri) {
    private val extractor: MediaExtractor? = try {
        MediaExtractor().also { ex ->
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            ex.selectTrack(track)
        }
    } catch (_: Exception) {
        null
    }

    fun previousSyncS(t: Double): Double? {
        val ex = extractor ?: return null
        return try {
            ex.seekTo((t * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            ex.sampleTime.takeIf { it >= 0 }?.let { it / 1_000_000.0 }
        } catch (_: Exception) {
            null
        }
    }

    fun release() {
        runCatching { extractor?.release() }
    }
}

/**
 * Narrows a coarse cut (between two samples 2 s apart) to ~0.125 s with a few exact frame grabs,
 * by binary search on which side of the midpoint the picture changes.
 */
class CutRefiner(private val context: Context) {
    suspend fun refine(uri: Uri, intervals: List<Triple<Double, Double, Pair<FrameSignature, FrameSignature>>>): List<Double> =
        withContext(Dispatchers.IO) {
            if (intervals.isEmpty()) return@withContext emptyList()
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                intervals.map { (t0, t1, sigs) ->
                    ensureActive()
                    var lo = t0
                    var hi = t1
                    var sLo = sigs.first
                    var sHi = sigs.second
                    repeat(4) {
                        val mid = (lo + hi) / 2
                        val sMid = frameSig(r, mid) ?: return@repeat
                        if (sLo.distance(sMid) > sMid.distance(sHi)) {
                            hi = mid; sHi = sMid
                        } else {
                            lo = mid; sLo = sMid
                        }
                    }
                    hi // first time that shows the new scene
                }
            } finally {
                runCatching { r.release() }
            }
        }

    private fun frameSig(r: MediaMetadataRetriever, tS: Double): FrameSignature? = try {
        val us = (tS * 1_000_000).toLong()
        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, 128, 128)
        } else {
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
        }
        bmp?.let { try { FrameSampler.signatureOf(it) } finally { it.recycle() } }
    } catch (_: Exception) {
        null
    }
}
