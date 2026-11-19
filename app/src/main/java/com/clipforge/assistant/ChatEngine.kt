package com.clipforge.assistant

import android.content.Context
import com.clipforge.data.db.ClipEntity
import com.clipforge.net.WorkerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** What the chat UI shows, derived from the raw message history (the single source of truth). */
sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Assistant(val text: String) : ChatItem
    data class Action(val tool: String, val summary: String, val isError: Boolean) : ChatItem
}

object ChatProtocol {
    const val STATE_OPEN = "<project_state>"

    fun system(projectName: String, game: String?, trendSummary: String?): String = """
You are the editing assistant inside ClipForge, an Android app that turns long gameplay recordings into vertical clips for TikTok, YouTube Shorts and Reels. You work on one project: "$projectName"${game?.let { ", a $it recording" } ?: ""}.
${trendSummary?.let { "What is working for this game right now: $it\n" } ?: ""}
Clips are identified by their rank number (1 = best). Every user message ends with the current project state. Trust it over earlier turns, because the user also edits clips by hand.

Make changes with the tools instead of describing them, and only say something changed after its tool succeeded. You can't export, share, delete clips or re-run the analysis; say where in the app to do that (the clip's Export tab, its Share button, or Re-run on the project screen).

Prefer clips of 8-30 seconds unless the user asks otherwise. For titles, read the clip details first and keep each one to 50 characters or fewer, punchy and true to what happens. Don't invent events. Hashtags: up to 5, no spaces.

If it's unclear which clip the user means, ask one short question. Keep replies short: a sentence or two, or a compact list of what changed.
""".trim()

    fun userMessage(text: String, state: String): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            addJsonObject { put("type", "text"); put("text", text) }
            addJsonObject { put("type", "text"); put("text", "$STATE_OPEN\n$state\n</project_state>") }
        }
    }

    fun toolResults(results: List<Triple<String, String, Boolean>>): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            results.forEach { (id, text, isError) ->
                addJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", id)
                    put("content", text)
                    if (isError) put("is_error", true)
                }
            }
        }
    }

    /**
     * Messages API request. tool_choice "auto" (Sonnet 5.5 rejects forced tool use), strict tools,
     * adaptive thinking by default, and top-level cache_control so each loop step reuses the
     * cached prefix (tools → system → history).
     */
    fun request(model: String, system: String, messages: List<JsonObject>): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", 8_000)
        put("system", system)
        put("tools", ChatTools.DEFINITIONS)
        putJsonObject("tool_choice") { put("type", "auto") }
        putJsonObject("output_config") { put("effort", "medium") }
        putJsonObject("cache_control") { put("type", "ephemeral") }
        put("messages", JsonArray(messages))
    }

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

    fun display(messages: List<JsonObject>): List<ChatItem> {
        val results = HashMap<String, Pair<String, Boolean>>()
        messages.filter { it["role"].str() == "user" }.forEach { m ->
            (m["content"] as? JsonArray)?.forEach { b ->
                val o = b as? JsonObject ?: return@forEach
                if (o["type"].str() == "tool_result") {
                    val id = o["tool_use_id"].str() ?: return@forEach
                    results[id] = (o["content"].str() ?: "") to ((o["is_error"] as? JsonPrimitive)?.booleanOrNull == true)
                }
            }
        }
        val out = ArrayList<ChatItem>()
        for (m in messages) {
            val blocks = (m["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            when (m["role"].str()) {
                "user" -> blocks.filter { it["type"].str() == "text" }
                    .mapNotNull { it["text"].str() }
                    .filterNot { it.startsWith(STATE_OPEN) }
                    .forEach { out += ChatItem.User(it) }
                "assistant" -> blocks.forEach { b ->
                    when (b["type"].str()) {
                        "text" -> b["text"].str()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += ChatItem.Assistant(it) }
                        "tool_use" -> {
                            val r = results[b["id"].str()]
                            out += ChatItem.Action(b["name"].str() ?: "", r?.first ?: "…", r?.second ?: false)
                        }
                    }
                }
            }
        }
        return out
    }
}

/**
 * Append-only chat history for one project, persisted as the raw Messages API turns. Assistant
 * turns are stored and re-sent exactly as returned (thinking blocks included); nothing earlier is
 * ever edited. "New chat" starts a fresh history instead of trimming the old one.
 */
