package com.clipforge.analysis

import kotlinx.serialization.Serializable

/** A moment proposed by the per-batch frame analysis. Times in seconds. */
data class MomentCandidate(
    val startS: Double,
    val endS: Double,
    /** 0–10 within its batch; not comparable across batches until the ranking pass. */
    val score: Double,
    val title: String,
    val hashtags: List<String>,
    val reason: String,
) {
    val lengthS: Double get() = endS - startS
}

enum class SpecialKind { TEXT_CARD, WIDE_SHOT }

/** Frames the vision pass flagged for Phase 3's auto crop rule (fit-with-blur instead of center crop). */
@Serializable
data class SpecialFrame(val tS: Double, val kind: String)

data class BatchResult(val candidates: List<MomentCandidate>, val specialFrames: List<SpecialFrame>, val droppedItems: Int)

enum class Tier { S, A, B, C, D;
    companion object {
        fun fromScore(score: Int): Tier = when {
            score >= 85 -> S
            score >= 70 -> A
            score >= 55 -> B
            score >= 40 -> C
            else -> D
        }
    }
}

@Serializable
data class SubScores(
    val hook: Int,
    val payoff: Int,
    val clarity: Int,
    val emotion: Int,
    val trendFit: Int,
)

data class RankedMoment(
    val candidateId: Int,
    val tier: Tier,
    val score: Int,
    val sub: SubScores?,
    val title: String,
    val hashtags: List<String>,
    val why: String,
)

data class RankingResult(val ranked: List<RankedMoment>, val trendSummary: String)

/** Scene cut between two sampled frames; refined to a precise time near clip edges. */
data class SceneCut(
    val timeS: Double,
    val strength: Double,
    /** Index of the first sample after the cut (the cut lies between samples index-1 and index). */
    val afterIndex: Int = -1,
)
