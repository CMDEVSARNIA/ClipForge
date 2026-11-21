package com.clipforge.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Snaps clip edges to scene cuts.
 *
 * Rules, in order:
 * 1. Start snaps to the nearest cut within [start - startLookBack, start + startLookAhead], so the
 *    clip opens on the first frame of the shot instead of mid-transition.
 * 2. The clip never crosses into the next scene: the end is capped just before the first cut after
 *    the start. If the proposed end is near that cut, it ends exactly on it.
 * 3. Length is pulled toward [preferredMin, preferredMax]: too long trims the end; too short extends
 *    within the current scene (earlier start, later end) without crossing a cut.
 */
object ClipSnapper {
    data class Config(
        val preferredMinS: Double = 8.0,
        val preferredMaxS: Double = 30.0,
        val startLookBackS: Double = 3.0,
        val startLookAheadS: Double = 2.0,
        val endSnapWindowS: Double = 2.0,
        /** Gap kept before a cut so the next scene's first frame is never included. */
        val cutGuardS: Double = 0.05,
        /** Cuts this close after the start are the same transition the start snapped to. */
        val minSceneS: Double = 0.5,
    )

    data class Snapped(val startS: Double, val endS: Double, val startSnapped: Boolean, val endSnapped: Boolean)

    fun snap(startS: Double, endS: Double, cuts: List<Double>, durationS: Double, cfg: Config = Config()): Snapped {
        val sorted = cuts.filter { it > 0 && it < durationS }.sorted()
        var start = startS.coerceIn(0.0, durationS)
        var end = endS.coerceIn(start, durationS)

        // 1. start → nearest cut in window
        val startCut = sorted
            .filter { it >= startS - cfg.startLookBackS && it <= startS + cfg.startLookAheadS }
            .minByOrNull { abs(it - startS) }
        val startSnapped = startCut != null
        if (startCut != null) start = startCut
        end = endS.coerceIn(start, durationS)

        // 2. never include the next scene
        val prevCut = sorted.lastOrNull { it <= start + 1e-6 } ?: 0.0
        val nextCut = sorted.firstOrNull { it > start + cfg.minSceneS }
        val hardEnd = nextCut?.let { it - cfg.cutGuardS } ?: durationS
        var endSnapped = false
        if (nextCut != null && (end >= hardEnd || abs(end - nextCut) <= cfg.endSnapWindowS)) {
            end = hardEnd
            endSnapped = true
        }
        end = min(end, hardEnd)

        // 3. length preferences
        if (end - start > cfg.preferredMaxS) {
            end = start + cfg.preferredMaxS
            endSnapped = false
        }
        if (end - start < cfg.preferredMinS) {
            val need = cfg.preferredMinS - (end - start)
            // Extend the end first (keeps the hook), then the start, both within the scene.
            val endRoom = hardEnd - end
            val addEnd = min(need, max(0.0, endRoom))
            end += addEnd
            val stillNeed = need - addEnd
            if (stillNeed > 0 && !startSnapped) {
                val startRoom = start - prevCut
                start -= min(stillNeed, max(0.0, startRoom))
            }
        }
        return Snapped(start.coerceAtLeast(0.0), end.coerceAtMost(durationS), startSnapped, endSnapped)
    }

    /** Removes near-duplicates (e.g. the same moment proposed by two overlapping batches), keeping the higher score. */
    fun dedupe(candidates: List<MomentCandidate>, overlapThreshold: Double = 0.5): List<MomentCandidate> {
        val kept = ArrayList<MomentCandidate>()
        for (c in candidates.sortedByDescending { it.score }) {
            val clash = kept.any { k ->
                val overlap = min(k.endS, c.endS) - max(k.startS, c.startS)
                overlap > 0 && overlap / min(k.lengthS, c.lengthS) >= overlapThreshold
            }
            if (!clash) kept += c
        }
        return kept.sortedBy { it.startS }
    }
}
