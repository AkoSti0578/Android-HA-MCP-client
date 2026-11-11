package nl.ako.hachat.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.core.obj
import nl.ako.hachat.core.str
import nl.ako.hachat.core.with

/** Eén tool-aanroep zoals Claude die vroeg. */
data class ToolCall(
    val id: String,
    /** naam zoals Claude hem kent (gesaneerd) */
    val name: String,
    /** naam op de MCP-server */
    val mcpName: String,
    val input: JsonObject,
    val access: ToolAccess,
)

/** Uitkomst van een tool-uitvoering. */
data class ToolOutcome(val content: List<JsonObject>, val isError: Boolean, val displayText: String)

interface ToolExecutor {
    suspend fun execute(call: ToolCall): ToolOutcome
}

/** Vraagt de gebruiker om bevestiging voor schrijf-tools. */
fun interface ToolApprover {
    suspend fun approve(call: ToolCall): Boolean
}

/** Callbacks naar de UI / opslag. Persist-callbacks worden altijd (ook bij annuleren) aangeroepen. */
interface AgentListener {
    fun onDelta(delta: StreamDelta) {}
    suspend fun onMessage(message: JsonObject, response: AssistantResponse?) {}
    fun onToolStatus(callId: String, status: ToolStatus) {}
    suspend fun onNotice(notice: Notice) {}
}

enum class ToolStatus { AWAITING_APPROVAL, RUNNING, DONE, FAILED, REJECTED }

data class Notice(val kind: Kind, val text: String) {
    enum class Kind { INFO, WARNING, ERROR, REFUSAL }
}

data class TurnResult(val iterations: Int, val usage: Usage, val stopReason: String?)

/**
 * De agent-loop: stuur historie + tools naar Claude; voer tool_use-blokken uit via MCP;
 * stuur tool_results terug; herhaal tot een eindantwoord of [TurnConfig.maxIterations].
 *
 * Invarianten (zodat het gesprek na annuleren/fouten altijd geldig blijft voor de API):
 *  - Elk assistant-bericht met tool_use wordt direct gevolgd door één user-bericht met
 *    álle bijbehorende tool_results (ook bij weigeren, fouten of annuleren).
 *  - De historie is append-only: eerdere berichten worden nooit gewijzigd (preserved thinking).
 */
