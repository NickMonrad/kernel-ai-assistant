package com.kernel.ai.bootrestore

import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.dao.ImportantDateDao
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.notification.ImportantDateNotificationPreferences
import com.kernel.ai.core.memory.notification.ImportantDateNotificationScheduler
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootRestoreDatabaseEntryPoint {
    fun kernelDatabase(): KernelDatabase
    fun clockRepository(): ClockRepository
    fun listItemDao(): ListItemDao
    fun listNameDao(): ListNameDao
    fun listNotificationScheduler(): ListNotificationScheduler
    fun importantDateDao(): ImportantDateDao
    fun importantDateNotificationScheduler(): ImportantDateNotificationScheduler
    fun importantDateNotificationPreferences(): ImportantDateNotificationPreferences
}
