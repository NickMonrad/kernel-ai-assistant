package com.kernel.ai.core.memory.repository

import com.kernel.ai.core.memory.dao.UserProfileDao
import com.kernel.ai.core.memory.entity.UserProfileEntity
import com.kernel.ai.core.memory.profile.UserProfileExtractionUseCase
import com.kernel.ai.core.memory.profile.UserProfileYaml
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests that [UserProfileRepository.save] correctly preserves facts
 * from the LLM extraction path.
 */
class UserProfileRepositoryTest {

    private val dao: UserProfileDao = mockk()
    private val extractionUseCase: UserProfileExtractionUseCase = mockk()
    private lateinit var repository: UserProfileRepository

    private var lastUpserted: UserProfileEntity? = null

    @BeforeEach
    fun setUp() {
        val entityFlow = MutableStateFlow<UserProfileEntity?>(null)

        every { dao.observe() } returns entityFlow
        coEvery { dao.upsert(any()) } answers {
            lastUpserted = firstArg()
            entityFlow.value = lastUpserted
        }
        coEvery { dao.get() } answers { lastUpserted }
        coEvery { dao.clear() } answers {
            lastUpserted = null
            entityFlow.value = null
        }

        repository = UserProfileRepository(dao, extractionUseCase)
    }

    @Test
    fun `save uses extraction and falls back to parser when extraction returns null`() = runTest {
        coEvery { extractionUseCase.extract(any()) } returns null

        repository.save("my name is Nick")

        coVerify { extractionUseCase.extract("my name is Nick") }
        assertNotNull(lastUpserted, "should have upserted an entity via parser fallback")
        assertEquals("my name is Nick", lastUpserted?.profileText)
    }

    @Test
    fun `save preserves facts from extraction in structured JSON`() = runTest {
        val extracted = UserProfileYaml(
            name = "Nick",
            role = "android software developer",
            facts = listOf("for this application", "building an Android app called Jandal AI"),
        )
        coEvery { extractionUseCase.extract(any()) } returns extracted

        repository.save("my name is Nick, I'm an android software developer for this application")

        coVerify { extractionUseCase.extract(any()) }
        val json = lastUpserted?.structuredJson
        assertNotNull(json, "structuredJson should not be null")
        assertTrue(json!!.contains("name\":\"Nick\""), "JSON should contain name")
        assertTrue(json.contains("android software developer"), "JSON should contain role")
        assertTrue(json.contains("facts"), "JSON should contain facts field")
        assertTrue(json.contains("for this application"),
            "JSON should contain 'for this application', got: $json")
        assertTrue(json.contains("Jandal AI"),
            "JSON should contain 'Jandal AI', got: $json")
    }

    @Test
    fun `save with empty profile stores null structured JSON`() = runTest {
        val extracted = UserProfileYaml()
        coEvery { extractionUseCase.extract(any()) } returns extracted

        repository.save("my name is Nick")

        assertEquals(null, lastUpserted?.structuredJson,
            "empty profile should produce null structuredJson")
    }

    @Test
    fun `observeStructured returns parsed profile with facts`() = runTest {
        val extracted = UserProfileYaml(
            name = "Nick",
            role = "android software developer",
            facts = listOf("for this application"),
        )
        coEvery { extractionUseCase.extract(any()) } returns extracted

        repository.save("my name is Nick, I'm an android software developer for this application")

        val json = lastUpserted?.structuredJson
        assertNotNull(json)
        assertTrue(json!!.contains("name\":\"Nick\""))
        assertTrue(json.contains("for this application"),
            "observed structured JSON should contain fact 'for this application', got: $json")
    }

    @Test
    fun `save trims to maxLength`() = runTest {
        coEvery { extractionUseCase.extract(any()) } returns null

        val longText = "x".repeat(3000)
        repository.save(longText)

        assertEquals(2000, lastUpserted?.profileText?.length)
    }
}
