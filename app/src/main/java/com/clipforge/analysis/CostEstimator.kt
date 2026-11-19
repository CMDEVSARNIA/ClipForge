package com.clipforge.analysis

import kotlin.math.ceil

/** USD per million tokens. Unknown models fall back to Sonnet pricing so estimates err high. */
object ModelPricing {
    data class Price(val inputPerM: Double, val outputPerM: Double)

    private val table = mapOf(
        "claude-haiku-5-5" to Price(0.10, 0.50),
        "claude-sonnet-5-5" to Price(2.00, 10.00),
        "claude-opus-5-5" to Price(4.00, 20.00),
        "claude-fable-5-2" to Price(8.00, 40.00),
    )

    fun of(model: String): Price = table[model] ?: table.getValue("claude-sonnet-5-5")
    fun isKnown(model: String) = model in table
    fun cost(model: String, inputTokens: Long, outputTokens: Long): Double {
        val p = of(model)
        return (inputTokens * p.inputPerM + outputTokens * p.outputPerM) / 1_000_000.0
    }
}

object CostEstimator {
    const val SAMPLE_INTERVAL_S = 2.0
    const val FRAMES_PER_BATCH = 40
    const val BATCH_OVERLAP_FRAMES = 4
    const val FRAME_WIDTH = 512
    const val MAX_RANKED_CANDIDATES = 40

    // Empirical-ish per-request overheads (prompt text, timestamps, output incl. thinking).
    private const val BATCH_PROMPT_TOKENS = 1_400
    private const val LABEL_TOKENS_PER_FRAME = 12
    private const val BATCH_OUTPUT_TOKENS = 1_500
    private const val RANK_PROMPT_TOKENS = 2_500
    private const val RANK_TEXT_PER_CANDIDATE = 90
    private const val RANK_OUTPUT_PER_CANDIDATE = 130
    private const val RANK_THINKING_TOKENS = 4_000
    private const val GAME_DETECT_IMAGES = 4

    /** Visual tokens ≈ ceil(w/28)·ceil(h/28) for images under the model's resize limits. */
    fun imageTokens(width: Int, height: Int): Int = ceil(width / 28.0).toInt() * ceil(height / 28.0).toInt()

    fun frameSize(displayW: Int, displayH: Int): Pair<Int, Int> {
        if (displayW <= 0 || displayH <= 0) return FRAME_WIDTH to (FRAME_WIDTH * 9 / 16)
        val h = (FRAME_WIDTH.toDouble() * displayH / displayW).toInt().coerceAtLeast(1)
        return FRAME_WIDTH to h
    }

    fun frameCount(durationS: Double): Int = (durationS / SAMPLE_INTERVAL_S).toInt() + 1

    fun batchCount(frames: Int): Int {
        if (frames <= FRAMES_PER_BATCH) return 1
        val step = FRAMES_PER_BATCH - BATCH_OVERLAP_FRAMES
        return 1 + ceil((frames - FRAMES_PER_BATCH).toDouble() / step).toInt()
    }

    /** Frame index ranges per request: [FRAMES_PER_BATCH] frames, overlapping by [BATCH_OVERLAP_FRAMES]. */
    fun planBatches(frames: Int): List<IntRange> {
        if (frames <= 0) return emptyList()
        val step = FRAMES_PER_BATCH - BATCH_OVERLAP_FRAMES
        val out = ArrayList<IntRange>()
        var start = 0
        while (true) {
            val end = minOf(start + FRAMES_PER_BATCH, frames)
            out += start until end
            if (end >= frames) break
            start += step
        }
        return out
    }

    data class Estimate(
        val frames: Int,
        val batches: Int,
        val tokensPerFrame: Int,
        val analysisInputTokens: Long,
        val analysisOutputTokens: Long,
        val rankInputTokens: Long,
        val rankOutputTokens: Long,
        val analysisUsd: Double,
        val rankUsd: Double,
    ) {
        val totalUsd: Double get() = analysisUsd + rankUsd
    }

    fun estimate(durationS: Double, displayW: Int, displayH: Int, analysisModel: String, rankModel: String, detectGame: Boolean): Estimate {
        val (fw, fh) = frameSize(displayW, displayH)
        val perFrame = imageTokens(fw, fh)
        val frames = frameCount(durationS)
        val batches = batchCount(frames)
        val framesSent = frames + (batches - 1) * BATCH_OVERLAP_FRAMES
        var aIn = framesSent.toLong() * (perFrame + LABEL_TOKENS_PER_FRAME) + batches.toLong() * BATCH_PROMPT_TOKENS
        var aOut = batches.toLong() * BATCH_OUTPUT_TOKENS
        if (detectGame) {
            aIn += GAME_DETECT_IMAGES * perFrame + 300
            aOut += 200
        }
        // Expect roughly 1–2 candidates per batch, capped.
        val candidates = (batches * 2).coerceAtMost(MAX_RANKED_CANDIDATES).coerceAtLeast(3)
        val rIn = RANK_PROMPT_TOKENS + candidates.toLong() * (perFrame + RANK_TEXT_PER_CANDIDATE)
        val rOut = candidates.toLong() * RANK_OUTPUT_PER_CANDIDATE + RANK_THINKING_TOKENS
        return Estimate(
            frames = frames,
            batches = batches,
            tokensPerFrame = perFrame,
            analysisInputTokens = aIn,
            analysisOutputTokens = aOut,
            rankInputTokens = rIn,
            rankOutputTokens = rOut,
            analysisUsd = ModelPricing.cost(analysisModel, aIn, aOut),
            rankUsd = ModelPricing.cost(rankModel, rIn, rOut),
        )
    }
}
