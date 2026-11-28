package com.kernel.ai.alarm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Device evidence (S21, build 2312): after Snooze the alert service was gone but notification
 * [ClockAlertContract.ALERT_NOTIFICATION_ID] stayed posted and ongoing. [FakeAlertNotificationHost]
 * models that: `stopForeground(REMOVE)` does not clear the posted notification, only an explicit
 * cancel does. Teardown must cancel it, whichever path ends the service.
 */
class ClockAlertNotificationTeardownTest {

    private class FakeAlertNotificationHost : ClockAlertNotificationHost {
        val posted = mutableSetOf(ClockAlertContract.ALERT_NOTIFICATION_ID)
        var foreground = true

        override fun stopForegroundRemovingNotification() {
            foreground = false
        }

        override fun cancelNotification(id: Int) {
            posted.remove(id)
        }
    }

    @Test
    fun `teardown after snooze leaves no alert notification posted`() {
        val host = FakeAlertNotificationHost()

        releaseAlertNotification(host)

        assertFalse(
            host.posted.contains(ClockAlertContract.ALERT_NOTIFICATION_ID),
            "Alert notification must not stay posted after the service is torn down",
        )
        assertFalse(host.foreground, "Service must leave foreground during teardown")
    }
}
