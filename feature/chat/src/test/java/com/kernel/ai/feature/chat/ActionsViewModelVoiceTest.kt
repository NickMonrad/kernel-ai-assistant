package com.kernel.ai.feature.chat

import android.util.Log
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.slot.SlotSpec
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.VoiceOutputController
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        every { voiceInputController.events } returns emptyFlow()
        every { voiceInputController.stopListening() } just Runs
        coEvery { voiceOutputController.speak(any()) } returns VoiceOutputResult.Spoken
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
}
