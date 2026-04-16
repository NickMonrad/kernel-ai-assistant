package com.kernel.ai.core.memory

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kernel.ai.core.memory.dao.ContactAliasDao
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.dao.ModelSettingsDao
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.dao.UserProfileDao
import com.kernel.ai.core.memory.entity.ContactAliasEntity
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity
import com.kernel.ai.core.memory.entity.MessageEntity
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
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
        QuickActionEntity::class,
        ScheduledAlarmEntity::class,
        ContactAliasEntity::class,
        ListItemEntity::class,
    ],
    version = 15,
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
    abstract fun quickActionDao(): QuickActionDao
    abstract fun scheduledAlarmDao(): ScheduledAlarmDao
    abstract fun contactAliasDao(): ContactAliasDao
    abstract fun listItemDao(): ListItemDao

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

        /** Adds toolCallJson column to messages for Gemma-4 native tool calling (#197). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCallJson TEXT DEFAULT NULL")
            }
        }

        /** Creates quick_actions table for action history (#actions). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS quick_actions (
                        id TEXT NOT NULL PRIMARY KEY,
                        userQuery TEXT NOT NULL,
                        skillName TEXT DEFAULT NULL,
                        resultText TEXT NOT NULL,
                        isSuccess INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** Adds vectorized column to core_memories and episodic_memories (#284). */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE core_memories ADD COLUMN vectorized INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodic_memories ADD COLUMN vectorized INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds category column to core_memories for NZ knowledge separation (#367). */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE core_memories ADD COLUMN category TEXT NOT NULL DEFAULT 'user'")
                // Reclassify existing jandal_persona entries as agent_identity
                db.execSQL("UPDATE core_memories SET category = 'agent_identity' WHERE source = 'jandal_persona'")
            }
        }

        /** Adds structuredJson column to user_profile for structured YAML profile (#374). */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN structuredJson TEXT DEFAULT NULL")
            }
        }

        /** Adds topK and showThinkingProcess columns to model_settings (#342/#343). */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE model_settings ADD COLUMN topK INTEGER NOT NULL DEFAULT 40")
                db.execSQL("ALTER TABLE model_settings ADD COLUMN showThinkingProcess INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Creates scheduled_alarms table for exact AlarmManager scheduling (#327). */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_alarms (
                        id TEXT NOT NULL PRIMARY KEY,
                        triggerAtMillis INTEGER NOT NULL,
                        label TEXT,
                        createdAt INTEGER NOT NULL,
                        fired INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /** Creates list_items table for add_to_list skill persistence (#315). */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `list_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `listName` TEXT NOT NULL,
                        `item` TEXT NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        `checked` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /** Creates contact_aliases table for relationship-name to contact mapping (#355). */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `contact_aliases` (`alias` TEXT NOT NULL, `displayName` TEXT NOT NULL, `contactId` TEXT NOT NULL, `phoneNumber` TEXT NOT NULL, PRIMARY KEY(`alias`))")
            }
        }
    }
}
