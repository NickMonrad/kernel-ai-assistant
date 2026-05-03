package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockStopwatch
import com.kernel.ai.core.memory.clock.StopwatchStatus

internal interface ClockStopwatchNotificationTransport {
    fun show(stopwatch: ClockStopwatch)
    fun cancel(notificationId: Int)
}

internal class ClockStopwatchNotificationSyncer(
    private val transport: ClockStopwatchNotificationTransport,
) {
    private var visible = false

    fun sync(stopwatch: ClockStopwatch) {
        if (stopwatch.status == StopwatchStatus.IDLE) {
            if (visible) {
                transport.cancel(ClockAlertContract.STOPWATCH_NOTIFICATION_ID)
                visible = false
            }
            return
        }
        transport.show(stopwatch)
        visible = true
    }
}
