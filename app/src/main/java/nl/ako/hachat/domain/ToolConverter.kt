package nl.ako.hachat.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.core.obj
import nl.ako.hachat.core.str
import nl.ako.hachat.core.with
import nl.ako.hachat.data.mcp.McpTool

/** Resultaat van de conversie: tooldefinities voor Claude + mapping terug naar MCP-namen. */
data class ConvertedTools(
    val definitions: List<JsonObject>,
    /** Anthropic toolnaam -> MCP toolnaam */
    val nameMap: Map<String, String>,
)

/**
 * Zet MCP-tools om naar Anthropic tool-definities (`name`, `description`, `input_schema`).
 * - Namen worden gesaneerd naar `^[a-zA-Z0-9_-]{1,64}$` (uniek gemaakt indien nodig).
 * - Het schema krijgt altijd `"type": "object"`.
 * - Tools worden op naam gesorteerd: een stabiele volgorde houdt de prompt-cache warm.
 */
object ToolConverter {
    private const val MAX_NAME = 64
    private val invalid = Regex("[^a-zA-Z0-9_-]")

    fun convert(tools: List<McpTool>, eagerInputStreaming: Boolean = false): ConvertedTools {
        val used = mutableSetOf<String>()
        val nameMap = linkedMapOf<String, String>()
        val defs = tools.sortedBy { it.name }.map { t ->
            val name = uniqueName(sanitize(t.name), used)
            nameMap[name] = t.name
            val desc = listOfNotNull(
                t.title?.takeIf { it.isNotBlank() && it != t.name && t.description?.contains(it) != true },
                t.description?.trim()?.takeIf { it.isNotEmpty() },
            ).joinToString("\n").ifEmpty { t.name }
            val base = jsonOf(
                "name" to name,
                "description" to desc,
                "input_schema" to normalizeSchema(t.inputSchema),
            )
            if (eagerInputStreaming) base.with("eager_input_streaming", JsonPrimitive(true)) else base
        }
        return ConvertedTools(defs, nameMap)
    }

    fun sanitize(name: String): String {
        val s = name.replace(invalid, "_").take(MAX_NAME)
        return s.ifEmpty { "tool" }
    }

    private fun uniqueName(base: String, used: MutableSet<String>): String {
        var candidate = base
        var i = 2
        while (!used.add(candidate)) {
            val suffix = "_$i"
            candidate = base.take(MAX_NAME - suffix.length) + suffix
            i++
        }
        return candidate
    }

    fun normalizeSchema(schema: JsonObject): JsonObject {
        var s = schema
        if (s.str("type") != "object") s = s.with("type", JsonPrimitive("object"))
        if (s.obj("properties") == null && "properties" !in s) s = s.with("properties", JsonObject(emptyMap()))
        return s
    }
}
