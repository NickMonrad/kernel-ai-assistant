package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockSchedulerModule {
    @Binds
    @Singleton
    abstract fun bindClockScheduler(
        impl: AlarmManagerClockScheduler,
    ): ClockScheduler
}
