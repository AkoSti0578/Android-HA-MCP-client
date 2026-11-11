package nl.ako.hachat.data.anthropic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import nl.ako.hachat.core.AppJson
import nl.ako.hachat.core.SseReader
import nl.ako.hachat.core.arr
import nl.ako.hachat.core.bool
import nl.ako.hachat.core.int
import nl.ako.hachat.core.obj
import nl.ako.hachat.core.str
import nl.ako.hachat.data.settings.ModelInfo
import nl.ako.hachat.domain.AssistantResponse
import nl.ako.hachat.domain.ClaudeApi
import nl.ako.hachat.domain.StreamDelta
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Directe client voor de Claude Messages API (geen SDK: Kotlin/Android heeft geen officiële).
 * - Streaming via SSE, niet-streaming via JSON.
 * - Automatische retry met backoff op 429/529/5xx en netwerkfouten (respecteert `retry-after`),
 *   maar alleen zolang er nog geen output gestreamd is.
 */
class AnthropicClient(
    private val http: OkHttpClient,
    private val apiKeyProvider: suspend () -> String?,
    private val baseUrl: String = "https://api.anthropic.com",
    private val maxAttempts: Int = 4,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : ClaudeApi {

    override suspend fun createMessage(
        body: JsonObject,
        betas: List<String>,
        onDelta: (StreamDelta) -> Unit,
    ): AssistantResponse {
        val apiKey = apiKeyProvider() ?: throw ClaudeApiException.MissingKey()
        val streaming = body.bool("stream") == true
        var attempt = 1
        while (true) {
            try {
                return if (streaming) streamOnce(apiKey, body, betas, onDelta) else sendOnce(apiKey, body, betas)
            } catch (e: ClaudeApiException) {
                if (!e.retryable || attempt >= maxAttempts || (e is StreamStartedException)) throw e.unwrap()
                val wait = backoffSeconds(attempt, (e as? ClaudeApiException.RateLimited)?.retryAfterSeconds)
                onDelta(StreamDelta.Retrying(attempt, wait, e.message ?: ""))
                sleeper(wait * 1000)
                onDelta(StreamDelta.Reset)
                attempt++
            }
        }
    }

    /** Exponentiële backoff 2,4,8… s (max 60), of de `retry-after` van de server. */
    internal fun backoffSeconds(attempt: Int, retryAfter: Long?): Long =
        retryAfter?.coerceIn(1, 120) ?: min(60L, 1L shl attempt)

    private fun request(apiKey: String, path: String, body: JsonObject?, betas: List<String>): Request {
        val b = Request.Builder()
            .url("$baseUrl$path")
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
        if (betas.isNotEmpty()) b.header("anthropic-beta", betas.joinToString(","))
        if (body != null) {
            b.post(AppJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            if (body.bool("stream") == true) b.header("accept", "text/event-stream")
        } else b.get()
        return b.build()
    }

    private suspend fun sendOnce(apiKey: String, body: JsonObject, betas: List<String>): AssistantResponse =
        execute(request(apiKey, "/v1/messages", body, betas)).use { resp ->
            ensureSuccess(resp)
            val json = withContext(Dispatchers.IO) { resp.body!!.string() }
            MessageAccumulator.fromMessage(AppJson.parseToJsonElement(json) as JsonObject)
        }

    private suspend fun streamOnce(
        apiKey: String,
        body: JsonObject,
        betas: List<String>,
        onDelta: (StreamDelta) -> Unit,
    ): AssistantResponse {
        val resp = execute(request(apiKey, "/v1/messages", body, betas))
        return resp.use {
            ensureSuccess(resp)
            var produced = false
            val acc = MessageAccumulator { d -> produced = true; onDelta(d) }
            // Stop-knop: bij annuleren de verbinding sluiten zodat de blokkerende read direct stopt.
            coroutineScope {
            val watcher = launch(Dispatchers.IO) { try { awaitCancellation() } finally { resp.close() } }
            try { withContext(Dispatchers.IO) {
                val reader = SseReader(resp.body!!.source())
                try {
                    while (true) {
                        ensureActive()
                        val ev = reader.next() ?: break
                        if (ev.data.isBlank()) continue
                        val data = runCatching { AppJson.parseToJsonElement(ev.data) as JsonObject }.getOrNull() ?: continue
                        acc.accept(ev.event ?: data.str("type") ?: continue, data)
                        if (acc.finished) break
                    }
                } catch (e: ClaudeApiException) {
                    // Fout midden in de stream: alleen opnieuw proberen als er nog niets getoond is.
                    if (produced) throw StreamStartedException(e) else throw e
                } catch (e: IOException) {
                    ensureActive() // geannuleerd? dan CancellationException i.p.v. netwerkfout
                    if (produced) throw StreamStartedException(ClaudeApiException.Network(e))
                    throw ClaudeApiException.Network(e)
                }
            } } finally { watcher.cancel() }
            }
            if (!acc.finished) {
                val partial = acc.result()
                if (partial.stopReason == null) throw StreamStartedException(
                    ClaudeApiException.Network(IOException("stream onverwacht beëindigd")),
                )
            }
            acc.result()
        }
    }

    /** Haalt alle modellen op via GET /v1/models (gepagineerd). */
    suspend fun listModels(): List<ModelInfo> {
        val apiKey = apiKeyProvider() ?: throw ClaudeApiException.MissingKey()
        val out = mutableListOf<ModelInfo>()
        var after: String? = null
        do {
            val path = "/v1/models?limit=100" + (after?.let { "&after_id=$it" } ?: "")
            val page = execute(request(apiKey, path, null, emptyList())).use { resp ->
                ensureSuccess(resp)
                AppJson.parseToJsonElement(withContext(Dispatchers.IO) { resp.body!!.string() }) as JsonObject
            }
            page.arr("data")?.forEach { (it as? JsonObject)?.let { m -> out += parseModel(m) } }
            after = if (page.bool("has_more") == true) page.str("last_id") else null
        } while (after != null)
        return out
    }

    private suspend fun execute(req: Request): Response = try {
        http.newCall(req).await()
    } catch (e: IOException) {
        throw ClaudeApiException.Network(e)
    }

    private suspend fun ensureSuccess(resp: Response) {
        if (resp.isSuccessful) return
        val text = withContext(Dispatchers.IO) { runCatching { resp.body?.string() }.getOrNull().orEmpty() }
        val message = runCatching {
            (AppJson.parseToJsonElement(text) as JsonObject).obj("error")?.str("message")
        }.getOrNull() ?: text.take(300).ifBlank { resp.message }
        val retryAfter = resp.header("retry-after")?.toDoubleOrNull()?.toLong()
        throw ClaudeApiException.fromHttp(resp.code, message, retryAfter)
    }

    /** Fout nadat er al output was: niet opnieuw proberen (zou dubbele tekst geven). */
    private class StreamStartedException(val inner: ClaudeApiException) :
        ClaudeApiException(inner.message ?: "", false)

    private fun ClaudeApiException.unwrap(): ClaudeApiException = (this as? StreamStartedException)?.inner ?: this

    companion object {
        /** Bewust vastgezet; de app stuurt geen sampling-parameters, dus ook 2026-10-21 werkt. */
        const val API_VERSION = "2023-06-01"
        const val BETA_FALLBACK = "server-side-fallback-2026-07-01"
        private val JSON = "application/json".toMediaType()

        fun parseModel(m: JsonObject): ModelInfo {
            val caps = m.obj("capabilities")
            fun supported(o: JsonObject?) = runCatching { o?.get("supported")?.jsonPrimitive?.boolean }.getOrNull() == true
            val effort = caps?.obj("effort")
            val levels = listOf("low", "medium", "high", "xhigh", "max").filter { supported(effort?.obj(it)) }
            return ModelInfo(
                id = m.str("id") ?: "?",
                displayName = m.str("display_name") ?: m.str("id") ?: "?",
                supportsAdaptiveThinking = supported(caps?.obj("thinking")?.obj("types")?.obj("adaptive")),
                supportsEffort = supported(effort),
                effortLevels = levels,
                maxOutputTokens = m.int("max_tokens"),
            )
        }
    }
}

/** OkHttp-call als annuleerbare suspend-functie (Stop-knop breekt de verbinding echt af). */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
