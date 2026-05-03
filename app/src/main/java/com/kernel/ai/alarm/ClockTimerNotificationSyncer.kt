package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockTimer

internal interface ClockTimerNotificationTransport {
    fun show(timer: ClockTimer)
    fun cancel(notificationId: Int)
}

internal class ClockTimerNotificationSyncer(
    private val transport: ClockTimerNotificationTransport,
) {
    private var activeTimerIds: Set<String> = emptySet()

    fun sync(timers: List<ClockTimer>) {
        val nextTimerIds = timers.map { it.id }.toSet()
        timers.forEach(transport::show)
        (activeTimerIds - nextTimerIds)
            .map(ClockAlertContract::timerNotificationId)
            .forEach(transport::cancel)
        activeTimerIds = nextTimerIds
    }
}
