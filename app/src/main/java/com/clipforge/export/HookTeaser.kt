package com.clipforge.export

/**
 * Hook teaser: a short flash of the clip's payoff, shown before the clip starts ("wait for it…").
 * The payoff is the loudest moment in the back two-thirds of the clip (reactions, explosions), or
 * a point the user picked. Clips too short to need a teaser get none.
 */
object HookTeaser {
    const val LENGTH_MS = 1_200L
    /** The teaser starts this long before the peak, so the payoff lands inside it. */
    const val LEAD_MS = 700L
    const val MIN_CLIP_MS = 6_000L
    /** Never tease something from the first seconds: the clip shows that right away anyway. */
    const val SKIP_OPENING_MS = 2_000L
    const val LABEL = "Wait for it…"

    /** @return (start, end) source ms of the teaser, or null when the clip is too short. */
    fun range(
        clipStartMs: Long,
        clipEndMs: Long,
        manualStartMs: Long?,
        loudnessDb: List<Float>,
        loudnessWindowS: Double,
    ): Pair<Long, Long>? {
        val len = clipEndMs - clipStartMs
        if (len < MIN_CLIP_MS) return null
        val latestStart = clipEndMs - LENGTH_MS
        if (manualStartMs != null && manualStartMs in clipStartMs..clipEndMs) {
            val s = manualStartMs.coerceIn(clipStartMs, latestStart)
            return s to s + LENGTH_MS
        }
        val from = clipStartMs + len * 35 / 100
        val to = clipEndMs - 300
        val peak = loudestMs(loudnessDb, loudnessWindowS, from, to) ?: (clipStartMs + len * 75 / 100)
        val s = (peak - LEAD_MS).coerceIn(clipStartMs + SKIP_OPENING_MS, latestStart)
        return s to s + LENGTH_MS
    }

    fun loudestMs(db: List<Float>, windowS: Double, fromMs: Long, toMs: Long): Long? {
        if (db.isEmpty() || windowS <= 0 || toMs <= fromMs) return null
        val wMs = windowS * 1000
        val a = (fromMs / wMs).toInt().coerceIn(0, db.size - 1)
        val b = (toMs / wMs).toInt().coerceIn(a, db.size - 1)
        val best = (a..b).maxByOrNull { db[it] } ?: return null
        return (best * wMs + wMs / 2).toLong()
    }
}
