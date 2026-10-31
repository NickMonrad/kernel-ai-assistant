package com.kernel.ai.bootrestore

import com.kernel.ai.core.memory.clock.ClockPlatformState
import com.kernel.ai.core.memory.clock.ClockScheduledEvent
import com.kernel.ai.core.memory.clock.ClockScheduler
import com.kernel.ai.core.memory.clock.SchedulingResult
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BootRestoreTestBindings {
    @Provides
    @Singleton
    fun isolatedClockScheduler(): ClockScheduler = object : ClockScheduler {
        override fun getPlatformState() = ClockPlatformState(
            canScheduleExactAlarms = false,
            notificationsEnabled = false,
            canUseFullScreenIntent = false,
        )

        override fun schedule(event: ClockScheduledEvent): SchedulingResult<Unit> =
            error("The isolated boot test must not contain clock events.")

        override fun cancel(event: ClockScheduledEvent) = Unit
    }
}
