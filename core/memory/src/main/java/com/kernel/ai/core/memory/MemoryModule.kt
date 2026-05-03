package com.kernel.ai.core.memory

import android.content.Context
import androidx.room.Room
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
import com.kernel.ai.core.memory.dao.WorldClockDao
import com.kernel.ai.core.memory.dao.UserProfileDao
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockRepositoryImpl
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.MemoryRepositoryImpl
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepositoryImpl
import com.kernel.ai.core.memory.vector.SqliteVecStore
import com.kernel.ai.core.memory.vector.VectorStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryModule {

    @Binds
    @Singleton
    abstract fun bindVectorStore(impl: SqliteVecStore): VectorStore

    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: MemoryRepositoryImpl): MemoryRepository

    @Binds
    @Singleton
    abstract fun bindClockRepository(impl: ClockRepositoryImpl): ClockRepository

    @Binds
    @Singleton
    abstract fun bindModelSettingsRepository(impl: ModelSettingsRepositoryImpl): ModelSettingsRepository

    companion object {

        @Provides
        @Singleton
        fun provideKernelDatabase(@ApplicationContext context: Context): KernelDatabase =
            Room.databaseBuilder(context, KernelDatabase::class.java, "kernel_db")
                .addMigrations(
                    KernelDatabase.MIGRATION_4_5,
                    KernelDatabase.MIGRATION_5_6,
                    KernelDatabase.MIGRATION_6_7,
                    KernelDatabase.MIGRATION_7_8,
                    KernelDatabase.MIGRATION_8_9,
                    KernelDatabase.MIGRATION_9_10,
                    KernelDatabase.MIGRATION_10_11,
                    KernelDatabase.MIGRATION_11_12,
                    KernelDatabase.MIGRATION_12_13,
                    KernelDatabase.MIGRATION_13_14,
                    KernelDatabase.MIGRATION_14_15,
                    KernelDatabase.MIGRATION_15_16,
                    KernelDatabase.MIGRATION_16_17,
                    KernelDatabase.MIGRATION_17_18,
                    KernelDatabase.MIGRATION_18_19,
                    KernelDatabase.MIGRATION_19_20,
                    KernelDatabase.MIGRATION_20_21,
                    KernelDatabase.MIGRATION_21_22,
                    KernelDatabase.MIGRATION_22_23,
                    KernelDatabase.MIGRATION_23_24,
                    KernelDatabase.MIGRATION_24_25,
                    KernelDatabase.MIGRATION_25_26,
                    KernelDatabase.MIGRATION_26_27,
                )
                .build()

        @Provides
        fun provideConversationDao(db: KernelDatabase): ConversationDao = db.conversationDao()

        @Provides
        fun provideMessageDao(db: KernelDatabase): MessageDao = db.messageDao()

        @Provides
        fun provideMessageEmbeddingDao(db: KernelDatabase): MessageEmbeddingDao = db.messageEmbeddingDao()

        @Provides
        fun provideUserProfileDao(db: KernelDatabase): UserProfileDao = db.userProfileDao()

        @Provides
        fun provideEpisodicMemoryDao(db: KernelDatabase): EpisodicMemoryDao = db.episodicMemoryDao()

        @Provides
        fun provideCoreMemoryDao(db: KernelDatabase): CoreMemoryDao = db.coreMemoryDao()

        @Provides
        fun provideModelSettingsDao(db: KernelDatabase): ModelSettingsDao = db.modelSettingsDao()

        @Provides
        fun provideQuickActionDao(db: KernelDatabase): QuickActionDao = db.quickActionDao()

        @Provides
        fun provideScheduledAlarmDao(db: KernelDatabase): ScheduledAlarmDao = db.scheduledAlarmDao()

        @Provides
        fun provideWorldClockDao(db: KernelDatabase): WorldClockDao = db.worldClockDao()

        @Provides
        fun provideContactAliasDao(db: KernelDatabase): ContactAliasDao = db.contactAliasDao()

        @Provides
        fun provideListItemDao(db: KernelDatabase): ListItemDao = db.listItemDao()

        @Provides
        fun provideListNameDao(db: KernelDatabase): ListNameDao = db.listNameDao()

        @Provides
        @Singleton
        fun provideKiwiMemoryDao(db: KernelDatabase): KiwiMemoryDao = db.kiwiMemoryDao()

        @Provides
        @Singleton
        fun provideContactAliasRepository(dao: ContactAliasDao): ContactAliasRepository =
            ContactAliasRepository(dao)
    }
}
