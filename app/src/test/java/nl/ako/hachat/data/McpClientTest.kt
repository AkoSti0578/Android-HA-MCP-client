package nl.ako.hachat.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import nl.ako.hachat.core.AppJson
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.core.str
import nl.ako.hachat.data.mcp.McpClient
import nl.ako.hachat.data.mcp.McpConfig
import nl.ako.hachat.data.mcp.McpException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Simuleert ha-mcp: Streamable HTTP met sessie-id, JSON- en SSE-antwoorden. */
class McpClientTest {
    private lateinit var server: MockWebServer
    private val received = mutableListOf<RecordedRequest>()
    private var sessionCounter = 0
    private var validSession: String? = null
    private var useSse = false

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += request
                if (request.method == "DELETE") return MockResponse().setResponseCode(200)
                val msg = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                val method = msg.str("method")
                val id = msg["id"]
                if (method != "initialize" && request.getHeader("Mcp-Session-Id") != validSession) {
                    return MockResponse().setResponseCode(404).setBody("""{"error":"session not found"}""")
                }
                return when (method) {
                    "initialize" -> {
                        validSession = "sess-${++sessionCounter}"
                        reply(id.toString(), """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"ha-mcp","version":"6.1.0"}}""")
                            .setHeader("Mcp-Session-Id", validSession!!)
                    }
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> {
                        val cursor = msg["params"]?.jsonObject?.str("cursor")
                        if (cursor == null) reply(id.toString(), """{"tools":[{"name":"ha_get_state","description":"State","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}],"nextCursor":"p2"}""")
                        else reply(id.toString(), """{"tools":[{"name":"ha_call_service","description":"Call","inputSchema":{"type":"object"}}]}""")
                    }
                    "tools/call" -> reply(id.toString(), """{"content":[{"type":"text","text":"{\"state\":\"on\"}"}],"isError":false}""")
                    else -> reply(id.toString(), "{}")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun reply(id: String, result: String): MockResponse {
        val json = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
        return if (useSse) {
            // eerst een server-notificatie, dan het echte antwoord — zoals FastMCP kan doen
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                    "event: message\ndata: $json\n\n",
            )
        } else MockResponse().setHeader("Content-Type", "application/json").setBody(json)
    }

    private fun client(header: Pair<String, String>? = null) = McpClient(OkHttpClient()) {
        McpConfig(server.url("/private_s3cr3t").toString(), header?.first, header?.second)
    }

    @Test
    fun `initialize, initialized-notificatie en sessie-id`() = runTest {
        val c = client()
        val info = c.connect()
        assertEquals("ha-mcp", info.name)
        assertEquals("6.1.0", info.version)
        assertEquals("sess-1", received[1].getHeader("Mcp-Session-Id"))
        assertEquals("application/json, text/event-stream", received[0].getHeader("Accept"))
        assertEquals("/private_s3cr3t", received[0].path)
    }

    @Test
    fun `tools-list volgt paginatie en leest annotations`() = runTest {
        val tools = client().listTools()
        assertEquals(listOf("ha_get_state", "ha_call_service"), tools.map { it.name })
        assertEquals("true", tools[0].annotations!!["readOnlyHint"].toString())
    }

    @Test
    fun `tools-call met JSON-antwoord`() = runTest {
        val r = client().callTool("ha_get_state", jsonOf("entity_id" to "light.keuken"))
        assertFalse(r.isError)
        assertEquals("""{"state":"on"}""", r.displayText)
        assertEquals("text", r.content.single().str("type"))
        val call = received.last()
        assertEquals("sess-1", call.getHeader("Mcp-Session-Id"))
        assertEquals("2025-06-18", call.getHeader("MCP-Protocol-Version"))
    }

    @Test
    fun `SSE-antwoorden worden gelezen en notificaties overgeslagen`() = runTest {
        useSse = true
        val c = client()
        assertEquals("ha-mcp", c.connect().name)
        assertEquals(2, c.listTools().size)
        assertEquals("""{"state":"on"}""", c.callTool("x", jsonOf()).displayText)
    }

    @Test
    fun `verlopen sessie (404) leidt tot automatisch her-initialiseren`() = runTest {
        val c = client()
        c.connect()
        validSession = "iets-anders" // server herstart: oude sessie ongeldig
        val r = c.callTool("ha_get_state", jsonOf())
        assertEquals("""{"state":"on"}""", r.displayText)
        assertEquals(2, sessionCounter)
        assertEquals("sess-2", received.last().getHeader("Mcp-Session-Id"))
    }

    @Test
    fun `extra header wordt meegestuurd`() = runTest {
        client("Authorization" to "Bearer abc").connect()
        assertEquals("Bearer abc", received.first().getHeader("Authorization"))
    }

    @Test
    fun `onbereikbare server geeft VPN-hint zonder geheim pad`() = runTest {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/private_topsecret").toString()
        dead.shutdown()
        val c = McpClient(OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build()) { McpConfig(url, null, null) }
        try {
            c.connect(); fail()
        } catch (e: McpException.Unreachable) {
            assertTrue(e.message!!.contains("Zet VPN aan"))
            assertFalse(e.message!!.contains("topsecret"))
        }
    }

    @Test
    fun `tool-resultaat met isError, afbeelding en structuredContent`() {
        val r = McpClient.toToolResult(AppJson.parseToJsonElement(
            """{"content":[{"type":"text","text":"mislukt"},{"type":"image","data":"AAA","mimeType":"image/png"}],"isError":true}""",
        ).jsonObject)
        assertTrue(r.isError)
        assertEquals("image", r.content[1].str("type"))
        val s = McpClient.toToolResult(AppJson.parseToJsonElement("""{"content":[],"structuredContent":{"a":1}}""").jsonObject)
        assertTrue(s.displayText.contains("\"a\": 1"))
    }

    @Test
    fun `extreem lange resultaten worden zichtbaar ingekort`() {
        val long = "x".repeat(McpClient.MAX_RESULT_CHARS + 500)
        val r = McpClient.toToolResult(jsonOf("content" to listOf(jsonOf("type" to "text", "text" to long))))
        val text = r.content.single().str("text")!!
        assertTrue(text.length < long.length)
        assertTrue(text.contains("500 tekens weggelaten"))
    }
}
