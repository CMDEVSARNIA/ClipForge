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

enum class CamLayout(val label: String) { NONE("Gameplay only"), SPLIT("Split"), PIP("Picture-in-picture");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: NONE }
}

enum class PipCorner(val label: String) { TL("Top left"), TR("Top right"), BL("Bottom left"), BR("Bottom right");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: TR }
}

/** Facecam region in the source plus how to lay it out. */
data class CamSpec(val rect: NRect, val layout: CamLayout, val corner: PipCorner = PipCorner.TR)

/** Mode + parameters, resolved against the real input size on the GL thread. */
data class LayoutRequest(val mode: CropMode, val panX: Float = 0f, val zoomRect: NRect? = null, val cam: CamSpec? = null)

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

    // ---- Facecam layouts ----
    /** Split: the cam band takes the top 35%, gameplay the rest. */
    const val SPLIT_CAM_FRACTION = 0.35f
    /** PiP box width as a fraction of the output width. */
    const val PIP_WIDTH = 0.38f
    const val PIP_RADIUS_PX = 36f
    /** PiP margins keep the box clear of TikTok/Shorts UI: status/top bar, caption + buttons at the bottom. */
    const val PIP_SIDE_MARGIN = 0.04f
    const val PIP_TOP_MARGIN = 0.09f
    const val PIP_BOTTOM_MARGIN = 0.22f
    /** Very tall or very wide cam boxes are cropped to this aspect range for PiP. */
    const val PIP_MIN_ASPECT = 0.75f
    const val PIP_MAX_ASPECT = 1.78f

    /**
     * Largest sub-rect of [region] (normalized) whose *pixel* aspect is [targetAspect], centered and
     * shifted by [panX] (-1..1) along the cropped axis.
     */
    fun coverWithin(region: NRect, srcW: Int, srcH: Int, targetAspect: Float, panX: Float = 0f): NRect {
        val rw = region.w * srcW
        val rh = region.h * srcH
        return if (rw / rh > targetAspect) {
            val w = rh * targetAspect / srcW
            val l = region.l + (region.w - w) / 2 * (1f + panX.coerceIn(-1f, 1f))
            NRect(l, region.t, l + w, region.b)
        } else {
            val h = rw / targetAspect / srcH
            val t = region.t + (region.h - h) / 2
            NRect(region.l, t, region.r, t + h)
        }
    }

    private fun withCam(base: LayoutSpec, req: LayoutRequest, cam: CamSpec, srcW: Int, srcH: Int, outW: Int, outH: Int): LayoutSpec {
        val camRect = clampRect(cam.rect)
        return when (cam.layout) {
            CamLayout.NONE -> base
            CamLayout.SPLIT -> {
                val camDst = NRect(0f, 0f, 1f, SPLIT_CAM_FRACTION)
                val gameDst = NRect(0f, SPLIT_CAM_FRACTION, 1f, 1f)
                val camSrc = coverWithin(camRect, srcW, srcH, outW / (outH * SPLIT_CAM_FRACTION))
                val region = if (req.mode == CropMode.ZOOM_FIT) clampRect(req.zoomRect ?: defaultZoomRect()) else NRect.FULL
                val pan = if (req.mode == CropMode.PAN) req.panX else 0f
                val gameSrc = coverWithin(region, srcW, srcH, outW / (outH * (1f - SPLIT_CAM_FRACTION)), pan)
                LayoutSpec(null, 1f, listOf(Layer(gameSrc, gameDst), Layer(camSrc, camDst)))
            }
            CamLayout.PIP -> {
                val rawAspect = camRect.w * srcW / (camRect.h * srcH)
                val aspect = rawAspect.coerceIn(PIP_MIN_ASPECT, PIP_MAX_ASPECT)
                val camSrc = if (aspect == rawAspect) camRect else coverWithin(camRect, srcW, srcH, aspect)
                val w = PIP_WIDTH
                val h = w * outW / aspect / outH
                val l = if (cam.corner == PipCorner.TL || cam.corner == PipCorner.BL) PIP_SIDE_MARGIN else 1f - PIP_SIDE_MARGIN - w
                val t = if (cam.corner == PipCorner.TL || cam.corner == PipCorner.TR) PIP_TOP_MARGIN else 1f - PIP_BOTTOM_MARGIN - h
                val pip = Layer(camSrc, NRect(l, t, l + w, t + h), PIP_RADIUS_PX)
                base.copy(layers = base.layers.take(1) + pip)
            }
        }
    }

    fun spec(req: LayoutRequest, srcW: Int, srcH: Int, outW: Int = OUT_W, outH: Int = OUT_H): LayoutSpec {
        val base = baseSpec(req, srcW, srcH, outW, outH)
        return req.cam?.let { withCam(base, req, it, srcW, srcH, outW, outH) } ?: base
    }

    private fun baseSpec(req: LayoutRequest, srcW: Int, srcH: Int, outW: Int, outH: Int): LayoutSpec {
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
