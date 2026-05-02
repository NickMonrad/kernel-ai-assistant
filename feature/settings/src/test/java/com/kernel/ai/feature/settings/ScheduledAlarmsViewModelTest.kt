package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScheduledAlarmsViewModelTest {
    private val context = mockk<Context>(relaxed = true)
    private val clockRepository = mockk<ClockRepository>()

    @Test
    fun `scheduleAlarm returns false when repository rejects exact alarm`() = runTest {
        io.mockk.every { clockRepository.observeUpcomingAlarms() } returns emptyFlow()
        coEvery { clockRepository.scheduleAlarm(any(), any()) } returns null
        val viewModel = ScheduledAlarmsViewModel(clockRepository, context)

        val result = viewModel.tryScheduleAlarm(1_234L, "Wake")

        assertEquals(false, result)
    }

    @Test
    fun `editAlarm returns true when repository accepts update`() = runTest {
        val alarm = ClockAlarm(
            id = "alarm-1",
            triggerAtMillis = 1_234L,
            label = "Wake",
            createdAtMillis = 100L,
            enabled = true,
        )
        io.mockk.every { clockRepository.observeUpcomingAlarms() } returns emptyFlow()
        coEvery { clockRepository.editAlarm(alarm.id, any(), any()) } returns alarm
        val viewModel = ScheduledAlarmsViewModel(clockRepository, context)

        val result = viewModel.tryEditAlarm(alarm, 2_345L, "Updated")

        assertEquals(true, result)
    }
}