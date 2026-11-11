package nl.ako.hachat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
