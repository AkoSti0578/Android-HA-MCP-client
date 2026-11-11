package nl.ako.hachat.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import nl.ako.hachat.core.AppJson
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.core.obj
import nl.ako.hachat.core.str
import nl.ako.hachat.data.anthropic.AnthropicClient
import nl.ako.hachat.data.anthropic.ClaudeApiException
import nl.ako.hachat.domain.StreamDelta
import nl.ako.hachat.domain.Usage
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AnthropicClientTest {
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client(key: String? = "sk-ant-test") = AnthropicClient(
        OkHttpClient(), apiKeyProvider = { key }, baseUrl = server.url("").toString().trimEnd('/'),
        sleeper = { sleeps += it },
    )

    private fun sse(vararg events: Pair<String, String>) = MockResponse()
        .setHeader("content-type", "text/event-stream")
        .setBody(events.joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" })

    private val streamBody = jsonOf("model" to "claude-opus-5-5", "max_tokens" to 100, "stream" to true, "messages" to emptyList<Any>())

    private val happyStream = arrayOf(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[],"usage":{"input_tokens":12,"cache_read_input_tokens":3000,"cache_creation_input_tokens":0,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Even "}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"EqQB"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "ping" to """{"type":"ping"}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Ik kijk "}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"even."}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"ha_get_state","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"entity_"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"id\": \"light.keuken\"}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":2}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":42}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun `streaming stelt het volledige bericht samen inclusief thinking-signature en tool-invoer`() = runTest {
        server.enqueue(sse(*happyStream))
        val deltas = mutableListOf<StreamDelta>()
        val r = client().createMessage(streamBody) { deltas += it }

        assertEquals("tool_use", r.stopReason)
        assertEquals(Usage(inputTokens = 12, outputTokens = 42, cacheReadTokens = 3000, cacheWriteTokens = 0), r.usage)
        val blocks = r.content.map { it.jsonObject }
        assertEquals("Even ", blocks[0].str("thinking"))
        assertEquals("EqQB", blocks[0].str("signature"))
        assertEquals("Ik kijk even.", blocks[1].str("text"))
        assertEquals("light.keuken", blocks[2].obj("input")!!.str("entity_id"))
        assertTrue(r.invalidToolInputs.isEmpty())
        assertEquals("Ik kijk even.", deltas.filterIsInstance<StreamDelta.Text>().joinToString("") { it.text })

        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        assertEquals("text/event-stream", req.getHeader("accept"))
    }

    @Test
    fun `afgekapte tool-JSON wordt gemarkeerd als ongeldig`() = runTest {
        server.enqueue(sse(
            "message_start" to """{"type":"message_start","message":{"id":"m","model":"x","usage":{"input_tokens":1}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t9","name":"x","input":{}}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"a\": [1, 2"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":5}}""",
            "message_stop" to """{"type":"message_stop"}""",
        ))
        val r = client().createMessage(streamBody)
        assertEquals(setOf("t9"), r.invalidToolInputs)
        assertEquals("{}", r.content.single().jsonObject["input"].toString())
    }

    @Test
    fun `429 wordt opnieuw geprobeerd met retry-after en daarna succes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "7")
            .setBody("""{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""))
        server.enqueue(sse(*happyStream))
        val deltas = mutableListOf<StreamDelta>()
        val r = client().createMessage(streamBody) { deltas += it }
        assertEquals("tool_use", r.stopReason)
        assertEquals(listOf(7000L), sleeps)
        val retry = deltas.filterIsInstance<StreamDelta.Retrying>().single()
        assertEquals(7L, retry.delaySeconds)
        assertTrue(deltas.contains(StreamDelta.Reset))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `aanhoudende 529 geeft na max pogingen een duidelijke fout met exponentiele backoff`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(529).setBody("""{"error":{"type":"overloaded_error","message":"Overloaded"}}""")) }
        try {
            client().createMessage(streamBody); fail()
        } catch (e: ClaudeApiException.Overloaded) {
            assertTrue(e.message!!.contains("overbelast"))
        }
        assertEquals(listOf(2000L, 4000L, 8000L), sleeps)
    }

    @Test
    fun `401 geeft meteen 'ongeldige API key' zonder retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        try {
            client().createMessage(streamBody); fail()
        } catch (e: ClaudeApiException.Unauthorized) {
            assertTrue(e.message!!.contains("Ongeldige API key"))
        }
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `fout midden in de stream na output wordt niet opnieuw geprobeerd`() = runTest {
        server.enqueue(sse(
            "message_start" to """{"type":"message_start","message":{"id":"m","model":"x","usage":{"input_tokens":1}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Half"}}""",
            "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        ))
        try {
            client().createMessage(streamBody); fail()
        } catch (e: ClaudeApiException.StreamError) {
            assertTrue(e.message!!.contains("overloaded_error"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ontbrekende key geeft MissingKey`() = runTest {
        try { client(key = null).createMessage(streamBody); fail() } catch (_: ClaudeApiException.MissingKey) {}
    }

    @Test
    fun `niet-streaming response en beta-header`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"id":"m","model":"claude-opus-5-5","content":[{"type":"text","text":"Hoi"}],"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2}}""",
        ))
        val body = AppJson.parseToJsonElement("""{"model":"claude-opus-5-5","max_tokens":10,"messages":[]}""").jsonObject
        val r = client().createMessage(body, listOf("server-side-fallback-2026-07-01"))
        assertEquals("end_turn", r.stopReason)
        assertEquals("Hoi", r.content.single().jsonObject.str("text"))
        assertEquals("server-side-fallback-2026-07-01", server.takeRequest().getHeader("anthropic-beta"))
    }

    @Test
    fun `modellijst wordt gepagineerd opgehaald met capabilities`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"data":[{"id":"claude-opus-5-5","display_name":"Claude Opus 5.5","max_tokens":128000,
               "capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":true}}},
               "effort":{"supported":true,"low":{"supported":true},"medium":{"supported":true},"high":{"supported":true},"xhigh":{"supported":true},"max":{"supported":true}}}}],
               "has_more":true,"last_id":"claude-opus-5-5"}""",
        ))
        server.enqueue(MockResponse().setBody(
            """{"data":[{"id":"claude-haiku-4-5","display_name":"Claude Haiku 4.5","capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":false}}},"effort":{"supported":false}}}],"has_more":false}""",
        ))
        val models = client().listModels()
        assertEquals(listOf("claude-opus-5-5", "claude-haiku-4-5"), models.map { it.id })
        assertTrue(models[0].supportsAdaptiveThinking)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), models[0].effortLevels)
        assertEquals(false, models[1].supportsAdaptiveThinking)
        assertEquals(false, models[1].supportsEffort)
        server.takeRequest()
        assertEquals("/v1/models?limit=100&after_id=claude-opus-5-5", server.takeRequest().path)
    }

    @Test
    fun `backoff gebruikt exponentiele stappen tot maximaal 60s`() {
        val c = client()
        assertEquals(2L, c.backoffSeconds(1, null))
        assertEquals(8L, c.backoffSeconds(3, null))
        assertEquals(60L, c.backoffSeconds(10, null))
        assertEquals(30L, c.backoffSeconds(1, 30))
    }
}
