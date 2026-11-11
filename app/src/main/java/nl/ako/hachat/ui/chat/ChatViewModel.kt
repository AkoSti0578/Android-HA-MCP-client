package nl.ako.hachat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import nl.ako.hachat.core.Redaction
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.core.str
import nl.ako.hachat.data.anthropic.AnthropicClient
import nl.ako.hachat.data.chat.ChatRepository
import nl.ako.hachat.data.db.ConversationEntity
import nl.ako.hachat.data.db.MessageEntity
import nl.ako.hachat.data.mcp.McpRepository
import nl.ako.hachat.data.mcp.McpState
import nl.ako.hachat.data.settings.AppSettings
import nl.ako.hachat.data.settings.SettingsRepository
import nl.ako.hachat.domain.AgentListener
import nl.ako.hachat.domain.AgentLoop
import nl.ako.hachat.domain.AssistantResponse
import nl.ako.hachat.domain.Notice
import nl.ako.hachat.domain.StreamDelta
import nl.ako.hachat.domain.ToolAccess
import nl.ako.hachat.domain.ToolCall
import nl.ako.hachat.domain.ToolClassifier
import nl.ako.hachat.domain.ToolConverter
import nl.ako.hachat.domain.ToolStatus
import nl.ako.hachat.domain.TurnConfig
import java.util.UUID
import javax.inject.Inject

data class PendingApproval(val call: ToolCall, val decision: CompletableDeferred<Boolean>)

/** Live (nog niet opgeslagen) blok van het bericht dat nu binnenstroomt. */
data class LiveBlock(val type: String, val text: String = "", val toolId: String? = null, val toolName: String? = null)

data class ChatUiState(
    val conversations: List<ConversationEntity> = emptyList(),
    val currentId: Long? = null,
    val items: List<ChatItem> = emptyList(),
    val busy: Boolean = false,
    val status: String? = null,
    val pending: PendingApproval? = null,
    val mcp: McpState = McpState.Idle,
    val settings: AppSettings = AppSettings(),
    val canRetry: Boolean = false,
)

/** Tool-metadata van de huidige verbinding, voor weergave. */
private data class ToolMeta(val names: Map<String, String> = emptyMap(), val access: Map<String, ToolAccess> = emptyMap())

private data class Prepared(
    val cfg: TurnConfig,
    val nameMap: Map<String, String>,
    val access: Map<String, ToolAccess>,
    val allowed: Set<String>,
)

