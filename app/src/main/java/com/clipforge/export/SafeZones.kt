package com.clipforge.export

/** Pixel box in the 1080×1920 output. */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2
    fun intersects(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
}

/**
 * Where text can go without being covered by TikTok / Shorts / Reels UI:
 * - top ~8%: status bar, tabs ("Following | For You")
 * - bottom ~20%: username, caption, sound
 * - right rail ~170 px from ~42% to ~86% height: like/comment/share buttons
 * Titles go near the top (or on the Split seam, or under a top PiP); captions sit in the lower
 * middle, left of the right rail (and above a bottom PiP).
 */
object SafeZones {
    const val W = 1080f
    const val H = 1920f
    const val SIDE = 70f
    const val TOP_UI = 0.08f * H
    const val BOTTOM_UI = 0.80f * H
    const val RIGHT_RAIL_X = W - 180f
    const val RAIL_TOP = 0.42f * H
    const val RAIL_BOTTOM = 0.86f * H

    val rightRail = Box(RIGHT_RAIL_X, RAIL_TOP, W, RAIL_BOTTOM)

    fun titleBox(cam: CamSpec?, pip: NRect?): Box = when {
        cam?.layout == CamLayout.SPLIT -> {
            val seam = CropMath.SPLIT_CAM_FRACTION * H
            // Straddles the seam but stays above the right rail, which starts at 42% height.
            Box(SIDE, seam - 170f, W - SIDE, minOf(seam + 120f, RAIL_TOP - 10f))
        }
        cam?.layout == CamLayout.PIP && pip != null && pip.t < 0.5f -> {
            val top = pip.b * H + 30f
            Box(SIDE, top, W - SIDE, (top + 300f).coerceAtMost(RAIL_TOP))
        }
        else -> Box(SIDE, 0.10f * H, W - SIDE, 0.26f * H)
    }

    fun captionBox(cam: CamSpec?, pip: NRect?): Box {
        val default = Box(SIDE, 0.60f * H, RIGHT_RAIL_X - 10f, 0.72f * H)
        if (cam?.layout == CamLayout.PIP && pip != null && pip.t >= 0.5f) {
            val bottom = pip.t * H - 30f
            return Box(SIDE, bottom - default.h, RIGHT_RAIL_X - 10f, bottom)
        }
        return default
    }

    /** PiP destination for this cam spec on a typical 16:9 source (for placement only). */
    fun pipRect(cam: CamSpec?, srcW: Int = 1920, srcH: Int = 1080): NRect? =
        if (cam?.layout != CamLayout.PIP) null
        else CropMath.spec(LayoutRequest(CropMode.CENTER, cam = cam), srcW, srcH).layers.getOrNull(1)?.dst
}

enum class WatermarkPosition(val label: String) {
    TL("Top left"), TR("Top right"), BL("Bottom left"), MOVING("Moving");
    companion object { fun of(n: String?) = entries.firstOrNull { it.name == n } ?: MOVING }
}

enum class WatermarkSize(val label: String, val heightPx: Float) {
    S("Small", 44f), M("Medium", 58f), L("Large", 76f);
    companion object { fun of(n: String?) = entries.firstOrNull { it.name == n } ?: M }
}

object WatermarkPlacement {
    /** Moving mode hops to the next free corner this often, so one crop can't remove it. */
    const val HOP_MS = 4_000L
    const val GAP = 14f

    private val order = listOf(WatermarkPosition.TL, WatermarkPosition.TR, WatermarkPosition.BL)

    /** Corners not covered by a PiP box (a top PiP blocks its own corner; a bottom-left PiP blocks BL). */
    fun freeCorners(cam: CamSpec?, pip: NRect?): List<WatermarkPosition> = order.filter { c ->
        if (cam?.layout != CamLayout.PIP || pip == null) true
        else boxFor(c, 200f, 60f).let { b -> !b.intersects(Box(pip.l * SafeZones.W, pip.t * SafeZones.H, pip.r * SafeZones.W, pip.b * SafeZones.H)) }
    }

    fun cornerAt(pos: WatermarkPosition, cam: CamSpec?, pip: NRect?, tMs: Long): WatermarkPosition {
        val free = freeCorners(cam, pip).ifEmpty { listOf(WatermarkPosition.BL) }
        return if (pos == WatermarkPosition.MOVING) free[((tMs / HOP_MS) % free.size).toInt().coerceAtLeast(0)]
        else pos.takeIf { it in free } ?: free.first()
    }

    /** Pixel box for a [w]×[h] watermark in a corner, inside the platform-UI safe area. */
    fun boxFor(corner: WatermarkPosition, w: Float, h: Float): Box {
        val left = SafeZones.SIDE
        val right = SafeZones.W - SafeZones.SIDE
        val top = SafeZones.TOP_UI + GAP
        return when (corner) {
            WatermarkPosition.TR -> Box(right - w, top, right, top + h)
            WatermarkPosition.BL -> Box(left, SafeZones.BOTTOM_UI - GAP - h, left + w, SafeZones.BOTTOM_UI - GAP)
            else -> Box(left, top, left + w, top + h)
        }
    }

    /** Lowest point any top-corner watermark can reach; titles start below it. */
    fun topReserve(size: WatermarkSize) = SafeZones.TOP_UI + GAP + size.heightPx + GAP
}