class ChatStore(private val file: File) {
    constructor(context: Context, projectId: Long) : this(File(context.filesDir, "chat/$projectId.json"))

    private val json = Json { ignoreUnknownKeys = true }

    var messages: MutableList<JsonObject> = mutableListOf()
        private set
    var costUsd: Double = 0.0

    suspend fun load() = withContext(Dispatchers.IO) {
        runCatching {
            val o = json.parseToJsonElement(file.readText()).jsonObject
            messages = (o["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.toMutableList()
            costUsd = (o["cost"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        }
    }

    suspend fun save() = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val body = buildJsonObject { put("messages", JsonArray(messages)); put("cost", costUsd) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body.toString())
        tmp.renameTo(file)
    }

    suspend fun clear() {
        messages = mutableListOf()
        costUsd = 0.0
        save()
    }
}

/** Runs one user message through the tool loop. [apply] saves changed clips; [state] reads the current project. */
class ChatEngine(
    /** One Messages API call; the app passes WorkerClient::messagesRaw, tests pass a scripted fake. */
    private val call: suspend (JsonObject) -> WorkerClient.RawReply,
    private val store: ChatStore,
    private val model: String,
    private val system: String,
    private val state: suspend () -> ProjectState,
    private val apply: suspend (List<ClipEntity>) -> Unit,
) {
    sealed interface Outcome {
        data object Done : Outcome
        data class Stopped(val note: String) : Outcome
    }

    /** Original versions of every clip changed during the last send, for Undo. */
    var lastChanges: Map<Long, ClipEntity> = emptyMap()
        private set

    suspend fun send(text: String, onUpdate: () -> Unit): Outcome {
        val before = HashMap<Long, ClipEntity>()
        store.messages += ChatProtocol.userMessage(text, ChatTools.stateSummary(state()))
        onUpdate()
        try {
            repeat(MAX_STEPS) {
                val reply = call(ChatProtocol.request(model, system, store.messages))
                store.costUsd += reply.costUsd
                val content = reply.body["content"] as? JsonArray ?: JsonArray(emptyList())
                val stop = (reply.body["stop_reason"] as? JsonPrimitive)?.contentOrNull
                if (stop == "refusal" && content.isEmpty()) {
                    // Nothing to keep; drop the unanswered user turn so the chat can continue.
                    if (store.messages.lastOrNull()?.get("role")?.let { (it as? JsonPrimitive)?.contentOrNull } == "user") {
                        store.messages.removeAt(store.messages.lastIndex)
                    }
                    return Outcome.Stopped("Claude declined that request.")
                }
                // Append the assistant turn exactly as returned (thinking / fallback blocks included).
                store.messages += buildJsonObject { put("role", "assistant"); put("content", content) }
                onUpdate()
                if (stop != "tool_use") {
                    return when (stop) {
                        "max_tokens" -> Outcome.Stopped("The reply was cut off. Ask again in a shorter way.")
                        "refusal" -> Outcome.Stopped("Claude declined part of that request.")
                        else -> Outcome.Done
                    }
                }
                val results = ArrayList<Triple<String, String, Boolean>>()
                for (b in content) {
                    val o = b as? JsonObject ?: continue
                    if ((o["type"] as? JsonPrimitive)?.contentOrNull != "tool_use") continue
                    val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val input = o["input"] as? JsonObject ?: buildJsonObject { }
                    val st = state()
                    val outcome = ChatTools.execute(name, input, st)
                    if (outcome.changed.isNotEmpty()) {
                        outcome.changed.forEach { c -> st.clips.firstOrNull { it.id == c.id }?.let { before.putIfAbsent(c.id, it) } }
                        apply(outcome.changed)
                    }
                    results += Triple(id, outcome.result, outcome.isError)
                }
                // All results for this turn go back in one user message.
                store.messages += ChatProtocol.toolResults(results)
                onUpdate()
            }
            return Outcome.Stopped("Stopped after $MAX_STEPS steps. Ask again to continue.")
        } finally {
            if (before.isNotEmpty()) lastChanges = before
            store.save()
        }
    }

    fun clearUndo() {
        lastChanges = emptyMap()
    }

    companion object {
        const val MAX_STEPS = 8
    }
}
