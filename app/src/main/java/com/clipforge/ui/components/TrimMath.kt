package com.clipforge.ui.components

import kotlin.math.abs

/** Pure math for the trim editor (unit-tested). All times in ms. */
object TrimMath {
    const val MIN_CLIP_MS = 1_000L
    const val SNAP_MS = 250L

    fun xFor(ms: Long, windowStart: Long, windowEnd: Long, widthPx: Float): Float {
        val span = (windowEnd - windowStart).coerceAtLeast(1)
        return ((ms - windowStart).toFloat() / span).coerceIn(0f, 1f) * widthPx
    }

    fun msFor(x: Float, windowStart: Long, windowEnd: Long, widthPx: Float): Long {
        if (widthPx <= 0f) return windowStart
        return windowStart + ((x / widthPx).coerceIn(0f, 1f) * (windowEnd - windowStart)).toLong()
    }

    /** Pulls [ms] onto a scene cut when within [SNAP_MS], so edges land exactly on shot boundaries. */
    fun magnet(ms: Long, cutsMs: List<Long>, thresholdMs: Long = SNAP_MS): Long =
        cutsMs.minByOrNull { abs(it - ms) }?.takeIf { abs(it - ms) <= thresholdMs } ?: ms

    fun clampStart(start: Long, end: Long, windowStart: Long): Long =
        start.coerceIn(windowStart, (end - MIN_CLIP_MS).coerceAtLeast(windowStart))

    fun clampEnd(end: Long, start: Long, windowEnd: Long): Long =
        end.coerceIn((start + MIN_CLIP_MS).coerceAtMost(windowEnd), windowEnd)

    /** Editing window: the suggested clip plus [padMs] of context each side, always containing the current edges. */
    fun window(suggestedStart: Long, suggestedEnd: Long, start: Long, end: Long, durationMs: Long, padMs: Long = 20_000): Pair<Long, Long> {
        val ws = (minOf(suggestedStart, start) - padMs).coerceAtLeast(0)
        val we = (maxOf(suggestedEnd, end) + padMs).coerceAtMost(durationMs)
        return ws to we
    }

    enum class Handle { START, END, NONE }

    /** Which handle a touch at [x] grabs: the nearer one within [slopPx], else NONE (seek). */
    fun pick(x: Float, startX: Float, endX: Float, slopPx: Float): Handle {
        val ds = abs(x - startX)
        val de = abs(x - endX)
        return when {
            ds > slopPx && de > slopPx -> Handle.NONE
            ds <= de -> Handle.START
            else -> Handle.END
        }
    }
}
