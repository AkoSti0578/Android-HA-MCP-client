package nl.ako.hachat.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import nl.ako.hachat.core.str

/**
 * Server-side refusal fallback: na een fallback midden in de output mogen thinking-,
 * redacted_thinking- en tool_use-blokken (en ongepaarde server_tool_use) vóór het laatste
 * `fallback`-blok niet worden teruggestuurd. Tekst en alles ná de grens wel; het
 * `fallback`-blok zelf blijft op zijn plek.
 */
object FallbackEcho {
    private val dropBefore = setOf("thinking", "redacted_thinking", "tool_use")

    fun sanitize(content: JsonArray): JsonArray {
        val blocks = content.mapNotNull { it as? JsonObject }
        val last = blocks.indexOfLast { it.str("type") == "fallback" }
        if (last <= 0) return content
        val serverResults = blocks.filter { it.str("type")?.endsWith("_tool_result") == true }
            .mapNotNull { it.str("tool_use_id") }.toSet()
        val kept = blocks.filterIndexed { i, b ->
            if (i >= last) return@filterIndexed true
            when (val t = b.str("type")) {
                in dropBefore -> false
                "server_tool_use" -> b.str("id") in serverResults
                "text", "fallback" -> true
                else -> t?.endsWith("_tool_result") == true
            }
        }
        return JsonArray(kept)
    }
}
