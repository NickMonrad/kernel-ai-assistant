package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Singleton
class ClockTimerNotificationCoordinator @Inject constructor(
    private val clockRepository: ClockRepository,
    private val notificationSink: AndroidClockTimerNotificationSink,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            clockRepository.observeActiveTimers()
                .distinctUntilChanged()
                .collect(notificationSink::sync)
        }
    }
}
