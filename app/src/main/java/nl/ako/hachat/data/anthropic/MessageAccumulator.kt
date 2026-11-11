package nl.ako.hachat.data.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.ako.hachat.core.AppJson
import nl.ako.hachat.core.long
import nl.ako.hachat.core.obj
import nl.ako.hachat.core.str
import nl.ako.hachat.core.with
import nl.ako.hachat.domain.AssistantResponse
import nl.ako.hachat.domain.StreamDelta
import nl.ako.hachat.domain.Usage

/**
 * Bouwt uit de SSE-events van de Messages API het complete assistant-bericht op.
 * Onbekende bloktypes (bv. `fallback`, `redacted_thinking`) worden ongewijzigd bewaard,
 * zodat ze later exact teruggestuurd kunnen worden (preserved thinking).
 */
class MessageAccumulator(private val onDelta: (StreamDelta) -> Unit = {}) {
    private var id: String? = null
    private var model: String? = null
    private var stopReason: String? = null
    private var stopDetails: JsonObject? = null
    private var usage = Usage()
    private val blocks = sortedMapOf<Int, JsonObject>()
    private val text = mutableMapOf<Int, StringBuilder>()
    private val thinking = mutableMapOf<Int, StringBuilder>()
    private val signature = mutableMapOf<Int, StringBuilder>()
    private val toolJson = mutableMapOf<Int, StringBuilder>()
    private val invalid = mutableSetOf<String>()
    var finished = false
        private set

    /** Verwerk één event (type + JSON data). Gooit [ClaudeApiException.StreamError] bij een `error`-event. */
    fun accept(type: String, data: JsonObject) {
        when (type) {
            "message_start" -> data.obj("message")?.let { m ->
                id = m.str("id"); model = m.str("model")
                m.obj("usage")?.let { usage = parseUsage(it, usage) }
            }
            "content_block_start" -> {
                val index = data.long("index")?.toInt() ?: return
                val block = data.obj("content_block") ?: return
                blocks[index] = block
                when (block.str("type")) {
                    "text" -> text[index] = StringBuilder(block.str("text").orEmpty())
                    "thinking" -> {
                        thinking[index] = StringBuilder(block.str("thinking").orEmpty())
                        signature[index] = StringBuilder(block.str("signature").orEmpty())
                    }
                    "tool_use", "server_tool_use" -> toolJson[index] = StringBuilder()
                }
                onDelta(StreamDelta.BlockStart(index, block.str("type") ?: "unknown", block))
            }
            "content_block_delta" -> {
                val index = data.long("index")?.toInt() ?: return
                val delta = data.obj("delta") ?: return
                when (delta.str("type")) {
                    "text_delta" -> delta.str("text")?.let {
                        text.getOrPut(index) { StringBuilder() }.append(it); onDelta(StreamDelta.Text(index, it))
                    }
                    "thinking_delta" -> delta.str("thinking")?.let {
                        thinking.getOrPut(index) { StringBuilder() }.append(it); onDelta(StreamDelta.Thinking(index, it))
                    }
                    "signature_delta" -> delta.str("signature")?.let { signature.getOrPut(index) { StringBuilder() }.append(it) }
                    "input_json_delta" -> delta.str("partial_json")?.let {
                        toolJson.getOrPut(index) { StringBuilder() }.append(it); onDelta(StreamDelta.ToolInput(index, it))
                    }
                    "citations_delta" -> delta.obj("citation")?.let { c ->
                        val b = blocks[index] ?: return@let
                        val existing = (b["citations"] as? JsonArray)?.toList().orEmpty()
                        blocks[index] = b.with("citations", JsonArray(existing + c))
                    }
                }
            }
            "content_block_stop" -> {
                val index = data.long("index")?.toInt() ?: return
                finalizeBlock(index)
                onDelta(StreamDelta.BlockStop(index))
            }
            "message_delta" -> {
                data.obj("delta")?.let { d ->
                    d.str("stop_reason")?.let { stopReason = it }
                    d.obj("stop_details")?.let { stopDetails = it }
                }
                data.obj("usage")?.let { usage = parseUsage(it, usage) }
            }
            "message_stop" -> finished = true
            "error" -> {
                val err = data.obj("error")
                throw ClaudeApiException.StreamError(err?.str("type") ?: "error", err?.str("message") ?: data.toString())
            }
            // "ping" en onbekende events negeren
        }
    }

    private fun finalizeBlock(index: Int) {
        val block = blocks[index] ?: return
        blocks[index] = when (block.str("type")) {
            "text" -> block.with("text", JsonPrimitive(text[index]?.toString().orEmpty()))
            "thinking" -> block
                .with("thinking", JsonPrimitive(thinking[index]?.toString().orEmpty()))
                .with("signature", JsonPrimitive(signature[index]?.toString().orEmpty()))
            "tool_use", "server_tool_use" -> {
                val raw = toolJson[index]?.toString().orEmpty()
                val parsed = parseToolInput(raw)
                if (parsed == null) {
                    block.str("id")?.let { invalid += it }
                    block.with("input", JsonObject(emptyMap()))
                } else block.with("input", parsed)
            }
            else -> block
        }
    }

    fun result(): AssistantResponse {
        // blokken zonder content_block_stop (afgebroken stream) ook afronden
        blocks.keys.toList().forEach { idx ->
            val b = blocks[idx]!!
            val needs = when (b.str("type")) {
                "text" -> text.containsKey(idx) && b.str("text") != text[idx].toString()
                "tool_use", "server_tool_use" -> toolJson[idx]?.isNotEmpty() == true && (b["input"] as? JsonObject)?.isEmpty() != false
                "thinking" -> thinking.containsKey(idx) && b.str("thinking") != thinking[idx].toString()
                else -> false
            }
            if (needs) finalizeBlock(idx)
        }
        return AssistantResponse(
            id = id,
            model = model,
            content = JsonArray(blocks.values.toList()),
            stopReason = stopReason,
            stopDetails = stopDetails,
            usage = usage,
            invalidToolInputs = invalid.toSet(),
        )
    }

    companion object {
        /** Lege invoer = `{}`; ongeldige/afgekapte JSON = null. */
        fun parseToolInput(raw: String): JsonObject? {
            if (raw.isBlank()) return JsonObject(emptyMap())
            return runCatching { AppJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        }

        fun parseUsage(u: JsonObject, previous: Usage = Usage()): Usage = Usage(
            inputTokens = u.long("input_tokens") ?: previous.inputTokens,
            outputTokens = u.long("output_tokens") ?: previous.outputTokens,
            cacheReadTokens = u.long("cache_read_input_tokens") ?: previous.cacheReadTokens,
            cacheWriteTokens = u.long("cache_creation_input_tokens") ?: previous.cacheWriteTokens,
        )

        /** Niet-streaming response omzetten. */
        fun fromMessage(m: JsonObject): AssistantResponse = AssistantResponse(
            id = m.str("id"),
            model = m.str("model"),
            content = (m["content"] as? JsonArray) ?: JsonArray(emptyList()),
            stopReason = m.str("stop_reason"),
            stopDetails = m.obj("stop_details"),
            usage = m.obj("usage")?.let { parseUsage(it) } ?: Usage(),
        )
    }
}
