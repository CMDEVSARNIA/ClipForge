package com.clipforge.analysis

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds Messages API request bodies (sent to the Worker, which forwards them unchanged).
 * Shapes follow the current docs: image blocks with a base64 source, and JSON outputs via
 * output_config.format = {type: "json_schema", schema}. Thinking is left at the model default
 * (adaptive on Haiku 5.5 / Sonnet 5.5); effort controls depth and cost.
 */
object ClaudeRequests {
    fun text(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    fun jpeg(base64: String): JsonObject = buildJsonObject {
        put("type", "image")
        putJsonObject("source") {
            put("type", "base64")
            put("media_type", "image/jpeg")
            put("data", base64)
        }
    }

    fun structured(
        model: String,
        system: String,
        content: List<JsonObject>,
        schema: JsonObject,
        maxTokens: Int,
        effort: String,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("system", system)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", JsonArray(content))
            }
        }
        putJsonObject("output_config") {
            put("effort", effort)
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", schema)
            }
        }
    }
}
