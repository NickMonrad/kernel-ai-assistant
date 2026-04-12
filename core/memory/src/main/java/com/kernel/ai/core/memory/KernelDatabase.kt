package com.kernel.ai.core.memory

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.dao.ModelSettingsDao
import com.kernel.ai.core.memory.dao.UserProfileDao
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity
import com.kernel.ai.core.memory.entity.MessageEntity
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import com.kernel.ai.core.memory.entity.UserProfileEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageEmbeddingEntity::class,
        UserProfileEntity::class,
        EpisodicMemoryEntity::class,
        CoreMemoryEntity::class,
        ModelSettingsEntity::class,
    ],
    version = 6,
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
    abstract fun modelSettingsDao(): ModelSettingsDao

    companion object {
        /** Adds lastDistilledAt to conversations (#165) and lastAccessedAt to episodic_memories (#167). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN lastDistilledAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE episodic_memories ADD COLUMN lastAccessedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE episodic_memories SET lastAccessedAt = createdAt")
            }
        }

        /** Creates model_settings table for user-configurable inference parameters (#46). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS model_settings (
                        modelId TEXT NOT NULL PRIMARY KEY,
                        contextWindowSize INTEGER NOT NULL,
                        temperature REAL NOT NULL,
                        topP REAL NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
