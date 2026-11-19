package com.clipforge.export

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** Normalized rectangle in image coordinates (0..1, y down). */
@Serializable
data class NRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val cx: Float get() = (l + r) / 2
    val cy: Float get() = (t + b) / 2

    fun toCsv() = "$l,$t,$r,$b"

    companion object {
        val FULL = NRect(0f, 0f, 1f, 1f)
        fun fromCsv(s: String?): NRect? = s?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
            ?.takeIf { it.size == 4 }?.let { NRect(it[0], it[1], it[2], it[3]) }?.takeIf { it.w > 0 && it.h > 0 }
    }
}

enum class CropMode(val label: String) {
    AUTO("Auto"),
    CENTER("Center crop"),
    FIT_BLUR("Fit + blur"),
    ZOOM_FIT("Zoom-fit"),
    PAN("Pan"),
    ;

    companion object {
        fun of(name: String?): CropMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** One sharp source region drawn into a destination region of the output. */
data class Layer(val src: NRect, val dst: NRect, val cornerRadiusPx: Float = 0f)

/**
 * What the GPU effect draws: an optional blurred, darkened background (cover-cropped from [background])
 * and up to two sharp layers on top (Phase 4 uses the second one for the facecam).
 */
data class LayoutSpec(val background: NRect?, val bgDim: Float, val layers: List<Layer>)

/** Mode + parameters, resolved against the real input size on the GL thread. */
data class LayoutRequest(val mode: CropMode, val panX: Float = 0f, val zoomRect: NRect? = null)

object CropMath {
    const val OUT_W = 1080
    const val OUT_H = 1920
    const val BG_DIM = 0.55f

    /**
     * Largest region of a [srcW]×[srcH] frame with the output's aspect ratio.
     * [panX] in -1..1 slides it from the left edge to the right edge (0 = centered); [panY] likewise.
     */
    fun coverRect(srcW: Int, srcH: Int, outW: Int, outH: Int, panX: Float = 0f, panY: Float = 0f): NRect {
        val srcAspect = srcW.toFloat() / srcH
        val outAspect = outW.toFloat() / outH
        return if (srcAspect > outAspect) {
            val w = outAspect / srcAspect
            val l = (1f - w) / 2 * (1f + panX.coerceIn(-1f, 1f))
            NRect(l, 0f, l + w, 1f)
        } else {
            val h = srcAspect / outAspect
            val t = (1f - h) / 2 * (1f + panY.coerceIn(-1f, 1f))
            NRect(0f, t, 1f, t + h)
        }
    }

    /** Destination rect that shows a source region of [regionWpx]×[regionHpx] fully, centered (letterboxed). */
    fun fitDst(regionWpx: Float, regionHpx: Float, outW: Int, outH: Int): NRect {
        val scale = min(outW / regionWpx, outH / regionHpx)
        val w = regionWpx * scale / outW
        val h = regionHpx * scale / outH
        return NRect((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2)
    }

    fun spec(req: LayoutRequest, srcW: Int, srcH: Int, outW: Int = OUT_W, outH: Int = OUT_H): LayoutSpec {
        val full = NRect.FULL
        return when (req.mode) {
            CropMode.CENTER, CropMode.AUTO -> LayoutSpec(null, 1f, listOf(Layer(coverRect(srcW, srcH, outW, outH), full)))
            CropMode.PAN -> LayoutSpec(null, 1f, listOf(Layer(coverRect(srcW, srcH, outW, outH, req.panX), full)))
            CropMode.FIT_BLUR -> LayoutSpec(
                coverRect(srcW, srcH, outW, outH), BG_DIM,
                listOf(Layer(full, fitDst(srcW.toFloat(), srcH.toFloat(), outW, outH))),
            )
            CropMode.ZOOM_FIT -> {
                val z = clampRect(req.zoomRect ?: defaultZoomRect())
                LayoutSpec(
                    coverRect(srcW, srcH, outW, outH), BG_DIM,
                    listOf(Layer(z, fitDst(z.w * srcW, z.h * srcH, outW, outH))),
                )
            }
        }
    }

    /** The middle 60% of the frame: a sensible starting zoom. */
    fun defaultZoomRect() = NRect(0.2f, 0.2f, 0.8f, 0.8f)

    const val MIN_RECT = 0.08f

    fun clampRect(r: NRect): NRect {
        val w = r.w.coerceIn(MIN_RECT, 1f)
        val h = r.h.coerceIn(MIN_RECT, 1f)
        val l = r.l.coerceIn(0f, 1f - w)
        val t = r.t.coerceIn(0f, 1f - h)
        return NRect(l, t, l + w, t + h)
    }

    enum class RectHandle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** Applies a drag (normalized deltas) to a rect via one of its handles, keeping it valid. */
    fun drag(r: NRect, handle: RectHandle, dx: Float, dy: Float): NRect = when (handle) {
        RectHandle.MOVE -> {
            val l = (r.l + dx).coerceIn(0f, 1f - r.w)
            val t = (r.t + dy).coerceIn(0f, 1f - r.h)
            NRect(l, t, l + r.w, t + r.h)
        }
        RectHandle.TOP_LEFT -> NRect(min(r.l + dx, r.r - MIN_RECT).coerceAtLeast(0f), min(r.t + dy, r.b - MIN_RECT).coerceAtLeast(0f), r.r, r.b)
        RectHandle.TOP_RIGHT -> NRect(r.l, min(r.t + dy, r.b - MIN_RECT).coerceAtLeast(0f), max(r.r + dx, r.l + MIN_RECT).coerceAtMost(1f), r.b)
        RectHandle.BOTTOM_LEFT -> NRect(min(r.l + dx, r.r - MIN_RECT).coerceAtLeast(0f), r.t, r.r, max(r.b + dy, r.t + MIN_RECT).coerceAtMost(1f))
        RectHandle.BOTTOM_RIGHT -> NRect(r.l, r.t, max(r.r + dx, r.l + MIN_RECT).coerceAtMost(1f), max(r.b + dy, r.t + MIN_RECT).coerceAtMost(1f))
    }

    /** Which handle a touch at (x, y) (normalized) grabs: a corner within [slop], else MOVE inside, else null. */
    fun pickHandle(r: NRect, x: Float, y: Float, slop: Float): RectHandle? {
        fun near(px: Float, py: Float) = kotlin.math.abs(x - px) <= slop && kotlin.math.abs(y - py) <= slop
        return when {
            near(r.l, r.t) -> RectHandle.TOP_LEFT
            near(r.r, r.t) -> RectHandle.TOP_RIGHT
            near(r.l, r.b) -> RectHandle.BOTTOM_LEFT
            near(r.r, r.b) -> RectHandle.BOTTOM_RIGHT
            x in r.l..r.r && y in r.t..r.b -> RectHandle.MOVE
            else -> null
        }
    }
}
