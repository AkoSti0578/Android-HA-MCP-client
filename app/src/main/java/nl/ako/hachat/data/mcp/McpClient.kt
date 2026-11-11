package nl.ako.hachat.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.ako.hachat.core.AppJson
import nl.ako.hachat.core.CleartextPolicy
import nl.ako.hachat.core.PrettyJson
import nl.ako.hachat.core.Redaction
import nl.ako.hachat.core.SseReader
import nl.ako.hachat.core.arr
import nl.ako.hachat.core.bool
import nl.ako.hachat.core.int
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.core.obj
import nl.ako.hachat.core.str
import nl.ako.hachat.data.anthropic.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimale MCP-client over Streamable HTTP (spec 2025-06-18), JSON-RPC 2.0:
 *  - initialize → notifications/initialized → tools/list (met paginatie) → tools/call
 *  - `Mcp-Session-Id` wordt bewaard en meegestuurd; bij 404 op een bestaande sessie
 *    wordt automatisch opnieuw geïnitialiseerd (sessie verlopen, bv. na add-on herstart).
 *  - Responses kunnen `application/json` óf `text/event-stream` zijn; beide worden afgehandeld.
 *
 * Waarom geen officiële Kotlin MCP SDK: die trekt Ktor + een eigen serialization-stack mee en
 * geeft minder grip op foutafhandeling ("Niet thuis? Zet VPN aan.") en geheim-redactie. Het
 * benodigde deel van het protocol is klein en hier volledig unit-getest met MockWebServer.
 */
