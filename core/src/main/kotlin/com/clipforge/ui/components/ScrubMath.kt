package com.clipforge.ui.components

import kotlin.math.abs

/** Pure math for timeline scrubbing, kept separate from Compose so it can be unit-tested. */
object ScrubMath {
    fun positionForX(x: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f || durationMs <= 0) return 0
        return ((x / widthPx).coerceIn(0f, 1f) * durationMs).toLong()
    }

    fun xForPosition(positionMs: Long, widthPx: Float, durationMs: Long): Float {
        if (durationMs <= 0) return 0f
        return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) * widthPx
    }

    /**
     * Fine scrubbing: the further the finger is dragged vertically away from the timeline, the
     * slower the playhead moves. Lets you land on a precise moment in a 60-minute video where one
     * pixel of timeline would otherwise be ~10 seconds.
     *
     * @param distanceOutsidePx how far (px) the pointer is above/below the timeline's edge; 0 when on it.
     * @param dpToPx density multiplier.
     */
    fun fineFactor(distanceOutsidePx: Float, dpToPx: Float): Float {
        val dp = abs(distanceOutsidePx) / dpToPx
        return when {
            dp < 48f -> 1f
            dp < 120f -> 0.25f
            dp < 200f -> 0.05f
            else -> 0.01f
        }
    }

    /** Distance outside a vertical band [0, heightPx]; 0 when inside it. */
    fun distanceOutside(y: Float, heightPx: Float): Float = when {
        y < 0f -> -y
        y > heightPx -> y - heightPx
        else -> 0f
    }

    fun applyDrag(positionMs: Double, dxPx: Float, widthPx: Float, durationMs: Long, factor: Float): Double {
        if (widthPx <= 0f) return positionMs
        return (positionMs + dxPx.toDouble() / widthPx * durationMs * factor).coerceIn(0.0, durationMs.toDouble())
    }

    fun factorLabel(factor: Float): String? = when {
        factor >= 1f -> null
        factor >= 0.25f -> "Fine scrub · ¼×"
        factor >= 0.05f -> "Fine scrub · 1/20×"
        else -> "Fine scrub · 1/100×"
    }
}
