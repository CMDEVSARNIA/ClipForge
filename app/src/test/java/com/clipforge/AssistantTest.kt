package com.clipforge

import com.clipforge.assistant.ChatEngine
import com.clipforge.assistant.ChatItem
import com.clipforge.assistant.ChatProtocol
import com.clipforge.assistant.ChatStore
import com.clipforge.assistant.ChatTools
import com.clipforge.assistant.ProjectState
import com.clipforge.data.db.ClipEntity
import com.clipforge.net.WorkerClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun clip(id: Long, rank: Int, start: Long, end: Long, title: String = "Clip $rank") = ClipEntity(
    id = id, projectId = 1, analysisId = 1, rank = rank, tier = "A", score = 80, startMs = start, endMs = end,
    suggestedStartMs = start, suggestedEndMs = end, title = title, hashtags = "#a", why = "why $rank", firstPassNote = "",
    hook = 8, payoff = 8, clarity = 8, emotion = 8, trendFit = 8, loudnessAboveMedianDb = 0f, thumbMs = start,
)

private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

class ChatToolsTest {
    private val st = ProjectState(listOf(clip(10, 1, 60_000, 80_000), clip(20, 2, 120_000, 145_000)), 600_000, hasFacecam = false)

    @Test fun trimMovesEdgesAndReportsLength() {
        val o = ChatTools.execute("trim_clip", obj("""{"clip":2,"start_delta_s":2,"end_delta_s":-5.5}"""), st)
        assertFalse(o.result, o.isError)
        val c = o.changed.single()
        assertEquals(122_000L, c.startMs); assertEquals(139_500L, c.endMs); assertTrue(c.edited)
        assertTrue(o.result, o.result.contains("25.0s → 17.5s"))
    }

    @Test fun trimRejectsTooShortAndUnknownClip() {
        assertTrue(ChatTools.execute("trim_clip", obj("""{"clip":1,"start_delta_s":19,"end_delta_s":0}"""), st).isError)
        val missing = ChatTools.execute("trim_clip", obj("""{"clip":9,"start_delta_s":0,"end_delta_s":0}"""), st)
        assertTrue(missing.isError); assertTrue(missing.result.contains("1–2"))
    }

    @Test fun rangeClampsToVideo() {
        val c = ChatTools.execute("set_clip_range", obj("""{"clip":1,"start_s":590,"end_s":700}"""), st).changed.single()
        assertEquals(600_000L, c.endMs)
    }

    @Test fun cropResetsSegmentsAndSetsPan() {
        val segmented = st.copy(clips = listOf(st.clips[0].copy(segmentsJson = "[]")))
        val c = ChatTools.execute("set_crop", obj("""{"clip":1,"mode":"PAN","pan_x":0.6}"""), segmented).changed.single()
        assertEquals("PAN", c.cropMode); assertEquals(0.6f, c.panX, 1e-6f); assertEquals(null, c.segmentsJson)
        val z = ChatTools.execute("set_crop", obj("""{"clip":1,"mode":"ZOOM_FIT","pan_x":0}"""), st).changed.single()
        assertTrue(z.zoomRect != null)
    }

    @Test fun layoutNeedsFacecamAndClipZeroMeansAll() {
        assertTrue(ChatTools.execute("set_layout", obj("""{"clip":0,"layout":"SPLIT","pip_corner":"TR"}"""), st).isError)
        val o = ChatTools.execute("set_layout", obj("""{"clip":0,"layout":"PIP","pip_corner":"BL"}"""), st.copy(hasFacecam = true))
        assertEquals(2, o.changed.size)
        assertTrue(o.changed.all { it.camLayout == "PIP" && it.pipCorner == "BL" })
        // Turning the cam off never needs a facecam.
        assertFalse(ChatTools.execute("set_layout", obj("""{"clip":1,"layout":"NONE","pip_corner":"TR"}"""), st).isError)
    }

    @Test fun titleHashtagsOverlays() {
        val t = ChatTools.execute("set_title", obj("""{"clip":1,"title":"  Ace on eco  ","style":"POP"}"""), st).changed.single()
        assertEquals("Ace on eco", t.title); assertEquals("POP", t.titleStyle)
        assertEquals("PUNCH", ChatTools.execute("set_title", obj("""{"clip":1,"title":"x","style":"KEEP"}"""), st).changed.single().titleStyle)
        assertTrue(ChatTools.execute("set_title", obj("""{"clip":1,"title":" ","style":"KEEP"}"""), st).isError)
        val h = ChatTools.execute("set_hashtags", obj("""{"clip":1,"hashtags":["valorant","#ace","fps","clutch","gaming","extra"]}"""), st).changed.single()
        assertEquals("#valorant #ace #fps #clutch #gaming", h.hashtags)
        val ov = ChatTools.execute("set_overlays", obj("""{"clip":0,"title":"OFF","captions":"KEEP"}"""), st)
        assertTrue(ov.changed.all { !it.showTitle && it.showCaptions })
    }

    @Test fun detailsAreReadOnly() {
        val o = ChatTools.execute("get_clip_details", obj("""{"clip":1}"""), st)
        assertTrue(o.changed.isEmpty()); assertTrue(o.result.contains("why 1")); assertTrue(o.result.contains("not transcribed"))
    }

