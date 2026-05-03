package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.clock.AlarmRepeatRule
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import io.mockk.coEvery
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
class ScheduledAlarmsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clockRepository = mockk<ClockRepository>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scheduleAlarm returns false when repository rejects exact alarm`() = runTest {
        every { clockRepository.observeUpcomingAlarms() } returns emptyFlow()
        coEvery { clockRepository.createAlarm(any()) } returns null
        val viewModel = ScheduledAlarmsViewModel(clockRepository)

        val result = viewModel.tryScheduleAlarm(1_234L, "Wake")

        assertEquals(false, result)
    }

    @Test
    fun `scheduleAlarm reports failure when repository cannot store alarm`() = runTest {
        every { clockRepository.observeUpcomingAlarms() } returns emptyFlow()
        coEvery { clockRepository.createAlarm(any()) } returns null
        val viewModel = ScheduledAlarmsViewModel(clockRepository)
        var result: AlarmSaveResult? = null

        viewModel.scheduleAlarm(System.currentTimeMillis() + 172_800_000L, "Wake") { result = it }
        advanceUntilIdle()

        assertEquals(AlarmSaveResult.FAILED, result)
    }

    @Test
    fun `editAlarm returns true when repository accepts update`() = runTest {
        val alarm = ClockAlarm(
            id = "alarm-1",
            label = "Wake",
            createdAtMillis = 100L,
            enabled = true,
            hour = 7,
            minute = 0,
            repeatRule = AlarmRepeatRule.OneOff(19_000L),
            timeZoneId = java.time.ZoneId.systemDefault().id,
            triggerAtMillis = 1_234L,
        )
        every { clockRepository.observeUpcomingAlarms() } returns emptyFlow()
        coEvery { clockRepository.updateAlarm(alarm.id, any()) } returns alarm
        val viewModel = ScheduledAlarmsViewModel(clockRepository)

        val result = viewModel.tryEditAlarm(alarm, 2_345L, "Updated")

        assertEquals(true, result)
    }
}