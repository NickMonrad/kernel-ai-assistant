package com.kernel.ai.core.memory

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.entity.MessageEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class KernelDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
