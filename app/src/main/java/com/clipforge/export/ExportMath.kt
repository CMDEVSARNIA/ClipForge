package com.clipforge.export

import java.text.Normalizer

object ExportMath {
    const val FADE_IN_US = 80_000L
    const val FADE_OUT_US = 400_000L

    /**
     * Linear gain for the audio frame at [frame] of [totalFrames]: ramps 0→1 over [fadeInFrames] at the
     * start and 1→0 over [fadeOutFrames] at the end. Short clips get both ramps scaled to fit.
     */
    fun fadeGain(frame: Long, totalFrames: Long, fadeInFrames: Long, fadeOutFrames: Long): Float {
        if (totalFrames <= 0) return 1f
        var fin = fadeInFrames
        var fout = fadeOutFrames
        if (fin + fout > totalFrames) {
            val k = totalFrames.toDouble() / (fin + fout)
            fin = (fin * k).toLong(); fout = (fout * k).toLong()
        }
        var g = 1f
        if (fin > 0 && frame < fin) g = minOf(g, frame.toFloat() / fin)
        val fromEnd = totalFrames - 1 - frame
        if (fout > 0 && fromEnd < fout) g = minOf(g, (fromEnd.coerceAtLeast(0)).toFloat() / fout)
        return g.coerceIn(0f, 1f)
    }

    fun slug(title: String, maxLen: Int = 40): String {
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val s = ascii.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return s.take(maxLen).trimEnd('_').ifEmpty { "clip" }
    }

    /** "01_insane_1v4_clutch.mp4" */
    fun fileName(index: Int, title: String): String = "%02d_%s.mp4".format(java.util.Locale.US, index, slug(title))

    data class EncodeSettings(val fps: Int, val bitrate: Int, val avcLevel: Int)

    /** Output fps never exceeds the source (frames can be dropped, not invented). */
    fun outputFps(sourceFps: Float, targetFps: Int): Int {
        if (sourceFps <= 0f) return targetFps
        return if (sourceFps > targetFps + 0.5f) targetFps else minOf(targetFps, kotlin.math.round(sourceFps).toInt().coerceAtLeast(1))
    }

    fun encodeSettings(fps: Int): EncodeSettings = if (fps > 30) {
        EncodeSettings(fps, 16_000_000, 0x2000 /* AVCLevel42 */)
    } else {
        EncodeSettings(fps, 10_000_000, 0x800 /* AVCLevel4 */)
    }

    /**
     * Trailing black-frame trim: steps back from the end while frames are black, up to [maxTrimMs],
     * never leaving less than [minLengthMs].
     */
    fun trimTrailingBlack(startMs: Long, endMs: Long, isBlackAt: (Long) -> Boolean, stepMs: Long = 100, maxTrimMs: Long = 3_000, minLengthMs: Long = 3_000): Long {
        var end = endMs
        val floor = maxOf(startMs + minLengthMs, endMs - maxTrimMs)
        while (end - stepMs >= floor && isBlackAt(end - 50)) end -= stepMs
        return end
    }
}
