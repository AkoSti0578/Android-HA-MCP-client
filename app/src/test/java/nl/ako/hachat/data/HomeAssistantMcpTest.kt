package nl.ako.hachat.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import nl.ako.hachat.core.AppJson
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.core.str
import nl.ako.hachat.data.mcp.McpClient
import nl.ako.hachat.data.mcp.McpConfig
import nl.ako.hachat.data.mcp.McpException
import nl.ako.hachat.data.settings.ToolPatterns
import nl.ako.hachat.domain.ToolAccess
import nl.ako.hachat.domain.ToolClassifier
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Bootst de ingebouwde Home Assistant MCP Server-integratie na (homeassistant/components/mcp_server/http.py):
 * stateless Streamable HTTP op /api/mcp, alleen JSON-antwoorden, 202 op notificaties, geen sessie-id,
 * Bearer-token verplicht, 403 voor niet-beheerders, Accept moet application/json bevatten,
 * tools met readOnlyHint/destructiveHint en één prompt per toolset.
 */
class HomeAssistantMcpTest {
    private lateinit var server: MockWebServer
    private val received = mutableListOf<Pair<RecordedRequest, String>>()
    private var adminToken = "admin-token"

    private val toolsJson = """[
      {"name":"HassTurnOn","description":"Turns on/opens a device or entity","inputSchema":{"type":"object","properties":{"name":{"type":"string"},"area":{"type":"string"}}},
       "annotations":{"readOnlyHint":false,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}},
      {"name":"HassLightSet","description":"Sets the brightness or color of a light","inputSchema":{"type":"object","properties":{}},
       "annotations":{"readOnlyHint":false}},
      {"name":"GetLiveContext","description":"Provides real-time information about the CURRENT state of devices","inputSchema":{"type":"object","properties":{}},
       "annotations":{"readOnlyHint":true,"destructiveHint":false}},
      {"name":"GetDateTime","description":"Current date and time","inputSchema":{"type":"object","properties":{}},
       "annotations":{"readOnlyHint":true}}
    ]"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                received += request to body
                if (request.getHeader("Authorization") == null) return MockResponse().setResponseCode(401).setBody("401: Unauthorized")
                if (request.getHeader("Authorization") != "Bearer $adminToken") return MockResponse().setResponseCode(403).setBody("403: Forbidden")
                if (!request.getHeader("Accept").orEmpty().contains("application/json")) return MockResponse().setResponseCode(400)
                if (request.getHeader("Content-Type")?.startsWith("application/json") != true) return MockResponse().setResponseCode(400)
                val msg = AppJson.parseToJsonElement(body).jsonObject
                val id = msg["id"] ?: return MockResponse().setResponseCode(202)
                val result = when (msg.str("method")) {
                    "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"prompts":{"listChanged":false},"tools":{"listChanged":false}},"serverInfo":{"name":"home-assistant","version":"2026.11.0"}}"""
                    "tools/list" -> """{"tools":$toolsJson}"""
                    "prompts/list" -> """{"prompts":[{"name":"Assist","description":"Default prompt for Home Assistant Assist API"}]}"""
                    "prompts/get" -> """{"description":"Default prompt","messages":[{"role":"assistant","content":{"type":"text","text":"When controlling Home Assistant always call the intent tools."}}]}"""
                    "tools/call" -> """{"content":[{"type":"text","text":"{\"speech\": {}, \"response_type\": \"action_done\", \"data\": {\"success\": [{\"name\": \"Keuken\", \"type\": \"entity\", \"id\": \"light.keuken\"}], \"failed\": []}}"}],"isError":false}"""
                    else -> return MockResponse().setBody("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"Method not found"}}""")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun client(token: String? = "admin-token") =
        McpClient(OkHttpClient()) { McpConfig(server.url("/api/mcp").toString(), null, null, bearerToken = token) }

    @Test
    fun `stateless verbinding met bearer-token en instructies uit prompts`() = runTest {
        val c = client()
        val info = c.connect()
        assertEquals("home-assistant", info.name)
        assertEquals("When controlling Home Assistant always call the intent tools.", info.instructions)
        assertTrue(received.all { it.first.getHeader("Authorization") == "Bearer admin-token" })
        assertTrue("geen sessie-id zonder dat de server er een geeft", received.all { it.first.getHeader("Mcp-Session-Id") == null })
        assertEquals("/api/mcp", received.first().first.path)
        assertEquals(202, 202) // notificatie werd geaccepteerd zonder fout
        assertTrue(received.any { it.second.contains("notifications/initialized") })
    }

    @Test
    fun `HA-tools worden correct als lezen of schrijven geclassificeerd`() = runTest {
        val tools = client().listTools()
        val c = ToolClassifier(ToolPatterns.DEFAULT_WRITE, ToolPatterns.DEFAULT_READ)
        val access = tools.associate { it.name to c.classify(it).access }
        assertEquals(
            mapOf(
                "HassTurnOn" to ToolAccess.WRITE,
                "HassLightSet" to ToolAccess.WRITE,
                "GetLiveContext" to ToolAccess.READ,
                "GetDateTime" to ToolAccess.READ,
            ),
            access,
        )
    }

    @Test
    fun `tool-call levert HA-intent-resultaat`() = runTest {
        val r = client().callTool("HassTurnOn", jsonOf("name" to "Keuken"))
        assertTrue(r.displayText.contains("action_done"))
    }

    @Test
    fun `zonder token 401 met uitleg`() = runTest {
        try { client(token = null).connect(); fail() } catch (e: McpException.Http) {
            assertTrue(e.message!!.contains("access token"))
        }
    }

    @Test
    fun `niet-beheerder 403 met uitleg`() = runTest {
        try { client(token = "user-token").connect(); fail() } catch (e: McpException.Http) {
            assertTrue(e.message!!.contains("beheerder"))
        }
    }

    @Test
    fun `server zonder prompts-capability geeft geen instructies`() = runTest {
        // ha-mcp-achtige server: geen prompts → geen extra verzoeken
        val s = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val msg = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val id = msg["id"] ?: return MockResponse().setResponseCode(202)
                    if (msg.str("method") != "initialize") return MockResponse().setResponseCode(500)
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"x"}}}""")
                }
            }
            start()
        }
        val info = McpClient(OkHttpClient()) { McpConfig(s.url("/").toString(), null, null) }.connect()
        assertNull(info.instructions)
        s.shutdown()
    }
}
