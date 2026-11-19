package com.clipforge.captions

import com.clipforge.analysis.ClaudeRequests
import com.clipforge.analysis.InvalidModelOutput
import com.clipforge.analysis.MomentParser
import com.clipforge.net.WorkerClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One Claude call per clip: polishes the transcribed words in place (same indices, so word
 * timings survive) and writes 3 title options + 5 hashtags.
 */
object CaptionWriter {

    data class Result(val words: List<CaptionWord>, val titles: List<String>, val hashtags: List<String>, val costUsd: Double)

    val SYSTEM = """
You write on-screen text for short gaming clips (TikTok, YouTube Shorts, Reels).

You get the clip's context and, when someone speaks, the auto-transcribed words, each with an index.

1. Polish the captions. Fix misheard words using the game's own terms (characters, weapons, places, slang), and add natural punctuation and capitalization. Return exactly one entry per input index, in order. Set filler words (um, uh) and obvious transcription garbage to an empty string. If two indices should become one word, put the word on the first and an empty string on the second. Don't move words between distant indices, and don't invent speech that isn't there. Keep the speaker's tone, including slang and swearing.
2. Write 3 different title options, each 50 characters or fewer, punchy and true to the clip. Make one of them a hook or a question.
3. Write 5 hashtags without spaces: the game, the kind of moment, and a couple of broad-reach tags.
""".trimIndent()

    val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("words") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("i") { put("type", "integer") }
                        putJsonObject("text") { put("type", "string") }
                    }
                    putJsonArray("required") { add("i"); add("text") }
                    put("additionalProperties", false)
                }
            }
            putJsonObject("titles") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            putJsonObject("hashtags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
        }
        putJsonArray("required") { add("words"); add("titles"); add("hashtags") }
        put("additionalProperties", false)
    }

    fun prompt(game: String?, currentTitle: String, why: String, lengthS: Int, words: List<CaptionWord>): String = buildString {
        appendLine("Game: ${game ?: "unknown"}")
        appendLine("Clip length: $lengthS s")
        appendLine("Current title: $currentTitle")
        appendLine("Why it was picked: $why")
        appendLine()
        if (words.isEmpty()) {
            appendLine("No one speaks in this clip, so return an empty words array.")
        } else {
            appendLine("Transcribed words (index: word):")
            words.forEachIndexed { i, w -> appendLine("$i: ${w.text}") }
        }
    }

    /** Applies the model's edits to [raw], keeping each word's timing. Throws on unusable output. */
    fun apply(raw: List<CaptionWord>, text: String): Triple<List<CaptionWord>, List<String>, List<String>> {
        val root = MomentParser.parseRoot(text) as? JsonObject ?: throw InvalidModelOutput("Expected an object")
        val edits = HashMap<Int, String>()
        (root["words"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val i = (o["i"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            val t = (o["text"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (i in raw.indices) edits[i] = t.trim()
        }
        if (raw.isNotEmpty() && edits.size < (raw.size * 0.9).toInt()) {
            throw InvalidModelOutput("Polish covered ${edits.size} of ${raw.size} words")
        }
        val polished = raw.mapIndexedNotNull { i, w ->
            val t = edits[i] ?: w.text
            // Guard against the model stuffing text into one slot.
            val safe = if (t.length > w.text.length * 3 + 12) w.text else t
            if (safe.isBlank()) null else w.copy(text = safe)
        }
        val titles = (root["titles"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() }?.take(70) }
            .distinct().take(3)
        val tags = MomentParser.normalizeHashtags(root["hashtags"]).take(5)
        return Triple(polished, titles, tags)
    }

    suspend fun run(
        worker: WorkerClient,
        model: String,
        game: String?,
        currentTitle: String,
        why: String,
        lengthS: Int,
        words: List<CaptionWord>,
    ): Result {
        val req = ClaudeRequests.structured(
            model, SYSTEM, listOf(ClaudeRequests.text(prompt(game, currentTitle, why, lengthS, words))), SCHEMA,
            maxTokens = (2_000 + words.size * 20).coerceAtMost(16_000), effort = "low",
        )
        var cost = 0.0
        repeat(2) { attempt ->
            val reply = worker.messages(req)
            cost += reply.costUsd
            try {
                if (reply.stopReason == "max_tokens") throw InvalidModelOutput("cut off")
                val (w, t, h) = apply(words, reply.text)
                return Result(w, t, h, cost)
            } catch (e: InvalidModelOutput) {
                if (attempt == 1) throw e
            }
        }
        error("unreachable")
    }
}
