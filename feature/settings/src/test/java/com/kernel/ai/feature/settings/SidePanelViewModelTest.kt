package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockStopwatch
import com.kernel.ai.core.memory.clock.ClockTimer
import com.kernel.ai.core.memory.clock.StopwatchStatus
import com.kernel.ai.core.memory.clock.WorldClock
import com.kernel.ai.core.memory.clock.WorldClockCandidate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SidePanelViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clockRepository = mockk<ClockRepository>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { clockRepository.observeManageableAlarms() } returns emptyFlow()
        every { clockRepository.observeActiveTimers() } returns emptyFlow()
        every { clockRepository.observeRecentCompletedTimers() } returns emptyFlow()
        every { clockRepository.observeWorldClocks() } returns emptyFlow()
        every { clockRepository.observeStopwatch() } returns kotlinx.coroutines.flow.flowOf(
            ClockStopwatch(
                id = "primary",
                status = StopwatchStatus.IDLE,
                accumulatedElapsedMs = 0L,
                updatedAtMillis = 0L,
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scheduleAlarm returns false when repository rejects exact alarm`() = runTest {
        val draft = sampleAlarmDraft(label = "Wake")
        coEvery { clockRepository.createAlarm(draft) } returns null
        val viewModel = SidePanelViewModel(clockRepository)

        val result = viewModel.tryScheduleAlarm(draft)

        assertEquals(false, result)
    }

    @Test
    fun `scheduleAlarm reports failure when repository cannot store alarm`() = runTest {
        val draft = sampleAlarmDraft(label = "Wake")
        coEvery { clockRepository.createAlarm(draft) } returns null
        val viewModel = SidePanelViewModel(clockRepository)
        var result: AlarmSaveResult? = null

        viewModel.scheduleAlarm(draft) { result = it }
        advanceUntilIdle()

        assertEquals(AlarmSaveResult.FAILED, result)
    }

    @Test
    fun `scheduleTimer returns true when repository accepts timer`() = runTest {
        coEvery { clockRepository.scheduleTimer(60_000L, "Tea") } returns ClockTimer(
            id = "timer-1",
            triggerAtMillis = 5_000L,
            label = "Tea",
            createdAtMillis = 1_000L,
            durationMs = 60_000L,
            startedAtMillis = 2_000L,
        )
        val viewModel = SidePanelViewModel(clockRepository)

        val result = viewModel.tryScheduleTimer(60_000L, "Tea")

        assertEquals(true, result)
    }

    @Test
    fun `restartTimer reuses timer duration and label`() = runTest {
        val timer = ClockTimer(
            id = "timer-1",
            triggerAtMillis = 5_000L,
            label = "Tea",
            createdAtMillis = 1_000L,
            durationMs = 60_000L,
            startedAtMillis = 2_000L,
            completedAtMillis = 5_000L,
        )
        coEvery { clockRepository.scheduleTimer(60_000L, "Tea") } returns timer
        val viewModel = SidePanelViewModel(clockRepository)
        var success: Boolean? = null

        viewModel.restartTimer(timer) { success = it }
        advanceUntilIdle()

        assertEquals(true, success)
        coVerify(exactly = 1) { clockRepository.scheduleTimer(60_000L, "Tea") }
    }

    @Test
    fun `clearCompletedTimers reports deleted count`() = runTest {
        coEvery { clockRepository.clearCompletedTimers() } returns 3
        val viewModel = SidePanelViewModel(clockRepository)
        var deleted: Int? = null

        viewModel.clearCompletedTimers { deleted = it }
        advanceUntilIdle()

        assertEquals(3, deleted)
        coVerify(exactly = 1) { clockRepository.clearCompletedTimers() }
    }

    @Test
    fun `startStopwatch forwards wall and elapsed clocks to repository`() = runTest {
        coEvery { clockRepository.startStopwatch(10_000L, 5_000L) } returns ClockStopwatch(
            id = "primary",
            status = StopwatchStatus.RUNNING,
            accumulatedElapsedMs = 0L,
            runningSinceElapsedRealtimeMs = 5_000L,
            runningSinceWallClockMs = 10_000L,
            updatedAtMillis = 10_000L,
        )
        val viewModel = SidePanelViewModel(clockRepository)

        viewModel.startStopwatch(nowWallClockMillis = 10_000L, nowElapsedRealtimeMs = 5_000L)
        advanceUntilIdle()

        coVerify(exactly = 1) { clockRepository.startStopwatch(10_000L, 5_000L) }
    }

    @Test
    fun `recordStopwatchLap delegates to repository`() = runTest {
        coEvery { clockRepository.recordStopwatchLap(12_000L, 6_000L) } returns null
        val viewModel = SidePanelViewModel(clockRepository)

        viewModel.recordStopwatchLap(nowWallClockMillis = 12_000L, nowElapsedRealtimeMs = 6_000L)
        advanceUntilIdle()

        coVerify(exactly = 1) { clockRepository.recordStopwatchLap(12_000L, 6_000L) }
    }


    @Test
    fun `editAlarm returns false when repository rejects update`() = runTest {
        val alarm = sampleAlarm()
        val draft = sampleAlarmDraft(label = "Updated")
        coEvery { clockRepository.updateAlarm(alarm.id, draft) } returns null
        val viewModel = SidePanelViewModel(clockRepository)

        val result = viewModel.tryEditAlarm(alarm, draft)

        assertEquals(false, result)
    }

    @Test
    fun `addWorldClock forwards candidate to repository`() = runTest {
        coEvery { clockRepository.addWorldClock("Europe/London", "London") } returns WorldClock(
            id = "clock-1",
            zoneId = "Europe/London",
            displayName = "London",
            sortOrder = 0,
            createdAtMillis = 1_000L,
        )
        val viewModel = SidePanelViewModel(clockRepository)
        var success: Boolean? = null

        viewModel.addWorldClock(WorldClockCandidate("Europe/London", "London", "Europe/London")) {
            success = it
        }
        advanceUntilIdle()

        assertEquals(true, success)
        coVerify(exactly = 1) { clockRepository.addWorldClock("Europe/London", "London") }
    }

    @Test
    fun `moveWorldClock reorders current favorites`() = runTest {
        val london = WorldClock("1", "Europe/London", "London", 0, 1_000L)
        val tokyo = WorldClock("2", "Asia/Tokyo", "Tokyo", 1, 2_000L)
        every { clockRepository.observeWorldClocks() } returns kotlinx.coroutines.flow.flowOf(listOf(london, tokyo))
        coEvery { clockRepository.reorderWorldClocks(listOf("2", "1")) } returns true
        val viewModel = SidePanelViewModel(clockRepository)
        val collectionJob = launch { viewModel.worldClocks.collect {} }
        advanceUntilIdle()
        var success: Boolean? = null

        viewModel.moveWorldClock(tokyo, direction = -1) { success = it }
        advanceUntilIdle()
        collectionJob.cancel()

        assertEquals(true, success)
        coVerify(exactly = 1) { clockRepository.reorderWorldClocks(listOf("2", "1")) }
    }
}

private fun sampleAlarmDraft(label: String?) = com.kernel.ai.core.memory.clock.AlarmDraft(
    label = label,
    hour = 7,
    minute = 0,
    repeatRule = com.kernel.ai.core.memory.clock.AlarmRepeatRule.OneOff(19_000L),
    timeZoneId = java.time.ZoneId.systemDefault().id,
 )


private fun sampleAlarm() = ClockAlarm(
    id = "alarm-1",
    label = "Wake",
    createdAtMillis = 100L,
    enabled = true,
    hour = 7,
    minute = 0,
    repeatRule = com.kernel.ai.core.memory.clock.AlarmRepeatRule.OneOff(19_000L),
    timeZoneId = java.time.ZoneId.systemDefault().id,
    triggerAtMillis = 1_234L,
 )
