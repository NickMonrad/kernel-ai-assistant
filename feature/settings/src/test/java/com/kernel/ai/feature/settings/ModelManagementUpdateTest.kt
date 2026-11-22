package com.kernel.ai.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.inference.prefs.ModelPreferences
import com.kernel.ai.core.inference.prefs.DownloadNetworkPreferences
import com.kernel.ai.core.model.availability.GatedModelStatus
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import com.kernel.ai.core.voice.SherpaVoicePackDownloadManager
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoiceOutputPreferences
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagementUpdateTest {
    private val downloadManager = mockk<ModelDownloadManager>(relaxed = true)
    private val modelPreferences = mockk<ModelPreferences>(relaxed = true)
    private val authRepository = mockk<HuggingFaceAuthRepository>(relaxed = true)
    private val persona = mockk<JandalPersona>(relaxed = true)
    private val gatedStatusRepository = mockk<GatedModelStatusRepository>(relaxed = true)
    private val voicePacks = mockk<SherpaVoicePackDownloadManager>(relaxed = true)
    private val voiceOutputPreferences = mockk<VoiceOutputPreferences>(relaxed = true)
    private val voiceInputPreferences = mockk<VoiceInputPreferences>(relaxed = true)
    private val downloadNetworkPreferences = mockk<DownloadNetworkPreferences>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val viewModelStore = ViewModelStore()
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.getExternalFilesDir(any()) } returns tempDir.toFile()
        every { context.filesDir } returns tempDir.toFile()
        every { downloadManager.downloadStates } returns MutableStateFlow(emptyMap())
        every { downloadManager.downloadSources } returns MutableStateFlow(emptyMap())
        every { downloadManager.deviceTier } returns HardwareTier.MID_RANGE
        every { authRepository.isAuthenticated } returns MutableStateFlow(false)
        every { authRepository.username } returns MutableStateFlow(null)
        every { modelPreferences.preferredConversationModel } returns flowOf(null)
        every { persona.personaMode } returns MutableStateFlow(PersonaMode.HALF)
        every { gatedStatusRepository.get(any()) } returns flowOf(GatedModelStatus.NONE)
        every { voicePacks.downloadStates } returns MutableStateFlow(emptyMap())
        every { voiceOutputPreferences.selectedEngine } returns flowOf(VoiceOutputEngine.AndroidTts)
        every { downloadManager.waitingForWifi } returns MutableStateFlow(emptySet())
        every { downloadNetworkPreferences.choice } returns flowOf(null)
        every { voiceInputPreferences.selectedEngine } returns flowOf(VoiceInputEngine.Auto)
        every { voiceInputPreferences.asr4allSize } returns flowOf(Asr4AllSize.M)
    }

    @AfterEach
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `model management update force-downloads Arctic from its rolling source`() {
        val arctic = KernelModel.ARCTIC_EMBED_M_V1_5
        assertTrue(arctic.downloadUrl.contains("model-arctic-embed-m-v1.5-current"))
        assertTrue(arctic.downloadUrl.endsWith("/${arctic.fileName}"))

        val viewModel = ModelManagementViewModel(
            modelDownloadManager = downloadManager,
            modelPreferences = modelPreferences,
            authRepository = authRepository,
            jandalPersona = persona,
            gatedModelStatusRepository = gatedStatusRepository,
            context = context,
            sherpaVoicePackDownloadManager = voicePacks,
            voiceOutputPreferences = voiceOutputPreferences,
            voiceInputPreferences = voiceInputPreferences,
            downloadNetworkPreferences = downloadNetworkPreferences,
        )
        viewModelStore.put("model-management", viewModel)

        viewModel.updateModel(arctic)

        verify(exactly = 1) {
            downloadManager.startDownload(arctic, force = true, source = DownloadSource.USER_INITIATED)
        }
    }

    /** Builds the ViewModel and registers it so teardown cancels its viewModelScope. */
    private fun newViewModel(): ModelManagementViewModel = ModelManagementViewModel(
        modelDownloadManager = downloadManager,
        modelPreferences = modelPreferences,
        authRepository = authRepository,
        jandalPersona = persona,
        gatedModelStatusRepository = gatedStatusRepository,
        context = context,
        sherpaVoicePackDownloadManager = voicePacks,
        voiceOutputPreferences = voiceOutputPreferences,
        voiceInputPreferences = voiceInputPreferences,
        downloadNetworkPreferences = downloadNetworkPreferences,
    ).also {
        it.ioDispatcher = Dispatchers.Main
        viewModelStore.put("model-management", it)
    }

    @Test
    fun `deleting a saved E-4B choice that mid-range cannot select resets it to automatic`() {
        val e4b = KernelModel.GEMMA_4_E4B
        every { downloadManager.deviceTier } returns HardwareTier.MID_RANGE
        every { downloadManager.downloadStates } returns MutableStateFlow(
            mapOf(e4b to DownloadState.Downloaded("/models/e4b")),
        )
        every { modelPreferences.preferredConversationModel } returns flowOf(e4b)
        val viewModel = newViewModel()
        val collector = viewModel.uiState.launchIn(CoroutineScope(Dispatchers.Unconfined))
        try {
            viewModel.deleteModel(e4b)
            coVerify(exactly = 1) { modelPreferences.setPreferredModel(null) }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `deleting the saved E-4B choice on flagship, where it answers chat, changes nothing`() {
        val e4b = KernelModel.GEMMA_4_E4B
        every { downloadManager.deviceTier } returns HardwareTier.FLAGSHIP
        every { downloadManager.downloadStates } returns MutableStateFlow(
            mapOf(
                e4b to DownloadState.Downloaded("/models/e4b"),
                KernelModel.GEMMA_4_E2B to DownloadState.Downloaded("/models/e2b"),
            ),
        )
        every { modelPreferences.preferredConversationModel } returns flowOf(e4b)
        val viewModel = newViewModel()
        val collector = viewModel.uiState.launchIn(CoroutineScope(Dispatchers.Unconfined))
        try {
            viewModel.deleteModel(e4b)
            coVerify(exactly = 0) { modelPreferences.setPreferredModel(any()) }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `Downloads switch shows mobile data allowed when the saved choice is mobile data`() {
        every { downloadNetworkPreferences.choice } returns flowOf(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)
        val viewModel = newViewModel()
        val collector = viewModel.uiState.launchIn(CoroutineScope(Dispatchers.Unconfined))
        try {
            assertTrue(viewModel.uiState.value.mobileDataAllowed)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `Downloads switch writes the same Wi-Fi only choice the setup guide writes`() {
        val viewModel = newViewModel()

        viewModel.setMobileDataAllowed(false)

        verify(exactly = 1) {
            downloadManager.setDownloadNetworkChoice(DownloadNetworkChoice.WIFI_ONLY)
        }
    }

    @Test
    fun `Downloads switch writes mobile data when turned on`() {
        val viewModel = newViewModel()

        viewModel.setMobileDataAllowed(true)

        verify(exactly = 1) {
            downloadManager.setDownloadNetworkChoice(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)
        }
    }

    @Test
    fun `models queued on Wi-Fi only are reported as waiting for Wi-Fi`() {
        every { downloadManager.waitingForWifi } returns MutableStateFlow(setOf(KernelModel.GEMMA_4_E4B))
        val viewModel = newViewModel()
        val collector = viewModel.uiState.launchIn(CoroutineScope(Dispatchers.Unconfined))
        try {
            assertEquals(setOf(KernelModel.GEMMA_4_E4B), viewModel.uiState.value.waitingForWifi)
        } finally {
            collector.cancel()
        }
    }
}
