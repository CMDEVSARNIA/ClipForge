package com.clipforge.analysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

class InvalidModelOutput(message: String) : Exception(message)

/**
 * Strict parsing + validation of Claude's JSON. Structured outputs guarantee the shape, but a
 * truncated response or a semantically bad item (end before start, out-of-range times) still has
 * to be caught. Whole-response problems throw [InvalidModelOutput] so the caller can retry once;
 * individually bad items are dropped and counted.
 */
object MomentParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** Tolerates a ```json fence (not expected with structured outputs, but cheap to handle). */
    fun parseRoot(text: String): JsonElement {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t.isEmpty()) throw InvalidModelOutput("Empty response")
        return try {
            json.parseToJsonElement(t)
        } catch (e: Exception) {
            throw InvalidModelOutput("Not valid JSON: ${e.message?.take(120)}")
        }
    }

    /** Accepts either a bare array of clips or {"clips":[...], "special_frames":[...]}. */
    fun parseBatch(text: String, batchStartS: Double, batchEndS: Double, videoDurationS: Double): BatchResult {
        val root = parseRoot(text)
        val (items, specials) = when (root) {
            is JsonArray -> root to JsonArray(emptyList())
            is JsonObject -> {
                val clips = root["clips"] as? JsonArray ?: throw InvalidModelOutput("Missing \"clips\" array")
                clips to (root["special_frames"] as? JsonArray ?: JsonArray(emptyList()))
            }
            else -> throw InvalidModelOutput("Expected an array or object")
        }
        // A clip may run a little past the batch (the model sees context), but not wildly.
        val lo = (batchStartS - 10).coerceAtLeast(0.0)
        val hi = (batchEndS + 30).coerceAtMost(videoDurationS)
        var dropped = 0
        val candidates = items.mapNotNull { el ->
            val c = (el as? JsonObject)?.let { candidateOrNull(it, lo, hi) }
            if (c == null) dropped++
            c
        }
        val specialFrames = specials.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val t = o.num("t_s") ?: return@mapNotNull null
            val kind = o.str("kind")?.lowercase() ?: return@mapNotNull null
            if (kind !in setOf("text_card", "wide_shot") || t < lo || t > hi) null else SpecialFrame(t, kind)
        }
        return BatchResult(candidates, specialFrames, dropped)
    }

    private fun candidateOrNull(o: JsonObject, lo: Double, hi: Double): MomentCandidate? {
        val start = o.num("start_s") ?: return null
        val end = o.num("end_s") ?: return null
        val score = o.num("score") ?: return null
        val title = o.str("title")?.trim()?.take(100).orEmpty()
        if (start.isNaN() || end.isNaN() || start < lo || end > hi || end - start < 2.0 || end - start > 90.0) return null
        if (score < 0 || score > 10) return null
        if (title.isBlank()) return null
        return MomentCandidate(
            startS = start,
            endS = end,
            score = score,
            title = title,
            hashtags = normalizeHashtags(o["hashtags"]),
            reason = o.str("reason")?.trim()?.take(400).orEmpty(),
        )
    }

    fun parseRanking(text: String, candidateIds: Set<Int>): RankingResult {
        val root = parseRoot(text) as? JsonObject ?: throw InvalidModelOutput("Expected an object")
        val arr = root["ranking"] as? JsonArray ?: throw InvalidModelOutput("Missing \"ranking\" array")
        val seen = HashSet<Int>()
        val ranked = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.int("id") ?: return@mapNotNull null
            if (id !in candidateIds || !seen.add(id)) return@mapNotNull null
            val score = (o.int("score") ?: return@mapNotNull null).coerceIn(0, 100)
            val tier = o.str("tier")?.uppercase()?.let { t -> Tier.entries.firstOrNull { it.name == t } } ?: Tier.fromScore(score)
            val sub = (o["subscores"] as? JsonObject)?.let { s ->
                SubScores(
                    hook = s.int("hook")?.coerceIn(0, 10) ?: 0,
                    payoff = s.int("payoff")?.coerceIn(0, 10) ?: 0,
                    clarity = s.int("clarity")?.coerceIn(0, 10) ?: 0,
                    emotion = s.int("emotion")?.coerceIn(0, 10) ?: 0,
                    trendFit = s.int("trend_fit")?.coerceIn(0, 10) ?: 0,
                )
            }
            RankedMoment(
                candidateId = id,
                tier = tier,
                score = score,
                sub = sub,
                title = o.str("title")?.trim()?.take(100).orEmpty(),
                hashtags = normalizeHashtags(o["hashtags"]),
                why = o.str("why")?.trim()?.take(400).orEmpty(),
            )
        }
        // The model must rank most of what it was given; otherwise treat the response as broken.
        if (ranked.size < (candidateIds.size * 0.6).toInt().coerceAtLeast(1)) {
            throw InvalidModelOutput("Ranking covered only ${ranked.size} of ${candidateIds.size} candidates")
        }
        return RankingResult(ranked.sortedByDescending { it.score }, root.str("trend_summary")?.trim()?.take(600).orEmpty())
    }

    fun parseGame(text: String): Pair<String, Double>? {
        val o = runCatching { parseRoot(text) as? JsonObject }.getOrNull() ?: return null
        val game = o.str("game")?.trim()?.take(80) ?: return null
        val conf = o.num("confidence") ?: 0.0
        if (game.isBlank() || game.equals("unknown", true)) return null
        return game to conf.coerceIn(0.0, 1.0)
    }

    fun normalizeHashtags(el: JsonElement?): List<String> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .map { tag -> "#" + tag.trim().trimStart('#').replace(Regex("[^\\p{L}\\p{N}_]"), "") }
            .filter { it.length > 1 }
            .distinctBy { it.lowercase() }
            .take(8)
    }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { if (it.isString) null else it.doubleOrNull }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { if (it.isString) null else (it.intOrNull ?: it.doubleOrNull?.toInt()) }
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
