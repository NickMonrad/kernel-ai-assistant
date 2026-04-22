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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    fun `voice mode auto starts slot reply capture after prompt finishes`() = runTest(dispatcher) {
        every { quickIntentRouter.route("send a text message to my wife") } returns
            QuickIntentRouter.RouteResult.NeedsSlot(
                intent = QuickIntentRouter.MatchedIntent(
                    intentName = "send_sms",
                    params = mapOf("contact" to "my wife"),
                ),
                missingSlot = SlotSpec(
                    name = "message",
                    promptTemplate = "What would you like to say to {contact}?",
                ),
            )
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.SlotReply)
        } returns VoiceInputStartResult.Started

        viewModel.executeAction("send a text message to my wife", InputMode.Voice)
        advanceUntilIdle()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        runCurrent()

        coVerify(exactly = 0) { voiceInputController.startListening(VoiceCaptureMode.SlotReply) }
        advanceTimeBy(349)
        runCurrent()
        coVerify(exactly = 0) { voiceInputController.startListening(VoiceCaptureMode.SlotReply) }
        advanceTimeBy(1)
        runCurrent()

        coVerify { voiceInputController.startListening(VoiceCaptureMode.SlotReply) }
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
    fun `voice command corrects sure i mean map mishear`() = runTest(dispatcher) {
        every { quickIntentRouter.route("show me cafes on the map") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "show me cafes on the map")

        viewModel.executeAction("sure i mean cafes on the map", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("show me cafes on the map") }
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
    fun `voice command corrects start time timer mishear`() = runTest(dispatcher) {
        every { quickIntentRouter.route("start timer for 5 minutes") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "start timer for 5 minutes")

        viewModel.executeAction("start time for 5 minutes", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("start timer for 5 minutes") }
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
    fun `voice command corrects initial call verb mishears`() = runTest(dispatcher) {
        every { quickIntentRouter.route("call susan monrad") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "call susan monrad")
        every { quickIntentRouter.route("call michael sofoclis") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "call michael sofoclis")

        viewModel.executeAction("cold susan monrad", InputMode.Voice)
        viewModel.executeAction("cole michael sofoclis", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("call susan monrad") }
        verify { quickIntentRouter.route("call michael sofoclis") }
    }

    @Test
    fun `voice command corrects wifi dnd system and list mishears`() = runTest(dispatcher) {
        every { quickIntentRouter.route("turn off wifi") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "turn off wifi")
        every { quickIntentRouter.route("toggle wifi") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "toggle wifi")
        every { quickIntentRouter.route("turn on dnd") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "turn on dnd")
        every { quickIntentRouter.route("get system info") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "get system info")
        every { quickIntentRouter.route("create list called to do") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "create list called to do")

        viewModel.executeAction("turn off why fine", InputMode.Voice)
        viewModel.executeAction("toggle why fi", InputMode.Voice)
        viewModel.executeAction("turn on day in day", InputMode.Voice)
        viewModel.executeAction("get system far", InputMode.Voice)
        viewModel.executeAction("create lust called to do", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("turn off wifi") }
        verify { quickIntentRouter.route("toggle wifi") }
        verify { quickIntentRouter.route("turn on dnd") }
        verify { quickIntentRouter.route("get system info") }
        verify { quickIntentRouter.route("create list called to do") }
    }

    @Test
    fun `voice command corrects media and list item mishears`() = runTest(dispatcher) {
        every { quickIntentRouter.route("play youtube music") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "play youtube music")
        every { quickIntentRouter.route("play plexamp") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "play plexamp")
        every { quickIntentRouter.route("open youtube music") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "open youtube music")
        every { quickIntentRouter.route("open plexamp") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "open plexamp")
        every { quickIntentRouter.route("add panadol to the shopping list") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "add panadol to the shopping list")
        every { quickIntentRouter.route("next track") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "next track")
        every { quickIntentRouter.route("what's the date today") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "what's the date today")

        viewModel.executeAction("play huge your music", InputMode.Voice)
        viewModel.executeAction("play play exam", InputMode.Voice)
        viewModel.executeAction("open you tube music", InputMode.Voice)
        viewModel.executeAction("open plagues amp", InputMode.Voice)
        viewModel.executeAction("add and pen adult to the shopping list", InputMode.Voice)
        viewModel.executeAction("next drink", InputMode.Voice)
        viewModel.executeAction("what's the day today", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("play youtube music") }
        verify { quickIntentRouter.route("play plexamp") }
        verify { quickIntentRouter.route("open youtube music") }
        verify { quickIntentRouter.route("open plexamp") }
        verify { quickIntentRouter.route("add panadol to the shopping list") }
        verify { quickIntentRouter.route("next track") }
        verify { quickIntentRouter.route("what's the date today") }
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
    fun `voice command normalizes malformed alarm thirty phrases`() = runTest(dispatcher) {
        every { quickIntentRouter.route("set an alarm for 15:30") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "set an alarm for 15:30")
        every { quickIntentRouter.route("set an alarm for 17:30") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "set an alarm for 17:30")
        every { quickIntentRouter.route("set an alarm for 19:30") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "set an alarm for 19:30")
        every { quickIntentRouter.route("set an alarm for 2:30 called dentist") } returns
            QuickIntentRouter.RouteResult.FallThrough(input = "set an alarm for 2:30 called dentist")

        viewModel.executeAction("set an alarm for 15 dirty", InputMode.Voice)
        viewModel.executeAction("sit on the lam for 47", InputMode.Voice)
        viewModel.executeAction("set an alarm for 49", InputMode.Voice)
        viewModel.executeAction("set an alarm for to 30 called dentist", InputMode.Voice)
        advanceUntilIdle()

        verify { quickIntentRouter.route("set an alarm for 15:30") }
        verify { quickIntentRouter.route("set an alarm for 17:30") }
        verify { quickIntentRouter.route("set an alarm for 19:30") }
        verify { quickIntentRouter.route("set an alarm for 2:30 called dentist") }
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
