package nl.ako.hachat.ui

import nl.ako.hachat.data.db.MessageEntity
import nl.ako.hachat.domain.Notice
import nl.ako.hachat.domain.ToolAccess
import nl.ako.hachat.domain.ToolStatus
import nl.ako.hachat.domain.Usage
import nl.ako.hachat.ui.chat.ChatItem
import nl.ako.hachat.ui.chat.ChatItemMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatItemMapperTest {
    private var id = 0L
    private fun m(role: String, json: String, turn: String = "t1", usage: Usage = Usage(), kind: String? = null) = MessageEntity(
        id = ++id, conversationId = 1, role = role, contentJson = json, turnId = turn,
        inputTokens = usage.inputTokens, outputTokens = usage.outputTokens, cacheReadTokens = usage.cacheReadTokens,
        noticeKind = kind, model = if (role == "assistant") "claude-opus-5-5" else null, createdAt = 0,
    )

    @Test
    fun `koppelt tool_use aan tool_result en telt gebruik per beurt op`() {
        val msgs = listOf(
            m("user", """[{"type":"text","text":"Lampen?"}]"""),
            m("assistant", """[{"type":"thinking","thinking":"","signature":"s"},{"type":"tool_use","id":"a","name":"ha_get_state","input":{"entity_id":"light.x"}},{"type":"tool_use","id":"b","name":"ha_call_service","input":{}}]""", usage = Usage(100, 20, 900)),
            m("user", """[{"type":"tool_result","tool_use_id":"a","content":[{"type":"text","text":"on"}]},{"type":"tool_result","tool_use_id":"b","is_error":true,"content":[{"type":"text","text":"Gebruiker heeft geweigerd"}]}]"""),
            m("assistant", """[{"type":"text","text":"De lamp is **aan**."}]""", usage = Usage(50, 10, 1000)),
            m("notice", "\"Beurt gestopt.\"", kind = "INFO"),
            m("user", """[{"type":"text","text":"Volgende"}]""", turn = "t2"),
        )
        val items = ChatItemMapper.map(msgs, access = mapOf("ha_get_state" to ToolAccess.READ, "ha_call_service" to ToolAccess.WRITE))
        assertEquals(
            listOf("UserText", "ToolCallItem", "ToolCallItem", "AssistantText", "NoticeItem", "TurnUsage", "UserText"),
            items.map { it::class.simpleName },
        )
        val a = items[1] as ChatItem.ToolCallItem
        assertEquals(ToolStatus.DONE, a.status); assertEquals("on", a.result); assertEquals(ToolAccess.READ, a.access)
        val b = items[2] as ChatItem.ToolCallItem
        assertEquals(ToolStatus.REJECTED, b.status)
        val usage = items[5] as ChatItem.TurnUsage
        assertEquals(Usage(150, 30, 1900), usage.usage)
        assertEquals(2, usage.steps)
        assertEquals(Notice.Kind.INFO, (items[4] as ChatItem.NoticeItem).kind)
    }

    @Test
    fun `tool zonder resultaat krijgt live status`() {
        val msgs = listOf(m("assistant", """[{"type":"tool_use","id":"x","name":"n","input":{}}]"""))
        val item = ChatItemMapper.map(msgs, liveStatus = mapOf("x" to ToolStatus.AWAITING_APPROVAL)).first() as ChatItem.ToolCallItem
        assertEquals(ToolStatus.AWAITING_APPROVAL, item.status)
    }
}
