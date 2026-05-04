package com.kernel.ai.core.memory

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kernel.ai.core.memory.dao.ContactAliasDao
import com.kernel.ai.core.memory.dao.KiwiMemoryDao
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.CoreMemoryDao
import com.kernel.ai.core.memory.dao.EpisodicMemoryDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.dao.ModelSettingsDao
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.dao.StopwatchDao
import com.kernel.ai.core.memory.dao.WorldClockDao
import com.kernel.ai.core.memory.dao.UserProfileDao
import com.kernel.ai.core.memory.entity.ContactAliasEntity
import com.kernel.ai.core.memory.entity.KiwiMemoryEntity
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.entity.CoreMemoryEntity
import com.kernel.ai.core.memory.entity.EpisodicMemoryEntity
import com.kernel.ai.core.memory.entity.MessageEmbeddingEntity
import com.kernel.ai.core.memory.entity.MessageEntity
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import com.kernel.ai.core.memory.entity.StopwatchLapEntity
import com.kernel.ai.core.memory.entity.StopwatchStateEntity
import com.kernel.ai.core.memory.entity.WorldClockEntity
import com.kernel.ai.core.memory.entity.UserProfileEntity
import java.time.ZoneId

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageEmbeddingEntity::class,
        UserProfileEntity::class,
        EpisodicMemoryEntity::class,
        CoreMemoryEntity::class,
        KiwiMemoryEntity::class,
        ModelSettingsEntity::class,
        QuickActionEntity::class,
        ScheduledAlarmEntity::class,
        WorldClockEntity::class,
        StopwatchStateEntity::class,
        StopwatchLapEntity::class,
        ContactAliasEntity::class,
        ListItemEntity::class,
        ListNameEntity::class,
    ],
    version = 29,
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
    abstract fun worldClockDao(): WorldClockDao
    abstract fun stopwatchDao(): StopwatchDao
    abstract fun contactAliasDao(): ContactAliasDao
    abstract fun listItemDao(): ListItemDao
    abstract fun listNameDao(): ListNameDao
    abstract fun kiwiMemoryDao(): KiwiMemoryDao

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
                        `checked` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** Corrects topK default from 40 → 64 per Gemma 4 model card. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE model_settings SET topK = 64 WHERE topK = 40")
            }
        }

        /** Creates lists table so list names persist independently of their items (#477). */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /** Creates contact_aliases table for relationship-name to contact mapping (#355). */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `contact_aliases` (`alias` TEXT NOT NULL, `displayName` TEXT NOT NULL, `contactId` TEXT NOT NULL, `phoneNumber` TEXT NOT NULL, PRIMARY KEY(`alias`))")
            }
        }

        /** Adds enabled flag to scheduled_alarms for per-alarm toggle (#479). */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
            }
        }
        /** Adds entry_type, duration_ms, started_at_ms to scheduled_alarms for timer registry (#525). */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN entry_type TEXT NOT NULL DEFAULT 'ALARM'")
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN duration_ms INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN started_at_ms INTEGER DEFAULT NULL")
            }
        }

        /** Deduplicates list names and adds unique index to prevent future duplicates crashing LazyColumn. */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep only the earliest row for each duplicate list name
                db.execSQL("DELETE FROM lists WHERE id NOT IN (SELECT MIN(id) FROM lists GROUP BY name)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_lists_name ON lists(name)")
            }
        }
        /** Adds NZ truth structured fields to core_memories for vibe-aware RAG (#635). */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE core_memories ADD COLUMN term TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE core_memories ADD COLUMN definition TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE core_memories ADD COLUMN triggerContext TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE core_memories ADD COLUMN vibeLevel INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE core_memories ADD COLUMN metadataJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        /** Creates kiwi_memories table for NZ/Jandal corpus entries (#500). */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `kiwi_memories` (
                        `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastAccessedAt` INTEGER NOT NULL,
                        `accessCount` INTEGER NOT NULL DEFAULT 0,
                        `source` TEXT NOT NULL,
                        `vectorized` INTEGER NOT NULL DEFAULT 0,
                        `category` TEXT NOT NULL DEFAULT 'agent_identity',
                        `term` TEXT NOT NULL DEFAULT '',
                        `definition` TEXT NOT NULL DEFAULT '',
                        `triggerContext` TEXT NOT NULL DEFAULT '',
                        `vibeLevel` INTEGER NOT NULL DEFAULT 1,
                        `metadataJson` TEXT NOT NULL DEFAULT '{}'
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_kiwi_memories_id ON kiwi_memories (id)")
            }
        }

        /** Adds presentationJson to quick_actions for rich tool result UI (#222). */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quick_actions ADD COLUMN presentationJson TEXT DEFAULT NULL")
            }
        }

        /** Adds owner_id to scheduled_alarms so reminder events can point at their owning clock item (#720). */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN owner_id TEXT DEFAULT NULL")
                db.execSQL("UPDATE scheduled_alarms SET owner_id = id WHERE owner_id IS NULL")
            }
        }

        /** Adds repeating-alarm fields to scheduled_alarms for first-class alarms UI (#739). */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val systemZoneId = ZoneId.systemDefault().id
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN alarm_hour INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN alarm_minute INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN repeat_type TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN repeat_days_mask INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN one_off_date_epoch_day INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN time_zone_id TEXT DEFAULT NULL")
                db.execSQL(
                    """
                    UPDATE scheduled_alarms
                    SET alarm_hour = CAST(strftime('%H', triggerAtMillis / 1000, 'unixepoch', 'localtime') AS INTEGER),
                        alarm_minute = CAST(strftime('%M', triggerAtMillis / 1000, 'unixepoch', 'localtime') AS INTEGER),
                        repeat_type = 'ONE_OFF',
                        one_off_date_epoch_day = CAST(julianday(date(triggerAtMillis / 1000, 'unixepoch', 'localtime')) - julianday('1970-01-01') AS INTEGER),
                        time_zone_id = '$systemZoneId'
                    WHERE entry_type = 'ALARM'
                    """.trimIndent(),
                )
            }
        }

        /** Creates world_clocks for first-class world clock favorites (#742). */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `world_clocks` (
                        `id` TEXT NOT NULL,
                        `zone_id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `sort_order` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_world_clocks_zone_id` ON `world_clocks` (`zone_id`)")
            }
        }

        /** Creates stopwatch state + lap tables for first-class stopwatch UI (#745). */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stopwatch_state` (
                        `id` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `accumulated_elapsed_ms` INTEGER NOT NULL,
                        `running_since_elapsed_realtime_ms` INTEGER,
                        `running_since_wall_clock_ms` INTEGER,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stopwatch_laps` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `stopwatch_id` TEXT NOT NULL,
                        `lap_number` INTEGER NOT NULL,
                        `elapsed_ms` INTEGER NOT NULL,
                        `split_ms` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        FOREIGN KEY(`stopwatch_id`) REFERENCES `stopwatch_state`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stopwatch_laps_stopwatch_id` ON `stopwatch_laps` (`stopwatch_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_stopwatch_laps_stopwatch_id_lap_number` ON `stopwatch_laps` (`stopwatch_id`, `lap_number`)")
            }
        }

        /** Adds completed_at_ms to scheduled_alarms so fired timers can appear in recent history (#737). */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN completed_at_ms INTEGER DEFAULT NULL")
            }
        }

        /** Adds snoozed_until_ms to scheduled_alarms for alert-time snooze handling (#752). */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_alarms ADD COLUMN snoozed_until_ms INTEGER DEFAULT NULL")
            }
        }
    }
}
