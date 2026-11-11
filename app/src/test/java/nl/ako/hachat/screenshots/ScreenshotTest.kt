package nl.ako.hachat.screenshots

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import nl.ako.hachat.core.AppJson
import nl.ako.hachat.data.db.ConversationEntity
import nl.ako.hachat.data.mcp.McpServerInfo
import nl.ako.hachat.data.mcp.McpState
import nl.ako.hachat.data.mcp.McpTool
import nl.ako.hachat.data.settings.AppSettings
import nl.ako.hachat.data.settings.ModelInfo
import nl.ako.hachat.data.settings.ToolOverride
import nl.ako.hachat.domain.Classification
import nl.ako.hachat.domain.Notice
import nl.ako.hachat.domain.ToolAccess
import nl.ako.hachat.domain.ToolCall
import nl.ako.hachat.domain.ToolStatus
import nl.ako.hachat.domain.Usage
import nl.ako.hachat.ui.chat.ChatActions
import nl.ako.hachat.ui.chat.ChatContent
import nl.ako.hachat.ui.chat.ChatItem
import nl.ako.hachat.ui.chat.ChatUiState
import nl.ako.hachat.ui.chat.PendingApproval
import nl.ako.hachat.ui.settings.SecretHints
import nl.ako.hachat.ui.settings.SettingsActions
import nl.ako.hachat.ui.settings.SettingsContent
import nl.ako.hachat.ui.settings.SettingsUiState
import nl.ako.hachat.ui.settings.TaskState
import nl.ako.hachat.ui.settings.ToolRow
import nl.ako.hachat.ui.settings.ToolsContent
import nl.ako.hachat.ui.theme.AkoTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Rendert de echte schermen met voorbeelddata. Dient als rook-test (compositie mag niet crashen)
 * en levert met `-Pscreenshots` de PNG's in docs/screenshots voor de README.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val dir = System.getProperty("ako.screenshotDir") ?: "build/screenshots"
    private fun shot(name: String) = compose.onRoot().captureRoboImage(File(dir, "$name.png").path)

    private fun json(s: String): JsonObject = AppJson.parseToJsonElement(s).jsonObject

    private val noChat = object : ChatActions {
        override fun setInput(text: String) {}
        override fun send(text: String) {}
        override fun stop() {}
        override fun retry() {}
        override fun approve(ok: Boolean) {}
        override fun newConversation() {}
        override fun selectConversation(id: Long) {}
        override fun deleteConversation(id: Long) {}
        override fun reconnect() {}
    }

    private val noSettings = object : SettingsActions {
        override fun update(block: (AppSettings) -> AppSettings) {}
        override fun saveApiKey(key: String) {}
        override fun clearApiKey() {}
        override fun saveHaToken(token: String) {}
        override fun clearHaToken() {}
        override fun refreshModels() {}
        override fun saveMcp(url: String?, headerName: String, headerValue: String?) {}
        override fun clearMcpHeader() {}
        override fun testConnection(url: String, headerName: String, headerValue: String) {}
        override fun resetSystemPrompt() {}
        override fun savePatterns(write: String, read: String) {}
        override fun resetPatterns() {}
        override fun setOverride(tool: String, o: ToolOverride) {}
        override fun loadTools() {}
    }

    private val tools = listOf(
        McpTool("GetLiveContext", null, "Provides real-time information about the CURRENT state of devices", JsonObject(emptyMap())),
        McpTool("GetDateTime", null, "Provides the current date and time", JsonObject(emptyMap())),
        McpTool("HassTurnOn", null, "Turns on/opens/presses a device or entity", JsonObject(emptyMap())),
        McpTool("HassLightSet", null, "Sets the brightness percentage or color of a light", JsonObject(emptyMap())),
    )
    private val connected = McpState.Connected(McpServerInfo("home-assistant", "2026.11.0", "2025-06-18"), List(23) { tools[it % tools.size] })

    private val conversations = listOf(
        ConversationEntity(1, "Lichten uit om 23:00", 0, 1_762_850_000_000),
        ConversationEntity(2, "Temperatuur per kamer", 0, 1_762_760_000_000),
        ConversationEntity(3, "Waarom ging de wasmachine-melding niet af?", 0, 1_762_600_000_000),
    )

    private val automation = json(
        """{"config":{"alias":"Lichten uit om 23:00","description":"Zet alle lampen beneden uit","trigger":[{"platform":"time","at":"23:00:00"}],
           "condition":[{"condition":"state","entity_id":"group.familie","state":"home"}],
           "action":[{"service":"light.turn_off","target":{"area_id":["woonkamer","keuken"]}}],"mode":"single"}}""",
    )

    @Test
    fun chat_gesprek() {
        val items = listOf(
            ChatItem.UserText("u1", "Welke lampen staan er nog aan beneden?"),
            ChatItem.ToolCallItem("t1", "a", "GetLiveContext", json("{}"), result = "…", status = ToolStatus.DONE, access = ToolAccess.READ),
            ChatItem.AssistantText(
                "a1",
                """Er branden nog **3 lampen** beneden:

| Lamp | Helderheid |
|:-----|-----:|
| `light.woonkamer` | 80% |
| `light.keuken_plafond` | 100% |
| `light.eettafel` | 45% |

Wil je dat ik ze uitzet, of een automation maak die dit elke avond om 23:00 doet?""",
            ),
            ChatItem.TurnUsage("us1", Usage(1_204, 312, 18_950, 0), 3, "claude-opus-5-5"),
            ChatItem.UserText("u2", "Maak die automation maar"),
            ChatItem.AssistantText(
                "a2",
                """Dit is het voorstel:

```yaml
alias: Lichten uit om 23:00
trigger:
  - platform: time
    at: "23:00:00"
action:
  - service: light.turn_off
    target:
      area_id: [woonkamer, keuken]
```
Om **23:00** gaan alle lampen in woonkamer en keuken uit.""",
            ),
        )
        val state = ChatUiState(conversations, 1, items, busy = false, mcp = connected, settings = AppSettings())
        compose.setContent { AkoTheme { ChatContent(state, "", noChat, SnackbarHostState(), {}) } }
        shot("01-chat")
    }

    @Test
    fun chat_bevestiging() {
        val call = ToolCall("w1", "HassTurnOff", "HassTurnOff", json("""{"area":"Woonkamer","domain":["light"]}"""), ToolAccess.WRITE)
        val items = listOf(
            ChatItem.UserText("u2", "Doe de lampen in de woonkamer maar uit"),
            ChatItem.AssistantText("a2", "Ik zet de lampen in de **woonkamer** uit."),
        )
        val state = ChatUiState(
            conversations, 1, items, busy = true, status = "Wacht op jouw bevestiging…",
            pending = PendingApproval(call, CompletableDeferred()), mcp = connected,
        )
        compose.setContent { AkoTheme { ChatContent(state, "", noChat, SnackbarHostState(), {}) } }
        shot("02-bevestiging")
    }

    @Test
    fun chat_leeg_en_lezen() {
        val state = ChatUiState(conversations, null, emptyList(), mcp = connected, settings = AppSettings(readOnlyMode = true))
        compose.setContent { AkoTheme { ChatContent(state, "", noChat, SnackbarHostState(), {}) } }
        shot("03-nieuw-gesprek")
    }

    @Test
    fun chat_fouten() {
        val items = listOf(
            ChatItem.UserText("u", "Hoe warm is het in de hal?"),
            ChatItem.ToolCallItem("t", "x", "GetLiveContext", json("{}"),
                result = "MCP-server onbereikbaar (http://192.168.1.10:8123/api/mcp). Niet thuis? Zet VPN aan.", isError = true,
                status = ToolStatus.FAILED, access = ToolAccess.READ),
            ChatItem.NoticeItem("n1", Notice.Kind.ERROR, "Rate limit bereikt (429). Probeer het over 30s opnieuw."),
        )
        val state = ChatUiState(conversations, 2, items, mcp = McpState.Failed("onbereikbaar"), canRetry = true)
        compose.setContent { AkoTheme { ChatContent(state, "", noChat, SnackbarHostState(), {}) } }
        shot("04-fouten")
    }

    @Test
    fun gesprekken_lade() {
        val state = ChatUiState(conversations, 1, emptyList(), mcp = connected)
        compose.setContent { AkoTheme { ChatContent(state, "", noChat, SnackbarHostState(), {}, drawerInitiallyOpen = true) } }
        shot("05-gesprekken")
    }

    @Test
    fun instellingen() {
        val models = listOf(ModelInfo("claude-opus-5-5", "Claude Opus 5.5", true, true, listOf("low", "medium", "high", "xhigh", "max")))
        val state = SettingsUiState(
            settings = AppSettings(models = models, effort = "medium"),
            hints = SecretHints("sk-ant-…", "http://192.168.1.10:8123/api/mcp", null, "•••••• (183 tekens)"),
            models = TaskState.Ok("14 modellen geladen"),
            test = TaskState.Ok("Verbonden met home-assistant 2026.11.0 — 23 tools (4 lezen, 19 schrijven) + instructies"),
            mcp = connected,
        )
        compose.setContent { AkoTheme { SettingsContent(state, noSettings, {}, {}) } }
        shot("06-instellingen")
    }

    @Test
    fun tools() {
        val rows = tools.map {
            val write = it.name.startsWith("Hass")
            ToolRow(it, Classification(if (write) ToolAccess.WRITE else ToolAccess.READ, if (write) "server: readOnlyHint=false" else "server: readOnlyHint"), ToolOverride.AUTO)
        }
        val state = SettingsUiState(mcp = connected, tools = rows)
        compose.setContent { AkoTheme { ToolsContent(state, noSettings, {}) } }
        shot("07-tools")
    }

}