class AgentLoop(
    private val claude: ClaudeApi,
    private val executor: ToolExecutor,
    private val approver: ToolApprover,
) {
    suspend fun runTurn(
        cfg: TurnConfig,
        history: MutableList<JsonObject>,
        nameMap: Map<String, String>,
        access: Map<String, ToolAccess>,
        allowedTools: Set<String>,
        listener: AgentListener,
    ): TurnResult {
        var usage = Usage()
        var iterations = 0
        var lastStop: String? = null
        var pendingToolUses: List<JsonObject> = emptyList()
        try {
            while (iterations < cfg.maxIterations) {
                iterations++
                listener.onDelta(StreamDelta.Reset)
                val body = RequestBuilder.build(cfg, history)
                val response = claude.createMessage(body, RequestBuilder.betas(cfg), listener::onDelta)
                usage += response.usage
                lastStop = response.stopReason

                if (response.stopReason == "refusal") {
                    // Gedeeltelijke output van een weigering niet in de historie opnemen.
                    val cat = response.stopDetails?.str("category")
                    listener.onNotice(Notice(Notice.Kind.REFUSAL,
                        "Claude heeft dit verzoek geweigerd" + (cat?.let { " (categorie: $it)" } ?: "") +
                            ". Herformuleer je vraag of kies een ander model."))
                    break
                }

                val content = FallbackEcho.sanitize(response.content)
                val assistant = jsonOf("role" to "assistant", "content" to content)
                val toolUses = afterLastFallback(content).filter { it.str("type") == "tool_use" }
                history += assistant
                pendingToolUses = toolUses
                listener.onMessage(assistant, response)

                when {
                    toolUses.isNotEmpty() && response.stopReason == "max_tokens" -> {
                        // Afgekapte tool-invoer: niet uitvoeren, wel netjes afsluiten.
                        appendResults(history, toolUses.map { errorResult(it, "Niet uitgevoerd: invoer afgekapt doordat max_tokens werd bereikt.") }, listener)
                        pendingToolUses = emptyList()
                        listener.onNotice(Notice(Notice.Kind.WARNING, "Antwoord afgekapt: max_tokens (${cfg.maxTokens}) bereikt. Verhoog max_tokens in Instellingen."))
                        break
                    }
                    toolUses.isNotEmpty() -> {
                        val results = executeTools(toolUses, nameMap, access, allowedTools, response.invalidToolInputs, listener)
                        appendResults(history, results, listener)
                        pendingToolUses = emptyList()
                        if (iterations >= cfg.maxIterations) {
                            listener.onNotice(Notice(Notice.Kind.WARNING,
                                "Maximum van ${cfg.maxIterations} stappen bereikt; de beurt is gestopt. Stuur \"ga door\" om verder te gaan."))
                        }
                    }
                    response.stopReason == "pause_turn" -> continue
                    response.stopReason == "max_tokens" -> {
                        listener.onNotice(Notice(Notice.Kind.WARNING, "Antwoord afgekapt: max_tokens (${cfg.maxTokens}) bereikt. Verhoog max_tokens in Instellingen."))
                        break
                    }
                    else -> break
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (pendingToolUses.isNotEmpty()) {
                    appendResults(history, pendingToolUses.map { errorResult(it, "Afgebroken door gebruiker.") }, listener)
                }
                listener.onNotice(Notice(Notice.Kind.INFO, "Beurt gestopt."))
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                if (pendingToolUses.isNotEmpty()) {
                    appendResults(history, pendingToolUses.map { errorResult(it, "Niet uitgevoerd door een fout.") }, listener)
                }
            }
            throw e
        }
        return TurnResult(iterations, usage, lastStop)
    }

    private suspend fun appendResults(history: MutableList<JsonObject>, results: List<JsonObject>, listener: AgentListener) {
        val msg = jsonOf("role" to "user", "content" to JsonArray(results))
        history += msg
        listener.onMessage(msg, null)
    }

    /**
     * Voert alle tool_use-blokken uit. Lees-tools draaien parallel; schrijf-tools vragen eerst
     * (één voor één, in volgorde) om bevestiging. Resultaten keren terug in de oorspronkelijke volgorde,
     * samen in één user-bericht (zo blijft parallel tool-gebruik werken).
     */
    private suspend fun executeTools(
        toolUses: List<JsonObject>,
        nameMap: Map<String, String>,
        access: Map<String, ToolAccess>,
        allowedTools: Set<String>,
        invalidInputs: Set<String>,
        listener: AgentListener,
    ): List<JsonObject> = coroutineScope {
        val calls = toolUses.map { block ->
            val name = block.str("name").orEmpty()
            val mcpName = nameMap[name] ?: name
            ToolCall(
                id = block.str("id").orEmpty(),
                name = name,
                mcpName = mcpName,
                input = block.obj("input") ?: JsonObject(emptyMap()),
                access = access[mcpName] ?: ToolAccess.WRITE,
            )
        }
        // 1. Eerst alle bevestigingen (sequentieel), zodat de gebruiker ze één voor één ziet.
        val decisions = calls.associate { call ->
            call.id to when {
                call.id in invalidInputs -> Decision.Invalid
                call.name !in allowedTools -> Decision.NotAllowed
                call.access == ToolAccess.READ -> Decision.Run
                else -> {
                    listener.onToolStatus(call.id, ToolStatus.AWAITING_APPROVAL)
                    if (approver.approve(call)) Decision.Run else Decision.Rejected
                }
            }
        }
        // 2. Uitvoeren: lees-tools parallel; schrijf-tools in volgorde ná elkaar.
        val reads = calls.filter { decisions[it.id] == Decision.Run && it.access == ToolAccess.READ }
            .associate { call -> call.id to async { run(call, listener) } }
        val writes = mutableMapOf<String, JsonObject>()
        for (call in calls) {
            if (decisions[call.id] == Decision.Run && call.access == ToolAccess.WRITE) writes[call.id] = run(call, listener)
        }
        val readResults = reads.mapValues { it.value }.values.awaitAll().let { list -> reads.keys.zip(list).toMap() }

        calls.map { call ->
            when (decisions[call.id]) {
                Decision.Run -> readResults[call.id] ?: writes.getValue(call.id)
                Decision.Rejected -> {
                    listener.onToolStatus(call.id, ToolStatus.REJECTED)
                    errorResult(call.id, REJECTED_TEXT)
                }
                Decision.NotAllowed -> {
                    listener.onToolStatus(call.id, ToolStatus.REJECTED)
                    errorResult(call.id, "Tool '${call.name}' is niet beschikbaar (lees-modus of onbekende tool).")
                }
                Decision.Invalid, null -> {
                    listener.onToolStatus(call.id, ToolStatus.FAILED)
                    errorResult(call.id, "INVALID_JSON: de tool-invoer was geen geldige JSON. Probeer het opnieuw met geldige invoer.")
                }
            }
        }
    }

    private suspend fun run(call: ToolCall, listener: AgentListener): JsonObject {
        listener.onToolStatus(call.id, ToolStatus.RUNNING)
        return try {
            val out = executor.execute(call)
            listener.onToolStatus(call.id, if (out.isError) ToolStatus.FAILED else ToolStatus.DONE)
            toolResult(call.id, out.content, out.isError)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listener.onToolStatus(call.id, ToolStatus.FAILED)
            errorResult(call.id, "Fout bij uitvoeren: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private enum class Decision { Run, Rejected, NotAllowed, Invalid }

    companion object {
        const val REJECTED_TEXT = "Gebruiker heeft geweigerd"

        fun toolResult(id: String, content: List<JsonObject>, isError: Boolean): JsonObject {
            var r = jsonOf("type" to "tool_result", "tool_use_id" to id, "content" to JsonArray(content))
            if (isError) r = r.with("is_error", JsonPrimitive(true))
            return r
        }

        fun errorResult(id: String, text: String): JsonObject =
            toolResult(id, listOf(jsonOf("type" to "text", "text" to text)), true)

        private fun errorResult(block: JsonObject, text: String) = errorResult(block.str("id").orEmpty(), text)

        /** Bij een fallback alleen de blokken ná de laatste wissel uitvoeren. */
        fun afterLastFallback(content: JsonArray): List<JsonObject> {
            val blocks = content.mapNotNull { it as? JsonObject }
            val last = blocks.indexOfLast { it.str("type") == "fallback" }
            return if (last == -1) blocks else blocks.drop(last + 1)
        }
    }
}

