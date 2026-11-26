package com.kernel.ai.feature.chat

import android.util.Log
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import com.kernel.ai.core.skills.intent.PlaceDidYouMeanSuggester
import com.kernel.ai.core.voice.SpeechStopCoordinator
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.VoiceNotice
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputEvent
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceOutputResult
import com.kernel.ai.core.voice.VoiceOutputStreamingSession
import com.kernel.ai.core.voice.VoiceSpeakRequest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** #2079: the "Did you mean <place>?" question on the Actions and Hey Jandal voice path. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionsPlaceDidYouMeanTest {

    private val dispatcher = StandardTestDispatcher()

    private val quickIntentRouter: QuickIntentRouter = mockk()
    private val skillRegistry: SkillRegistry = mockk()
    private val quickActionDao: QuickActionDao = mockk()
    private val voiceInputController: VoiceInputController = mockk()
    private val voiceOutputController: VoiceOutputController = mockk()
    private val speechStopCoordinator = SpeechStopCoordinator(voiceOutputController)
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val startListeningCuePlayer: StartListeningCuePlayer = mockk(relaxed = true)
    private val voiceInputPreferences: com.kernel.ai.core.voice.VoiceInputPreferences = mockk(relaxed = true)
    private val suggester: PlaceDidYouMeanSuggester = mockk()
    private val voiceInputEvents = MutableSharedFlow<VoiceInputEvent>()
    private val voiceInputNotices = MutableSharedFlow<VoiceNotice>(replay = 1)
    private val voiceOutputEvents = MutableSharedFlow<VoiceOutputEvent>()
    private val spokenResponsesEnabled = MutableStateFlow(true)
    private val placeDidYouMeanOn = MutableStateFlow(true)
    private val navigateSkill: Skill = mockk()

    /** Text appended through streaming sessions; non-slot replies such as the drop notice go this way. */
    private val streamedText = mutableListOf<String>()

    private val prompt = "Did you mean Porirua?"
    private val retryPrompt = "Sorry, I didn't catch that. $prompt"
    private val dropped = "Okay, I didn't act on that."

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
        every { voiceInputController.notices } returns voiceInputNotices
        every { voiceInputController.stopListening() } just Runs
        coEvery { voiceInputController.prewarm() } just Runs
        coEvery {
            voiceInputController.startListening(VoiceCaptureMode.SlotReply)
        } returns VoiceInputStartResult.Started(1L)
        coEvery { voiceOutputController.warmUp() } returns VoiceOutputResult.Spoken
        coEvery { voiceOutputController.speak(any()) } returns VoiceOutputResult.Spoken
        every { voiceOutputController.events } returns voiceOutputEvents
        every { voiceOutputController.stop() } just Runs
        coEvery { voiceOutputController.openStreamingSession(any()) } answers {
            object : VoiceOutputStreamingSession {
                override suspend fun append(text: String, isFinal: Boolean): VoiceOutputResult {
                    streamedText += text
                    return VoiceOutputResult.Spoken
                }
            }
        }
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenResponsesEnabled
        every { voiceOutputPreferences.autoSpeak } returns flowOf(true)
        every { voiceOutputPreferences.maxSpokenSentences } returns flowOf(0)
        every { voiceOutputPreferences.placeDidYouMeanEnabled } returns placeDidYouMeanOn

        // Only "Porirwa" is a near miss; everything else is either exact or unrelated.
        every { suggester.suggest(any()) } returns null
        every { suggester.suggest("Porirwa") } returns "Porirua"

        every { quickIntentRouter.route("navigate to Porirwa") } returns navigateIntent("Porirwa")
        every { quickIntentRouter.route("navigate to Porirua") } returns navigateIntent("Porirua")
        every { skillRegistry.get("navigate_to") } returns navigateSkill
        every { navigateSkill.name } returns "navigate_to"
        every { navigateSkill.description } returns "Navigate"
        every { navigateSkill.schema } returns SkillSchema()
        coEvery { navigateSkill.execute(any()) } returns SkillResult.DirectReply(content = "Navigating.")

        viewModel = newViewModel()
    }

    private fun newViewModel() = ActionsViewModel(
        quickIntentRouter = quickIntentRouter,
        skillRegistry = skillRegistry,
        quickActionDao = quickActionDao,
        speechStopCoordinator = speechStopCoordinator,
        voiceInputController = voiceInputController,
        voiceOutputController = voiceOutputController,
        voiceOutputPreferences = voiceOutputPreferences,
        startListeningCuePlayer = startListeningCuePlayer,
        voiceInputPreferences = voiceInputPreferences,
        placeDidYouMeanSuggester = suggester,
    )

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun `voice near miss speaks the question and runs nothing until a yes`() = runTest(dispatcher) {
        viewModel.executeAction("navigate to Porirwa", InputMode.Voice)
        advanceUntilIdle()

        coVerify(exactly = 1) { voiceOutputController.speak(match<VoiceSpeakRequest> { it.text == prompt }) }
        coVerify(exactly = 0) { navigateSkill.execute(any()) }

        finishSpeech(prompt)
        coVerify(exactly = 1) { voiceInputController.startListening(VoiceCaptureMode.SlotReply) }
    }

    @Test
    fun `yes runs the suggested place once`() = runTest(dispatcher) {
        viewModel.executeAction("navigate to Porirwa", InputMode.Voice)
        advanceUntilIdle()
        finishSpeech(prompt)

        viewModel.onSlotReply("Yes")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            navigateSkill.execute(match<SkillCall> { it.arguments["destination"] == "Porirua" })
        }
        coVerify(exactly = 0) {
            navigateSkill.execute(match<SkillCall> { it.arguments["destination"] == "Porirwa" })
        }
    }

    @Test
    fun `no runs the heard place once`() = runTest(dispatcher) {
        viewModel.executeAction("navigate to Porirwa", InputMode.Voice)
        advanceUntilIdle()
        finishSpeech(prompt)

        viewModel.onSlotReply("No")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            navigateSkill.execute(match<SkillCall> { it.arguments["destination"] == "Porirwa" })
        }
        coVerify(exactly = 0) {
            navigateSkill.execute(match<SkillCall> { it.arguments["destination"] == "Porirua" })
        }
    }

    @Test
    fun `two silences re-ask then drop without running anything`() = runTest(dispatcher) {
        viewModel.executeAction("navigate to Porirwa", InputMode.Voice)
        advanceUntilIdle()
        finishSpeech(prompt)

        silentReply()
        finishSpeech(retryPrompt)
        silentReply()
        finishSpeech(retryPrompt)
        coVerify(exactly = 3) { voiceInputController.startListening(VoiceCaptureMode.SlotReply) }
        silentReply()
        advanceUntilIdle()

        assertEquals(1, streamedText.count { it.contains(dropped) })
        coVerify(exactly = 0) { navigateSkill.execute(any()) }
    }

    @Test
    fun `text input never asks the question`() = runTest(dispatcher) {
        viewModel.executeAction("navigate to Porirwa", InputMode.Text)
        advanceUntilIdle()

        coVerify(exactly = 0) { voiceOutputController.speak(match<VoiceSpeakRequest> { it.text == prompt }) }
        coVerify(exactly = 1) {
            navigateSkill.execute(match<SkillCall> { it.arguments["destination"] == "Porirwa" })
        }
    }

    @Test
    fun `setting off never asks the question`() = runTest(dispatcher) {
        placeDidYouMeanOn.value = false
        advanceUntilIdle()

        viewModel.executeAction("navigate to Porirwa", InputMode.Voice)
        advanceUntilIdle()

        coVerify(exactly = 0) { voiceOutputController.speak(match<VoiceSpeakRequest> { it.text == prompt }) }
        coVerify(exactly = 1) {
            navigateSkill.execute(match<SkillCall> { it.arguments["destination"] == "Porirwa" })
        }
    }

    @Test
    fun `spoken responses off never asks the question`() = runTest(dispatcher) {
        spokenResponsesEnabled.value = false
        advanceUntilIdle()

        viewModel.executeAction("navigate to Porirwa", InputMode.Voice)
        advanceUntilIdle()

        coVerify(exactly = 0) { voiceOutputController.speak(match<VoiceSpeakRequest> { it.text == prompt }) }
        coVerify(exactly = 1) {
            navigateSkill.execute(match<SkillCall> { it.arguments["destination"] == "Porirwa" })
        }
    }

    private fun navigateIntent(destination: String) = QuickIntentRouter.RouteResult.RegexMatch(
        QuickIntentRouter.MatchedIntent(
            intentName = "navigate_to",
            params = mapOf("destination" to destination),
        ),
    )

    /** Emulates TTS of [spoken] finishing, then the 350 ms mic re-arm. */
    private suspend fun TestScope.finishSpeech(spoken: String) {
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStarted(spoken))
        runCurrent()
        voiceOutputEvents.emit(VoiceOutputEvent.SpeakingStopped)
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
    }

    private suspend fun TestScope.silentReply() {
        voiceInputEvents.emit(
            VoiceInputEvent.Error(VoiceCaptureMode.SlotReply, "Didn't hear anything.", captureSessionId = 1L),
        )
        runCurrent()
    }
}