/** Acties van het chatscherm (los van de ViewModel, zodat de UI testbaar is). */
interface ChatActions {
    fun setInput(text: String)
    fun send(text: String)
    fun stop()
    fun retry()
    fun approve(ok: Boolean)
    fun newConversation()
    fun selectConversation(id: Long)
    fun deleteConversation(id: Long)
    fun reconnect()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val mcp: McpRepository,
    private val claude: AnthropicClient,
) : ViewModel(), ChatActions {

    private val currentId = MutableStateFlow<Long?>(null)
    private val live = MutableStateFlow<Map<Int, LiveBlock>>(emptyMap())
    private val toolStatus = MutableStateFlow<Map<String, ToolStatus>>(emptyMap())
    private val meta = MutableStateFlow(ToolMeta())
    private val busy = MutableStateFlow(false)
    private val status = MutableStateFlow<String?>(null)
    private val pending = MutableStateFlow<PendingApproval?>(null)
    private val canRetry = MutableStateFlow(false)

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var job: Job? = null

    private val messages = currentId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chat.messages(id) }

    private val items = combine(messages, live, toolStatus, meta, settingsRepo.settings) { msgs, lv, st, m, s ->
        buildItems(msgs, lv, st, m, s)
    }

    private data class Flags(val busy: Boolean, val status: String?, val pending: PendingApproval?, val canRetry: Boolean)

    private val flags = combine(busy, status, pending, canRetry) { b, s, p, r -> Flags(b, s, p, r) }

    val state: StateFlow<ChatUiState> = combine(
        combine(chat.conversations, currentId, ::Pair),
        items,
        flags,
        mcp.state,
        settingsRepo.settings,
    ) { (convs, id), its, f, mcpState, s ->
        ChatUiState(convs, id, its, f.busy, f.status, f.pending, mcpState, s, f.canRetry)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch { updateMeta(mcp.refresh()) }
    }

    override fun setInput(text: String) { _input.value = text }

    override fun newConversation() {
        if (busy.value) return
        currentId.value = null; canRetry.value = false
    }

    override fun selectConversation(id: Long) {
        if (busy.value) return
        currentId.value = id; canRetry.value = false
    }

    override fun deleteConversation(id: Long) {
        if (busy.value && currentId.value == id) return
        viewModelScope.launch {
            chat.delete(id)
            if (currentId.value == id) currentId.value = null
        }
    }

    override fun reconnect() {
        viewModelScope.launch { updateMeta(mcp.refresh(force = true)) }
    }

    override fun approve(ok: Boolean) {
        pending.value?.decision?.complete(ok)
    }

    /** Annuleert de lopende beurt; een openstaande bevestiging vervalt daarmee ook (await is annuleerbaar). */
    override fun stop() {
        job?.cancel()
    }

    override fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || job?.isActive == true) return
        _input.value = ""
        job = viewModelScope.launch {
            busy.value = true; canRetry.value = false
            try {
                status.value = "Verbinden met Home Assistant…"
                val prep = prepare() ?: run { _input.value = t; return@launch }
                val convId = currentId.value ?: chat.createConversation(t).also { currentId.value = it }
                val turnId = UUID.randomUUID().toString()
                val history = chat.history(convId)
                val user = jsonOf("role" to "user", "content" to JsonArray(listOf(jsonOf("type" to "text", "text" to t))))
                history += user
                chat.appendApiMessage(convId, turnId, user)
                runLoop(convId, turnId, history, prep)
            } finally {
                busy.value = false; status.value = null; live.value = emptyMap(); pending.value = null
            }
        }
    }

    /** Laatste beurt opnieuw proberen (na een fout), zonder het gebruikersbericht te dupliceren. */
    override fun retry() {
        val convId = currentId.value ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            busy.value = true; canRetry.value = false
            try {
                val prep = prepare() ?: return@launch
                val history = chat.history(convId)
                if (history.lastOrNull()?.str("role") != "user") return@launch
                runLoop(convId, UUID.randomUUID().toString(), history, prep)
            } finally {
                busy.value = false; status.value = null; live.value = emptyMap(); pending.value = null
            }
        }
    }

    private suspend fun prepare(): Prepared? {
        val s = settingsRepo.current()
        val secrets = settingsRepo.currentSecrets()
        if (secrets.apiKey == null) { _events.send("Stel eerst je Claude API key in (Instellingen)."); return null }
        val state = mcp.refresh()
        updateMeta(state)
        val connected = when (state) {
            is McpState.Connected -> state
            McpState.NotConfigured -> { _events.send("Stel eerst de MCP-server URL in (Instellingen)."); return null }
            is McpState.Failed -> { _events.send(state.message); return null }
            else -> { _events.send("MCP-server niet verbonden."); return null }
        }
        val classifier = ToolClassifier(s.writePatterns, s.readPatterns, s.toolOverrides)
        val access = connected.tools.associate { it.name to classifier.classify(it).access }
        val offered = if (s.readOnlyMode) connected.tools.filter { access[it.name] == ToolAccess.READ } else connected.tools
        val converted = ToolConverter.convert(offered, eagerInputStreaming = s.streaming)
        val info = s.models.find { it.id == s.model }
        val cfg = TurnConfig(
            model = s.model,
            maxTokens = s.maxTokens,
            systemPrompt = s.systemPrompt + if (s.readOnlyMode) "\n\nLees-modus staat aan: je kunt niets wijzigen in Home Assistant, alleen informatie opvragen." else "",
            tools = converted.definitions,
            stream = s.streaming,
            effort = s.effort?.takeIf { info?.effortLevels?.contains(it) == true },
            showThinking = s.showThinking && info?.supportsAdaptiveThinking == true,
            refusalFallback = s.refusalFallback,
        )
        // nameMap ook voor tools die (lees-modus) niet zijn meegestuurd, zodat oude tool_use-blokken herkend worden
        val fullNames = ToolConverter.convert(connected.tools).nameMap
        return Prepared(cfg, fullNames + converted.nameMap, access, converted.nameMap.keys)
    }

    private fun updateMeta(state: McpState) {
        if (state !is McpState.Connected) return
        viewModelScope.launch {
            val s = settingsRepo.current()
            val classifier = ToolClassifier(s.writePatterns, s.readPatterns, s.toolOverrides)
            meta.value = ToolMeta(
                names = ToolConverter.convert(state.tools).nameMap,
                access = state.tools.associate { it.name to classifier.classify(it).access },
            )
        }
    }

    private suspend fun runLoop(convId: Long, turnId: String, history: MutableList<JsonObject>, prep: Prepared) {
        val secrets = settingsRepo.currentSecrets()
        status.value = "Claude denkt na…"
        val loop = AgentLoop(claude, mcp) { call ->
            val d = CompletableDeferred<Boolean>()
            pending.value = PendingApproval(call, d)
            status.value = "Wacht op jouw bevestiging…"
            try { d.await() } finally { pending.value = null }
        }
        val listener = object : AgentListener {
            override fun onDelta(delta: StreamDelta) = handleDelta(delta)

            override suspend fun onMessage(message: JsonObject, response: AssistantResponse?) {
                chat.appendApiMessage(
                    convId, turnId, message,
                    model = response?.model, stopReason = response?.stopReason,
                    usage = response?.usage ?: nl.ako.hachat.domain.Usage(),
                )
                live.value = emptyMap()
            }

            override fun onToolStatus(callId: String, status: ToolStatus) {
                toolStatus.update { it + (callId to status) }
                if (status == ToolStatus.RUNNING) this@ChatViewModel.status.value = "Home Assistant raadplegen…"
            }

            override suspend fun onNotice(notice: Notice) = chat.appendNotice(convId, turnId, notice)

            /** Gestreamde maar afgebroken tekst zichtbaar houden (alleen UI, gaat niet naar de API). */
            override suspend fun onCancelled() {
                val partial = live.value.entries.sortedBy { it.key }
                    .filter { it.value.type == "text" }.joinToString("\n\n") { it.value.text }.trim()
                if (partial.isNotEmpty()) chat.appendNotice(convId, turnId, Notice(Notice.Kind.PARTIAL, partial))
            }
        }
        try {
            loop.runTurn(prep.cfg, history, prep.nameMap, prep.access, prep.allowed, listener)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = Redaction.scrub(e.message ?: e.javaClass.simpleName, secrets.apiKey, secrets.mcpUrl, secrets.mcpHeaderValue)
            chat.appendNotice(convId, turnId, Notice(Notice.Kind.ERROR, msg))
            canRetry.value = true
        }
    }

    private fun handleDelta(d: StreamDelta) {
        when (d) {
            StreamDelta.Reset -> live.value = emptyMap()
            is StreamDelta.BlockStart -> {
                live.update { it + (d.index to LiveBlock(d.type, "", d.block.str("id"), d.block.str("name"))) }
                status.value = when (d.type) {
                    "thinking" -> "Claude denkt na…"
                    "tool_use" -> "Claude kiest een tool…"
                    else -> "Claude schrijft…"
                }
            }
            is StreamDelta.Text -> live.update { m -> m[d.index]?.let { m + (d.index to it.copy(text = it.text + d.text)) } ?: m }
            is StreamDelta.Thinking -> live.update { m -> m[d.index]?.let { m + (d.index to it.copy(text = it.text + d.text)) } ?: m }
            is StreamDelta.ToolInput -> live.update { m -> m[d.index]?.let { m + (d.index to it.copy(text = it.text + d.partialJson)) } ?: m }
            is StreamDelta.Retrying -> status.value = "${d.reason} Nieuwe poging over ${d.delaySeconds}s…"
            is StreamDelta.BlockStop -> Unit
        }
    }

    private fun buildItems(
        msgs: List<MessageEntity>,
        lv: Map<Int, LiveBlock>,
        st: Map<String, ToolStatus>,
        m: ToolMeta,
        s: AppSettings,
    ): List<ChatItem> {
        val base = ChatItemMapper.map(msgs, m.names, m.access, st)
        val liveItems = lv.entries.sortedBy { it.key }.mapNotNull { (i, b) ->
            when (b.type) {
                "text" -> b.text.takeIf { it.isNotEmpty() }?.let { ChatItem.AssistantText("live-$i", it, streaming = true) }
                "thinking" -> ChatItem.Thinking("live-$i", b.text.ifEmpty { "…" }, streaming = true)
                "tool_use" -> {
                    val name = b.toolName.orEmpty()
                    val mcpName = m.names[name] ?: name
                    ChatItem.ToolCallItem("t-${b.toolId}", b.toolId.orEmpty(), mcpName, null, b.text, null, false, ToolStatus.RUNNING, m.access[mcpName])
                }
                else -> null
            }
        }
        return (base + liveItems).filter { item ->
            when (item) {
                is ChatItem.ToolCallItem -> s.showToolCalls || item.status == ToolStatus.AWAITING_APPROVAL
                is ChatItem.Thinking -> s.showThinking
                else -> true
            }
        }
    }

    override fun onCleared() {
        pending.value?.decision?.complete(false)
        super.onCleared()
    }
}
