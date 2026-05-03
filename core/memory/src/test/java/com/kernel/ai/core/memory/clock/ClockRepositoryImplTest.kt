package com.kernel.ai.core.memory.clock

import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class ClockRepositoryImplTest {
    private val scheduledAlarmDao = mockk<ScheduledAlarmDao>()
    private val scheduler = mockk<ClockScheduler>(relaxed = true)

    private lateinit var repository: ClockRepositoryImpl

    @BeforeEach
    fun setUp() {
        repository = ClockRepositoryImpl(scheduledAlarmDao, scheduler)
        every {
            scheduler.getPlatformState()
        } returns ClockPlatformState(
            canScheduleExactAlarms = true,
            notificationsEnabled = true,
            canUseFullScreenIntent = false,
        )
    }

    @Test
    fun `scheduleAlarm inserts schedule row and delegates scheduling`() = runTest {
        coEvery { scheduledAlarmDao.insert(any()) } just Runs

        val result = repository.scheduleAlarm(triggerAtMillis = 1_234_567L, label = "Morning")

        assertEquals("Morning", result?.label)
        assertEquals(1_234_567L, result?.triggerAtMillis)
        coVerify(exactly = 1) {
            scheduledAlarmDao.insert(
                match {
                    it.ownerId == it.id &&
                        it.entryType == ClockEventType.ALARM.name &&
                        it.triggerAtMillis == 1_234_567L &&
                        it.label == "Morning"
                }
            )
        }
        verify(exactly = 1) {
            scheduler.schedule(
                match {
                    it.ownerId == it.eventId &&
                        it.type == ClockEventType.ALARM &&
                        it.triggerAtMillis == 1_234_567L &&
                        it.label == "Morning"
                }
            )
        }
    }

    @Test
    fun `observeActiveTimers filters expired timers from the main list`() = runTest {
        val expired = scheduleRow(
            id = "expired-timer",
            triggerAtMillis = System.currentTimeMillis() - 60_000L,
            enabled = true,
            entryType = ClockEventType.TIMER.name,
        ).copy(durationMs = 60_000L, startedAtMs = System.currentTimeMillis() - 120_000L)
        val active = scheduleRow(
            id = "active-timer",
            triggerAtMillis = System.currentTimeMillis() + 60_000L,
            enabled = true,
            entryType = ClockEventType.TIMER.name,
        ).copy(durationMs = 60_000L, startedAtMs = System.currentTimeMillis())
        every { scheduledAlarmDao.observeActiveTimers() } returns flowOf(listOf(expired, active))

        val timers = repository.observeActiveTimers().first()

        assertEquals(listOf("active-timer"), timers.map { it.id })
    }

    @Test
    fun `restoreScheduledEntries marks expired rows and restores only enabled future rows`() = runTest {
        val now = 5_000L
        val expired = scheduleRow(id = "expired", triggerAtMillis = 4_000L)
        val enabledFuture = scheduleRow(id = "future-enabled", triggerAtMillis = 6_000L)
        val disabledFuture = scheduleRow(id = "future-disabled", triggerAtMillis = 7_000L, enabled = false)

        coEvery { scheduledAlarmDao.getUnfiredElapsed(now) } returns listOf(expired)
        coEvery { scheduledAlarmDao.getUnfiredFuture(now) } returns listOf(enabledFuture, disabledFuture)
        coEvery { scheduledAlarmDao.markFired(expired.id) } just Runs

        val report = repository.restoreScheduledEntries(nowMillis = now)

        assertEquals(
            ClockRestoreReport(
                restoredCount = 1,
                expiredCount = 1,
                disabledCount = 1,
                blockedByExactAlarmCapability = false,
            ),
            report,
        )
        coVerify(exactly = 1) { scheduledAlarmDao.markFired("expired") }
        verify(exactly = 1) { scheduler.schedule(match { it.eventId == "future-enabled" }) }
        verify(exactly = 0) { scheduler.schedule(match { it.eventId == "future-disabled" }) }
    }

    @Test
    fun `cancelNextAlarm cancels and deletes the earliest enabled alarm`() = runTest {
        val later = scheduleRow(id = "later", triggerAtMillis = 8_000L)
        val earlier = scheduleRow(id = "earlier", triggerAtMillis = 6_000L)
        coEvery { scheduledAlarmDao.getUnfiredFuture(any()) } returns listOf(earlier, later)
        coEvery { scheduledAlarmDao.delete("earlier") } just Runs

        val cancelled = repository.cancelNextAlarm()

        assertEquals("earlier", cancelled?.id)
        verify(exactly = 1) { scheduler.cancel(match { it.eventId == "earlier" }) }
        coVerify(exactly = 1) { scheduledAlarmDao.delete("earlier") }
    }

    @Test
    fun `cancelAlarmsByLabel cancels only enabled alarms`() = runTest {
        val enabledMatch = scheduleRow(id = "enabled", triggerAtMillis = 6_000L).copy(label = "Wake")
        val disabledMatch = scheduleRow(id = "disabled", triggerAtMillis = 7_000L, enabled = false).copy(label = "Wake")
        val other = scheduleRow(id = "other", triggerAtMillis = 8_000L).copy(label = "Other")
        coEvery { scheduledAlarmDao.getUnfiredFuture(any()) } returns listOf(enabledMatch, disabledMatch, other)
        coEvery { scheduledAlarmDao.delete("enabled") } just Runs

        val cancelled = repository.cancelAlarmsByLabel("wake")

        assertEquals(1, cancelled)
        verify(exactly = 1) { scheduler.cancel(match { it.eventId == "enabled" }) }
        verify(exactly = 0) { scheduler.cancel(match { it.eventId == "disabled" }) }
        coVerify(exactly = 1) { scheduledAlarmDao.delete("enabled") }
        coVerify(exactly = 0) { scheduledAlarmDao.delete("disabled") }
    }

    @Test
    fun `setAlarmEnabled rolls back scheduler cancel when disable write fails`() = runTest {
        val existing = scheduleRow(id = "alarm-1", triggerAtMillis = 6_000L, enabled = true)
        coEvery { scheduledAlarmDao.getById("alarm-1") } returns existing
        coEvery { scheduledAlarmDao.setEnabled("alarm-1", false) } throws IllegalStateException("db")

        val result = repository.setAlarmEnabled("alarm-1", enabled = false)

        assertEquals(false, result)
        verify(exactly = 1) { scheduler.cancel(match { it.eventId == "alarm-1" }) }
        verify(exactly = 1) { scheduler.schedule(match { it.eventId == "alarm-1" }) }
    }

    @Test
    fun `setAlarmEnabled rejects enabling a past due alarm`() = runTest {
        val existing = scheduleRow(
            id = "alarm-1",
            triggerAtMillis = System.currentTimeMillis() - 1_000L,
            enabled = false,
        )
        coEvery { scheduledAlarmDao.getById("alarm-1") } returns existing

        val result = repository.setAlarmEnabled("alarm-1", enabled = true)

        assertEquals(false, result)
        verify(exactly = 0) { scheduler.schedule(any()) }
        coVerify(exactly = 0) { scheduledAlarmDao.setEnabled(any(), any()) }
    }

    @Test
    fun `setAlarmEnabled rolls back scheduler schedule when enable write fails`() = runTest {
        val existing = scheduleRow(
            id = "alarm-1",
            triggerAtMillis = System.currentTimeMillis() + 60_000L,
            enabled = false,
        )
        coEvery { scheduledAlarmDao.getById("alarm-1") } returns existing
        coEvery { scheduledAlarmDao.setEnabled("alarm-1", true) } throws IllegalStateException("db")

        val result = repository.setAlarmEnabled("alarm-1", enabled = true)

        assertEquals(false, result)
        verify(exactly = 1) { scheduler.schedule(match { it.eventId == "alarm-1" }) }
        verify(exactly = 1) { scheduler.cancel(match { it.eventId == "alarm-1" }) }
    }

    @Test
    fun `recordDeliveredEvent marks alarms fired`() = runTest {
        val existing = scheduleRow(id = "alarm-1", triggerAtMillis = 6_000L, enabled = true)
        coEvery { scheduledAlarmDao.getById("alarm-1") } returns existing
        coEvery { scheduledAlarmDao.markFired("alarm-1") } just Runs

        repository.recordDeliveredEvent("alarm-1")

        coVerify(exactly = 1) { scheduledAlarmDao.markFired("alarm-1") }
        coVerify(exactly = 0) { scheduledAlarmDao.delete("alarm-1") }
    }

    @Test
    fun `recordDeliveredEvent marks timers completed instead of deleting them`() = runTest {
        val existing = scheduleRow(
            id = "timer-1",
            triggerAtMillis = 6_000L,
            enabled = true,
            entryType = ClockEventType.TIMER.name,
        ).copy(durationMs = 60_000L, startedAtMs = 1_000L)
        coEvery { scheduledAlarmDao.getById("timer-1") } returns existing
        coEvery { scheduledAlarmDao.markTimerCompleted(eq("timer-1"), any()) } just Runs

        repository.recordDeliveredEvent("timer-1")

        coVerify(exactly = 1) { scheduledAlarmDao.markTimerCompleted(eq("timer-1"), any()) }
        coVerify(exactly = 0) { scheduledAlarmDao.delete("timer-1") }
    }

    @Test
    fun `observeRecentCompletedTimers maps completed timer history`() = runTest {
        val completed = scheduleRow(
            id = "timer-history",
            triggerAtMillis = 8_000L,
            enabled = true,
            entryType = ClockEventType.TIMER.name,
        ).copy(durationMs = 120_000L, startedAtMs = 6_000L, completedAtMs = 8_100L)
        every { scheduledAlarmDao.observeRecentCompletedTimers() } returns flowOf(listOf(completed))

        val timers = repository.observeRecentCompletedTimers().first()

        assertEquals(listOf("timer-history"), timers.map { it.id })
        assertEquals(8_100L, timers.single().completedAtMillis)
    }

    @Test
    fun `editAlarm returns null when row is already gone`() = runTest {
        coEvery { scheduledAlarmDao.getById("missing") } returns null

        val result = repository.editAlarm("missing", newTriggerAtMillis = 9_000L, newLabel = "Updated")

        assertEquals(null, result)
        verify(exactly = 0) { scheduler.cancel(any()) }
    }

    @Test
    fun `restoreScheduledEntries disables unrecoverable alarms and expires timers when exact alarms are unavailable`() = runTest {
        val now = 5_000L
        val futureAlarm = scheduleRow(id = "future-alarm", triggerAtMillis = 6_000L)
        val futureTimer = scheduleRow(
            id = "future-timer",
            triggerAtMillis = 7_000L,
            entryType = ClockEventType.TIMER.name,
        ).copy(durationMs = 60_000L, startedAtMs = 1_000L)

        every { scheduler.getPlatformState() } returns ClockPlatformState(
            canScheduleExactAlarms = false,
            notificationsEnabled = true,
            canUseFullScreenIntent = false,
        )
        coEvery { scheduledAlarmDao.getUnfiredElapsed(now) } returns emptyList()
        coEvery { scheduledAlarmDao.getUnfiredFuture(now) } returns listOf(futureAlarm, futureTimer)
        coEvery { scheduledAlarmDao.setEnabled("future-alarm", false) } just Runs
        coEvery { scheduledAlarmDao.markFired("future-timer") } just Runs

        val report = repository.restoreScheduledEntries(nowMillis = now)

        assertEquals(
            ClockRestoreReport(
                restoredCount = 0,
                expiredCount = 0,
                disabledCount = 1,
                blockedByExactAlarmCapability = true,
            ),
            report,
        )
        coVerify(exactly = 1) { scheduledAlarmDao.setEnabled("future-alarm", false) }
        coVerify(exactly = 1) { scheduledAlarmDao.markFired("future-timer") }
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test
    fun `clearCompletedTimers delegates to dao delete completed timers`() = runTest {
        coEvery { scheduledAlarmDao.deleteCompletedTimers() } returns 2

        val deleted = repository.clearCompletedTimers()

        assertEquals(2, deleted)
        coVerify(exactly = 1) { scheduledAlarmDao.deleteCompletedTimers() }
    }
    private fun scheduleRow(
        id: String,
        triggerAtMillis: Long,
        enabled: Boolean = true,
        entryType: String = ClockEventType.ALARM.name,
    ): ScheduledAlarmEntity = ScheduledAlarmEntity(
        id = id,
        ownerId = id,
        triggerAtMillis = triggerAtMillis,
        label = id,
        createdAt = 1_000L,
        enabled = enabled,
        entryType = entryType,
    )
}
