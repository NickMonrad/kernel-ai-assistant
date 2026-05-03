package com.kernel.ai.core.memory.clock

import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.dao.WorldClockDao
import com.kernel.ai.core.memory.entity.ScheduledAlarmEntity
import com.kernel.ai.core.memory.entity.WorldClockEntity
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@ExtendWith(MockKExtension::class)
class ClockRepositoryImplTest {
    private val scheduledAlarmDao = mockk<ScheduledAlarmDao>()
    private val scheduler = mockk<ClockScheduler>(relaxed = true)
    private val worldClockDao = mockk<WorldClockDao>()

    private lateinit var repository: ClockRepositoryImpl

    @BeforeEach
    fun setUp() {
        repository = ClockRepositoryImpl(scheduledAlarmDao, worldClockDao, scheduler)
        every {
            scheduler.getPlatformState()
        } returns ClockPlatformState(
            canScheduleExactAlarms = true,
            notificationsEnabled = true,
            canUseFullScreenIntent = false,
        )
    }

    @Test
    fun `createAlarm inserts one off alarm row and delegates scheduling`() = runTest {
        coEvery { scheduledAlarmDao.insert(any()) } just Runs
        val zoneId = ZoneId.systemDefault().id
        val draft = AlarmDraft(
            label = "Morning",
            hour = 7,
            minute = 30,
            repeatRule = AlarmRepeatRule.OneOff(LocalDate.now(ZoneId.systemDefault()).plusDays(1).toEpochDay()),
            timeZoneId = zoneId,
        )

        val result = repository.createAlarm(draft)

        assertEquals("Morning", result?.label)
        assertEquals(7, result?.hour)
        assertEquals(30, result?.minute)
        coVerify(exactly = 1) {
            scheduledAlarmDao.insert(match {
                it.entryType == ClockEventType.ALARM.name &&
                    it.repeatType == "ONE_OFF" &&
                    it.alarmHour == 7 &&
                    it.alarmMinute == 30 &&
                    it.timeZoneId == zoneId
            })
        }
        verify(exactly = 1) { scheduler.schedule(match { it.type == ClockEventType.ALARM }) }
        verify(exactly = 0) { scheduler.schedule(match { it.type == ClockEventType.PRE_ALARM }) }
    }

