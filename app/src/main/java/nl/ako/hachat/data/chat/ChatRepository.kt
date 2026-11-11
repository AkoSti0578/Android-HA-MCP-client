package nl.ako.hachat.data.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import nl.ako.hachat.core.AppJson
import nl.ako.hachat.core.jsonOf
import nl.ako.hachat.data.db.ChatDao
import nl.ako.hachat.data.db.ConversationEntity
import nl.ako.hachat.data.db.MessageEntity
import nl.ako.hachat.domain.Notice
import nl.ako.hachat.domain.Usage

class ChatRepository(private val dao: ChatDao, private val clock: () -> Long = System::currentTimeMillis) {

    val conversations: Flow<List<ConversationEntity>> = dao.observeConversations()

    fun messages(conversationId: Long): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)

    suspend fun createConversation(firstMessage: String): Long {
        val now = clock()
        return dao.insertConversation(ConversationEntity(title = titleFrom(firstMessage), createdAt = now, updatedAt = now))
    }

    suspend fun delete(conversationId: Long) = dao.deleteConversation(conversationId)

    suspend fun rename(conversationId: Long, title: String) = dao.updateConversation(conversationId, title, clock())

    /** De API-historie (alleen user/assistant), exact zoals opgeslagen. */
    suspend fun history(conversationId: Long): MutableList<JsonObject> =
        dao.messages(conversationId).filter { it.role == "user" || it.role == "assistant" }.map { m ->
            jsonOf("role" to m.role, "content" to AppJson.parseToJsonElement(m.contentJson))
        }.toMutableList()

    suspend fun appendApiMessage(
        conversationId: Long,
        turnId: String,
        message: JsonObject,
        model: String? = null,
        stopReason: String? = null,
        usage: Usage = Usage(),
    ) {
        val content = message["content"] as? JsonArray ?: JsonArray(emptyList())
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = (message["role"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "user",
                contentJson = AppJson.encodeToString(JsonArray.serializer(), content),
                turnId = turnId,
                model = model,
                stopReason = stopReason,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                cacheReadTokens = usage.cacheReadTokens,
                cacheWriteTokens = usage.cacheWriteTokens,
                createdAt = clock(),
            ),
        )
        dao.touch(conversationId, clock())
    }

    suspend fun appendNotice(conversationId: Long, turnId: String, notice: Notice) {
        dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = "notice",
                contentJson = AppJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(notice.text)),
                turnId = turnId,
                noticeKind = notice.kind.name,
                createdAt = clock(),
            ),
        )
    }

    companion object {
        fun titleFrom(text: String): String {
            val oneLine = text.trim().replace(Regex("\\s+"), " ")
            return if (oneLine.length <= 48) oneLine.ifEmpty { "Nieuw gesprek" } else oneLine.take(47).trimEnd() + "…"
        }
    }
}
