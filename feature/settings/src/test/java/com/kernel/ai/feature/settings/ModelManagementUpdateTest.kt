package com.kernel.ai.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModelStore
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.core.inference.PersonaMode
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.inference.download.DownloadSource
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.inference.prefs.ModelPreferences
import com.kernel.ai.core.model.availability.GatedModelStatus
import com.kernel.ai.core.model.availability.GatedModelStatusRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagementUpdateTest {
    private val downloadManager = mockk<ModelDownloadManager>(relaxed = true)
    private val modelPreferences = mockk<ModelPreferences>(relaxed = true)
    private val authRepository = mockk<HuggingFaceAuthRepository>(relaxed = true)
    private val persona = mockk<JandalPersona>(relaxed = true)
    private val gatedStatusRepository = mockk<GatedModelStatusRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val viewModelStore = ViewModelStore()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.getExternalFilesDir("models") } returns File("models")
        every { downloadManager.downloadStates } returns MutableStateFlow(emptyMap())
        every { downloadManager.downloadSources } returns MutableStateFlow(emptyMap())
        every { downloadManager.deviceTier } returns HardwareTier.MID_RANGE
        every { authRepository.isAuthenticated } returns MutableStateFlow(false)
        every { authRepository.username } returns MutableStateFlow(null)
        every { modelPreferences.preferredConversationModel } returns flowOf(null)
        every { persona.personaMode } returns MutableStateFlow(PersonaMode.HALF)
        every { gatedStatusRepository.get(any()) } returns flowOf(GatedModelStatus.NONE)
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
        )
        viewModelStore.put("model-management", viewModel)

        viewModel.updateModel(arctic)

        verify(exactly = 1) {
            downloadManager.startDownload(arctic, force = true, source = DownloadSource.USER_INITIATED)
        }
    }
}
