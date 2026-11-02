package com.kernel.ai.core.memory

import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.hardware.HardwareProfile
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.memory.dao.ModelSettingsDao
import com.kernel.ai.core.memory.entity.ModelSettingsEntity
import com.kernel.ai.core.memory.repository.ModelSettingsRepositoryImpl
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModelSettingsRepositoryImplTest {
    private val dao = mockk<ModelSettingsDao>()
    private val hardwareProfileDetector = mockk<HardwareProfileDetector>()
    private val flagshipProfile = HardwareProfile(
        tier = HardwareTier.FLAGSHIP,
        totalRamBytes = 12L * 1024 * 1024 * 1024,
        socManufacturer = "Qualcomm",
        socModel = "SM8550",
        recommendedBackend = BackendType.GPU,
        recommendedMaxTokens = 8192,
    )
    private val midRangeProfile = flagshipProfile.copy(
        tier = HardwareTier.MID_RANGE,
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        recommendedMaxTokens = 3072,
    )

    private lateinit var repository: ModelSettingsRepositoryImpl

    @BeforeEach
    fun setUp() {
        every { hardwareProfileDetector.profile } returns flagshipProfile
        repository = ModelSettingsRepositoryImpl(dao, hardwareProfileDetector)
    }

    @Test
    fun `ModelSettingsEntity enables speculative decoding by default`() {
        val settings = ModelSettingsEntity(
            modelId = "gemma_4_e4b",
            contextWindowSize = 8192,
            temperature = 1.0f,
            topP = 0.95f,
        )

        assertTrue(settings.speculativeDecodingEnabled)
    }

    @Test
    fun `getSettings keeps thinking enabled by default on flagship`() = runTest {
        coEvery { dao.getSettings("gemma_4_e4b") } returns null

        val settings = repository.getSettings("gemma_4_e4b")

        assertTrue(settings.speculativeDecodingEnabled)
        assertTrue(settings.showThinkingProcess)
        assertEquals(8192, settings.contextWindowSize)
        coVerify(exactly = 0) { dao.upsertSettings(any()) }
    }

    @Test
    fun `getSettings disables thinking by default on mid range`() = runTest {
        every { hardwareProfileDetector.profile } returns midRangeProfile
        coEvery { dao.getSettings("gemma_4_e2b") } returns null

        val settings = repository.getSettings("gemma_4_e2b")

        assertFalse(settings.showThinkingProcess)
        assertEquals(3072, settings.contextWindowSize)
        coVerify(exactly = 0) { dao.upsertSettings(any()) }
    }

    @Test
    fun `defaultSettings returns tier-aware thinking defaults without touching saved settings`() {
        for ((profile, expectedThinking) in listOf(midRangeProfile to false, flagshipProfile to true)) {
            every { hardwareProfileDetector.profile } returns profile

            val defaults = repository.defaultSettings("gemma_4")

            assertEquals(expectedThinking, defaults.showThinkingProcess)
            assertEquals(profile.recommendedMaxTokens, defaults.contextWindowSize)
        }

        coVerify(exactly = 0) { dao.getSettings(any()) }
        coVerify(exactly = 0) { dao.upsertSettings(any()) }
    }

    @Test
    fun `getSettings preserves saved thinking choice regardless of hardware tier`() = runTest {
        for (profile in listOf(midRangeProfile, flagshipProfile)) {
            every { hardwareProfileDetector.profile } returns profile
            for (thinkingEnabled in listOf(false, true)) {
                val modelId = "${profile.tier}_$thinkingEnabled"
                val saved = ModelSettingsEntity(
                    modelId = modelId,
                    contextWindowSize = 4096,
                    temperature = 1.0f,
                    topP = 0.95f,
                    showThinkingProcess = thinkingEnabled,
                )
                coEvery { dao.getSettings(modelId) } returns saved

                assertEquals(saved, repository.getSettings(modelId))
            }
        }
        coVerify(exactly = 0) { dao.upsertSettings(any()) }
    }

    @Test
    fun `resetToDefaults persists speculative decoding as enabled`() = runTest {
        val persisted = slot<ModelSettingsEntity>()
        coEvery { dao.upsertSettings(capture(persisted)) } just Runs

        val settings = repository.resetToDefaults("gemma_4_e4b")

        assertTrue(settings.speculativeDecodingEnabled)
        assertEquals(settings, persisted.captured)
        coVerify(exactly = 1) { dao.upsertSettings(settings) }
    }
}
