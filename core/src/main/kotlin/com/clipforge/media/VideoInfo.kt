package com.clipforge.media

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Metadata for a source video. Pure data + formatting so it is unit-testable on the JVM. */
@Serializable
data class VideoInfo(
    val durationMs: Long,
    /** Encoded (stored) dimensions, before applying [rotationDegrees]. */
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    /** Average frame rate from the container, 0 if unknown. */
    val frameRate: Float,
    val bitrate: Int?,
    val videoMime: String?,
    val hasAudio: Boolean,
    val sizeBytes: Long?,
    val displayName: String?,
) {
    private val rotated: Boolean get() = ((rotationDegrees % 360) + 360) % 180 == 90

    /** Dimensions as the video is displayed, i.e. after rotation. */
    val displayWidth: Int get() = if (rotated) height else width
    val displayHeight: Int get() = if (rotated) width else height

    val aspectRatio: Float get() = if (displayHeight == 0) 16f / 9f else displayWidth.toFloat() / displayHeight

    val resolutionLabel: String get() = VideoFormat.resolutionLabel(displayWidth, displayHeight)
    val frameRateLabel: String get() = VideoFormat.frameRateLabel(frameRate)
}

object VideoFormat {
    /** Max duration we promise to handle (quality bar: up to 60 min). */
    const val MAX_SUPPORTED_DURATION_MS = 60L * 60 * 1000

    /** "1920×1080 · 1080p"; the "p" class uses the short side so portrait videos label correctly. */
    fun resolutionLabel(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "Unknown"
        val shortSide = min(w, h)
        val cls = when {
            shortSide >= 2100 -> "4K"
            shortSide >= 1400 -> "1440p"
            shortSide >= 1000 -> "1080p"
            shortSide >= 700 -> "720p"
            shortSide >= 460 -> "480p"
            else -> "${shortSide}p"
        }
        return "${w}×${h} · $cls"
    }

    private val commonRates = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 90f, 120f, 144f, 240f)

    /** Snaps near-standard rates (e.g. 59.93 → "59.94") and prints whole numbers without decimals. */
    fun frameRateLabel(fps: Float): String {
        if (fps <= 0f || fps.isNaN()) return "Unknown"
        val snapped = commonRates.minBy { abs(it - fps) }.takeIf { abs(it - fps) < 0.1f } ?: fps
        val rounded = (snapped * 100).roundToInt() / 100f
        return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()} fps" else "${trimZeros(rounded)} fps"
    }

    private fun trimZeros(v: Float): String = "%.3f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')

    fun bytesLabel(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "Unknown"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$bytes B" else "%.1f %s".format(java.util.Locale.US, v, units[i])
    }

    fun bitrateLabel(bps: Int?): String {
        if (bps == null || bps <= 0) return "Unknown"
        return if (bps >= 1_000_000) "%.1f Mbps".format(java.util.Locale.US, bps / 1_000_000.0)
        else "${bps / 1000} kbps"
    }

    fun codecLabel(mime: String?): String = when (mime) {
        null -> "Unknown"
        "video/avc" -> "H.264 (AVC)"
        "video/hevc" -> "H.265 (HEVC)"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp8" -> "VP8"
        else -> mime.removePrefix("video/")
    }
}