class McpClient(
    private val http: OkHttpClient,
    private val configProvider: suspend () -> McpConfig?,
) {
    private val ids = AtomicLong(1)
    private val mutex = Mutex()
    private var sessionId: String? = null
    private var protocolVersion: String = PROTOCOL_VERSION
    private var initializedFor: McpConfig? = null
    var serverInfo: McpServerInfo? = null
        private set

    /** Verbindt (indien nodig) en geeft serverinfo terug. */
    suspend fun connect(forceNew: Boolean = false): McpServerInfo = mutex.withLock {
        val cfg = configProvider() ?: throw McpException.NotConfigured()
        if (!forceNew && initializedFor == cfg && serverInfo != null) return serverInfo!!
        initialize(cfg)
    }

    suspend fun listTools(): List<McpTool> {
        connect()
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = if (cursor != null) jsonOf("cursor" to cursor) else JsonObject(emptyMap())
            val result = rpc("tools/list", params)
            result.arr("tools")?.forEach { t ->
                val o = t as? JsonObject ?: return@forEach
                tools += McpTool(
                    name = o.str("name") ?: return@forEach,
                    title = o.str("title") ?: o.obj("annotations")?.str("title"),
                    description = o.str("description"),
                    inputSchema = o.obj("inputSchema") ?: JsonObject(emptyMap()),
                    annotations = o.obj("annotations"),
                )
            }
            cursor = result.str("nextCursor")
        } while (cursor != null && ++pages < 50)
        return tools
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        connect()
        val result = rpc("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
        return toToolResult(result)
    }

    /** Netjes afsluiten (DELETE op de sessie), fouten negeren. */
    suspend fun close() = mutex.withLock {
        val cfg = initializedFor
        val sid = sessionId
        reset()
        if (cfg != null && sid != null) runCatching {
            http.newCall(baseRequest(cfg, sid).delete().build()).await().close()
        }
    }

    private fun reset() {
        sessionId = null; initializedFor = null; serverInfo = null; protocolVersion = PROTOCOL_VERSION
    }

    private suspend fun initialize(cfg: McpConfig): McpServerInfo {
        reset()
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", jsonOf("name" to "ako-ha-chat", "title" to "AKO HA Chat", "version" to "1.0.0"))
        }
        val (result, sid) = post(cfg, null, request("initialize", params))
        sessionId = sid
        protocolVersion = result.str("protocolVersion") ?: PROTOCOL_VERSION
        val info = result.obj("serverInfo")
        serverInfo = McpServerInfo(info?.str("name") ?: "MCP-server", info?.str("version"), protocolVersion)
        // notificatie: server antwoordt met 202 Accepted zonder body
        post(cfg, sessionId, jsonOf("jsonrpc" to "2.0", "method" to "notifications/initialized"), expectResponse = false)
        initializedFor = cfg
        return serverInfo!!
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val cfg = configProvider() ?: throw McpException.NotConfigured()
        return try {
            post(cfg, sessionId, request(method, params)).first
        } catch (e: SessionExpired) {
            mutex.withLock { initialize(cfg) }
            post(cfg, sessionId, request(method, params)).first
        }
    }

    private fun request(method: String, params: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", ids.getAndIncrement())
        put("method", method)
        put("params", params)
    }

    private fun baseRequest(cfg: McpConfig, sid: String?): Request.Builder {
        val url = cfg.url.trim().toHttpUrlOrNull() ?: throw McpException.Protocol("ongeldige URL")
        val b = Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", protocolVersion)
        if (sid != null) b.header("Mcp-Session-Id", sid)
        if (!cfg.headerName.isNullOrBlank() && !cfg.headerValue.isNullOrBlank()) {
            b.header(cfg.headerName.trim(), cfg.headerValue.trim())
        }
        return b
    }

    /** POST één JSON-RPC bericht; geeft (result, sessionId) terug. */
    private suspend fun post(
        cfg: McpConfig,
        sid: String?,
        message: JsonObject,
        expectResponse: Boolean = true,
    ): Pair<JsonObject, String?> {
        val body = AppJson.encodeToString(JsonObject.serializer(), message).toRequestBody(JSON)
        val req = baseRequest(cfg, sid).post(body).build()
        val resp = try {
            http.newCall(req).await()
        } catch (e: IOException) {
            throw mapNetworkError(cfg, e)
        }
        resp.use {
            val newSid = resp.header("Mcp-Session-Id") ?: sid
            if (resp.code == 404 && sid != null) throw SessionExpired()
            if (!resp.isSuccessful) {
                val detail = withContext(Dispatchers.IO) { runCatching { resp.body?.string() }.getOrNull().orEmpty() }
                throw McpException.Http(resp.code, Redaction.scrub(detail.take(200), cfg.url, cfg.headerValue))
            }
            if (!expectResponse) return JsonObject(emptyMap()) to newSid
            val id = message["id"]
            val reply = withContext(Dispatchers.IO) {
                try { readReply(resp, id) } catch (e: IOException) { throw mapNetworkError(cfg, e) }
            }
            reply.obj("error")?.let { err ->
                throw McpException.Rpc(err.int("code") ?: -1, err.str("message") ?: err.toString())
            }
            val result = reply.obj("result") ?: throw McpException.Protocol("antwoord zonder result")
            return result to newSid
        }
    }

    private fun readReply(resp: Response, id: JsonElement?): JsonObject {
        val type = resp.header("Content-Type").orEmpty().lowercase()
        val body = resp.body ?: throw McpException.Protocol("lege response")
        if (type.startsWith("text/event-stream")) {
            val reader = SseReader(body.source())
            while (true) {
                val ev = reader.next() ?: throw McpException.Protocol("SSE-stream eindigde zonder antwoord")
                if (ev.event != null && ev.event != "message") continue
                val msg = runCatching { AppJson.parseToJsonElement(ev.data) }.getOrNull() ?: continue
                matchReply(msg, id)?.let { return it }
            }
        }
        val text = body.string()
        if (text.isBlank()) throw McpException.Protocol("lege response")
        val msg = runCatching { AppJson.parseToJsonElement(text) }.getOrElse {
            throw McpException.Protocol("geen geldige JSON")
        }
        return matchReply(msg, id) ?: throw McpException.Protocol("geen antwoord met id $id")
    }

    /** Zoekt het antwoord op ons verzoek; notificaties/server-requests worden overgeslagen. Ook batches. */
    private fun matchReply(msg: JsonElement, id: JsonElement?): JsonObject? = when (msg) {
        is JsonObject -> msg.takeIf { it["id"] == id && ("result" in it || "error" in it) }
        is JsonArray -> msg.firstNotNullOfOrNull { matchReply(it, id) }
        else -> null
    }

    private fun mapNetworkError(cfg: McpConfig, e: IOException): IOException = when (e) {
        is McpException -> e
        is CleartextPolicy.CleartextBlockedException -> McpException.Blocked(e.message ?: "geblokkeerd")
        is ConnectException, is NoRouteToHostException, is SocketTimeoutException, is UnknownHostException ->
            McpException.Unreachable(Redaction.url(cfg.url), e)
        else -> McpException.Unreachable(Redaction.url(cfg.url), e)
    }

    private class SessionExpired : IOException("session expired")

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private val JSON = "application/json".toMediaType()
        /** Maximale tekstlengte van één tool-resultaat richting Claude (beschermt context & kosten). */
        const val MAX_RESULT_CHARS = 150_000

        fun toToolResult(result: JsonObject): McpToolResult {
            val blocks = mutableListOf<JsonObject>()
            val display = StringBuilder()
            result.arr("content")?.forEach { c ->
                val o = c as? JsonObject ?: return@forEach
                when (o.str("type")) {
                    "text" -> o.str("text")?.let { blocks += textBlock(it); display.appendLine(it) }
                    "image" -> {
                        val data = o.str("data"); val mime = o.str("mimeType")
                        if (data != null && mime != null) {
                            blocks += jsonOf("type" to "image", "source" to jsonOf("type" to "base64", "media_type" to mime, "data" to data))
                            display.appendLine("[afbeelding $mime]")
                        }
                    }
                    "resource" -> o.obj("resource")?.let { r ->
                        val t = r.str("text") ?: "[resource ${r.str("uri")}]"
                        blocks += textBlock(t); display.appendLine(t)
                    }
                    "resource_link" -> {
                        val t = "[resource ${o.str("uri")}]"; blocks += textBlock(t); display.appendLine(t)
                    }
                    else -> { val t = o.toString(); blocks += textBlock(t); display.appendLine(t) }
                }
            }
            // structuredContent zonder tekst-content: als JSON meesturen
            if (blocks.isEmpty()) {
                val structured = result["structuredContent"]
                val t = if (structured != null && structured !is JsonNull) PrettyJson.encodeToString(JsonElement.serializer(), structured) else "(geen inhoud)"
                blocks += textBlock(t); display.append(t)
            }
            return McpToolResult(
                content = truncate(blocks),
                isError = result.bool("isError") == true,
                displayText = display.toString().trim(),
            )
        }

        private fun textBlock(t: String) = jsonOf("type" to "text", "text" to t)

        /** Kapt extreem lange tekst af met een duidelijke markering (nooit stilzwijgend). */
        internal fun truncate(blocks: List<JsonObject>): List<JsonObject> {
            var budget = MAX_RESULT_CHARS
            return blocks.map { b ->
                val t = b.str("text") ?: return@map b
                if (t.length <= budget) { budget -= t.length; b } else {
                    val keep = budget.coerceAtLeast(0)
                    budget = 0
                    textBlock(t.take(keep) + "\n\n[ingekort door AKO HA Chat: ${t.length - keep} tekens weggelaten. Vraag gerichter op als je meer nodig hebt.]")
                }
            }
        }
    }
}

