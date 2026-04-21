package com.kernel.ai.feature.chat

import android.util.Log
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.slot.SlotSpec
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputEvent
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceSpeakRequest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionsViewModelVoiceTest {

    private val dispatcher = StandardTestDispatcher()

    private val quickIntentRouter: QuickIntentRouter = mockk()
    private val skillRegistry: SkillRegistry = mockk()
    private val quickActionDao: QuickActionDao = mockk()
    private val voiceInputController: VoiceInputController = mockk()
    private val voiceOutputController: VoiceOutputController = mockk()
    private val voiceInputEvents = MutableSharedFlow<VoiceInputEvent>()
    private val voiceOutputEvents = MutableSharedFlow<VoiceOutputEvent>()

    private lateinit var viewModel: ActionsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { quickActionDao.observeAll() } returns flowOf(emptyList<QuickActionEntity>())
        coEvery { quickActionDao.insert(any()) } just Runs
        every { voiceInputController.events } returns voiceInputEvents
        every { voiceInputController.stopListening() } just Runs
        coEvery { voiceOutputController.speak(any()) } returns VoiceOutputResult.Spoken
        every { voiceOutputController.events } returns voiceOutputEvents
        every { voiceOutputController.stop() } just Runs
        viewModel = ActionsViewModel(
            quickIntentRouter = quickIntentRouter,
            skillRegistry = skillRegistry,
            quickActionDao = quickActionDao,
            voiceInputController = voiceInputController,
            voiceOutputController = voiceOutputController,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun `startVoiceCommand surfaces unavailable offline voice input`() = runTest(dispatcher) {
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Unavailable("Offline voice input is not available yet in this build.")

        viewModel.startVoiceCommand()
        advanceUntilIdle()

        assertEquals(
            "Offline voice input is not available yet in this build.",
            viewModel.error.value,
        )
        assertEquals(
            ActionsViewModel.VoiceCaptureState.Idle,
            viewModel.voiceCaptureState.value,
        )
    }

    @Test
    fun `executeAction in voice mode speaks slot prompt for NeedsSlot`() = runTest(dispatcher) {
        every { quickIntentRouter.route("send a message to Laurelle") } returns
            QuickIntentRouter.RouteResult.NeedsSlot(
                intent = QuickIntentRouter.MatchedIntent(
                    intentName = "send_message",
                    params = mapOf("contact" to "Laurelle"),
                ),
                missingSlot = SlotSpec(
                    name = "message",
                    promptTemplate = "What would you like to say to {contact}?",
                ),
            )

        viewModel.executeAction("send a message to Laurelle", InputMode.Voice)
        advanceUntilIdle()

        val pending = viewModel.pendingSlot.value
        assertNotNull(pending)
        assertEquals(
            "What would you like to say to Laurelle?",
            pending?.request?.promptMessage,
        )
        coVerify {
            voiceOutputController.speak(
                match<VoiceSpeakRequest> {
                    it.text == "What would you like to say to Laurelle?"
                }
            )
        }
    }

    @Test
    fun `partial transcript is surfaced while listening`() = runTest(dispatcher) {
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Started

        viewModel.startVoiceCommand()
        voiceInputEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command))
        voiceInputEvents.emit(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "turn on"))
        advanceUntilIdle()

        assertEquals(
            ActionsViewModel.VoiceCaptureState.Listening(
                mode = VoiceCaptureMode.Command,
                transcript = "turn on",
            ),
            viewModel.voiceCaptureState.value,
        )
    }

    @Test
    fun `listening stopped preserves transcript while processing`() = runTest(dispatcher) {
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.Command)
        } returns VoiceInputStartResult.Started

        viewModel.startVoiceCommand()
        voiceInputEvents.emit(VoiceInputEvent.ListeningStarted(VoiceCaptureMode.Command))
        voiceInputEvents.emit(VoiceInputEvent.PartialTranscript(VoiceCaptureMode.Command, "set timer for 5"))
        voiceInputEvents.emit(VoiceInputEvent.ListeningStopped(VoiceCaptureMode.Command))
        advanceUntilIdle()

        assertEquals(
            ActionsViewModel.VoiceCaptureState.Processing(
                mode = VoiceCaptureMode.Command,
                transcript = "set timer for 5",
            ),
            viewModel.voiceCaptureState.value,
        )
    }

    @Test
    fun `voice mode speaks concise error summary`() = runTest(dispatcher) {
        every { quickIntentRouter.route("bad action") } throws IllegalStateException("Flashlight toggle failed because hardware service exploded")

        viewModel.executeAction("bad action", InputMode.Voice)
        advanceUntilIdle()

        coVerify {
            voiceOutputController.speak(
                match<VoiceSpeakRequest> {
                    it.text == "That didn't work. Check the action history for details."
                }
            )
        }
    }

    @Test
    fun `voice command normalizes spoken numbers before routing`() = runTest(dispatcher) {
        every { quickIntentRouter.route("set timer for 5 minutes") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "set timer for 5 minutes")

        viewModel.executeAction("set timer for five minutes", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("set timer for 5 minutes") }
    }

    @Test
    fun `voice command infers add to list when verb is dropped`() = runTest(dispatcher) {
        every { quickIntentRouter.route("add milk to shopping list") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "add milk to shopping list")

        viewModel.executeAction("milk to the shopping list", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("add milk to shopping list") }
    }

    @Test
    fun `voice command corrects sure me nearby mishear`() = runTest(dispatcher) {
        every { quickIntentRouter.route("show me nearby dog parks") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "show me nearby dog parks")

        viewModel.executeAction("sure me nearby dog parks", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("show me nearby dog parks") }
    }

    @Test
    fun `voice command corrects sit a minute time timer mishear`() = runTest(dispatcher) {
        every { quickIntentRouter.route("set a 20 minute timer") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "set a 20 minute timer")

        viewModel.executeAction("sit a 20 minute time", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("set a 20 minute timer") }
    }

    @Test
    fun `voice command corrects cancel the time of timer mishear`() = runTest(dispatcher) {
        every { quickIntentRouter.route("cancel the timer") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "cancel the timer")

        viewModel.executeAction("cancel the time of", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("cancel the timer") }
    }

    @Test
    fun `voice command normalizes spoken alarm time`() = runTest(dispatcher) {
        every { quickIntentRouter.route("set an alarm for 6:30 am") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "set an alarm for 6:30 am")

        viewModel.executeAction("set an alarm for six thirty am", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("set an alarm for 6:30 am") }
    }

    @Test
    fun `voice command normalizes mixed digit and spoken alarm time`() = runTest(dispatcher) {
        every { quickIntentRouter.route("set an alarm for 6:30 am") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "set an alarm for 6:30 am")

        viewModel.executeAction("set an alarm for 6 thirty am", InputMode.Voice)
        advanceUntilIdle()

        verify(atLeast = 1) { quickIntentRouter.route("set an alarm for 6:30 am") }
    }

    @Test
    fun `voice command recovers flattened thirty alarm time`() = runTest(dispatcher) {
        every { quickIntentRouter.route("set an alarm for 7:30 am") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "set an alarm for 7:30 am")

        viewModel.executeAction("set an alarm for 37am", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("set an alarm for 7:30 am") }
    }

    @Test
    fun `voice command normalizes compact alarm time`() = runTest(dispatcher) {
        every { quickIntentRouter.route("set an alarm for 7:30 am") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "set an alarm for 7:30 am")

        viewModel.executeAction("set an alarm for 730am", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("set an alarm for 7:30 am") }
    }
}
