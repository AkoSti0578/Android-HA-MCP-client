package nl.ako.hachat.data.settings

import kotlinx.serialization.Serializable

const val DEFAULT_MODEL = "claude-opus-5-5"
const val DEFAULT_MAX_TOKENS = 4096

const val DEFAULT_SYSTEM_PROMPT =
    "Je bent een Home Assistant-assistent voor het huis 'Gieterij'. Antwoord in het Nederlands. " +
        "Zoek altijd eerst de juiste entity_id's op voordat je iets wijzigt. Laat bij het aanmaken of " +
        "wijzigen van automations eerst de YAML zien en leg kort uit wat die doet."

/** Effort-niveaus; null = niet meesturen (modeldefault). */
val EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")

@Serializable
enum class ToolOverride { AUTO, READ, WRITE }

@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String,
    val supportsAdaptiveThinking: Boolean = false,
    val supportsEffort: Boolean = false,
    val effortLevels: List<String> = emptyList(),
    val maxOutputTokens: Int? = null,
)

/** Niet-geheime instellingen (DataStore). */
data class AppSettings(
    val model: String = DEFAULT_MODEL,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val effort: String? = null,
    val showThinking: Boolean = false,
    val refusalFallback: Boolean = true,
    val streaming: Boolean = true,
    val readOnlyMode: Boolean = false,
    val showToolCalls: Boolean = true,
    /** Instructies/prompts van de MCP-server (Home Assistant) aan de system prompt toevoegen. */
    val useServerInstructions: Boolean = true,
    val mcpHeaderName: String = "",
    val writePatterns: List<String> = ToolPatterns.DEFAULT_WRITE,
    val readPatterns: List<String> = ToolPatterns.DEFAULT_READ,
    val toolOverrides: Map<String, ToolOverride> = emptyMap(),
    val models: List<ModelInfo> = emptyList(),
)

/** Geheimen — alleen in-memory na ontsleutelen, nooit gelogd. */
data class Secrets(
    val apiKey: String? = null,
    val mcpUrl: String? = null,
    val mcpHeaderValue: String? = null,
    /** Home Assistant long-lived access token. */
    val haToken: String? = null,
) {
    override fun toString(): String =
        "Secrets(apiKey=${if (apiKey != null) "set" else "unset"}, mcpUrl=${if (mcpUrl != null) "set" else "unset"}, header=${if (mcpHeaderValue != null) "set" else "unset"}, haToken=${if (haToken != null) "set" else "unset"})"
}

object ToolPatterns {
    val DEFAULT_WRITE = listOf(
        "set", "remove", "delete", "call_service", "restart", "bulk_control", "create", "update",
        "add", "rename", "reload", "enable", "disable", "toggle", "turn_on", "turn_off", "trigger",
        "fire_event", "install", "uninstall", "restore", "reset", "import", "assign", "write",
        "upload", "execute", "run", "start", "stop", "move", "edit", "save", "send", "apply", "manage",
        // Home Assistant Assist-intents (HassTurnOn, HassMediaPause, HassCancelTimer, ...)
        "pause", "unpause", "cancel", "broadcast", "next", "previous", "return", "open", "close",
        "lock", "unlock", "play", "increase", "decrease", "complete", "respond",
    )
    val DEFAULT_READ = listOf(
        "get", "list", "search", "history", "eval_template", "logbook", "overview", "statistics",
        "find", "read", "describe", "show", "check", "status", "query", "info", "render", "validate",
        "live_context", "date_time",
    )

    fun parse(text: String): List<String> =
        text.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
}
