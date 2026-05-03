package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockTimer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scheduleAlarm returns false when repository rejects exact alarm`() = runTest {
        coEvery { clockRepository.scheduleAlarm(any(), any()) } returns null
        val viewModel = SidePanelViewModel(clockRepository)

        val result = viewModel.tryScheduleAlarm(1_234L, "Wake")

        assertEquals(false, result)
    }

    @Test
    fun `scheduleAlarm reports failure when repository cannot store alarm`() = runTest {
        coEvery { clockRepository.scheduleAlarm(any(), any()) } returns null
        val viewModel = SidePanelViewModel(clockRepository)
        var result: AlarmSaveResult? = null

        viewModel.scheduleAlarm(System.currentTimeMillis() + 172_800_000L, "Wake") { result = it }
        advanceUntilIdle()

        assertEquals(AlarmSaveResult.FAILED, result)
    }

    @Test
    fun `scheduleTimer returns true when repository accepts timer`() = runTest {
        coEvery { clockRepository.scheduleTimer(any(), any()) } returns ClockTimer(
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
    fun `editAlarm returns false when repository rejects update`() = runTest {
        val alarm = ClockAlarm(
            id = "alarm-1",
            triggerAtMillis = 1_234L,
            label = "Wake",
            createdAtMillis = 100L,
            enabled = true,
        )
        coEvery { clockRepository.editAlarm(alarm.id, any(), any()) } returns null
        val viewModel = SidePanelViewModel(clockRepository)

        val result = viewModel.tryEditAlarm(alarm, 2_345L, "Updated")

        assertEquals(false, result)
    }
}
