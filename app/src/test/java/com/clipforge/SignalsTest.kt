package com.clipforge

import com.clipforge.analysis.CostEstimator
import com.clipforge.analysis.FrameSignature
import com.clipforge.analysis.LoudnessMath
import com.clipforge.analysis.ModelPricing
import com.clipforge.analysis.SceneCutDetector
import com.clipforge.analysis.TrendBrief
import com.clipforge.analysis.TrendSource
import com.clipforge.analysis.TrendVideo
import com.clipforge.analysis.TrendsResponse
import com.clipforge.ui.components.TrimMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneCutDetectorTest {
    private fun solid(argb: Int, noise: Int = 0, seed: Int = 0): FrameSignature {
        val r = java.util.Random(seed.toLong())
        return FrameSignature.fromArgb(IntArray(FrameSignature.W * FrameSignature.H) {
            val n = if (noise == 0) 0 else r.nextInt(noise)
            val c = ((argb shr 16 and 0xFF) + n).coerceAtMost(255)
            (0xFF shl 24) or (c shl 16) or ((argb shr 8 and 0xFF) shl 8) or (argb and 0xFF)
        })
    }

    @Test fun detectsHardCutBetweenScenes() {
        val sigs = List(6) { solid(0x203040, noise = 20, seed = it) } + List(6) { solid(0xE0C010, noise = 20, seed = 100 + it) }
        val times = DoubleArray(sigs.size) { it * 2.0 }
        val cuts = SceneCutDetector.detect(times, sigs)
        assertEquals(1, cuts.size)
        assertEquals(11.0, cuts[0].timeS, 1e-9)
        assertEquals(6, cuts[0].afterIndex)
    }

    @Test fun noCutsInStableFootage() {
        val sigs = List(10) { solid(0x406080, noise = 30, seed = it) }
        assertTrue(SceneCutDetector.detect(DoubleArray(10) { it * 2.0 }, sigs).isEmpty())
    }

    @Test fun identicalFramesHaveZeroDistance() {
        assertEquals(0.0, solid(0x112233).distance(solid(0x112233)), 1e-12)
    }

    @Test fun blackDetection() {
        assertTrue(solid(0x000000).isBlack)
    }
}

class LoudnessMathTest {
    @Test fun rmsOfFullScaleIsZeroDb() = assertEquals(0f, LoudnessMath.rmsDb(100.0, 100), 1e-4f)

    @Test fun silenceFloors() = assertEquals(-96f, LoudnessMath.rmsDb(0.0, 0))

    @Test fun findsSeparatedPeaksAboveMedian() {
        val db = FloatArray(200) { -40f }
        db[20] = -20f; db[22] = -22f // close together: only the louder survives
        db[100] = -15f
        db[150] = -38f // not loud enough
        val peaks = LoudnessMath.findPeaks(db)
        assertEquals(listOf(10.25, 50.25), peaks.map { it.timeS })
        assertEquals(25f, peaks[1].aboveMedianDb, 1e-4f)
    }

    @Test fun maxAboveMedianInRange() {
        val db = FloatArray(100) { -40f }.also { it[40] = -10f }
        assertEquals(30f, LoudnessMath.maxAboveMedian(db, 19.0, 21.0), 1e-4f)
        assertEquals(0f, LoudnessMath.maxAboveMedian(db, 0.0, 5.0), 1e-4f)
    }
}

class CostEstimatorTest {
    @Test fun imageTokensMatchDocsTable() {
        assertEquals(64, CostEstimator.imageTokens(200, 200))
        assertEquals(1296, CostEstimator.imageTokens(1000, 1000))
        assertEquals(209, CostEstimator.imageTokens(512, 288))
    }

    @Test fun batchPlanCoversAllFramesWithOverlap() {
        val plan = CostEstimator.planBatches(100)
        assertEquals(CostEstimator.batchCount(100), plan.size)
        assertEquals(0, plan.first().first)
        assertEquals(99, plan.last().last)
        assertTrue(plan.all { it.count() <= CostEstimator.FRAMES_PER_BATCH })
        assertEquals(36, plan[1].first) // 4-frame overlap
        assertEquals(1, CostEstimator.planBatches(10).size)
    }

    @Test fun sixtyMinuteEstimateIsCheapOnHaiku() {
        val e = CostEstimator.estimate(3600.0, 1920, 1080, "claude-haiku-5-5", "claude-sonnet-5-5", detectGame = true)
        assertEquals(1801, e.frames)
        assertEquals(50, e.batches)
        assertTrue("analysis ${e.analysisUsd}", e.analysisUsd in 0.05..0.12)
        assertTrue("rank ${e.rankUsd}", e.rankUsd in 0.03..0.25)
    }

    @Test fun unknownModelsPriceAsSonnet() {
        assertEquals(ModelPricing.of("claude-sonnet-5-5"), ModelPricing.of("some-future-model"))
    }
}

class TrendBriefTest {
    @Test fun youtubeBriefListsTopVideos() {
        val r = TrendsResponse("Fortnite", "youtube", 7, "now", listOf(TrendVideo("Insane 1v5", "ch", 2_300_000, duration_s = 31.0)))
        val b = TrendBrief.fromYoutube(r)
        assertEquals(TrendSource.YOUTUBE, b.source)
        assertTrue(b.fullText.contains("\"Insane 1v5\" - 2.3M views, 31 s"))
    }

    @Test fun emptyYoutubeFallsBackToBuiltIn() {
        assertEquals(TrendSource.BUILT_IN, TrendBrief.fromYoutube(TrendsResponse("X", "youtube", 7, "now", emptyList())).source)
    }
}

class TrimMathTest {
    @Test fun magnetSnapsWithinThreshold() {
        assertEquals(10_000L, TrimMath.magnet(10_200, listOf(10_000, 20_000)))
        assertEquals(10_400L, TrimMath.magnet(10_400, listOf(10_000)))
    }

    @Test fun clampsKeepMinimumLength() {
        assertEquals(9_000L, TrimMath.clampStart(9_500, 10_000, 0))
        assertEquals(11_000L, TrimMath.clampEnd(10_200, 10_000, 60_000))
    }

    @Test fun pickNearestHandleOrSeek() {
        assertEquals(TrimMath.Handle.START, TrimMath.pick(105f, 100f, 300f, 30f))
        assertEquals(TrimMath.Handle.END, TrimMath.pick(290f, 100f, 300f, 30f))
        assertEquals(TrimMath.Handle.NONE, TrimMath.pick(200f, 100f, 300f, 30f))
    }

    @Test fun windowContainsEdgesAndClamps() {
        assertEquals(0L to 55_000L, TrimMath.window(5_000, 20_000, 5_000, 35_000, 100_000))
    }
}
