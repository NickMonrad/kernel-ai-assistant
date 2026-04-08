package com.kernel.ai.core.memory

import android.content.Context
import androidx.room.Room
import com.kernel.ai.core.memory.dao.ConversationDao
import com.kernel.ai.core.memory.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MemoryModule {

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
}
