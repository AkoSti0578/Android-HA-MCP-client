package nl.ako.hachat.data.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.ako.hachat.data.settings.SettingsRepository
import nl.ako.hachat.domain.ToolCall
import nl.ako.hachat.domain.ToolExecutor
import nl.ako.hachat.domain.ToolOutcome
import okhttp3.OkHttpClient

sealed interface McpState {
    data object NotConfigured : McpState
    data object Idle : McpState
    data object Connecting : McpState
    data class Connected(val server: McpServerInfo, val tools: List<McpTool>) : McpState
    data class Failed(val message: String) : McpState
}

/** Houdt de MCP-verbinding + toollijst bij en voert tools uit voor de agent-loop. */
class McpRepository(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) : ToolExecutor {
    private val _state = MutableStateFlow<McpState>(McpState.Idle)
    val state: StateFlow<McpState> = _state.asStateFlow()
    private val refreshLock = Mutex()

    private val client = McpClient(http) { currentConfig() }

    private suspend fun currentConfig(): McpConfig? {
        val s = settings.currentSecrets()
        val url = s.mcpUrl ?: return null
        return McpConfig(url, settings.current().mcpHeaderName, s.mcpHeaderValue)
    }

    /** (Her)verbinden en toollijst ophalen. */
    suspend fun refresh(force: Boolean = false): McpState = refreshLock.withLock {
        val current = _state.value
        if (!force && current is McpState.Connected) return current
        if (currentConfig() == null) return McpState.NotConfigured.also { _state.value = it }
        _state.value = McpState.Connecting
        val next = try {
            val info = client.connect(forceNew = force)
            McpState.Connected(info, client.listTools())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            McpState.Failed(e.message ?: e.javaClass.simpleName)
        }
        _state.value = next
        next
    }

    /** Gebruikt na wijzigen van URL/header in instellingen. */
    suspend fun reconfigure() {
        client.close()
        _state.value = McpState.Idle
    }

    /** Test een (nog niet opgeslagen) configuratie met een losse client. */
    suspend fun test(config: McpConfig): Result<Pair<McpServerInfo, List<McpTool>>> = runCatching {
        val c = McpClient(http) { config }
        try {
            c.connect() to c.listTools()
        } finally {
            c.close()
        }
    }

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val r = client.callTool(call.mcpName, call.input)
        return ToolOutcome(r.content, r.isError, r.displayText)
    }
}
