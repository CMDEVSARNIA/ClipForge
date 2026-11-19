package com.clipforge

import com.clipforge.analysis.InvalidModelOutput
import com.clipforge.analysis.MomentParser
import com.clipforge.analysis.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentParserTest {
    private val ok = """
        {"clips":[
          {"start_s":100.0,"end_s":118.5,"score":8.5,"title":"1v4 clutch","hashtags":["clutch","#Valorant","clutch"],"reason":"loud reaction"},
          {"start_s":130,"end_s":131,"score":6,"title":"too short","hashtags":[],"reason":""},
          {"start_s":140,"end_s":120,"score":6,"title":"backwards","hashtags":[],"reason":""},
          {"start_s":150,"end_s":170,"score":11,"title":"score out of range","hashtags":[],"reason":""},
          {"start_s":150,"end_s":170,"score":5,"title":"  ","hashtags":[],"reason":""}
        ],
        "special_frames":[{"t_s":104,"kind":"text_card"},{"t_s":104,"kind":"explosion"},{"t_s":999,"kind":"wide_shot"}]}
    """.trimIndent()

    @Test fun parsesValidItemsAndDropsBadOnes() {
        val r = MomentParser.parseBatch(ok, batchStartS = 80.0, batchEndS = 160.0, videoDurationS = 600.0)
        assertEquals(1, r.candidates.size)
        assertEquals(4, r.droppedItems)
        val c = r.candidates[0]
        assertEquals(100.0, c.startS, 0.0)
        assertEquals("1v4 clutch", c.title)
        assertEquals(listOf("#clutch", "#Valorant"), c.hashtags) // normalized + deduped
        assertEquals(1, r.specialFrames.size)
        assertEquals("text_card", r.specialFrames[0].kind)
    }

    @Test fun acceptsBareArray() {
        val r = MomentParser.parseBatch("""[{"start_s":10,"end_s":20,"score":7,"title":"t","hashtags":[],"reason":"r"}]""", 0.0, 80.0, 600.0)
        assertEquals(1, r.candidates.size)
    }

    @Test fun toleratesCodeFence() {
        val r = MomentParser.parseBatch("```json\n{\"clips\":[],\"special_frames\":[]}\n```", 0.0, 80.0, 600.0)
        assertEquals(0, r.candidates.size)
    }

    @Test fun clipsFarOutsideBatchAreDropped() {
        val r = MomentParser.parseBatch("""{"clips":[{"start_s":300,"end_s":320,"score":7,"title":"t","hashtags":[],"reason":""}],"special_frames":[]}""", 0.0, 80.0, 600.0)
        assertEquals(0, r.candidates.size)
    }

    @Test(expected = InvalidModelOutput::class) fun truncatedJsonThrows() {
        MomentParser.parseBatch("""{"clips":[{"start_s":10,"end_s":2""", 0.0, 80.0, 600.0)
    }

    @Test(expected = InvalidModelOutput::class) fun missingClipsThrows() {
        MomentParser.parseBatch("""{"moments":[]}""", 0.0, 80.0, 600.0)
    }

    @Test(expected = InvalidModelOutput::class) fun emptyThrows() {
        MomentParser.parseBatch("   ", 0.0, 80.0, 600.0)
    }

    @Test fun stringNumbersAreRejected() {
        val r = MomentParser.parseBatch("""{"clips":[{"start_s":"10","end_s":20,"score":7,"title":"t","hashtags":[],"reason":""}],"special_frames":[]}""", 0.0, 80.0, 600.0)
        assertEquals(0, r.candidates.size)
    }

    private fun rankItem(id: Int, score: Int, tier: String = "A") =
        """{"id":$id,"tier":"$tier","score":$score,"subscores":{"hook":8,"payoff":12,"clarity":7,"emotion":6,"trend_fit":5},"title":"T$id","hashtags":["a"],"why":"w"}"""

    @Test fun rankingSortsClampsAndIgnoresUnknownOrDuplicateIds() {
        val text = """{"trend_summary":"Clutches are hot","ranking":[${rankItem(2, 60, "B")},${rankItem(1, 91, "S")},${rankItem(1, 10)},${rankItem(9, 99)}]}"""
        val r = MomentParser.parseRanking(text, setOf(1, 2))
        assertEquals(listOf(1, 2), r.ranked.map { it.candidateId })
        assertEquals(Tier.S, r.ranked[0].tier)
        assertEquals(10, r.ranked[0].sub!!.payoff) // clamped from 12
        assertEquals("Clutches are hot", r.trendSummary)
    }

    @Test(expected = InvalidModelOutput::class) fun rankingCoveringTooFewThrows() {
        MomentParser.parseRanking("""{"trend_summary":"","ranking":[${rankItem(1, 50)}]}""", setOf(1, 2, 3, 4, 5))
    }

    @Test fun tierFromScoreBoundaries() {
        assertEquals(Tier.S, Tier.fromScore(85))
        assertEquals(Tier.A, Tier.fromScore(84))
        assertEquals(Tier.B, Tier.fromScore(55))
        assertEquals(Tier.C, Tier.fromScore(40))
        assertEquals(Tier.D, Tier.fromScore(39))
    }

    @Test fun gameParsing() {
        assertEquals("Valorant" to 0.9, MomentParser.parseGame("""{"game":"Valorant","confidence":0.9}"""))
        assertNull(MomentParser.parseGame("""{"game":"unknown","confidence":0.1}"""))
        assertNull(MomentParser.parseGame("not json"))
        assertTrue(MomentParser.normalizeHashtags(null).isEmpty())
    }
}
