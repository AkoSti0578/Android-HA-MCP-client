package nl.ako.hachat.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Eén bericht. `role` is "user"/"assistant" (onderdeel van de API-historie, `contentJson` is de
 * exacte content-array) of "notice" (alleen UI: fouten, weigeringen, info).
 */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val contentJson: String,
    /** Beurt-id: alle berichten van één gebruikersvraag + agent-iteraties delen dit. */
    val turnId: String,
    val model: String? = null,
    val stopReason: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val noticeKind: String? = null,
    val createdAt: Long,
)
