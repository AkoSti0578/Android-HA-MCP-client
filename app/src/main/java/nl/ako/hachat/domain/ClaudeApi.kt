package nl.ako.hachat.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Token-gebruik van één API-response (of opgeteld over een beurt). */
@Serializable
data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) {
    operator fun plus(o: Usage) = Usage(
        inputTokens + o.inputTokens,
        outputTokens + o.outputTokens,
        cacheReadTokens + o.cacheReadTokens,
        cacheWriteTokens + o.cacheWriteTokens,
    )
    val totalInput: Long get() = inputTokens + cacheReadTokens + cacheWriteTokens
}

/** Volledig assistant-bericht zoals de API het teruggaf (content blokken ongewijzigd). */
data class AssistantResponse(
    val id: String?,
    val model: String?,
    val content: JsonArray,
    val stopReason: String?,
    val stopDetails: JsonObject?,
    val usage: Usage,
    /** IDs van tool_use-blokken waarvan de gestreamde JSON-invoer niet te parsen was. */
    val invalidToolInputs: Set<String> = emptySet(),
)

/** Live gebeurtenissen tijdens het streamen, voor de UI. */
sealed interface StreamDelta {
    data class BlockStart(val index: Int, val type: String, val block: JsonObject) : StreamDelta
    data class Text(val index: Int, val text: String) : StreamDelta
    data class Thinking(val index: Int, val text: String) : StreamDelta
    data class ToolInput(val index: Int, val partialJson: String) : StreamDelta
    data class BlockStop(val index: Int) : StreamDelta
    data class Retrying(val attempt: Int, val delaySeconds: Long, val reason: String) : StreamDelta
    /** Er is een nieuw API-verzoek gestart (na retry of nieuwe iteratie) — UI moet live-buffer legen. */
    data object Reset : StreamDelta
}

/** Abstractie over de Messages API, zodat de agent-loop met een fake getest kan worden. */
interface ClaudeApi {
    /**
     * Stuurt [body] naar POST /v1/messages. Bij `"stream": true` worden live deltas via [onDelta]
     * gemeld; het resultaat is altijd het volledige, samengestelde bericht.
     */
    suspend fun createMessage(
        body: JsonObject,
        betas: List<String> = emptyList(),
        onDelta: (StreamDelta) -> Unit = {},
    ): AssistantResponse
}
