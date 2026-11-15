package com.kernel.ai.core.voice

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechStopCoordinatorTest {
    private val events = MutableSharedFlow<VoiceOutputEvent>(extraBufferCapacity = 8)
    private val controller: VoiceOutputController = mockk()
    private lateinit var coordinator: SpeechStopCoordinator

    @BeforeEach
    fun setUp() {
        every { controller.events } returns events
        every { controller.stop() } just runs
        coordinator = SpeechStopCoordinator(controller)
    }

    @Test
    fun `requestStop while idle is a no-op so volume keys fall through`() {
        assertFalse(coordinator.requestStop())

        verify(exactly = 0) { controller.stop() }
        assertFalse(coordinator.isSpeaking.value)
    }

    @Test
    fun `speaking started makes the session active and requestStop stops speech once`() {
        coordinator.onEvent(VoiceOutputEvent.SpeakingStarted("long reply"))
        assertTrue(coordinator.isSpeaking.value)

        assertTrue(coordinator.requestStop())

        verify(exactly = 1) { controller.stop() }
        assertFalse(coordinator.isSpeaking.value)
    }

    @Test
    fun `second requestStop after a stop does not call the controller again`() {
        coordinator.onEvent(VoiceOutputEvent.SpeakingStarted("reply"))
        coordinator.requestStop()

        assertFalse(coordinator.requestStop())

        verify(exactly = 1) { controller.stop() }
    }

    @Test
    fun `speaking stopped from the engine clears the session`() {
        coordinator.onEvent(VoiceOutputEvent.SpeakingStarted("reply"))
        coordinator.onEvent(VoiceOutputEvent.SpeakingStopped)

        assertFalse(coordinator.isSpeaking.value)
        assertFalse(coordinator.requestStop())
        verify(exactly = 0) { controller.stop() }
    }

    @Test
    fun `observe mirrors controller events into isSpeaking`() = runTest(StandardTestDispatcher()) {
        val job = coordinator.observe(this)
        advanceUntilIdle()

        events.emit(VoiceOutputEvent.SpeakingStarted("reply"))
        advanceUntilIdle()
        assertTrue(coordinator.isSpeaking.value)

        events.emit(VoiceOutputEvent.SpeakingStopped)
        advanceUntilIdle()
        assertFalse(coordinator.isSpeaking.value)

        job.cancel()
    }

    @Test
    fun `requestStop with no screen collecting stops the controller directly`() {
        coordinator.onEvent(VoiceOutputEvent.SpeakingStarted("reply"))

        assertTrue(coordinator.requestStop())

        verify(exactly = 1) { controller.stop() }
    }

    @Test
    fun `requestStop with a screen collecting emits one stop request and leaves stopping to the screen`() = runTest(StandardTestDispatcher()) {
        var received = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.stopRequests.collect { received++ } }
        advanceUntilIdle()
        coordinator.onEvent(VoiceOutputEvent.SpeakingStarted("reply"))

        assertTrue(coordinator.requestStop())
        advanceUntilIdle()

        assertEquals(1, received)
        verify(exactly = 0) { controller.stop() }
    }
}
