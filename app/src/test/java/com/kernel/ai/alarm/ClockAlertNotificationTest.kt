package com.kernel.ai.alarm

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import com.kernel.ai.core.memory.clock.ClockEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClockAlertNotificationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val mediaSession = MediaSession(context, "test-clock-alert")
    private val alert = TriggeredClockAlert(
        ownerId = "timer-1",
        type = ClockEventType.TIMER,
        title = "Timer",
        label = "Tea",
        occurrenceTriggerAtMillis = 1_000L,
    )

    @Test
    fun `ringing notification is ongoing alarm media notification with Stop and Snooze actions`() {
        val notification = ClockAlertNotification.build(context, alert, mediaSession.sessionToken)

        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_NO_CLEAR != 0)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(com.kernel.ai.R.drawable.ic_launcher_monochrome, notification.smallIcon.resId)
        assertNotNull(notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION))
        assertTrue(
            notification.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS)
                ?.contentEquals(intArrayOf(0, 1)) == true,
        )
        assertEquals(2, notification.actions.size)
        assertEquals("Stop", notification.actions[0].title)
        assertEquals(ClockAlertContract.ACTION_STOP_ALERT, notificationAction(notification, 0).action)
        assertEquals("Snooze", notification.actions[1].title)
        assertEquals(ClockAlertContract.ACTION_SNOOZE_ALERT, notificationAction(notification, 1).action)
        assertNotNull(notification.contentIntent)
        assertEquals(RingingActivity::class.java.name, notification.contentIntent?.let {
            org.robolectric.Shadows.shadowOf(it).savedIntent.component?.className
        })
        assertEquals(null, notification.fullScreenIntent)
    }

    @Test
    fun `ringing channel uses high importance and delegates sound to ringing service`() {
        ClockAlertNotification.ensureChannel(context)

        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(ClockAlertContract.ALERT_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
        assertEquals(null, channel.sound)
        assertEquals(false, channel.shouldVibrate())
    }

    @Test
    fun `notification and ringing screen share stop and snooze service actions`() {
        val notification = ClockAlertNotification.build(context, alert, mediaSession.sessionToken)
        val stopFromNotification = notificationAction(notification, 0)
        val snoozeFromNotification = notificationAction(notification, 1)
        val stopFromScreen = ringingActionIntent(context, ClockAlertContract.ACTION_STOP_ALERT, alert)
        val snoozeFromScreen = ringingActionIntent(context, ClockAlertContract.ACTION_SNOOZE_ALERT, alert)

        assertEquals(stopFromNotification.action, stopFromScreen.action)
        assertEquals(snoozeFromNotification.action, snoozeFromScreen.action)
        assertEquals(alert.ownerId, stopFromScreen.getStringExtra(ClockAlertContract.EXTRA_OWNER_ID))
    }

    @Test
    fun `foreground app path opens ringing activity only when main activity is visible`() {
        assertEquals(false, launchRingingActivityIfForeground(context, alert, isMainActivityVisible = false))
        assertEquals(null, org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)

        assertTrue(launchRingingActivityIfForeground(context, alert, isMainActivityVisible = true))
        assertEquals(
            RingingActivity::class.java.name,
            org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.component?.className,
        )
    }
    @Test
    fun `debug timer snooze is short while release timer snooze remains ten minutes`() {
        assertEquals(10_000L, timerSnoozeDurationMs(isDebugBuild = true))
        assertEquals(10 * 60 * 1_000L, timerSnoozeDurationMs(isDebugBuild = false))
    }
    @org.junit.After
    fun releaseMediaSession() {
        mediaSession.release()
    }


    private fun notificationAction(notification: Notification, index: Int): Intent =
        shadowOfPendingIntent(notification.actions[index].actionIntent)

    private fun shadowOfPendingIntent(pendingIntent: android.app.PendingIntent): Intent =
        org.robolectric.Shadows.shadowOf(pendingIntent).savedIntent
}
