package com.clipforge.captions

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A caption word with absolute source times (ms), so trims never invalidate captions. */
@Serializable
data class CaptionWord(val text: String, val startMs: Long, val endMs: Long)

/** Raw whisper segment (one word), times relative to the transcribed audio. */
@Serializable
data class RawWord(val t: String, val t0: Long, val t1: Long, val p: Float, val ns: Float)

@Serializable
data class WhisperResult(val lang: String = "", val segments: List<RawWord> = emptyList())

/** A group of words shown together; [words] highlight one at a time. */
data class CaptionPhrase(val words: List<CaptionWord>, val showFromMs: Long, val showUntilMs: Long)

object CaptionMath {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(words: List<CaptionWord>): String = json.encodeToString(ListSerializer(CaptionWord.serializer()), words)
    fun decode(s: String?): List<CaptionWord> = s?.let { runCatching { json.decodeFromString(ListSerializer(CaptionWord.serializer()), it) }.getOrNull() }.orEmpty()
    fun parseWhisper(bytes: ByteArray): WhisperResult = json.decodeFromString(WhisperResult.serializer(), bytes.toString(Charsets.UTF_8))

    /** Linear-interpolation resampler; whisper is robust to its mild aliasing. */
    fun resample(input: FloatArray, fromHz: Int, toHz: Int): FloatArray {
        if (fromHz == toHz || input.isEmpty()) return input
        val outLen = (input.size.toLong() * toHz / fromHz).toInt()
        val out = FloatArray(outLen)
        val step = fromHz.toDouble() / toHz
        for (i in 0 until outLen) {
            val x = i * step
            val i0 = x.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val f = (x - i0).toFloat()
            out[i] = input[i0] * (1 - f) + input[i1] * f
        }
        return out
    }

    // ---- speech filtering ----

    private val nonSpeech = Regex("""^\s*[\[(*♪♫].*|.*[\])*♪♫]\s*$""")
    private val hallucinations = setOf(
        "thank you", "thanks for watching", "thank you for watching", "you", "bye", "bye bye",
        "please subscribe", "subscribe", "like and subscribe", "thank you so much",
    )

    data class Speech(val words: List<RawWord>, val isSpeech: Boolean, val reason: String)

    /**
     * Cleans whisper output and decides whether there's real speech. Gameplay audio (music, gunfire)
     * tends to produce low-confidence fragments or classic hallucinations like "Thank you.".
     */
    fun filterSpeech(raw: List<RawWord>): Speech {
        val words = raw.filter { it.t.isNotBlank() && !nonSpeech.matches(it.t) && it.t1 > it.t0 }
        if (words.size < 2) return Speech(words, false, "no words")
        val avgP = words.map { it.p }.average()
        val avgNs = words.map { it.ns }.average()
        if (avgP < 0.45) return Speech(words, false, "low confidence")
        if (avgNs > 0.6) return Speech(words, false, "no-speech probability high")
        val text = words.joinToString(" ") { it.t.trim() }.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().replace(Regex("\\s+"), " ")
        if (words.size <= 5 && text in hallucinations) return Speech(words, false, "typical hallucination")
        return Speech(words, true, "ok")
    }

    /** Converts to absolute source time, capping words that swallowed a long pause. */
    fun toCaptionWords(words: List<RawWord>, clipStartMs: Long, maxWordMs: Long = 1_200): List<CaptionWord> =
        words.map {
            val start = clipStartMs + it.t0
            val end = clipStartMs + it.t1
            // whisper attaches leading silence to the next word; keep the tail where the word is spoken.
            val s = if (end - start > maxWordMs) end - maxWordMs else start
            CaptionWord(it.t.trim(), s, end)
        }

    // ---- phrasing & timing ----

    fun chunk(words: List<CaptionWord>, maxWords: Int = 3, maxChars: Int = 18, gapMs: Long = 600, tailMs: Long = 250): List<CaptionPhrase> {
        val groups = ArrayList<MutableList<CaptionWord>>()
        for (w in words.filter { it.text.isNotBlank() }) {
            val cur = groups.lastOrNull()
            val startNew = cur == null || cur.size >= maxWords ||
                (cur.sumOf { it.text.length + 1 } + w.text.length) > maxChars ||
                w.startMs - cur.last().endMs > gapMs ||
                cur.last().text.last() in ".!?"
            if (startNew) groups += mutableListOf(w) else cur!!.add(w)
        }
        return groups.mapIndexed { i, g ->
            val next = groups.getOrNull(i + 1)?.first()?.startMs ?: Long.MAX_VALUE
            CaptionPhrase(g, g.first().startMs, minOf(g.last().endMs + tailMs, next))
        }
    }

    /** (phrase index, active word index) at [tMs], or null when no caption is on screen. */
    fun activeAt(phrases: List<CaptionPhrase>, tMs: Long): Pair<Int, Int>? {
        val pi = phrases.indexOfFirst { tMs >= it.showFromMs && tMs < it.showUntilMs }
        if (pi < 0) return null
        val p = phrases[pi]
        val wi = p.words.indexOfLast { it.startMs <= tMs }.coerceAtLeast(0)
        return pi to wi
    }
}
