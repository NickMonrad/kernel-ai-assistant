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
    fun `getSettings enables speculative decoding when no model settings are saved`() = runTest {
        coEvery { dao.getSettings("gemma_4_e4b") } returns null

        val settings = repository.getSettings("gemma_4_e4b")

        assertTrue(settings.speculativeDecodingEnabled)
        assertEquals(8192, settings.contextWindowSize)
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
