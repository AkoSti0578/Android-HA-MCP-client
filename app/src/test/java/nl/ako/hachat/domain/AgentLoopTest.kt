package nl.ako.hachat.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

import kotlinx.serialization.json.jsonObject
import nl.ako.hachat.core.arr
import nl.ako.hachat.core.bool
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.core.obj
import nl.ako.hachat.core.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    // ---------- fakes ----------

    /** Geeft vooraf opgegeven responses terug en onthoudt elke request-body. */
    private class FakeClaude(vararg responses: AssistantResponse) : ClaudeApi {
        val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<JsonObject>()
        val betas = mutableListOf<List<String>>()
        override suspend fun createMessage(body: JsonObject, betas: List<String>, onDelta: (StreamDelta) -> Unit): AssistantResponse {
            requests += body; this.betas += betas
            return queue.removeFirstOrNull() ?: endTurn("klaar")
        }
    }

    private class FakeMcp(private val delayMs: Long = 0) : ToolExecutor {
        val calls = mutableListOf<ToolCall>()
        override suspend fun execute(call: ToolCall): ToolOutcome {
            if (delayMs > 0) delay(delayMs)
            calls += call
            return ToolOutcome(listOf(jsonOf("type" to "text", "text" to "ok:${call.mcpName}")), false, "ok")
        }
    }

    private class Recorder : AgentListener {
        val messages = mutableListOf<JsonObject>()
        val notices = mutableListOf<Notice>()
        val statuses = mutableListOf<Pair<String, ToolStatus>>()
        override suspend fun onMessage(message: JsonObject, response: AssistantResponse?) { messages += message }
        override suspend fun onNotice(notice: Notice) { notices += notice }
        override fun onToolStatus(callId: String, status: ToolStatus) { statuses += callId to status }
    }

    companion object {
        fun text(t: String) = jsonOf("type" to "text", "text" to t)
        fun toolUse(id: String, name: String, input: JsonObject = JsonObject(emptyMap())) =
            jsonOf("type" to "tool_use", "id" to id, "name" to name, "input" to input)

        fun response(stop: String, vararg blocks: JsonObject, usage: Usage = Usage(10, 5)) =
            AssistantResponse("msg", "claude-opus-5-5", JsonArray(blocks.toList()), stop, null, usage)

        fun endTurn(t: String) = response("end_turn", text(t))
    }

    private val tools = listOf(
        jsonOf("name" to "ha_get_state", "description" to "x", "input_schema" to jsonOf("type" to "object")),
        jsonOf("name" to "ha_call_service", "description" to "y", "input_schema" to jsonOf("type" to "object")),
    )
    private val names = mapOf("ha_get_state" to "ha_get_state", "ha_call_service" to "ha_call_service")
    private val access = mapOf("ha_get_state" to ToolAccess.READ, "ha_call_service" to ToolAccess.WRITE)
    private val cfg = TurnConfig("claude-opus-5-5", 4096, "Je bent een HA-assistent.", tools)

    private fun user(t: String) = jsonOf("role" to "user", "content" to JsonArray(listOf(text(t))))

    private fun toolResults(msg: JsonObject) = msg.arr("content")!!.map { it.jsonObject }

    // ---------- tests ----------

    @Test
    fun `eenvoudig antwoord zonder tools`() = runTest {
        val claude = FakeClaude(endTurn("Hallo!"))
        val history = mutableListOf(user("hoi"))
        val rec = Recorder()
        val r = AgentLoop(claude, FakeMcp()) { true }.runTurn(cfg, history, names, access, names.keys, rec)
        assertEquals(1, r.iterations)
        assertEquals(2, history.size)
        assertEquals("assistant", history.last().str("role"))
        assertEquals(Usage(10, 5), r.usage)
    }

    @Test
    fun `lees-tool wordt zonder bevestiging uitgevoerd en resultaat teruggestuurd`() = runTest {
        val claude = FakeClaude(
            response("tool_use", text("Even kijken"), toolUse("t1", "ha_get_state", jsonOf("entity_id" to "light.keuken"))),
            endTurn("De keukenlamp is aan."),
        )
        val mcp = FakeMcp()
        var asked = false
        val history = mutableListOf(user("staat de keukenlamp aan?"))
        val r = AgentLoop(claude, mcp) { asked = true; true }.runTurn(cfg, history, names, access, names.keys, Recorder())

        assertFalse("lees-tools vragen geen bevestiging", asked)
        assertEquals(2, r.iterations)
        assertEquals(1, mcp.calls.size)
        assertEquals("light.keuken", mcp.calls.single().input.str("entity_id"))
        // historie: user, assistant(tool_use), user(tool_result), assistant
        assertEquals(listOf("user", "assistant", "user", "assistant"), history.map { it.str("role") })
        val result = toolResults(history[2]).single()
        assertEquals("tool_result", result.str("type"))
        assertEquals("t1", result.str("tool_use_id"))
        assertEquals(null, result.bool("is_error"))
        // tweede request bevat het tool_result als laatste bericht
        val second = claude.requests[1].arr("messages")!!
        assertEquals("user", second.last().jsonObject.str("role"))
        assertEquals(Usage(20, 10), r.usage)
    }

    @Test
    fun `schrijf-tool vraagt bevestiging en wordt na akkoord uitgevoerd`() = runTest {
        val claude = FakeClaude(response("tool_use", toolUse("w1", "ha_call_service", jsonOf("service" to "light.turn_off"))), endTurn("Uit."))
        val mcp = FakeMcp()
        val asked = mutableListOf<ToolCall>()
        val rec = Recorder()
        AgentLoop(claude, mcp) { asked += it; true }.runTurn(cfg, mutableListOf(user("lamp uit")), names, access, names.keys, rec)
        assertEquals(listOf("ha_call_service"), asked.map { it.mcpName })
        assertEquals(ToolAccess.WRITE, asked.single().access)
        assertEquals(1, mcp.calls.size)
        assertEquals(listOf(ToolStatus.AWAITING_APPROVAL, ToolStatus.RUNNING, ToolStatus.DONE), rec.statuses.map { it.second })
    }

    @Test
    fun `geweigerde schrijf-tool geeft is_error tool_result 'Gebruiker heeft geweigerd'`() = runTest {
        val claude = FakeClaude(response("tool_use", toolUse("w1", "ha_call_service")), endTurn("Oké, niet gedaan."))
        val mcp = FakeMcp()
        val history = mutableListOf(user("lamp uit"))
        AgentLoop(claude, mcp) { false }.runTurn(cfg, history, names, access, names.keys, Recorder())
        assertTrue("niet uitgevoerd", mcp.calls.isEmpty())
        val result = toolResults(history[2]).single()
        assertEquals(true, result.bool("is_error"))
        assertEquals(AgentLoop.REJECTED_TEXT, result.arr("content")!!.single().jsonObject.str("text"))
        assertEquals(2, claude.requests.size) // Claude krijgt de weigering terug en mag reageren
    }

    @Test
    fun `parallelle tool-calls - alle resultaten in een user-bericht in originele volgorde`() = runTest {
        val claude = FakeClaude(
            response(
                "tool_use",
                toolUse("a", "ha_get_state", jsonOf("entity_id" to "1")),
                toolUse("b", "ha_call_service"),
                toolUse("c", "ha_get_state", jsonOf("entity_id" to "2")),
            ),
            endTurn("klaar"),
        )
        val history = mutableListOf(user("x"))
        AgentLoop(claude, FakeMcp(delayMs = 10)) { true }.runTurn(cfg, history, names, access, names.keys, Recorder())
        val results = toolResults(history[2])
        assertEquals(listOf("a", "b", "c"), results.map { it.str("tool_use_id") })
    }

    @Test
    fun `lees-modus - niet aangeboden schrijf-tool wordt geweigerd zonder te vragen`() = runTest {
        val claude = FakeClaude(response("tool_use", toolUse("w1", "ha_call_service")), endTurn("Kan niet."))
        val mcp = FakeMcp()
        var asked = false
        val readOnly = cfg.copy(tools = tools.filter { it.str("name") == "ha_get_state" })
        val history = mutableListOf(user("lamp uit"))
        AgentLoop(claude, mcp) { asked = true; true }.runTurn(readOnly, history, names, access, setOf("ha_get_state"), Recorder())
        assertFalse(asked)
        assertTrue(mcp.calls.isEmpty())
        assertEquals(true, toolResults(history[2]).single().bool("is_error"))
        assertEquals(1, claude.requests.first().arr("tools")!!.size)
    }

    @Test
    fun `stopt na maximaal 15 iteraties met melding`() = runTest {
        val looping = Array(30) { response("tool_use", toolUse("t$it", "ha_get_state")) }
        val claude = FakeClaude(*looping)
        val rec = Recorder()
        val history = mutableListOf(user("x"))
        val r = AgentLoop(claude, FakeMcp()) { true }.runTurn(cfg, history, names, access, names.keys, rec)
        assertEquals(15, r.iterations)
        assertEquals(15, claude.requests.size)
        assertTrue(rec.notices.any { it.kind == Notice.Kind.WARNING && it.text.contains("15") })
        // historie eindigt geldig: laatste tool_use heeft een tool_result
        assertEquals("user", history.last().str("role"))
    }

    @Test
    fun `annuleren tijdens bevestiging laat een geldige historie achter`() = runTest {
        val claude = FakeClaude(response("tool_use", toolUse("w1", "ha_call_service")))
        val gate = CompletableDeferred<Boolean>()
        val history = mutableListOf(user("lamp uit"))
        val rec = Recorder()
        val job = async { AgentLoop(claude, FakeMcp()) { gate.await() }.runTurn(cfg, history, names, access, names.keys, rec) }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(listOf("user", "assistant", "user"), history.map { it.str("role") })
        val r = toolResults(history[2]).single()
        assertEquals("w1", r.str("tool_use_id"))
        assertEquals(true, r.bool("is_error"))
        assertTrue(rec.notices.any { it.text == "Beurt gestopt." })
    }

    @Test
    fun `refusal wordt niet in de historie opgenomen`() = runTest {
        val claude = FakeClaude(
            AssistantResponse("m", "claude-opus-5-5", JsonArray(listOf(text("Ik"))), "refusal", jsonOf("category" to "cyber"), Usage()),
        )
        val history = mutableListOf(user("x"))
        val rec = Recorder()
        AgentLoop(claude, FakeMcp()) { true }.runTurn(cfg, history, names, access, names.keys, rec)
        assertEquals(1, history.size)
        assertEquals(Notice.Kind.REFUSAL, rec.notices.single().kind)
        assertTrue(rec.notices.single().text.contains("cyber"))
    }

    @Test
    fun `afgekapte tool-invoer bij max_tokens wordt niet uitgevoerd`() = runTest {
        val claude = FakeClaude(response("max_tokens", toolUse("t1", "ha_call_service")))
        val mcp = FakeMcp()
        val history = mutableListOf(user("x"))
        val rec = Recorder()
        AgentLoop(claude, mcp) { true }.runTurn(cfg, history, names, access, names.keys, rec)
        assertTrue(mcp.calls.isEmpty())
        assertEquals(true, toolResults(history.last()).single().bool("is_error"))
        assertTrue(rec.notices.any { it.text.contains("max_tokens") })
    }

    @Test
    fun `ongeldige gestreamde JSON-invoer geeft INVALID_JSON fout zonder uitvoering`() = runTest {
        val bad = AssistantResponse("m", null, JsonArray(listOf(toolUse("t1", "ha_get_state"))), "tool_use", null, Usage(), setOf("t1"))
        val mcp = FakeMcp()
        val history = mutableListOf(user("x"))
        AgentLoop(FakeClaude(bad, endTurn("ok")), mcp) { true }.runTurn(cfg, history, names, access, names.keys, Recorder())
        assertTrue(mcp.calls.isEmpty())
        assertTrue(toolResults(history[2]).single().arr("content")!!.single().jsonObject.str("text")!!.startsWith("INVALID_JSON"))
    }

    @Test
    fun `request bevat cache_control op laatste tool, system prompt en laatste bericht`() = runTest {
        val claude = FakeClaude(endTurn("ok"))
        AgentLoop(claude, FakeMcp()) { true }.runTurn(cfg, mutableListOf(user("x")), names, access, names.keys, Recorder())
        val body = claude.requests.single()
        val reqTools = body.arr("tools")!!.map { it.jsonObject }
        assertEquals(null, reqTools.first()["cache_control"])
        assertEquals("ephemeral", reqTools.last().obj("cache_control")!!.str("type"))
        assertEquals("ephemeral", body.arr("system")!!.single().jsonObject.obj("cache_control")!!.str("type"))
        val lastBlock = body.arr("messages")!!.last().jsonObject.arr("content")!!.last().jsonObject
        assertEquals("ephemeral", lastBlock.obj("cache_control")!!.str("type"))
        assertEquals("claude-opus-5-5", body.str("model"))
        assertEquals(4096, body["max_tokens"].toString().toInt())
    }

    @Test
    fun `cache_control komt niet in de opgeslagen historie terecht`() = runTest {
        val history = mutableListOf(user("x"))
        AgentLoop(FakeClaude(endTurn("ok")), FakeMcp()) { true }.runTurn(cfg, history, names, access, names.keys, Recorder())
        assertFalse(history.toString().contains("cache_control"))
    }

    @Test
    fun `thinking-blokken worden ongewijzigd teruggestuurd (preserved thinking)`() = runTest {
        val thinking = jsonOf("type" to "thinking", "thinking" to "", "signature" to "sig==")
        val claude = FakeClaude(response("tool_use", thinking, toolUse("t1", "ha_get_state")), endTurn("ok"))
        AgentLoop(claude, FakeMcp()) { true }.runTurn(cfg, mutableListOf(user("x")), names, access, names.keys, Recorder())
        val assistant = claude.requests[1].arr("messages")!![1].jsonObject
        assertEquals(thinking, assistant.arr("content")!!.first().jsonObject)
    }

    @Test
    fun `fallback beta en parameter alleen voor ondersteunde modellen`() = runTest {
        val claude = FakeClaude(endTurn("ok"), endTurn("ok"))
        val loop = AgentLoop(claude, FakeMcp()) { true }
        loop.runTurn(cfg.copy(refusalFallback = true), mutableListOf(user("x")), names, access, names.keys, Recorder())
        loop.runTurn(cfg.copy(refusalFallback = true, model = "claude-haiku-4-5"), mutableListOf(user("x")), names, access, names.keys, Recorder())
        assertEquals(listOf("server-side-fallback-2026-07-01"), claude.betas[0])
        assertEquals("\"default\"", claude.requests[0]["fallbacks"].toString())
        assertTrue(claude.betas[1].isEmpty())
        assertFalse("fallbacks" in claude.requests[1])
    }

    @Test
    fun `bij een fallback worden alleen tool_use-blokken na de wissel uitgevoerd`() = runTest {
        val content = arrayOf(
            jsonOf("type" to "thinking", "thinking" to "", "signature" to "a"),
            toolUse("old", "ha_get_state"),
            jsonOf("type" to "fallback", "from" to jsonOf("model" to "claude-opus-5-5"), "to" to jsonOf("model" to "claude-opus-5")),
            toolUse("new", "ha_get_state"),
        )
        val mcp = FakeMcp()
        val history = mutableListOf(user("x"))
        AgentLoop(FakeClaude(response("tool_use", *content), endTurn("ok")), mcp) { true }
            .runTurn(cfg, history, names, access, names.keys, Recorder())
        assertEquals(listOf("new"), mcp.calls.map { it.id })
        val stored = history[1].arr("content")!!.map { it.jsonObject.str("type") }
        assertEquals(listOf("fallback", "tool_use"), stored)
        assertEquals(listOf("new"), toolResults(history[2]).map { it.str("tool_use_id") })
    }

}
