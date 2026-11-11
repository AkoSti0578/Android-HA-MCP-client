package nl.ako.hachat.domain

import nl.ako.hachat.core.bool
import nl.ako.hachat.data.mcp.McpTool
import nl.ako.hachat.data.settings.ToolOverride

enum class ToolAccess { READ, WRITE }

data class Classification(val access: ToolAccess, val reason: String)

/**
 * Bepaalt of een tool alleen leest of iets wijzigt. Volgorde (eerste match wint):
 *  1. handmatige override van de gebruiker
 *  2. naam bevat een schrijf-patroon (set/remove/delete/call_service/...)  → schrijven
 *  3. MCP-annotatie readOnlyHint=true                                        → lezen
 *  4. naam bevat een lees-patroon (get/list/search/history/...)              → lezen
 *  5. onbekend                                                               → schrijven (fail-safe)
 *
 * Een patroon matcht op woordgrenzen binnen de naam (`_`, `-`, `.`): "set" matcht
 * `ha_config_set_automation` maar niet `ha_reset_x` of `asset_list`.
 */
class ToolClassifier(
    writePatterns: List<String>,
    readPatterns: List<String>,
    private val overrides: Map<String, ToolOverride> = emptyMap(),
) {
    private val write = writePatterns.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
    private val read = readPatterns.map { it.lowercase().trim() }.filter { it.isNotEmpty() }

    fun classify(tool: McpTool): Classification {
        when (overrides[tool.name]) {
            ToolOverride.READ -> return Classification(ToolAccess.READ, "handmatig: lezen")
            ToolOverride.WRITE -> return Classification(ToolAccess.WRITE, "handmatig: schrijven")
            else -> Unit
        }
        val name = tool.name.lowercase()
        write.firstOrNull { matches(name, it) }?.let { return Classification(ToolAccess.WRITE, "patroon '$it'") }
        if (tool.annotations?.bool("readOnlyHint") == true) return Classification(ToolAccess.READ, "server: readOnlyHint")
        read.firstOrNull { matches(name, it) }?.let { return Classification(ToolAccess.READ, "patroon '$it'") }
        return Classification(ToolAccess.WRITE, "onbekend → veilig als schrijven")
    }

    fun classifyAll(tools: List<McpTool>): Map<String, Classification> = tools.associate { it.name to classify(it) }

    companion object {
        private const val SEP = "[_.\\-/]"
        fun matches(name: String, pattern: String): Boolean {
            val p = Regex.escape(pattern)
            return Regex("(^|$SEP)$p($SEP|$)").containsMatchIn(name)
        }
    }
}
