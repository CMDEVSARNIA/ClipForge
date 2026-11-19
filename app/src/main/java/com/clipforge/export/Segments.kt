package com.clipforge.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A stretch of a clip rendered with one crop mode. Times are absolute source times in ms. */
@Serializable
data class Segment(
    val startMs: Long,
    val endMs: Long,
    val mode: CropMode,
    val panX: Float = 0f,
    val zoomRect: NRect? = null,
) {
    val lengthMs: Long get() = endMs - startMs
    fun request(cam: CamSpec? = null) = LayoutRequest(mode, panX, zoomRect, cam)
}

object SegmentPlanner {
    const val MIN_SEGMENT_MS = 500L
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(segments: List<Segment>): String = json.encodeToString(ListSerializer(Segment.serializer()), segments)
    fun decode(s: String?): List<Segment>? = s?.let { runCatching { json.decodeFromString(ListSerializer(Segment.serializer()), it) }.getOrNull() }

    /**
     * Auto rule: frames the vision pass flagged (full-screen text card, wide establishing shot) get
     * Fit-with-blur; everything else Center crop. A flagged sample covers ±half a sample interval;
     * neighbouring flags merge; edges snap to scene cuts within [snapMs].
     */
    fun auto(
        clipStartMs: Long,
        clipEndMs: Long,
        cutsMs: List<Long>,
        specialMs: List<Long>,
        sampleIntervalMs: Long = 2_000,
        snapMs: Long = 1_000,
    ): List<Segment> {
        val half = sampleIntervalMs / 2
        val intervals = specialMs.sorted()
            .map { (it - half) to (it + half) }
            .fold(mutableListOf<Pair<Long, Long>>()) { acc, iv ->
                val last = acc.lastOrNull()
                if (last != null && iv.first <= last.second) acc[acc.lastIndex] = last.first to maxOf(last.second, iv.second) else acc += iv
                acc
            }
            .map { (a, b) -> snap(a, cutsMs, snapMs) to snap(b, cutsMs, snapMs) }
            .map { (a, b) -> a.coerceIn(clipStartMs, clipEndMs) to b.coerceIn(clipStartMs, clipEndMs) }
            .filter { (a, b) -> b - a >= MIN_SEGMENT_MS }

        val out = ArrayList<Segment>()
        var cursor = clipStartMs
        for ((a, b) in intervals) {
            if (a > cursor) out += Segment(cursor, a, CropMode.CENTER)
            out += Segment(maxOf(a, cursor), b, CropMode.FIT_BLUR)
            cursor = b
        }
        if (cursor < clipEndMs) out += Segment(cursor, clipEndMs, CropMode.CENTER)
        return normalize(out, clipStartMs, clipEndMs)
    }

    private fun snap(t: Long, cuts: List<Long>, within: Long): Long =
        cuts.minByOrNull { kotlin.math.abs(it - t) }?.takeIf { kotlin.math.abs(it - t) <= within } ?: t

    /**
     * Makes segments tile [clipStartMs, clipEndMs] exactly: clamps to the clip, stretches the first and
     * last to the edges (after a trim), folds segments shorter than [MIN_SEGMENT_MS] into a neighbour,
     * and merges neighbours with identical settings.
     */
    fun normalize(segments: List<Segment>, clipStartMs: Long, clipEndMs: Long): List<Segment> {
        if (clipEndMs <= clipStartMs) return emptyList()
        val inside = segments.sortedBy { it.startMs }
            .map { it.copy(startMs = it.startMs.coerceIn(clipStartMs, clipEndMs), endMs = it.endMs.coerceIn(clipStartMs, clipEndMs)) }
            .filter { it.endMs > it.startMs }
        if (inside.isEmpty()) return listOf(Segment(clipStartMs, clipEndMs, CropMode.CENTER))

        // Close gaps/overlaps: each segment starts where the previous one ends.
        val tiled = ArrayList<Segment>()
        inside.forEachIndexed { i, s ->
            val start = if (i == 0) clipStartMs else tiled.last().endMs
            val end = if (i == inside.lastIndex) clipEndMs else maxOf(start, inside[i + 1].startMs)
            if (end > start) tiled += s.copy(startMs = start, endMs = end)
        }
        // Fold tiny segments into the previous (or next) one.
        val folded = ArrayList<Segment>()
        for (s in tiled) {
            if (s.lengthMs < MIN_SEGMENT_MS && folded.isNotEmpty()) {
                folded[folded.lastIndex] = folded.last().copy(endMs = s.endMs)
            } else folded += s
        }
        if (folded.size > 1 && folded.first().lengthMs < MIN_SEGMENT_MS) {
            folded[1] = folded[1].copy(startMs = folded[0].startMs)
            folded.removeAt(0)
        }
        // Merge identical neighbours.
        val merged = ArrayList<Segment>()
        for (s in folded) {
            val last = merged.lastOrNull()
            if (last != null && last.mode == s.mode && last.panX == s.panX && last.zoomRect == s.zoomRect) {
                merged[merged.lastIndex] = last.copy(endMs = s.endMs)
            } else merged += s
        }
        return merged
    }

    fun splitAt(segments: List<Segment>, tMs: Long): List<Segment> = segments.flatMap { s ->
        if (tMs - s.startMs >= MIN_SEGMENT_MS && s.endMs - tMs >= MIN_SEGMENT_MS) listOf(s.copy(endMs = tMs), s.copy(startMs = tMs)) else listOf(s)
    }

    fun indexAt(segments: List<Segment>, tMs: Long): Int =
        segments.indexOfFirst { tMs >= it.startMs && tMs < it.endMs }.takeIf { it >= 0 } ?: (segments.size - 1)

    /** Removes the boundary after segment [index]; the merged segment keeps the first one's settings. */
    fun mergeWithNext(segments: List<Segment>, index: Int): List<Segment> {
        if (index !in 0 until segments.lastIndex) return segments
        val m = segments[index].copy(endMs = segments[index + 1].endMs)
        return segments.take(index) + m + segments.drop(index + 2)
    }
}