    @Test fun stateSummaryListsEveryClip() {
        val s = ChatTools.stateSummary(st)
        assertTrue(s.contains("1 · A · 80")); assertTrue(s.contains("2 · A · 80")); assertTrue(s.contains("Facecam set up: no"))
    }

    @Test fun toolDefinitionsAreStrictAndClosed() {
        assertEquals(8, ChatTools.DEFINITIONS.size)
        ChatTools.DEFINITIONS.forEach { t ->
            val o = t.jsonObject
            assertEquals("true", o["strict"]!!.jsonPrimitive.content)
            val schema = o["input_schema"]!!.jsonObject
            assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
            // Strict tools: every property is required.
            assertEquals(schema["properties"]!!.jsonObject.keys, (schema["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet())
        }
    }
}

class ChatEngineTest {
    private var clips = mutableListOf(clip(10, 1, 60_000, 80_000), clip(20, 2, 120_000, 145_000))
    private val thinking = """{"type":"thinking","thinking":"","signature":"sig-abc"}"""

    private fun reply(content: String, stop: String) =
        WorkerClient.RawReply(obj("""{"content":[$content],"stop_reason":"$stop","usage":{"input_tokens":10,"output_tokens":5}}"""), 0.001)

    @Test fun toolLoopKeepsHistoryAppendOnlyAndUndoable() = runTest {
        val file = File.createTempFile("cf_chat", ".json").apply { delete() }
        val store = ChatStore(file)
        val requests = ArrayList<JsonObject>()
        val script = ArrayDeque(listOf(
            reply("""$thinking,{"type":"text","text":"On it."},{"type":"tool_use","id":"t1","name":"trim_clip","input":{"clip":2,"start_delta_s":0,"end_delta_s":-5}},{"type":"tool_use","id":"t2","name":"set_title","input":{"clip":2,"title":"Shorter now","style":"KEEP"}}""", "tool_use"),
            reply("""$thinking,{"type":"text","text":"Clip 2 is now 20 s."}""", "end_turn"),
        ))
        val engine = ChatEngine(
            call = { req -> requests += req; script.removeFirst() },
            store = store, model = "claude-sonnet-5-5", system = "sys",
            state = { ProjectState(clips.toList(), 600_000, false) },
            apply = { changed -> changed.forEach { c -> clips[clips.indexOfFirst { it.id == c.id }] = c } },
        )
        val outcome = engine.send("make clip 2 shorter") {}
        assertEquals(ChatEngine.Outcome.Done, outcome)
        assertEquals(140_000L, clips[1].endMs)
        assertEquals("Shorter now", clips[1].title)

        // user → assistant(tool_use) → user(tool_results) → assistant(text)
        val roles = store.messages.map { it["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("user", "assistant", "user", "assistant"), roles)
        // Assistant content is stored exactly as returned, thinking block and signature included.
        val firstAssistant = store.messages[1]["content"] as JsonArray
        assertEquals("sig-abc", firstAssistant[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals(4, firstAssistant.size)
        // Both tool results in ONE user message, matched by id.
        val results = store.messages[2]["content"] as JsonArray
        assertEquals(listOf("t1", "t2"), results.map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content })
        // The second request re-sends the first request's messages unchanged (append-only prefix).
        val first = requests[0]["messages"] as JsonArray
        val second = requests[1]["messages"] as JsonArray
        assertEquals(first, JsonArray(second.take(first.size)))

        val display = ChatProtocol.display(store.messages)
        assertEquals(ChatItem.User("make clip 2 shorter"), display.first())
        assertTrue(display.any { it is ChatItem.Action && it.summary.contains("25.0s → 20.0s") })
        assertEquals(ChatItem.Assistant("Clip 2 is now 20 s."), display.last())

        // Undo data holds the originals of everything changed in this request.
        assertEquals(145_000L, engine.lastChanges[20]!!.endMs)
        assertEquals("Clip 2", engine.lastChanges[20]!!.title)

        // Persisted and reloadable.
        val reloaded = ChatStore(file).apply { load() }
        assertEquals(store.messages, reloaded.messages)
        file.delete()
    }

    @Test fun emptyRefusalDropsTheUnansweredTurn() = runTest {
        val store = ChatStore(File.createTempFile("cf_chat", ".json").apply { delete() })
        val engine = ChatEngine({ reply("", "refusal") }, store, "m", "s", { ProjectState(clips, 1, false) }, {})
        val o = engine.send("hi") {}
        assertTrue(o is ChatEngine.Outcome.Stopped)
        assertTrue(store.messages.isEmpty())
    }

    @Test fun requestShape() {
        val r = ChatProtocol.request("claude-sonnet-5-5", "sys", listOf(ChatProtocol.userMessage("hi", "state")))
        assertEquals("auto", r["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content) // forced choice 400s on Sonnet 5.5
        assertEquals("ephemeral", r["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(r.containsKey("thinking")) // adaptive by default
        File("build").mkdirs()
        File("build/chat-request.json").writeText(r.toString())
        assertEquals(JsonPrimitive(8_000), r["max_tokens"])
    }
}
