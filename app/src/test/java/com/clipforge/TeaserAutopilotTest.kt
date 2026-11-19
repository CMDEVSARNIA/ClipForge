package com.clipforge

import com.clipforge.autopilot.AutopilotRules
import com.clipforge.data.db.ClipEntity
import com.clipforge.export.HookTeaser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookTeaserTest {
    private val window = 0.5
    /** -40 dB everywhere except a +30 dB spike at [peakS]. */
    private fun loud(peakS: Double, lengthS: Int = 120) = List((lengthS / window).toInt()) { i -> if (i == (peakS / window).toInt()) -10f else -40f }

    @Test fun teasesTheLoudestMomentWithLeadIn() {
        // Clip 60–80 s, explosion at 74 s.
        val r = HookTeaser.range(60_000, 80_000, null, loud(74.0), window)!!
        assertEquals(74_250L - HookTeaser.LEAD_MS, r.first) // window centre 74.25 s minus lead-in
        assertEquals(HookTeaser.LENGTH_MS, r.second - r.first)
        assertTrue("peak inside teaser", 74_250L in r.first..r.second)
    }

    @Test fun ignoresPeaksInTheOpening() {
        // Loudest moment is in the first third; the search starts at 35% of the clip instead.
        val r = HookTeaser.range(60_000, 80_000, null, loud(61.0), window)!!
        assertTrue(r.first >= 60_000 + HookTeaser.SKIP_OPENING_MS)
        assertTrue(r.first >= 60_000 + 20_000 * 35 / 100 - HookTeaser.LEAD_MS)
    }

    @Test fun manualPickWinsAndIsClamped() {
        assertEquals(70_000L to 71_200L, HookTeaser.range(60_000, 80_000, 70_000, loud(74.0), window))
        assertEquals(78_800L to 80_000L, HookTeaser.range(60_000, 80_000, 79_900, loud(74.0), window))
    }

    @Test fun noLoudnessFallsBackToThreeQuarters() {
        val r = HookTeaser.range(0, 20_000, null, emptyList(), window)!!
        assertEquals(15_000L - HookTeaser.LEAD_MS, r.first)
    }

    @Test fun shortClipsGetNoTeaser() = assertNull(HookTeaser.range(0, 5_000, null, emptyList(), window))

    @Test fun neverRunsPastTheEnd() {
        val r = HookTeaser.range(0, 7_000, null, loud(6.9, 10), window)!!
        assertTrue(r.second <= 7_000)
    }
}

class AutopilotRulesTest {
    private fun c(id: Long, rank: Int, tier: String) = ClipEntity(
        id = id, projectId = 1, analysisId = 1, rank = rank, tier = tier, score = 0, startMs = 0, endMs = 10_000,
        suggestedStartMs = 0, suggestedEndMs = 10_000, title = "", hashtags = "", why = "", firstPassNote = "",
        hook = null, payoff = null, clarity = null, emotion = null, trendFit = null, loudnessAboveMedianDb = 0f, thumbMs = 0,
    )
    private val clips = listOf(c(3, 3, "A"), c(1, 1, "S"), c(5, 5, "C"), c(2, 2, "A"), c(4, 4, "B"))

    @Test fun picksTiersAtOrAboveTheMinimumBestFirst() {
        assertEquals(listOf(1L, 2L, 3L), AutopilotRules.pick(clips, "A", 5).map { it.id })
        assertEquals(listOf(1L), AutopilotRules.pick(clips, "S", 5).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), AutopilotRules.pick(clips, "B", 10).map { it.id })
    }

    @Test fun respectsTheCap() = assertEquals(listOf(1L, 2L), AutopilotRules.pick(clips, "B", 2).map { it.id })

    @Test fun nothingQualifies() = assertTrue(AutopilotRules.pick(listOf(c(9, 1, "D")), "A", 5).isEmpty())
}
