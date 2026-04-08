package com.kernel.ai.core.memory

import android.content.Context
import androidx.room.Room
import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.MessageDao
import com.kernel.ai.core.memory.dao.MessageEmbeddingDao
import com.kernel.ai.core.memory.dao.UserProfileDao
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

    companion object {

        @Provides
        @Singleton
        fun provideKernelDatabase(@ApplicationContext context: Context): KernelDatabase =
            Room.databaseBuilder(context, KernelDatabase::class.java, "kernel_db")
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()

        @Provides
        fun provideConversationDao(db: KernelDatabase): ConversationDao = db.conversationDao()

        @Provides
        fun provideMessageDao(db: KernelDatabase): MessageDao = db.messageDao()

        @Provides
        fun provideMessageEmbeddingDao(db: KernelDatabase): MessageEmbeddingDao = db.messageEmbeddingDao()

        @Provides
        fun provideUserProfileDao(db: KernelDatabase): UserProfileDao = db.userProfileDao()
    }
}
