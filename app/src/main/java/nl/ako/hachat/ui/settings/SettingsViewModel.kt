package nl.ako.hachat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.ako.hachat.core.Redaction
import nl.ako.hachat.data.anthropic.AnthropicClient
import nl.ako.hachat.data.mcp.McpConfig
import nl.ako.hachat.data.mcp.McpRepository
import nl.ako.hachat.data.mcp.McpState
import nl.ako.hachat.data.mcp.McpTool
import nl.ako.hachat.data.settings.AppSettings
import nl.ako.hachat.data.settings.DEFAULT_SYSTEM_PROMPT
import nl.ako.hachat.data.settings.SettingsRepository
import nl.ako.hachat.data.settings.ToolOverride
import nl.ako.hachat.data.settings.ToolPatterns
import nl.ako.hachat.domain.Classification
import nl.ako.hachat.domain.ToolAccess
import nl.ako.hachat.domain.ToolClassifier
import javax.inject.Inject

sealed interface TaskState {
    data object Idle : TaskState
    data object Running : TaskState
    data class Ok(val message: String) : TaskState
    data class Failed(val message: String) : TaskState
}

data class SecretHints(val apiKey: String?, val mcpUrl: String?, val mcpHeader: String?)

data class ToolRow(val tool: McpTool, val classification: Classification, val override: ToolOverride)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val hints: SecretHints = SecretHints(null, null, null),
    val models: TaskState = TaskState.Idle,
    val test: TaskState = TaskState.Idle,
    val mcp: McpState = McpState.Idle,
    val tools: List<ToolRow> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val claude: AnthropicClient,
    private val mcp: McpRepository,
) : ViewModel() {

    private val modelsTask = MutableStateFlow<TaskState>(TaskState.Idle)
    private val testTask = MutableStateFlow<TaskState>(TaskState.Idle)

    private val hints = repo.secrets.combine(repo.secretPresence) { s, _ ->
        SecretHints(
            apiKey = s.apiKey?.let { "${it.take(7)}…" },
            mcpUrl = s.mcpUrl?.let { Redaction.url(it) },
            mcpHeader = s.mcpHeaderValue?.let { "•••••• (${it.length} tekens)" },
        )
    }

    val state: StateFlow<SettingsUiState> = combine(repo.settings, hints, modelsTask, testTask, mcp.state) { s, h, m, t, ms ->
        val classifier = ToolClassifier(s.writePatterns, s.readPatterns, s.toolOverrides)
        val rows = (ms as? McpState.Connected)?.tools.orEmpty().sortedBy { it.name }.map {
            ToolRow(it, classifier.classify(it), s.toolOverrides[it.name] ?: ToolOverride.AUTO)
        }
        SettingsUiState(s, h, m, t, ms, rows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun update(block: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(block) }

    fun saveApiKey(key: String) = viewModelScope.launch {
        val k = key.trim()
        if (k.isEmpty()) return@launch
        repo.setApiKey(k)
        refreshModels()
    }

    fun clearApiKey() = viewModelScope.launch { repo.setApiKey(null) }

    fun refreshModels() = viewModelScope.launch {
        modelsTask.value = TaskState.Running
        modelsTask.value = try {
            val models = claude.listModels()
            repo.update { s ->
                // huidige keuze behouden; bestaat die niet meer, dan de nieuwste Opus (of het eerste model)
                val keep = models.any { it.id == s.model }
                val fallback = models.firstOrNull { it.id.contains("opus") } ?: models.firstOrNull()
                s.copy(models = models, model = if (keep || fallback == null) s.model else fallback.id)
            }
            TaskState.Ok("${models.size} modellen geladen")
        } catch (e: Exception) {
            TaskState.Failed(e.message ?: "Onbekende fout")
        }
    }

    fun saveMcp(url: String?, headerName: String, headerValue: String?) = viewModelScope.launch {
        if (url != null) repo.setMcpUrl(url.takeIf { it.isNotBlank() })
        if (headerValue != null) repo.setMcpHeaderValue(headerValue.takeIf { it.isNotBlank() })
        repo.update { it.copy(mcpHeaderName = headerName) }
        mcp.reconfigure()
        mcp.refresh(force = true)
    }

    fun clearMcpHeader() = viewModelScope.launch {
        repo.setMcpHeaderValue(null)
        repo.update { it.copy(mcpHeaderName = "") }
        mcp.reconfigure()
    }

    /** Test met de ingevulde waarden; lege velden vallen terug op de opgeslagen waarden. */
    fun testConnection(url: String, headerName: String, headerValue: String) = viewModelScope.launch {
        testTask.value = TaskState.Running
        val saved = repo.currentSecrets()
        val u = url.trim().ifEmpty { saved.mcpUrl.orEmpty() }
        if (u.isEmpty()) { testTask.value = TaskState.Failed("Vul eerst een URL in."); return@launch }
        val cfg = McpConfig(u, headerName.trim().ifEmpty { repo.current().mcpHeaderName }, headerValue.trim().ifEmpty { saved.mcpHeaderValue })
        val s = repo.current()
        testTask.value = mcp.test(cfg).fold(
            onSuccess = { (info, tools) ->
                val c = ToolClassifier(s.writePatterns, s.readPatterns, s.toolOverrides)
                val writes = tools.count { c.classify(it).access == ToolAccess.WRITE }
                TaskState.Ok(
                    "Verbonden met ${info.name}${info.version?.let { " $it" } ?: ""} — ${tools.size} tools " +
                        "(${tools.size - writes} lezen, $writes schrijven)",
                )
            },
            onFailure = { TaskState.Failed(Redaction.scrub(it.message ?: "Onbekende fout", u, cfg.headerValue)) },
        )
    }

    fun resetSystemPrompt() = update { it.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) }

    fun savePatterns(write: String, read: String) =
        update { it.copy(writePatterns = ToolPatterns.parse(write), readPatterns = ToolPatterns.parse(read)) }

    fun resetPatterns() = update { it.copy(writePatterns = ToolPatterns.DEFAULT_WRITE, readPatterns = ToolPatterns.DEFAULT_READ) }

    fun setOverride(tool: String, o: ToolOverride) = update { s ->
        s.copy(toolOverrides = if (o == ToolOverride.AUTO) s.toolOverrides - tool else s.toolOverrides + (tool to o))
    }

    fun loadTools() = viewModelScope.launch { mcp.refresh() }
}
