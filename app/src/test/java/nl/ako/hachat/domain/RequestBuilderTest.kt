package nl.ako.hachat.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import nl.ako.hachat.core.arr
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.core.obj
import nl.ako.hachat.core.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestBuilderTest {
    private fun msg(role: String, vararg blocks: Any) = jsonOf("role" to role, "content" to blocks.toList())
    private fun text(t: String) = jsonOf("type" to "text", "text" to t)

    @Test
    fun `opeenvolgende berichten met dezelfde rol worden samengevoegd, tool_result vooraan`() {
        val h = listOf(
            msg("user", text("a")),
            msg("assistant", jsonOf("type" to "tool_use", "id" to "t", "name" to "x", "input" to jsonOf())),
            msg("user", jsonOf("type" to "tool_result", "tool_use_id" to "t", "content" to "afgebroken")),
            msg("user", text("nieuwe vraag")),
        )
        val n = RequestBuilder.normalize(h)
        assertEquals(listOf("user", "assistant", "user"), n.map { it.str("role") })
        assertEquals(listOf("tool_result", "text"), n[2].arr("content")!!.map { it.jsonObject.str("type") })
    }

    @Test
    fun `historie begint altijd bij de gebruiker en lege berichten vallen weg`() {
        val n = RequestBuilder.normalize(listOf(msg("assistant", text("x")), jsonOf("role" to "user", "content" to ""), msg("user", text("y"))))
        assertEquals(1, n.size)
        assertEquals("user", n.single().str("role"))
    }

    @Test
    fun `effort, thinking en streaming alleen als ingesteld`() {
        val cfg = TurnConfig("claude-opus-5-5", 1000, "sys", emptyList(), stream = false)
        val plain = RequestBuilder.build(cfg, listOf(msg("user", text("x"))))
        assertFalse("tools" in plain); assertFalse("stream" in plain); assertFalse("output_config" in plain); assertFalse("thinking" in plain)
        val rich = RequestBuilder.build(cfg.copy(stream = true, effort = "high", showThinking = true), listOf(msg("user", text("x"))))
        assertEquals("high", rich.obj("output_config")!!.str("effort"))
        assertEquals("summarized", rich.obj("thinking")!!.str("display"))
        assertEquals("true", rich["stream"].toString())
        assertFalse("temperature" in rich)
    }

    @Test
    fun `fallback-ondersteuning per model`() {
        listOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-2", "claude-sonnet-5-5").forEach { assertTrue(it, RequestBuilder.supportsFallback(it)) }
        listOf("claude-haiku-5-5", "claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-8").forEach { assertFalse(it, RequestBuilder.supportsFallback(it)) }
    }

    @Test
    fun `cache breakpoint op laatste cachebare blok van laatste bericht`() {
        val h = listOf(msg("user", text("a"), jsonOf("type" to "tool_result", "tool_use_id" to "t", "content" to JsonArray(emptyList()))))
        val body = RequestBuilder.build(TurnConfig("m", 10, "s", emptyList()), h)
        val blocks = body.arr("messages")!!.single().jsonObject.arr("content")!!.map { it.jsonObject }
        assertEquals(null, blocks[0]["cache_control"])
        assertEquals("ephemeral", blocks[1].obj("cache_control")!!.str("type"))
    }
}

class FallbackEchoTest {
    @Test
    fun `thinking en tool_use voor de laatste fallback vallen weg, tekst en alles erna blijft`() {
        val content = JsonArray(listOf(
            jsonOf("type" to "thinking", "thinking" to "", "signature" to "a"),
            jsonOf("type" to "text", "text" to "deel"),
            jsonOf("type" to "tool_use", "id" to "x", "name" to "n", "input" to jsonOf()),
            jsonOf("type" to "fallback", "from" to jsonOf("model" to "a"), "to" to jsonOf("model" to "b")),
            jsonOf("type" to "thinking", "thinking" to "", "signature" to "b"),
            jsonOf("type" to "text", "text" to "rest"),
        ))
        val out = FallbackEcho.sanitize(content).map { it.jsonObject }
        assertEquals(listOf("text", "fallback", "thinking", "text"), out.map { it.str("type") })
        assertEquals("b", out[2].str("signature"))
    }

    @Test
    fun `zonder fallback ongewijzigd`() {
        val content = JsonArray(listOf(jsonOf("type" to "thinking", "thinking" to "", "signature" to "a")))
        assertEquals(content, FallbackEcho.sanitize(content))
    }
}
