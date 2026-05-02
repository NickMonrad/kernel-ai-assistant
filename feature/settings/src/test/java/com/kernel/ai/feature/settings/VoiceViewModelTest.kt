package com.kernel.ai.feature.settings

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
    private val voiceInputPreferences: VoiceInputPreferences = mockk()
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val selectedInputEngine = MutableStateFlow(VoiceInputEngine.Vosk)
    private val spokenResponsesEnabled = MutableStateFlow(true)

    private lateinit var viewModel: VoiceViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { voiceInputPreferences.selectedEngine } returns selectedInputEngine
        coEvery { voiceInputPreferences.setSelectedEngine(any()) } just Runs
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenResponsesEnabled
        coEvery { voiceOutputPreferences.setSpokenResponsesEnabled(any()) } just Runs
        viewModel = VoiceViewModel(voiceInputPreferences, voiceOutputPreferences)
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
