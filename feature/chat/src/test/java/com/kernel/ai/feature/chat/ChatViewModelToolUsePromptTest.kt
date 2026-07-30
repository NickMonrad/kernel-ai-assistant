package com.kernel.ai.feature.chat

import android.util.Log
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.inference.GenerationResult
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.entity.ConversationEntity
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.ModelSettingsRepository
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import com.kernel.ai.core.skills.mealplan.MealPlannerCoordinator
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.usecase.EpisodicDistillationUseCase
import com.kernel.ai.core.memory.usecase.VerboseLoggingPreferenceUseCase
import com.kernel.ai.core.skills.KernelAIToolSet
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillExecutor
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.slot.SlotFillerManager
import com.kernel.ai.core.skills.slot.SlotValidationRegistry
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelToolUsePromptTest {

    private val dispatcher = StandardTestDispatcher()
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
    private val kernelAIToolSet: KernelAIToolSet = mockk(relaxed = true)
    private val toolProvider: com.google.ai.edge.litertlm.ToolProvider = mockk(relaxed = true)
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
        every { inferenceEngine.generate(any()) } returns flowOf(GenerationResult.Complete(durationMs = 1L))
        coEvery { inferenceEngine.updateSystemPrompt(any()) } just runs
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
        coEvery { conversationRepository.addMessage(any(), any(), any(), any(), any()) } returnsMany listOf("user-msg", "assistant-msg")
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
        every { skillRegistry.buildNativeDeclarations() } answers {
            "  - run_intent: Perform a native Android device action.\n" +
            "  - save_memory: Saves an important fact or preference.\n" +
            "  - get_system_info: Returns current device info.\n" +
            "  - query_wikipedia: Look up a topic on Wikipedia."
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun createViewModel(): ChatViewModel {
        return ChatViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
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
            kernelAIToolSet = kernelAIToolSet,
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
    }

    @Test
    fun `buildToolUsePrompt contains action priority rule`() {
        val p = createViewModel().buildToolUsePrompt()
        assertTrue(p.contains("Action requests MUST use run_intent"), "Missing action rule\n$p")
    }

    @Test
    fun `buildToolUsePrompt contains calendar scheduling rule`() {
        val p = createViewModel().buildToolUsePrompt()
        assertTrue(p.contains("route to") && p.contains("run_intent(create_calendar_event)"), "Missing calendar rule\n$p")
    }

    @Test
    fun `buildToolUsePrompt says NOT save_memory for calendar`() {
        val p = createViewModel().buildToolUsePrompt()
        assertTrue(p.contains("NOT save_memory"), "Missing save_memory exclusion\n$p")
    }

    @Test
    fun `buildToolUsePrompt distinguishes memory keep from calendar keep`() {
        val p = createViewModel().buildToolUsePrompt()
        assertTrue(p.contains("keep Friday free") && p.contains("RESERVE TIME"), "Missing keep distinction\n$p")
    }

    @Test
    fun `buildToolUsePrompt contains memory vs action distinction`() {
        val p = createViewModel().buildToolUsePrompt()
        assertTrue(p.contains("personal facts") && p.contains("save_memory") && p.contains("run_intent"), "Missing mem/action distinction\n$p")
    }

    @Test
    fun `buildToolUsePrompt says do not use info tools for actions`() {
        val p = createViewModel().buildToolUsePrompt()
        assertTrue(p.contains("get_system_info") && p.contains("query_wikipedia"), "Missing info tool exclusion\n$p")
    }

    @Test
    fun `buildToolUsePrompt addresses load_skill to executable tool path`() {
        val p = createViewModel().buildToolUsePrompt()
        assertTrue(p.contains("load_skill first") || p.contains("load_skill results"), "Missing load_skill guidance\n$p")
    }
}
