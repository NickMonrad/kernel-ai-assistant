package com.kernel.ai.core.memory

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.dao.UserProfileDao
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity
import com.kernel.ai.core.memory.entity.MessageEntity
import com.kernel.ai.core.memory.entity.UserProfileEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageEmbeddingEntity::class,
        UserProfileEntity::class,
        EpisodicMemoryEntity::class,
        CoreMemoryEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class KernelDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun messageEmbeddingDao(): MessageEmbeddingDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun episodicMemoryDao(): EpisodicMemoryDao
    abstract fun coreMemoryDao(): CoreMemoryDao
}
