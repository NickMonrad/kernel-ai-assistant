package com.kernel.ai.feature.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import com.google.ai.edge.litertlm.ToolProvider
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.usecase.EpisodicDistillationUseCase
import com.kernel.ai.core.memory.usecase.VerboseLoggingPreferenceUseCase
import com.kernel.ai.core.skills.KernelAIToolSet
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.skills.slot.SlotValidationRegistry
import com.kernel.ai.core.skills.slot.SlotValidationResult
import com.kernel.ai.core.skills.mealplan.MealPlannerCoordinator
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.memory.prefs.ChatPreferences
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * State-machine tests for ChatViewModel's generation retry behaviour with a real
 * [KernelAIToolSet]. Each scenario simulates LiteRT-LM calling tools during generation
 * by invoking the real tool set's methods inside the mocked generation flow.
 *
 * Scenarios A–J from the PR #1427 remediation specification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelRetryStateMachineTest {

    private val dispatcher = StandardTestDispatcher()

    // Mocks for ChatViewModel dependencies (KernelAIToolSet is real)
    private val inferenceEngine: InferenceEngine = mockk(relaxed = true)
    private val downloadManager: ModelDownloadManager = mockk(relaxed = true)
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val ragRepository: RagRepository = mockk(relaxed = true)
    private val userProfileRepository: UserProfileRepository = mockk(relaxed = true)
    private val memoryRepository: MemoryRepository = mockk(relaxed = true)
    private val mealPlanSessionRepository: MealPlanSessionRepository = mockk(relaxed = true)
    private val episodicDistillationUseCase: EpisodicDistillationUseCase = mockk(relaxed = true)
    private val modelSettingsRepository: ModelSettingsRepository = mockk(relaxed = true)
    private val mealPlannerCoordinator: MealPlannerCoordinator = mockk(relaxed = true)
    private val skillRegistry: SkillRegistry = mockk(relaxed = true)
    private val skillExecutor: SkillExecutor = mockk(relaxed = true)
    private val quickIntentRouter: QuickIntentRouter = mockk(relaxed = true)
    private val slotFillerManager: SlotFillerManager = mockk(relaxed = true)
    private val slotValidationRegistry: SlotValidationRegistry = mockk(relaxed = true)
    private val toolProvider: ToolProvider = mockk(relaxed = true)
    private val embeddingEngine: EmbeddingEngine = mockk(relaxed = true)
    private val voiceInputController: VoiceInputController = mockk(relaxed = true)
    private val voiceOutputController: VoiceOutputController = mockk(relaxed = true)
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk(relaxed = true)
    private val jandalPersona: JandalPersona = mockk(relaxed = true)
    private val nzTruthSeedingService: NzTruthSeedingService = mockk(relaxed = true)
    private val verboseLoggingPreferenceUseCase: VerboseLoggingPreferenceUseCase = mockk(relaxed = true)
    private val gatedModelStatusRepository: GatedModelStatusRepository = mockk(relaxed = true)
    private val startListeningCuePlayer: StartListeningCuePlayer = mockk(relaxed = true)
    private val authRepository: HuggingFaceAuthRepository = mockk(relaxed = true)
    private val chatPreferences: ChatPreferences = mockk(relaxed = true)
    private val intentRecoveryOrchestrator: IntentRecoveryOrchestrator = mockk(relaxed = true)

    // Real tool set backed by the mock SkillRegistry
    private val realToolSet = KernelAIToolSet(object : Lazy<SkillRegistry> {
        override fun get(): SkillRegistry = skillRegistry
    })

    // Convenience — the string captured for addMessage verification
    private val savedContents = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        every { inferenceEngine.isGenerating } returns MutableStateFlow(false)
        every { inferenceEngine.activeBackend } returns MutableStateFlow<BackendType?>(null)
        every { inferenceEngine.resolvedMaxTokens } returns MutableStateFlow(0)
        every { inferenceEngine.evictionEvents } returns emptyFlow()
        every { slotValidationRegistry.validateParams(any(), any()) } returns null
        every { slotValidationRegistry.validate(any(), any(), any()) } returns SlotValidationResult.valid()
        coEvery { inferenceEngine.resetConversation() } just runs

        every { downloadManager.downloadStates } returns MutableStateFlow<Map<KernelModel, DownloadState>>(emptyMap())
        every { downloadManager.downloadSources } returns MutableStateFlow(emptyMap())
        every { downloadManager.areRequiredModelsDownloaded() } returns false
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP

        coEvery { conversationRepository.createConversation() } returns "conv-new"
        coEvery { conversationRepository.getConversation(any()) } answers {
            val id = firstArg<String>()
            ConversationEntity(id = id, title = null, createdAt = 1L, updatedAt = 1L)
        }
        coEvery { conversationRepository.getMessagesOnce(any()) } returns emptyList()
        every { conversationRepository.observeConversationById(any()) } answers {
            val id = firstArg<String>()
            flowOf(ConversationEntity(id = id, title = null, createdAt = 1L, updatedAt = 1L))
        }

        // wire up addMessage to capture saved contents
        coEvery { conversationRepository.addMessage(any(), eq("assistant"), capture(savedContents), any(), any()) } returnsMany
            listOf("assistant-msg-1", "assistant-msg-2", "assistant-msg-3")

        every { authRepository.isAuthenticated } returns MutableStateFlow(false)
        every { chatPreferences.fontSize } returns flowOf(1)
        every { chatPreferences.bubbleTheme } returns flowOf("system")
        every { chatPreferences.userFontColor } returns flowOf(null)
        every { chatPreferences.assistantFontColor } returns flowOf(null)
        every { chatPreferences.wallpaperType } returns flowOf("none")
        every { chatPreferences.wallpaperColor } returns flowOf(null)
        every { chatPreferences.wallpaperImageUri } returns flowOf(null)
        every { chatPreferences.copyToolCalls } returns flowOf(false)
        every { chatPreferences.copyThinking } returns flowOf(false)
        every { jandalPersona.personaMode } returns MutableStateFlow(PersonaMode.FULL)
        every { jandalPersona.currentPersonaMode } returns PersonaMode.FULL
        every { voiceOutputPreferences.spokenResponsesEnabled } returns MutableStateFlow(false)
        every { voiceOutputPreferences.autoSpeak } returns MutableStateFlow(false)
        every { voiceOutputPreferences.maxSpokenSentences } returns flowOf(0)
        every { nzTruthSeedingService.isSeeding } returns MutableStateFlow(false)
        every { nzTruthSeedingService.seedIfNeeded() } just runs
        coEvery { verboseLoggingPreferenceUseCase.loadAndApplyVerboseLoggingPreference() } just runs

        savedContents.clear()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Register [load_skill] in the mock [SkillRegistry]. */
    private fun setupLoadSkill(result: SkillResult = SkillResult.Success("instructions")) {
        val skill = mockk<Skill>()
        every { skill.name } returns "load_skill"
        every { skill.description } returns "instructions"
        coEvery { skill.execute(any()) } returns result
        every { skillRegistry.get("load_skill") } returns skill
    }

    /** Register [run_intent] in the mock [SkillRegistry]. */
    private fun setupRunIntent(result: SkillResult = SkillResult.DirectReply("Alarm set")) {
        val skill = mockk<Skill>()
        every { skill.name } returns "run_intent"
        coEvery { skill.execute(any()) } returns result
        every { skillRegistry.get("run_intent") } returns skill
    }

    /**
     * Build a generation flow that first runs [chain] (invoking real tool methods)
     * then emits the given tokens and a Complete event.
     */
    private fun toolChainFlow(
        tokens: List<String> = emptyList(),
        chain: suspend () -> Unit = {},
    ): Flow<GenerationResult> = flow {
        chain()
        tokens.forEach { emit(GenerationResult.Token(it)) }
        emit(GenerationResult.Complete(durationMs = 0))
    }

    private fun createViewModel(): ChatViewModel = ChatViewModel(
        savedStateHandle = SavedStateHandle(),
        chatPreferences = chatPreferences,
        authRepository = authRepository,
        inferenceEngine = inferenceEngine,
        downloadManager = downloadManager,
        conversationRepository = conversationRepository,
        ragRepository = ragRepository,
        userProfileRepository = userProfileRepository,
        memoryRepository = memoryRepository,
        episodicDistillationUseCase = episodicDistillationUseCase,
        modelSettingsRepository = modelSettingsRepository,
        skillRegistry = skillRegistry,
        skillExecutor = skillExecutor,
        quickIntentRouter = quickIntentRouter,
        intentRecoveryOrchestrator = intentRecoveryOrchestrator,
        intentContractRegistry = IntentContractRegistry(),
        slotFillerManager = slotFillerManager,
        slotValidationRegistry = slotValidationRegistry,
        kernelAIToolSet = realToolSet,
        toolProvider = toolProvider,
        embeddingEngine = embeddingEngine,
        voiceInputController = voiceInputController,
        voiceOutputController = voiceOutputController,
        voiceOutputPreferences = voiceOutputPreferences,
        jandalPersona = jandalPersona,
        nzTruthSeedingService = nzTruthSeedingService,
        verboseLoggingPreferenceUseCase = verboseLoggingPreferenceUseCase,
        gatedModelStatusRepository = gatedModelStatusRepository,
        startListeningCuePlayer = startListeningCuePlayer,
        mealPlanSessionRepository = mealPlanSessionRepository,
        mealPlannerCoordinator = mealPlannerCoordinator,
    )

    // -----------------------------------------------------------------------
    // Scenario A — Same-attempt successful chain
    // -----------------------------------------------------------------------

    @Test
    fun `A same-attempt chain load_skill then run_intent with final text`() = runTest(dispatcher) {
        setupLoadSkill()
        setupRunIntent()

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returns toolChainFlow(
            tokens = listOf("Alarm set for 7 AM"),
            chain = {
                realToolSet.loadSkill("run_intent")
                realToolSet.runIntent("set_alarm", """{"hour":"7"}""")
            },
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Exactly one generation attempt
        coVerify(exactly = 1) { inferenceEngine.generate(any()) }

        // Visible tool is run_intent, not load_skill
        assertEquals("run_intent", realToolSet.terminalToolName())
        // load_skill is in the turn sequence but not as terminal
        assertEquals("load_skill>run_intent", realToolSet.turnToolSequence())
        assertEquals("run_intent", realToolSet.terminalToolNameOrDefault())
        assertTrue(realToolSet.terminalToolSucceeded())

        // For a DirectReply the tool's result text is used, not model-generated tokens
        assertEquals("Alarm set", savedContents.last())
    }

    // -----------------------------------------------------------------------
    // Scenario B — False confirmation followed by successful continuation
    // -----------------------------------------------------------------------

    @Test
    fun `B false confirmation after load_skill triggers continuation then run_intent succeeds`() = runTest(dispatcher) {
        setupLoadSkill()
        setupRunIntent()

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returnsMany listOf(
            toolChainFlow(
                tokens = listOf("The alarm has been set."),
                chain = { realToolSet.loadSkill("run_intent") },
            ),
            toolChainFlow(
                tokens = listOf("Alarm set for 7 AM"),
                chain = { realToolSet.runIntent("set_alarm", """{"hour":"7"}""") },
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Two generation attempts
        coVerify(exactly = 2) { inferenceEngine.generate(any()) }

        // First confirmation is NOT persisted — only the final response
        assertEquals("Alarm set", savedContents.last())

        // Final visible tool is run_intent
        assertEquals("run_intent", realToolSet.terminalToolName())
        assertTrue(realToolSet.terminalToolSucceeded())
        // Turn sequence includes both attempts
        assertEquals("load_skill>run_intent", realToolSet.turnToolSequence())
    }

    // -----------------------------------------------------------------------
    // Scenario C — Blank completion followed by successful continuation
    // -----------------------------------------------------------------------

    @Test
    fun `C blank after load_skill triggers targeted continuation then run_intent succeeds`() = runTest(dispatcher) {
        setupLoadSkill()
        setupRunIntent()

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returnsMany listOf(
            toolChainFlow(
                tokens = emptyList(), // blank
                chain = { realToolSet.loadSkill("run_intent") },
            ),
            toolChainFlow(
                tokens = listOf("Alarm set for 7 AM"),
                chain = { realToolSet.runIntent("set_alarm", """{"hour":"7"}""") },
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Two attempts — no generic blank retry third attempt
        coVerify(exactly = 2) { inferenceEngine.generate(any()) }

        // Incomplete-chain continuation was used — the generic blank guard is NOT what fired
        assertEquals("Alarm set", savedContents.last())
        assertEquals("run_intent", realToolSet.terminalToolName())
    }

    // -----------------------------------------------------------------------
    // Scenario D — Failed continuation with normal text
    // -----------------------------------------------------------------------

    @Test
    fun `D failed continuation after load_skill with normal text shows honest failure`() = runTest(dispatcher) {
        setupLoadSkill()

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returnsMany listOf(
            toolChainFlow(
                tokens = listOf("I have the instructions ready."),
                chain = { realToolSet.loadSkill("run_intent") },
            ),
            toolChainFlow(
                tokens = listOf("Done."),
                chain = {}, // no executable tool
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Exactly two attempts
        coVerify(exactly = 2) { inferenceEngine.generate(any()) }

        // Honest failure — "Done." is NOT persisted
        val lastContent = savedContents.last()
        assertTrue(lastContent.contains("could not complete that action") ||
            lastContent.contains("wasn't able to complete that action"))

        // No load_skill chip — terminalToolName is null
        assertNull(realToolSet.terminalToolName())
    }

    // -----------------------------------------------------------------------
    // Scenario E — Failed continuation with blank result
    // -----------------------------------------------------------------------

    @Test
    fun `E failed continuation after load_skill with blank shows honest failure`() = runTest(dispatcher) {
        setupLoadSkill()

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returnsMany listOf(
            toolChainFlow(
                tokens = listOf("I have the instructions ready."),
                chain = { realToolSet.loadSkill("run_intent") },
            ),
            toolChainFlow(
                tokens = emptyList(), // blank
                chain = {}, // no tool
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Exactly two attempts — no generic blank third attempt
        coVerify(exactly = 2) { inferenceEngine.generate(any()) }

        val lastContent = savedContents.last()
        assertTrue(lastContent.contains("wasn't able to complete that action"))
        assertNull(realToolSet.terminalToolName())
    }

    // -----------------------------------------------------------------------
    // Scenario F — Repeated load_skill
    // -----------------------------------------------------------------------

    @Test
    fun `F repeated load_skill without executable tool shows honest failure`() = runTest(dispatcher) {
        setupLoadSkill()

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returnsMany listOf(
            toolChainFlow(
                tokens = listOf("Let me look that up."),
                chain = { realToolSet.loadSkill("run_intent") },
            ),
            toolChainFlow(
                tokens = listOf("I have the instructions."),
                chain = { realToolSet.loadSkill("run_intent") },
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Exactly two attempts
        coVerify(exactly = 2) { inferenceEngine.generate(any()) }

        // Turn sequence is load_skill>load_skill
        assertEquals("load_skill>load_skill", realToolSet.turnToolSequence())

        // No terminal tool
        assertNull(realToolSet.terminalToolName())

        // Honest failure
        val lastContent = savedContents.last()
        assertTrue(lastContent.contains("wasn't able to complete that action"))
    }

    // -----------------------------------------------------------------------
    // Scenario G — Stale attempt state
    // -----------------------------------------------------------------------

    @Test
    fun `G stale attempt state does not make retry appear tool-backed`() = runTest(dispatcher) {
        setupLoadSkill()

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returnsMany listOf(
            toolChainFlow(
                tokens = listOf("Let me check the instructions."),
                chain = { realToolSet.loadSkill("run_intent") },
            ),
            toolChainFlow(
                tokens = listOf("I did the thing."),
                chain = {}, // no tool
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        // First attempt's load_skill must NOT make attempt 2 appear tool-backed.
        // terminalToolName is null because no executable tool was ever called.
        assertNull(realToolSet.terminalToolName())

        val lastContent = savedContents.last()
        assertTrue(lastContent.contains("wasn't able to complete that action"))
    }

    // -----------------------------------------------------------------------
    // Scenario H — Failed executable tool preserves failure
    // -----------------------------------------------------------------------

    @Test
    fun `H failed executable tool surface failure instead of model confirmation`() = runTest(dispatcher) {
        setupLoadSkill()
        setupRunIntent(result = SkillResult.Failure("run_intent", "Permission denied"))

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returns toolChainFlow(
            tokens = listOf("The alarm has been set."),
            chain = {
                realToolSet.loadSkill("run_intent")
                realToolSet.runIntent("set_alarm", """{"hour":"7"}""")
            },
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        // No incomplete-chain retry — failed executable tool is still terminal
        coVerify(exactly = 1) { inferenceEngine.generate(any()) }

        // Terminal tool is run_intent
        assertEquals("run_intent", realToolSet.terminalToolName())
        assertFalse(realToolSet.terminalToolSucceeded())
        assertTrue(realToolSet.terminalToolFailed())

        // Persisted content must contain the failure, NOT "alarm has been set"
        val lastContent = savedContents.last()
        assertTrue(lastContent.contains("Permission denied"))
        assertFalse(lastContent.contains("alarm has been set"))
    }

    // -----------------------------------------------------------------------
    // Scenario I — Failed load_skill
    // -----------------------------------------------------------------------

    @Test
    fun `I failed load_skill shows honest failure without continuation prompt`() = runTest(dispatcher) {
        setupLoadSkill(result = SkillResult.Failure("load_skill", "Unknown skill"))

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returns toolChainFlow(
            tokens = listOf("Loading instructions..."),
            chain = { realToolSet.loadSkill("run_intent") },
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        // No targeted continuation because load_skill failed
        coVerify(exactly = 1) { inferenceEngine.generate(any()) }

        // Honest failure
        val lastContent = savedContents.last()
        assertTrue(lastContent.contains("could not complete that action") ||
            lastContent.contains("wasn't able to complete that action"))

        // No load_skill shown as tool chip
        assertNull(realToolSet.terminalToolName())
    }

    // -----------------------------------------------------------------------
    // Scenario J — Existing behaviour preserved
    // -----------------------------------------------------------------------

    @Test
    fun `J1 direct run_intent success`() = runTest(dispatcher) {
        setupRunIntent()

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returns toolChainFlow(
            tokens = listOf("Alarm set"),
            chain = { realToolSet.runIntent("set_alarm", """{"hour":"7"}""") },
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        // One attempt
        coVerify(exactly = 1) { inferenceEngine.generate(any()) }
        assertEquals("run_intent", realToolSet.terminalToolName())
        assertTrue(realToolSet.terminalToolSucceeded())
    }

    @Test
    fun `J2 direct run_intent failure`() = runTest(dispatcher) {
        setupRunIntent(result = SkillResult.Failure("run_intent", "Not available"))

        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "set an alarm for 7 AM",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        coEvery { inferenceEngine.generate(any()) } returns toolChainFlow(
            tokens = listOf("Setting alarm..."),
            chain = { realToolSet.runIntent("set_alarm", """{"hour":"7"}""") },
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("set an alarm for 7 AM")
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 1) { inferenceEngine.generate(any()) }
        assertEquals("run_intent", realToolSet.terminalToolName())
        assertFalse(realToolSet.terminalToolSucceeded())
        // Failure result surfaced
        assertTrue(savedContents.last().contains("Not available"))
    }

    @Test
    fun `J3 ordinary non-tool response`() = runTest(dispatcher) {
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "what is the weather",
        )

        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"
        coEvery { conversationRepository.addMessage(any(), eq("assistant"), capture(savedContents), any(), any()) } returns
            "assistant-msg-id"

        coEvery { inferenceEngine.generate(any()) } returns flowOf(
            GenerationResult.Token("I can help with that."),
            GenerationResult.Complete(durationMs = 0),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("what is the weather")
        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 1) { inferenceEngine.generate(any()) }
        assertEquals("I can help with that.", savedContents.last())
        assertNull(realToolSet.terminalToolName())
    }

    @Test
    fun `J4 ordinary blank response retry`() = runTest(dispatcher) {
        every { quickIntentRouter.route(any()) } returns QuickIntentRouter.RouteResult.FallThrough(
            input = "hello",
        )
        coEvery { conversationRepository.addMessage(any(), eq("user"), any(), any(), any()) } returns "user-msg-id"

        // Override addMessage capture for this test: two assistant messages
        coEvery { conversationRepository.addMessage(any(), eq("assistant"), capture(savedContents), any(), any()) } returnsMany
            listOf("assistant-1", "assistant-2")

        coEvery { inferenceEngine.generate(any()) } returnsMany listOf(
            flowOf(GenerationResult.Complete(durationMs = 0)),      // blank
            flowOf(
                GenerationResult.Token("Hello! How can I help?"),
                GenerationResult.Complete(durationMs = 0),
            ),
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChanged("hello")
        viewModel.sendMessage()
        advanceUntilIdle()

        // Two attempts — generic blank guard
        coVerify(exactly = 2) { inferenceEngine.generate(any()) }
        assertTrue(savedContents.any { it.contains("Hello! How can I help?") })
    }
}
