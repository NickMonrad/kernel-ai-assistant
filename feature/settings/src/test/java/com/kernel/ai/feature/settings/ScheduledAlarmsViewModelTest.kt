package com.kernel.ai.feature.settings

import android.content.Context
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockPlatformState
import com.kernel.ai.core.memory.clock.ClockRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
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
    private val context = mockk<Context>(relaxed = true)
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
        coEvery { clockRepository.scheduleAlarm(any(), any()) } returns null
        val viewModel = ScheduledAlarmsViewModel(clockRepository, context)

        val result = viewModel.tryScheduleAlarm(1_234L, "Wake")

        assertEquals(false, result)
    }

    @Test
    fun `scheduleAlarm reports clock app fallback when exact alarms are unavailable`() = runTest {
        every { clockRepository.observeUpcomingAlarms() } returns emptyFlow()
        every { clockRepository.getPlatformState() } returns ClockPlatformState(
            canScheduleExactAlarms = false,
            notificationsEnabled = true,
            canUseFullScreenIntent = false,
        )
        coEvery { clockRepository.scheduleAlarm(any(), any()) } returns null
        mockkConstructor(android.content.Intent::class)
        every { anyConstructed<android.content.Intent>().setFlags(any()) } answers { self as android.content.Intent }
        every { anyConstructed<android.content.Intent>().putExtra(any<String>(), any<Int>()) } answers { self as android.content.Intent }
        every { anyConstructed<android.content.Intent>().putExtra(any<String>(), any<String>()) } answers { self as android.content.Intent }
        try {
            val viewModel = ScheduledAlarmsViewModel(clockRepository, context)
            var result: AlarmSaveResult? = null

            viewModel.scheduleAlarm(System.currentTimeMillis() + 172_800_000L, "Wake") { result = it }
            advanceUntilIdle()

            assertEquals(AlarmSaveResult.CLOCK_APP_FALLBACK, result)
            verify(exactly = 1) { context.startActivity(any()) }
        } finally {
            unmockkConstructor(android.content.Intent::class)
        }
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
        every { clockRepository.observeUpcomingAlarms() } returns emptyFlow()
        coEvery { clockRepository.editAlarm(alarm.id, any(), any()) } returns alarm
        val viewModel = ScheduledAlarmsViewModel(clockRepository, context)

        val result = viewModel.tryEditAlarm(alarm, 2_345L, "Updated")

        assertEquals(true, result)
    }
}