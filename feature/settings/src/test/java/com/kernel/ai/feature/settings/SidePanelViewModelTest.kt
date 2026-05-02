package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockTimer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SidePanelViewModelTest {
    private val context = mockk<Context>(relaxed = true)
    private val clockRepository = mockk<ClockRepository>()

    @Test
    fun `scheduleAlarm returns false when repository rejects exact alarm`() = runTest {
        every { clockRepository.observeManageableAlarms() } returns emptyFlow()
        every { clockRepository.observeActiveTimers() } returns emptyFlow()
        coEvery { clockRepository.scheduleAlarm(any(), any()) } returns null
        val viewModel = SidePanelViewModel(clockRepository, context)

        val result = viewModel.tryScheduleAlarm(1_234L, "Wake")

        assertEquals(false, result)
    }

    @Test
    fun `scheduleTimer returns true when repository accepts timer`() = runTest {
        every { clockRepository.observeManageableAlarms() } returns emptyFlow()
        every { clockRepository.observeActiveTimers() } returns emptyFlow()
        coEvery { clockRepository.scheduleTimer(any(), any()) } returns ClockTimer(
            id = "timer-1",
            triggerAtMillis = 5_000L,
            label = "Tea",
            createdAtMillis = 1_000L,
            durationMs = 60_000L,
            startedAtMillis = 2_000L,
        )
        val viewModel = SidePanelViewModel(clockRepository, context)

        val result = viewModel.tryScheduleTimer(60_000L, "Tea")

        assertEquals(true, result)
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
        every { clockRepository.observeManageableAlarms() } returns emptyFlow()
        every { clockRepository.observeActiveTimers() } returns emptyFlow()
        coEvery { clockRepository.editAlarm(alarm.id, any(), any()) } returns null
        val viewModel = SidePanelViewModel(clockRepository, context)

        val result = viewModel.tryEditAlarm(alarm, 2_345L, "Updated")

        assertEquals(false, result)
    }
}
