package com.kernel.ai.feature.settings

import com.kernel.ai.core.voice.AndroidNativeRecognitionAvailability
import com.kernel.ai.core.voice.AndroidNativeRecognitionLocaleStatus
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceInputPreferences
import com.kernel.ai.core.voice.VoiceOutputPreferences
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport = mockk()
    private val voiceInputPreferences: VoiceInputPreferences = mockk()
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val selectedInputEngine = MutableStateFlow(VoiceInputEngine.Vosk)
    private val spokenResponsesEnabled = MutableStateFlow(true)

    private lateinit var viewModel: VoiceViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { androidNativeRecognitionSupport.getAvailability() } returns
            AndroidNativeRecognitionAvailability(
                isRecognitionAvailable = true,
                isOnDeviceRecognitionAvailable = true,
                languageTag = "en-NZ",
                languageDisplayName = "English (New Zealand)",
                localeStatus = AndroidNativeRecognitionLocaleStatus.Ready,
            )
        every { voiceInputPreferences.selectedEngine } returns selectedInputEngine
        coEvery { voiceInputPreferences.setSelectedEngine(any()) } just Runs
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenResponsesEnabled
        coEvery { voiceOutputPreferences.setSpokenResponsesEnabled(any()) } just Runs
        viewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `spoken responses default to enabled when preference flow emits true`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.spokenResponsesEnabled)
    }

    @Test
    fun `voice input engine defaults to vosk when preference flow emits vosk`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(VoiceInputEngine.Vosk, viewModel.uiState.value.selectedInputEngine)
    }

    @Test
    fun `android native language summary is exposed from recognizer support`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "English (New Zealand) (en-NZ)",
            viewModel.uiState.value.androidNativeLanguageSummary,
        )
    }

    @Test
    fun `android native availability message is exposed when on-device recognizer is unavailable`() = runTest {
        coEvery { androidNativeRecognitionSupport.getAvailability() } returns
            AndroidNativeRecognitionAvailability(
                isRecognitionAvailable = true,
                isOnDeviceRecognitionAvailable = false,
                languageTag = "en-US",
                languageDisplayName = "English (United States)",
                localeStatus = AndroidNativeRecognitionLocaleStatus.Unknown,
            )

        viewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "On-device Android speech recognition is unavailable for the current setup. Install the required language pack or keep using Vosk for guaranteed local voice input.",
            viewModel.uiState.value.androidNativeAvailabilityMessage,
        )
    }

    @Test
    fun `android native availability message is exposed when locale is unsupported`() = runTest {
        coEvery { androidNativeRecognitionSupport.getAvailability() } returns
            AndroidNativeRecognitionAvailability(
                isRecognitionAvailable = true,
                isOnDeviceRecognitionAvailable = true,
                languageTag = "en-NZ",
                languageDisplayName = "English (New Zealand)",
                localeStatus = AndroidNativeRecognitionLocaleStatus.NotSupported,
            )

        viewModel = VoiceViewModel(
            androidNativeRecognitionSupport,
            voiceInputPreferences,
            voiceOutputPreferences,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "English (New Zealand) is not supported by Android native speech recognition on this device.",
            viewModel.uiState.value.androidNativeAvailabilityMessage,
        )
    }

    @Test
    fun `setVoiceInputEngine updates ui state immediately`() = runTest {
        viewModel.setVoiceInputEngine(VoiceInputEngine.AndroidNative)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoiceInputEngine.AndroidNative, viewModel.uiState.value.selectedInputEngine)
        coVerify { voiceInputPreferences.setSelectedEngine(VoiceInputEngine.AndroidNative) }
    }

    @Test
    fun `setSpokenResponsesEnabled updates ui state immediately`() = runTest {
        viewModel.setSpokenResponsesEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.spokenResponsesEnabled)
        coVerify { voiceOutputPreferences.setSpokenResponsesEnabled(false) }
    }
}
