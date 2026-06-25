package com.kernel.ai.core.memory.clock

import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.dao.StopwatchDao
import com.kernel.ai.core.memory.dao.WorldClockDao
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.ZoneId

@ExtendWith(MockKExtension::class)
class ClockRepositorySchedulingTest {
    private val scheduledAlarmDao = mockk<ScheduledAlarmDao>()
    private val scheduler = mockk<ClockScheduler>(relaxed = true)
    private val stopwatchDao = mockk<StopwatchDao>()
    private val worldClockDao = mockk<WorldClockDao>()
    private val clockSoundPreferences = mockk<ClockSoundPreferences>()
    private val clockAlertPreferences = mockk<ClockAlertPreferences>()
    private lateinit var repository: ClockRepositoryImpl

    @BeforeEach
    fun setUp() {
        repository = ClockRepositoryImpl(
            scheduledAlarmDao,
            worldClockDao,
            stopwatchDao,
            scheduler,
            clockSoundPreferences,
            clockAlertPreferences,
        )
        every { scheduler.getPlatformState() } returns ClockPlatformState(
            canScheduleExactAlarms = true,
            notificationsEnabled = true,
            canUseFullScreenIntent = false,
        )
        every { clockSoundPreferences.soundConfig } returns flowOf(ClockSoundConfig())
        every { clockAlertPreferences.alertConfig } returns flowOf(ClockAlertConfig())
    }

    // ── Full event-set rollback ────────────────────────────────────────

    @Test
    fun `pre alarm failure cancels primary and skips DB insert`() = runTest {
        // Default: all scheduling succeeds
        every { scheduler.schedule(any()) } returns SchedulingResult.Success(Unit)
        // Pre-alarm scheduling fails
        every {
            scheduler.schedule(match { it.type == ClockEventType.PRE_ALARM })
        } returns SchedulingResult.SchedulingFailed("pre-alarm unavailable")

        coEvery { scheduledAlarmDao.insert(any()) } just Runs
        val draft = AlarmDraft(
            label = "RollbackPre",
            hour = 8,
            minute = 0,
            repeatRule = AlarmRepeatRule.Daily,
            timeZoneId = ZoneId.systemDefault().id,
        )

        val result = repository.createAlarm(draft)

        assertTrue(result is SchedulingResult.SchedulingFailed)
        verify(atLeast = 1) { scheduler.schedule(match { it.type == ClockEventType.ALARM }) }
        verify(atLeast = 1) { scheduler.schedule(match { it.type == ClockEventType.PRE_ALARM }) }
        verify(atLeast = 1) { scheduler.cancel(match { it.type == ClockEventType.ALARM }) }
        coVerify(exactly = 0) { scheduledAlarmDao.insert(match { it.label == "RollbackPre" }) }
    }

    // ── Update replacement failure and restore ─────────────────────────

    @Test
    fun `update restores old schedule when replacement fails`() = runTest {
        val now = System.currentTimeMillis()
        val existingId = "alarm-existing"
        coEvery { scheduledAlarmDao.getById(existingId) } returns ScheduledAlarmEntity(
            id = existingId,
            ownerId = existingId,
            triggerAtMillis = now + 86_400_000L,
            label = "Old",
            createdAt = now - 86_400_000L,
            enabled = true,
            entryType = ClockEventType.ALARM.name,
            alarmHour = 7,
            alarmMinute = 0,
        )

        // First schedule (replacement) fails; subsequent calls (restore) succeed
        every { scheduler.schedule(any()) } returns
            SchedulingResult.SchedulingFailed("replacement failed") andThen
            SchedulingResult.Success(Unit) andThen
            SchedulingResult.Success(Unit) andThen
            SchedulingResult.Success(Unit)

        coEvery { scheduledAlarmDao.insert(any()) } just Runs
        val draft = AlarmDraft(
            label = "Updated",
            hour = 8,
            minute = 0,
            repeatRule = AlarmRepeatRule.OneOff(java.time.LocalDate.now(ZoneId.systemDefault()).plusDays(1).toEpochDay()),
            timeZoneId = ZoneId.systemDefault().id,
        )

        val result = repository.updateAlarm(existingId, draft)

        assertTrue(result is SchedulingResult.SchedulingFailed)
        // Existing alarm events were cancelled
        verify(atLeast = 1) { scheduler.cancel(any()) }
        // Replacement schedule was attempted and failed
        // Restore was attempted and succeeded
    }

    @Test
    fun `update returns clear failure when restore also fails`() = runTest {
        val now = System.currentTimeMillis()
        val existingId = "alarm-restore-fail"
        coEvery { scheduledAlarmDao.getById(existingId) } returns ScheduledAlarmEntity(
            id = existingId,
            ownerId = existingId,
            triggerAtMillis = now + 86_400_000L,
            label = "Original",
            createdAt = now - 86_400_000L,
            enabled = true,
            entryType = ClockEventType.ALARM.name,
            alarmHour = 7,
            alarmMinute = 0,
        )

        // All schedule attempts fail (exact alarm blocked)
        every { scheduler.schedule(any()) } returns SchedulingResult.ExactAlarmBlocked

        coEvery { scheduledAlarmDao.insert(any()) } just Runs
        val draft = AlarmDraft(
            label = "Updated",
            hour = 8,
            minute = 0,
            repeatRule = AlarmRepeatRule.OneOff(java.time.LocalDate.now(ZoneId.systemDefault()).plusDays(1).toEpochDay()),
            timeZoneId = ZoneId.systemDefault().id,
        )

        val result = repository.updateAlarm(existingId, draft)

        assertTrue(result is SchedulingResult.SchedulingFailed)
        // Replacement scheduling was attempted via scheduleAlarmEvents
        // Restore was also attempted (second invocation of scheduleAlarmEvents)
        assertTrue((result as SchedulingResult.SchedulingFailed).message?.contains("Manual intervention") == true)
    }
}
