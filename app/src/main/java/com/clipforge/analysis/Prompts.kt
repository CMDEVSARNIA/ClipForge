package com.clipforge.analysis

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object Prompts {

    val BATCH_SYSTEM = """
You are a short-form video editor for gaming content. You receive frames sampled every 2 seconds from a gameplay recording, each preceded by its timestamp, plus loudness peaks (dB above the video's own median) and detected scene cuts for this time range.

Find moments that work as standalone vertical clips for TikTok, YouTube Shorts and Reels.
- A good clip has a hook in its first 1-2 seconds, a payoff (kill, clutch, fail, funny glitch, big reaction, boss down, win or loss reveal), and makes sense without context.
- Loud audio often marks action or a reaction; silence is fine when the visuals carry it.
- Prefer 8-30 seconds. Start just before the action builds and end just after the payoff.
- Return at most 3 clips for this range, or none if nothing qualifies. Don't pad with filler.
- Score honestly from 0 to 10: 8 or more is genuinely worth posting, 5 is filler.
- Times are seconds from the start of the whole video. Use the timestamps you are given.

Also list frames that are a full-screen text card or results/death screen (for example "WASTED", "VICTORY", "YOU DIED") as kind "text_card", and wide establishing or scenery shots where the action is small or spread across the frame as kind "wide_shot". These decide the crop later, so only list clear cases.
""".trimIndent()

    val RANK_SYSTEM = """
You rank candidate gaming clips from one video for short-form platforms (TikTok, YouTube Shorts, Reels), from best to worst.

You get the game, trend data when available (the most-viewed Shorts for this game from the past week, with view counts), and each candidate with its time range, a frame from it, its loudness, and the first-pass note.

1. From the trend data, work out what audiences are watching right now: recurring formats, characters, mechanics, kinds of moments, and title styles. Summarize that in trend_summary in 2-3 sentences. If there is no trend data, say so and rely on what generally performs for gaming Shorts.
2. Score every candidate 0-100 relative to the others but calibrated: 85+ (tier S) would plausibly outperform a typical post for this game, 70-84 A, 55-69 B, 40-54 C, below 40 D (not worth posting).
3. Subscores 0-10: hook (do the first 2 seconds grab attention), payoff, clarity (understandable with no context), emotion (energy, reaction, intensity), trend_fit (matches what is working now).
4. Write a punchy title, 60 characters or fewer, that is true to the clip, and up to 5 hashtags.
5. why: one sentence on what earns or costs this clip its rank.

Include every candidate id exactly once.
""".trimIndent()

    const val GAME_SYSTEM = "Identify the video game shown in these gameplay frames. If you can't tell, answer \"unknown\" with low confidence."

    val BATCH_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("clips") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("start_s") { put("type", "number") }
                        putJsonObject("end_s") { put("type", "number") }
                        putJsonObject("score") { put("type", "number"); put("description", "0-10") }
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("hashtags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                        putJsonObject("reason") { put("type", "string") }
                    }
                    putJsonArray("required") { listOf("start_s", "end_s", "score", "title", "hashtags", "reason").forEach { add(it) } }
                    put("additionalProperties", false)
                }
            }
            putJsonObject("special_frames") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("t_s") { put("type", "number") }
                        putJsonObject("kind") { put("type", "string"); putJsonArray("enum") { add("text_card"); add("wide_shot") } }
                    }
                    putJsonArray("required") { add("t_s"); add("kind") }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add("clips"); add("special_frames") }
        put("additionalProperties", false)
    }

    val RANK_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("trend_summary") { put("type", "string") }
            putJsonObject("ranking") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") { put("type", "integer") }
                        putJsonObject("tier") { put("type", "string"); putJsonArray("enum") { listOf("S", "A", "B", "C", "D").forEach { add(it) } } }
                        putJsonObject("score") { put("type", "integer"); put("description", "0-100") }
                        putJsonObject("subscores") {
                            put("type", "object")
                            val keys = listOf("hook", "payoff", "clarity", "emotion", "trend_fit")
                            putJsonObject("properties") { keys.forEach { k -> putJsonObject(k) { put("type", "integer"); put("description", "0-10") } } }
                            putJsonArray("required") { keys.forEach { add(it) } }
                            put("additionalProperties", false)
                        }
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("hashtags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                        putJsonObject("why") { put("type", "string") }
                    }
                    putJsonArray("required") { listOf("id", "tier", "score", "subscores", "title", "hashtags", "why").forEach { add(it) } }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add("trend_summary"); add("ranking") }
        put("additionalProperties", false)
    }

    val GAME_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("game") { put("type", "string") }
            putJsonObject("confidence") { put("type", "number"); put("description", "0-1") }
        }
        putJsonArray("required") { add("game"); add("confidence") }
        put("additionalProperties", false)
    }

    fun fmt(s: Double): String = "%.1f".format(java.util.Locale.US, s)

    /** Text header for one frame batch. */
    fun batchHeader(
        game: String?,
        videoDurationS: Double,
        rangeStartS: Double,
        rangeEndS: Double,
        peaks: List<LoudnessMath.Peak>,
        cuts: List<SceneCut>,
        trendHints: String?,
        hasAudio: Boolean,
    ): String = buildString {
        appendLine("Game: ${game ?: "unknown"}")
        appendLine("Video length: ${fmt(videoDurationS)} s. This batch covers ${fmt(rangeStartS)}-${fmt(rangeEndS)} s.")
        if (!hasAudio) appendLine("Loudness peaks: none (the video has no audio track).")
        else if (peaks.isEmpty()) appendLine("Loudness peaks: none in this range.")
        else appendLine("Loudness peaks (time s: dB above median): " + peaks.joinToString(", ") { "${fmt(it.timeS)}: +${"%.0f".format(java.util.Locale.US, it.aboveMedianDb)}" })
        if (cuts.isEmpty()) appendLine("Scene cuts: none in this range.")
        else appendLine("Scene cuts (s): " + cuts.joinToString(", ") { fmt(it.timeS) })
        if (!trendHints.isNullOrBlank()) {
            appendLine()
            appendLine("What is getting views for this game right now (for context, don't force it):")
            appendLine(trendHints)
        }
        appendLine()
        append("Frames follow, each labeled with its timestamp.")
    }
}
