package nl.ako.hachat.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import nl.ako.hachat.core.AppJson
import nl.ako.hachat.core.bool
import nl.ako.hachat.core.obj
import nl.ako.hachat.core.str
import nl.ako.hachat.data.db.MessageEntity
import nl.ako.hachat.domain.AgentLoop
import nl.ako.hachat.domain.Notice
import nl.ako.hachat.domain.ToolAccess
import nl.ako.hachat.domain.ToolStatus
import nl.ako.hachat.domain.Usage

/** Wat de chat-lijst toont. */
sealed interface ChatItem {
    val key: String

    data class UserText(override val key: String, val text: String) : ChatItem
    data class AssistantText(override val key: String, val markdown: String, val streaming: Boolean = false) : ChatItem
    data class Thinking(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem
    data class ToolCallItem(
        override val key: String,
        val id: String,
        val name: String,
        val input: JsonObject?,
        val partialInput: String? = null,
        val result: String? = null,
        val isError: Boolean = false,
        val status: ToolStatus?,
        val access: ToolAccess?,
    ) : ChatItem
    data class FallbackSwitch(override val key: String, val from: String?, val to: String?) : ChatItem
    data class NoticeItem(override val key: String, val kind: Notice.Kind, val text: String) : ChatItem
    data class TurnUsage(override val key: String, val usage: Usage, val steps: Int, val model: String?) : ChatItem
}

object ChatItemMapper {
    /**
     * Zet opgeslagen berichten om naar chat-items. Tool-calls worden gekoppeld aan hun resultaat.
     * @param displayNames Anthropic-toolnaam -> MCP-naam (voor weergave)
     */
    fun map(
        messages: List<MessageEntity>,
        displayNames: Map<String, String> = emptyMap(),
        access: Map<String, ToolAccess> = emptyMap(),
        liveStatus: Map<String, ToolStatus> = emptyMap(),
    ): List<ChatItem> {
        val parsed = messages.map { it to runCatching { AppJson.parseToJsonElement(it.contentJson) }.getOrNull() }
        // eerst alle tool_results verzamelen
        val results = mutableMapOf<String, JsonObject>()
        parsed.forEach { (m, c) ->
            if (m.role == "user") (c as? JsonArray)?.forEach { b ->
                val o = b as? JsonObject ?: return@forEach
                if (o.str("type") == "tool_result") o.str("tool_use_id")?.let { results[it] = o }
            }
        }
        val out = mutableListOf<ChatItem>()
        var turn: String? = null
        var turnUsage = Usage(); var steps = 0; var model: String? = null
        fun closeTurn() {
            if (turn != null && steps > 0) out += ChatItem.TurnUsage("usage-$turn", turnUsage, steps, model)
            turnUsage = Usage(); steps = 0; model = null
        }
        parsed.forEach { (m, content) ->
            if (m.turnId != turn) { closeTurn(); turn = m.turnId }
            when (m.role) {
                "notice" -> {
                    val kind = runCatching { Notice.Kind.valueOf(m.noticeKind ?: "INFO") }.getOrDefault(Notice.Kind.INFO)
                    val text = (content as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                    out += if (kind == Notice.Kind.PARTIAL) {
                        ChatItem.AssistantText("n-${m.id}", "$text\n\n*(afgebroken)*")
                    } else ChatItem.NoticeItem("n-${m.id}", kind, text)
                }
                "user" -> (content as? JsonArray)?.forEachIndexed { i, b ->
                    val o = b as? JsonObject ?: return@forEachIndexed
                    if (o.str("type") == "text") out += ChatItem.UserText("u-${m.id}-$i", o.str("text").orEmpty())
                }
                "assistant" -> {
                    steps++
                    turnUsage += Usage(m.inputTokens, m.outputTokens, m.cacheReadTokens, m.cacheWriteTokens)
                    model = m.model ?: model
                    (content as? JsonArray)?.forEachIndexed { i, b ->
                        val o = b as? JsonObject ?: return@forEachIndexed
                        blockToItem("a-${m.id}-$i", o, results, displayNames, access, liveStatus)?.let { out += it }
                    }
                }
            }
        }
        closeTurn()
        return out
    }

    fun blockToItem(
        key: String,
        o: JsonObject,
        results: Map<String, JsonObject>,
        displayNames: Map<String, String>,
        access: Map<String, ToolAccess>,
        liveStatus: Map<String, ToolStatus>,
    ): ChatItem? = when (o.str("type")) {
        "text" -> o.str("text")?.takeIf { it.isNotBlank() }?.let { ChatItem.AssistantText(key, it) }
        "thinking" -> o.str("thinking")?.takeIf { it.isNotBlank() }?.let { ChatItem.Thinking(key, it) }
        "tool_use" -> {
            val id = o.str("id").orEmpty()
            val name = o.str("name").orEmpty()
            val mcpName = displayNames[name] ?: name
            val r = results[id]
            val resultText = r?.let { resultText(it) }
            val isError = r?.bool("is_error") == true
            val status = when {
                r == null -> liveStatus[id]
                resultText == AgentLoop.REJECTED_TEXT -> ToolStatus.REJECTED
                isError -> ToolStatus.FAILED
                else -> ToolStatus.DONE
            }
            ChatItem.ToolCallItem("t-$id", id, mcpName, o.obj("input"), null, resultText, isError, status, access[mcpName])
        }
        "fallback" -> ChatItem.FallbackSwitch(key, o.obj("from")?.str("model"), o.obj("to")?.str("model"))
        else -> null
    }

    fun resultText(r: JsonObject): String = when (val c = r["content"]) {
        is JsonArray -> c.mapNotNull { b ->
            val o = b as? JsonObject ?: return@mapNotNull null
            when (o.str("type")) {
                "text" -> o.str("text")
                "image" -> "[afbeelding]"
                else -> null
            }
        }.joinToString("\n")
        is kotlinx.serialization.json.JsonPrimitive -> c.content
        else -> ""
    }

}
