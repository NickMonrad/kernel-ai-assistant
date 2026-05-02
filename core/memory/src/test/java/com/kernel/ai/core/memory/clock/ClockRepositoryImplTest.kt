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
