package com.clipforge

import com.clipforge.analysis.InvalidModelOutput
import com.clipforge.captions.CaptionMath
import com.clipforge.captions.CaptionWord
import com.clipforge.captions.CaptionWriter
import com.clipforge.captions.RawWord
import com.clipforge.export.CamLayout
import com.clipforge.export.CamSpec
import com.clipforge.export.NRect
import com.clipforge.export.PipCorner
import com.clipforge.export.SafeZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionMathTest {
    /** Real whisper.cpp output (tiny q5_1) of samples/jfk.wav through the app's native transcription code. */
    private val jfk = CaptionMath.parseWhisper(javaClass.getResource("/whisper_jfk.json")!!.readBytes())

    @Test fun realSpeechPassesTheGate() {
        val s = CaptionMath.filterSpeech(jfk.segments)
        assertTrue(s.reason, s.isSpeech)
        assertEquals("en", jfk.lang)
        assertFalse(s.words.any { it.t.isBlank() }) // the empty leading segment is dropped
        assertEquals("And", s.words.first().t.trim())
    }

    @Test fun capsWordsThatSwallowedAPause() {
        val s = CaptionMath.filterSpeech(jfk.segments)
        val words = CaptionMath.toCaptionWords(s.words, clipStartMs = 60_000)
        val americans = words.first { it.text == "Americans" }
        assertEquals(60_000 + 3_280L, americans.endMs)
        assertEquals(1_200L, americans.endMs - americans.startMs) // was 1.77 s incl. silence
        assertTrue(words.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }

    @Test fun hallucinationsAndNonSpeechAreRejected() {
        fun w(t: String, p: Float = 0.9f) = RawWord(t, 0, 500, p, 0.1f)
        assertFalse(CaptionMath.filterSpeech(listOf(w(" Thank"), w(" you."))).isSpeech)
        assertFalse(CaptionMath.filterSpeech(listOf(w(" [Music]"), w(" ♪"))).isSpeech)
        assertFalse(CaptionMath.filterSpeech(listOf(w(" go", 0.2f), w(" go", 0.3f), w(" go", 0.2f))).isSpeech)
        assertFalse(CaptionMath.filterSpeech(emptyList()).isSpeech)
        assertTrue(CaptionMath.filterSpeech(listOf(w(" push"), w(" B"), w(" now!"))).isSpeech)
    }

    @Test fun resampleHalvesLength() {
        val out = CaptionMath.resample(FloatArray(48_000) { it / 48_000f }, 48_000, 16_000)
        assertEquals(16_000, out.size)
        assertEquals(0.5f, out[8_000], 1e-3f)
        assertTrue(CaptionMath.resample(FloatArray(10), 16_000, 16_000).size == 10)
    }

    private fun cw(text: String, s: Long, e: Long) = CaptionWord(text, s, e)

    @Test fun chunksByCountPunctuationAndPauses() {
        val words = listOf(cw("oh", 0, 200), cw("my", 200, 400), cw("god", 400, 600), cw("he", 700, 800),
            cw("missed!", 800, 1100), cw("run", 2500, 2700))
        val p = CaptionMath.chunk(words)
        assertEquals(listOf(listOf("oh", "my", "god"), listOf("he", "missed!"), listOf("run")), p.map { ph -> ph.words.map { it.text } })
        assertEquals(700L, p[0].showUntilMs) // ends when the next phrase starts
        assertEquals(1_350L, p[1].showUntilMs) // tail after the last word
    }

    @Test fun activeWordFollowsTime() {
        val p = CaptionMath.chunk(listOf(cw("one", 0, 300), cw("two", 300, 600), cw("three", 600, 900)))
        assertEquals(0 to 0, CaptionMath.activeAt(p, 100))
        assertEquals(0 to 1, CaptionMath.activeAt(p, 450))
        assertEquals(0 to 2, CaptionMath.activeAt(p, 1_000)) // tail keeps the last word lit
        assertNull(CaptionMath.activeAt(p, 5_000))
    }

    @Test fun captionJsonRoundTrip() {
        val w = listOf(cw("hi", 1, 2))
        assertEquals(w, CaptionMath.decode(CaptionMath.encode(w)))
        assertTrue(CaptionMath.decode("nope").isEmpty())
    }
}

class CaptionWriterTest {
    private val raw = listOf(CaptionWord("um", 0, 100), CaptionWord("the", 100, 200), CaptionWord("vandle", 200, 500), CaptionWord("is", 500, 600), CaptionWord("op", 600, 900))

    @Test fun appliesEditsKeepingTimings() {
        val reply = """{"words":[{"i":0,"text":""},{"i":1,"text":"The"},{"i":2,"text":"Vandal"},{"i":3,"text":"is"},{"i":4,"text":"OP!"}],
            "titles":["Vandal diff","Is the Vandal broken?","One tap wonder"],"hashtags":["valorant","#Vandal","clutch","gaming","fyp","extra"]}"""
        val (w, titles, tags) = CaptionWriter.apply(raw, reply)
        assertEquals(listOf("The", "Vandal", "is", "OP!"), w.map { it.text })
        assertEquals(200L, w[1].startMs) // timing preserved
        assertEquals(3, titles.size)
        assertEquals(5, tags.size)
        assertEquals("#valorant", tags[0])
    }

    @Test fun missingIndicesKeepOriginalAndStuffingIsRejected() {
        val reply = """{"words":[{"i":0,"text":""},{"i":1,"text":"The"},{"i":2,"text":"Vandal is absolutely overpowered in every single way"},{"i":3,"text":"is"},{"i":4,"text":"op"}],"titles":[],"hashtags":[]}"""
        val (w, _, _) = CaptionWriter.apply(raw, reply)
        assertEquals("vandle", w[1].text) // stuffed slot reverted
    }

    @Test(expected = InvalidModelOutput::class) fun lowCoverageThrows() {
        CaptionWriter.apply(raw, """{"words":[{"i":0,"text":"x"}],"titles":[],"hashtags":[]}""")
    }

    @Test fun noSpeechStillGivesTitles() {
        val (w, titles, _) = CaptionWriter.apply(emptyList(), """{"words":[],"titles":["A","B","C","D"],"hashtags":["x"]}""")
        assertTrue(w.isEmpty())
        assertEquals(3, titles.size)
    }

    @Test fun promptListsIndexedWords() {
        val p = CaptionWriter.prompt("Valorant", "t", "why", 12, raw)
        assertTrue(p.contains("2: vandle"))
        assertTrue(CaptionWriter.prompt(null, "t", "w", 5, emptyList()).contains("No one speaks"))
    }
}

class SafeZonesTest {
    private val cam = NRect(0.75f, 0.65f, 0.98f, 0.97f)

    private fun checkClear(cs: CamSpec?) {
        val pip = SafeZones.pipRect(cs)
        val t = SafeZones.titleBox(cs, pip)
        val c = SafeZones.captionBox(cs, pip)
        for (b in listOf(t, c)) {
            assertTrue("$cs top", b.t >= SafeZones.TOP_UI)
            assertTrue("$cs bottom", b.b <= SafeZones.BOTTOM_UI)
            assertFalse("$cs rail $b", b.intersects(SafeZones.rightRail))
            pip?.let { p -> assertFalse("$cs pip", b.intersects(com.clipforge.export.Box(p.l * 1080, p.t * 1920, p.r * 1080, p.b * 1920))) }
        }
        assertFalse("$cs title/caption overlap", t.intersects(c))
    }

    @Test fun textStaysClearOfPlatformUiAndPip() {
        checkClear(null)
        checkClear(CamSpec(cam, CamLayout.SPLIT))
        PipCorner.entries.forEach { checkClear(CamSpec(cam, CamLayout.PIP, it)) }
    }

    @Test fun splitTitleSitsOnTheSeam() {
        val t = SafeZones.titleBox(CamSpec(cam, CamLayout.SPLIT), null)
        assertTrue(t.t < 0.35f * 1920 && t.b > 0.35f * 1920)
    }
}
