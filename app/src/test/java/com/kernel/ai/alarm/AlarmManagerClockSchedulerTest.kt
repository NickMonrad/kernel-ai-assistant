package com.kernel.ai.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.kernel.ai.core.memory.clock.ClockEventType
import com.kernel.ai.core.memory.clock.ClockScheduledEvent
import com.kernel.ai.core.memory.clock.SchedulingResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Mocks AlarmManager to check the exact or inexact call each timer and alarm makes (#1990). */
class AlarmManagerClockSchedulerTest {
    private val context = mockk<Context>(relaxed = true)
    private val alarmManager = mockk<AlarmManager>(relaxed = true)
    private val notificationManager = mockk<NotificationManager>(relaxed = true)
    private val pendingIntent = mockk<PendingIntent>(relaxed = true)
    private val notifications = mockk<NotificationManagerCompat>(relaxed = true)
    private val scheduler = AlarmManagerClockScheduler(context)
    private val event = ClockScheduledEvent(
        eventId = "timer-1",
        ownerId = "timer-1",
        type = ClockEventType.TIMER,
        triggerAtMillis = TRIGGER_AT_MILLIS,
        label = null,
    )

    @BeforeEach
    fun setUp() {
        every { context.getSystemService(AlarmManager::class.java) } returns alarmManager
        every { context.getSystemService(NotificationManager::class.java) } returns notificationManager
        every { notifications.areNotificationsEnabled() } returns true
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns notifications
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns pendingIntent
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(NotificationManagerCompat::class)
        unmockkStatic(PendingIntent::class)
    }

    @Test
    fun `timer uses the exact wakeup call when exact alarms are allowed (#1990)`() {
        every { alarmManager.canScheduleExactAlarms() } returns true

        val result = scheduler.schedule(event)

        assertTrue(result is SchedulingResult.Success)
        verify(exactly = 1) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, TRIGGER_AT_MILLIS, pendingIntent)
        }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any<PendingIntent>()) }
    }

    @Test
    fun `timer falls back to the inexact wakeup call when exact alarms are denied (#1990)`() {
        every { alarmManager.canScheduleExactAlarms() } returns false

        val result = scheduler.schedule(event)

        assertTrue(result is SchedulingResult.Success)
        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, TRIGGER_AT_MILLIS, pendingIntent)
        }
        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any<PendingIntent>()) }
    }

    @Test
    fun `timer is kept inexact when exact delivery throws SecurityException (#1990)`() {
        every { alarmManager.canScheduleExactAlarms() } returns true
        every {
            alarmManager.setExactAndAllowWhileIdle(any(), any(), any<PendingIntent>())
        } throws SecurityException("exact alarm refused")

        val result = scheduler.schedule(event)

        assertTrue(result is SchedulingResult.Success)
        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, TRIGGER_AT_MILLIS, pendingIntent)
        }
    }

    @Test
    fun `blocked notifications schedule nothing`() {
        every { notifications.areNotificationsEnabled() } returns false

        val result = scheduler.schedule(event)

        assertEquals(SchedulingResult.NotificationBlocked, result)
        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any<PendingIntent>()) }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any<PendingIntent>()) }
    }

    private companion object {
        const val TRIGGER_AT_MILLIS = 1_000_000L
    }
}
