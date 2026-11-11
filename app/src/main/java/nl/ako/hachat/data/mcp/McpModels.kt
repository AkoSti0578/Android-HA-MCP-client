package nl.ako.hachat.data.mcp

import kotlinx.serialization.json.JsonObject

/** Een tool zoals de MCP-server hem aanbiedt (tools/list). */
data class McpTool(
    val name: String,
    val title: String? = null,
    val description: String?,
    val inputSchema: JsonObject,
    /** MCP tool annotations (readOnlyHint, destructiveHint, ...), indien aanwezig. */
    val annotations: JsonObject? = null,
)

data class McpServerInfo(val name: String, val version: String?, val protocolVersion: String)

/** Resultaat van tools/call, omgezet naar wat Claude nodig heeft. */
data class McpToolResult(
    /** Inhoud als Anthropic tool_result content-blokken (text / image). */
    val content: List<JsonObject>,
    val isError: Boolean,
    /** Platte tekstweergave voor de UI. */
    val displayText: String,
)

data class McpConfig(val url: String, val headerName: String?, val headerValue: String?)

/** Fouten met Nederlandse uitleg. */
sealed class McpException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause) {
    class NotConfigured : McpException("Geen MCP-server ingesteld. Vul de URL in bij Instellingen.")
    class Unreachable(where: String, cause: Throwable?) : McpException(
        "MCP-server onbereikbaar ($where). Niet thuis? Zet VPN aan.", cause,
    )
    class Http(code: Int, detail: String) : McpException(
        when (code) {
            401, 403 -> "MCP-server weigert toegang ($code). Klopt de URL/het geheime pad of de extra header?"
            404 -> "MCP-endpoint niet gevonden (404). Klopt het pad /private_… in de URL?"
            else -> "MCP-server gaf HTTP $code: $detail"
        },
    )
    class Protocol(detail: String) : McpException("MCP-protocolfout: $detail")
    class Rpc(val code: Int, detail: String) : McpException("MCP-fout ($code): $detail")
    class Blocked(detail: String) : McpException(detail)
}
