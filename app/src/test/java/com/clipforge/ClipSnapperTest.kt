package com.clipforge

import com.clipforge.analysis.ClipSnapper
import com.clipforge.analysis.MomentCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSnapperTest {
    private val cfg = ClipSnapper.Config(cutGuardS = 0.15)
    private val d = 600.0

    @Test fun startSnapsToNearbyCut() {
        val r = ClipSnapper.snap(101.0, 115.0, listOf(99.4), d, cfg)
        assertEquals(99.4, r.startS, 1e-9)
        assertTrue(r.startSnapped)
    }

    @Test fun startDoesNotSnapToFarCut() {
        val r = ClipSnapper.snap(110.0, 125.0, listOf(100.0), d, cfg)
        assertEquals(110.0, r.startS, 1e-9)
        assertFalse(r.startSnapped)
    }

    @Test fun neverIncludesNextScene() {
        // Cut at 112 inside the proposed clip: clip must end before it.
        val r = ClipSnapper.snap(100.0, 120.0, listOf(112.0), d, cfg)
        assertTrue(r.endS < 112.0)
        assertEquals(111.85, r.endS, 1e-9)
    }

    @Test fun endSnapsOntoCutJustAfterProposedEnd() {
        val r = ClipSnapper.snap(100.0, 118.0, listOf(119.5), d, cfg)
        assertEquals(119.35, r.endS, 1e-9)
        assertTrue(r.endSnapped)
    }

    @Test fun cutRightAtStartIsNotTreatedAsNextScene() {
        val r = ClipSnapper.snap(100.0, 115.0, listOf(100.0, 100.3), d, cfg)
        assertEquals(115.0, r.endS, 1e-9)
    }

    @Test fun tooLongIsTrimmedToMax() {
        val r = ClipSnapper.snap(100.0, 160.0, emptyList(), d, cfg)
        assertEquals(30.0, r.endS - r.startS, 1e-9)
    }

    @Test fun tooShortExtendsWithinScene() {
        val r = ClipSnapper.snap(100.0, 104.0, listOf(95.0, 130.0), d, cfg)
        assertEquals(8.0, r.endS - r.startS, 1e-9)
        assertTrue(r.endS < 130.0)
    }

    @Test fun tooShortCannotCrossCuts() {
        // Scene is only 98–103: clip stays inside it even though that's under 8 s.
        val r = ClipSnapper.snap(98.5, 101.0, listOf(98.0, 103.0), d, cfg)
        assertTrue(r.startS >= 98.0)
        assertTrue(r.endS <= 103.0)
    }

    @Test fun clampsToVideoBounds() {
        val r = ClipSnapper.snap(-5.0, 700.0, emptyList(), d, cfg)
        assertEquals(0.0, r.startS, 1e-9)
        assertTrue(r.endS <= d)
    }

    @Test fun startSnappingForwardKeepsEndAfterStart() {
        val r = ClipSnapper.snap(100.0, 101.0, listOf(101.5), d, cfg)
        assertTrue(r.endS > r.startS)
    }

    @Test fun dedupeKeepsHigherScoreOfOverlaps() {
        val a = MomentCandidate(100.0, 120.0, 7.0, "a", emptyList(), "")
        val b = MomentCandidate(105.0, 122.0, 9.0, "b", emptyList(), "")
        val c = MomentCandidate(200.0, 215.0, 5.0, "c", emptyList(), "")
        val out = ClipSnapper.dedupe(listOf(a, b, c))
        assertEquals(listOf("b", "c"), out.map { it.title })
    }
}
