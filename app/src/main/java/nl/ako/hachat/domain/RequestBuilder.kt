package nl.ako.hachat.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.ako.hachat.core.arr
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.core.str
import nl.ako.hachat.core.with

/** Alles wat per beurt vastligt voor de request-opbouw. */
data class TurnConfig(
    val model: String,
    val maxTokens: Int,
    val systemPrompt: String,
    val tools: List<JsonObject>,
    val stream: Boolean = true,
    val effort: String? = null,
    val showThinking: Boolean = false,
    val refusalFallback: Boolean = false,
    val maxIterations: Int = 15,
)

object RequestBuilder {
    private val EPHEMERAL = jsonOf("type" to "ephemeral")

    /** Modellen waarvoor server-side `fallbacks: "default"` bestaat. */
    private val FALLBACK_MODELS = Regex("^claude-(fable-5(-[12])?|mythos-5(-[12])?|opus-5(-5)?|sonnet-5-5)$")

    fun supportsFallback(model: String) = FALLBACK_MODELS.matches(model)

    fun betas(cfg: TurnConfig): List<String> =
        if (cfg.refusalFallback && supportsFallback(cfg.model)) listOf("server-side-fallback-2026-07-01") else emptyList()

    /**
     * Bouwt de body voor POST /v1/messages.
     * Prompt caching: breakpoints op de laatste tool, de system prompt en het laatste
     * content-blok van het laatste bericht (gesprek-historie). Max 4 toegestaan; wij gebruiken 3.
     */
    fun build(cfg: TurnConfig, history: List<JsonObject>): JsonObject {
        val tools = cfg.tools.mapIndexed { i, t -> if (i == cfg.tools.lastIndex) t.with("cache_control", EPHEMERAL) else t }
        val messages = withHistoryBreakpoint(normalize(history))
        var body = jsonOf(
            "model" to cfg.model,
            "max_tokens" to cfg.maxTokens,
            "system" to listOf(jsonOf("type" to "text", "text" to cfg.systemPrompt, "cache_control" to EPHEMERAL)),
            "messages" to messages,
        )
        if (tools.isNotEmpty()) body = body.with("tools", JsonArray(tools))
        if (cfg.stream) body = body.with("stream", JsonPrimitive(true))
        if (cfg.effort != null) body = body.with("output_config", jsonOf("effort" to cfg.effort))
        if (cfg.showThinking) body = body.with("thinking", jsonOf("type" to "adaptive", "display" to "summarized"))
        if (cfg.refusalFallback && supportsFallback(cfg.model)) body = body.with("fallbacks", JsonPrimitive("default"))
        return body
    }

    /**
     * Opeenvolgende berichten met dezelfde rol samenvoegen (kan ontstaan na een afgebroken
     * of geweigerde beurt) en string-content naar blokken omzetten. Het eerste bericht moet
     * van de gebruiker zijn.
     */
    fun normalize(history: List<JsonObject>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (m in history) {
            val role = m.str("role") ?: continue
            val blocks = contentBlocks(m)
            if (blocks.isEmpty()) continue
            val prev = out.lastOrNull()
            if (prev != null && prev.str("role") == role) {
                // tool_result-blokken moeten vooraan in een user-bericht staan
                val merged = (prev.arr("content")!!.toList() + blocks).let { all ->
                    if (role == "user") all.sortedBy { if ((it as? JsonObject)?.str("type") == "tool_result") 0 else 1 } else all
                }
                out[out.lastIndex] = prev.with("content", JsonArray(merged))
            } else {
                out += jsonOf("role" to role, "content" to JsonArray(blocks))
            }
        }
        while (out.firstOrNull()?.str("role") == "assistant") out.removeAt(0)
        return out
    }

    private fun contentBlocks(m: JsonObject): List<JsonElement> = when (val c = m["content"]) {
        is JsonPrimitive -> if (c.content.isEmpty()) emptyList() else listOf(jsonOf("type" to "text", "text" to c.content))
        is JsonArray -> c.toList()
        else -> emptyList()
    }

    private fun withHistoryBreakpoint(messages: List<JsonObject>): List<JsonObject> {
        if (messages.isEmpty()) return messages
        val last = messages.last()
        val blocks = last.arr("content")?.toMutableList() ?: return messages
        val idx = blocks.indexOfLast { (it as? JsonObject)?.str("type") in CACHEABLE }
        if (idx == -1) return messages
        blocks[idx] = (blocks[idx] as JsonObject).with("cache_control", EPHEMERAL)
        return messages.dropLast(1) + last.with("content", JsonArray(blocks))
    }

    private val CACHEABLE = setOf("text", "tool_result", "image", "document", "tool_use")
}
