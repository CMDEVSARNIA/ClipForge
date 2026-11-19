package com.clipforge.net

import com.clipforge.analysis.ModelPricing
import com.clipforge.analysis.TrendsResponse
import com.clipforge.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Error from the Worker or from the Messages API relayed by it. */
class WorkerException(val status: Int, val type: String, message: String, val retryAfterS: Long? = null) : Exception(message) {
    val retryable: Boolean
        get() = (status == 429 && type != "budget_exceeded") || status == 408 || status == 500 || status == 502 ||
            status == 503 || status == 504 || status == 529
}

/** Claude refused (stop_reason "refusal"); retrying the same request won't help. */
class ModelRefusedException(message: String) : Exception(message)

data class ClaudeReply(
    val text: String,
    val stopReason: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
)

data class WorkerHealth(val models: List<String>, val trends: Boolean)

/**
 * The app's only network client. It talks to the ClipForge Worker, never to api.anthropic.com:
 * the Worker holds the Anthropic key and forwards Messages API requests.
 */
class WorkerClient(private val settings: SettingsRepository) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    fun normalizeUrl(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return withScheme.toHttpUrlOrNull()?.toString()?.trimEnd('/')
    }

    suspend fun health(baseUrl: String): WorkerHealth {
        val body = execute(Request.Builder().url("$baseUrl/health").get().build())
        val o = json.parseToJsonElement(body).jsonObject
        val models = (o["models"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val trends = (o["trends"] as? JsonPrimitive)?.contentOrNull == "true"
        return WorkerHealth(models, trends)
    }

    suspend fun register(baseUrl: String, inviteCode: String): String {
        val payload = buildJsonObject {
            put("invite_code", inviteCode.trim())
            put("install_id", settings.installId())
        }
        val body = execute(
            Request.Builder().url("$baseUrl/v1/register").post(payload.toString().toRequestBody(jsonType)).build()
        )
        return (json.parseToJsonElement(body).jsonObject["token"] as? JsonPrimitive)?.contentOrNull
            ?: throw WorkerException(500, "api_error", "Worker returned no token.")
    }

    suspend fun trends(game: String): TrendsResponse {
        val s = settings.current()
        val url = "${s.workerUrl}/v1/trends".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("game", game).build()
        val body = execute(Request.Builder().url(url).get().header("authorization", "Bearer ${s.appToken}").build())
        return json.decodeFromString(TrendsResponse.serializer(), body)
    }

    /** POST /v1/messages with retries on transient failures (429, 5xx, overloaded, network). */
    suspend fun messages(request: JsonObject, maxAttempts: Int = 3): ClaudeReply {
        val s = settings.current()
        if (!s.isConnected) throw WorkerException(401, "not_connected", "Connect ClipForge to your Worker in Settings first.")
        val model = (request["model"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        var attempt = 0
        while (true) {
            attempt++
            try {
                val (body, headers) = executeWithHeaders(
                    Request.Builder()
                        .url("${s.workerUrl}/v1/messages")
                        .header("authorization", "Bearer ${s.appToken}")
                        .post(request.toString().toRequestBody(jsonType))
                        .build()
                )
                return parseReply(body, model, headers["x-clipforge-cost-usd"]?.toDoubleOrNull())
            } catch (e: WorkerException) {
                if (!e.retryable || attempt >= maxAttempts) throw e
                delay(((e.retryAfterS ?: 0L) * 1000).coerceAtLeast(2_000L * attempt * attempt).coerceAtMost(60_000))
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw e
                delay(2_000L * attempt * attempt)
            }
        }
    }

    private fun parseReply(body: String, model: String, headerCost: Double?): ClaudeReply {
        val o = json.parseToJsonElement(body).jsonObject
        val stop = (o["stop_reason"] as? JsonPrimitive)?.contentOrNull
        val text = (o["content"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            .joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val usage = o["usage"] as? JsonObject
        fun u(k: String) = (usage?.get(k) as? JsonPrimitive)?.longOrNull ?: 0L
        val inTok = u("input_tokens") + u("cache_creation_input_tokens") + u("cache_read_input_tokens")
        val outTok = u("output_tokens")
        if (stop == "refusal") {
            val explanation = ((o["stop_details"] as? JsonObject)?.get("explanation") as? JsonPrimitive)?.contentOrNull
            throw ModelRefusedException("Claude declined this request${explanation?.let { ": $it" } ?: "."}")
        }
        return ClaudeReply(text, stop, inTok, outTok, headerCost ?: ModelPricing.cost(model, inTok, outTok))
    }

    private suspend fun execute(request: Request): String = executeWithHeaders(request).first

    private suspend fun executeWithHeaders(request: Request): Pair<String, Map<String, String>> {
        val response = http.newCall(request).await()
        response.use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw toError(r, body)
            return body to r.headers.toMap()
        }
    }

    private fun toError(r: Response, body: String): WorkerException {
        val err = runCatching { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject }.getOrNull()
        val type = (err?.get("type") as? JsonPrimitive)?.contentOrNull ?: "http_${r.code}"
        val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Request failed (HTTP ${r.code})."
        return WorkerException(r.code, type, msg, r.header("retry-after")?.toLongOrNull())
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