    @Test
    fun `createAlarm schedules pre alarm companion for repeating alarms`() = runTest {
        coEvery { scheduledAlarmDao.insert(any()) } just Runs
        val draft = dailyDraft(label = "Gym", hour = 8, minute = 0)

        val result = repository.createAlarm(draft)

        assertEquals(AlarmRepeatRule.Daily, result?.repeatRule)
        verify(exactly = 1) { scheduler.schedule(match { it.type == ClockEventType.ALARM }) }
        verify(exactly = 1) { scheduler.schedule(match { it.type == ClockEventType.PRE_ALARM }) }
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
    fun `setAlarmEnabled rolls repeating alarms forward when re enabling stale row`() = runTest {
        val staleDaily = scheduleRow(
            id = "alarm-1",
            triggerAtMillis = System.currentTimeMillis() - 3_600_000L,
            enabled = false,
            repeatType = "DAILY",
            alarmHour = 7,
            alarmMinute = 30,
        )
        coEvery { scheduledAlarmDao.getById("alarm-1") } returns staleDaily
        coEvery { scheduledAlarmDao.insert(any()) } just Runs

        val result = repository.setAlarmEnabled("alarm-1", enabled = true)

        assertTrue(result)
        coVerify(exactly = 1) { scheduledAlarmDao.insert(match { it.enabled && it.triggerAtMillis > System.currentTimeMillis() }) }
        verify(exactly = 1) { scheduler.schedule(match { it.type == ClockEventType.ALARM }) }
        verify(exactly = 1) { scheduler.schedule(match { it.type == ClockEventType.PRE_ALARM }) }
    }

    @Test
    fun `skipAlarmOccurrence advances repeating alarm only once`() = runTest {
        val daily = scheduleRow(
            id = "alarm-1",
            triggerAtMillis = System.currentTimeMillis() + 3_600_000L,
            repeatType = "DAILY",
            alarmHour = 7,
            alarmMinute = 30,
        )
        coEvery { scheduledAlarmDao.getById("alarm-1") } returns daily
        coEvery { scheduledAlarmDao.insert(any()) } just Runs

        val skipped = repository.skipAlarmOccurrence("alarm-1", daily.triggerAtMillis)

        assertTrue(skipped)
        coVerify(exactly = 1) { scheduledAlarmDao.insert(match { it.triggerAtMillis > daily.triggerAtMillis }) }
        verify(atLeast = 1) { scheduler.cancel(match { it.type == ClockEventType.PRE_ALARM }) }
        verify(atLeast = 1) { scheduler.schedule(match { it.type == ClockEventType.ALARM }) }
    }

    @Test
    fun `handleScheduledEvent advances repeating alarms after delivery`() = runTest {
        val weekdayMask = weekdayMaskFor(DayOfWeek.MONDAY) or weekdayMaskFor(DayOfWeek.FRIDAY)
        val repeating = scheduleRow(
            id = "alarm-1",
            triggerAtMillis = System.currentTimeMillis() + 60_000L,
            repeatType = "SELECTED_WEEKDAYS",
            repeatDaysMask = weekdayMask,
            alarmHour = 7,
            alarmMinute = 30,
        )
        coEvery { scheduledAlarmDao.getById("alarm-1") } returns repeating
        coEvery { scheduledAlarmDao.insert(any()) } just Runs

        repository.handleScheduledEvent("alarm-1", ClockEventType.ALARM, repeating.triggerAtMillis)

        coVerify(exactly = 1) { scheduledAlarmDao.insert(match { it.triggerAtMillis > repeating.triggerAtMillis }) }
        coVerify(exactly = 0) { scheduledAlarmDao.markFired("alarm-1") }
    }

    @Test
    fun `handleScheduledEvent marks one off alarms fired`() = runTest {
        val oneOff = scheduleRow(id = "alarm-1", triggerAtMillis = System.currentTimeMillis() + 60_000L)
        coEvery { scheduledAlarmDao.getById("alarm-1") } returns oneOff
        coEvery { scheduledAlarmDao.markFired("alarm-1") } just Runs

        repository.handleScheduledEvent("alarm-1", ClockEventType.ALARM, oneOff.triggerAtMillis)

        coVerify(exactly = 1) { scheduledAlarmDao.markFired("alarm-1") }
    }

    @Test
    fun `handleScheduledEvent marks timers completed instead of deleting them`() = runTest {
        val existing = scheduleRow(
            id = "timer-1",
            triggerAtMillis = 6_000L,
            enabled = true,
            entryType = ClockEventType.TIMER.name,
        ).copy(durationMs = 60_000L, startedAtMs = 1_000L)
        coEvery { scheduledAlarmDao.getById("timer-1") } returns existing
        coEvery { scheduledAlarmDao.markTimerCompleted(eq("timer-1"), any()) } just Runs

        repository.handleScheduledEvent("timer-1", ClockEventType.TIMER, existing.triggerAtMillis)

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
    fun `restoreScheduledEntries rolls expired repeating alarms forward`() = runTest {
        val now = System.currentTimeMillis()
        val repeatingExpired = scheduleRow(
            id = "alarm-1",
            triggerAtMillis = now - 60_000L,
            repeatType = "DAILY",
            alarmHour = 7,
            alarmMinute = 30,
        )
        coEvery { scheduledAlarmDao.getUnfiredElapsed(now) } returns listOf(repeatingExpired)
        coEvery { scheduledAlarmDao.getUnfiredFuture(now) } returns emptyList()
        coEvery { scheduledAlarmDao.insert(any()) } just Runs

        val report = repository.restoreScheduledEntries(nowMillis = now)

        assertEquals(1, report.restoredCount)
        assertEquals(0, report.expiredCount)
        coVerify(exactly = 1) { scheduledAlarmDao.insert(match { it.triggerAtMillis > now }) }
        verify(exactly = 1) { scheduler.schedule(match { it.type == ClockEventType.ALARM }) }
    }

    @Test
    fun `clearCompletedTimers delegates to dao delete completed timers`() = runTest {
        coEvery { scheduledAlarmDao.deleteCompletedTimers() } returns 2

        val deleted = repository.clearCompletedTimers()

        assertEquals(2, deleted)
        coVerify(exactly = 1) { scheduledAlarmDao.deleteCompletedTimers() }
    }

    @Test
    fun `addWorldClock inserts ordered favorite and returns model`() = runTest {
        coEvery { worldClockDao.getByZoneId("Europe/London") } returns null
        coEvery { worldClockDao.getMaxSortOrder() } returns 2
        coEvery { worldClockDao.insert(any()) } just Runs

        val result = repository.addWorldClock("Europe/London", "London")

        assertEquals("London", result?.displayName)
        coVerify(exactly = 1) {
            worldClockDao.insert(match {
                it.zoneId == "Europe/London" &&
                    it.displayName == "London" &&
                    it.sortOrder == 3
            })
        }
    }

    @Test
    fun `reorderWorldClocks rewrites sort order in requested order`() = runTest {
        val first = WorldClockEntity("1", "Europe/London", "London", 0, 1_000L)
        val second = WorldClockEntity("2", "Asia/Tokyo", "Tokyo", 1, 2_000L)
        coEvery { worldClockDao.getAll() } returns listOf(first, second)
        coEvery { worldClockDao.updateAll(any()) } just Runs

        val reordered = repository.reorderWorldClocks(listOf("2", "1"))

        assertTrue(reordered)
        coVerify(exactly = 1) {
            worldClockDao.updateAll(match { entities ->
                entities[0].id == "2" && entities[0].sortOrder == 0 &&
                    entities[1].id == "1" && entities[1].sortOrder == 1
            })
        }
    }

    private fun dailyDraft(label: String, hour: Int, minute: Int) = AlarmDraft(
        label = label,
        hour = hour,
        minute = minute,
        repeatRule = AlarmRepeatRule.Daily,
        timeZoneId = ZoneId.systemDefault().id,
    )

    private fun scheduleRow(
        id: String,
        triggerAtMillis: Long,
        enabled: Boolean = true,
        entryType: String = ClockEventType.ALARM.name,
        repeatType: String = "ONE_OFF",
        repeatDaysMask: Int? = null,
        alarmHour: Int? = 7,
        alarmMinute: Int? = 30,
    ): ScheduledAlarmEntity = ScheduledAlarmEntity(
        id = id,
        ownerId = id,
        triggerAtMillis = triggerAtMillis,
        label = id,
        createdAt = 1_000L,
        enabled = enabled,
        entryType = entryType,
        alarmHour = if (entryType == ClockEventType.ALARM.name) alarmHour else null,
        alarmMinute = if (entryType == ClockEventType.ALARM.name) alarmMinute else null,
        repeatType = if (entryType == ClockEventType.ALARM.name) repeatType else null,
        repeatDaysMask = repeatDaysMask,
        oneOffDateEpochDay = Instant.ofEpochMilli(triggerAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(),
        timeZoneId = ZoneId.systemDefault().id,
    )
}

private fun weekdayMaskFor(day: DayOfWeek): Int = 1 shl (day.value - 1)