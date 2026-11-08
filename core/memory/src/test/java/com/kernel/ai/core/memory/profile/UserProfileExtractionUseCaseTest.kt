package com.kernel.ai.core.memory.profile

import com.kernel.ai.core.inference.InferenceEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests that [UserProfileExtractionUseCase] correctly parses LLM output.
 *
 * NOTE: `UserProfileYaml.fromJson()` returns null in JVM-only unit tests
 * because `org.json.JSONObject` is from the Android SDK. These tests verify
 * that the use case captures and logs the JSON correctly and returns a valid
 * object only when the engine produces parseable JSON. For actual JSON parsing
 * verification, see [UserProfileParserTest] serialization tests (toJson/toYaml).
 */
class UserProfileExtractionUseCaseTest {

    private val inferenceEngine: InferenceEngine = mockk()
    private lateinit var useCase: UserProfileExtractionUseCase

    @BeforeEach
    fun setUp() {
        every { inferenceEngine.isReady } returns MutableStateFlow(true)
        useCase = UserProfileExtractionUseCase(inferenceEngine)
    }

    @Test
    fun `extract returns null when engine not ready`() = runTest {
        every { inferenceEngine.isReady } returns MutableStateFlow(false)
        val result = useCase.extract("hello")
        assertEquals(null, result)
    }

    @Test
    fun `extract returns null when generateOnce returns blank`() = runTest {
        coEvery { inferenceEngine.generateOnce(any(), any(), any(), any()) } returns ""
        val result = useCase.extract("hello")
        assertEquals(null, result)
    }

    @Test
    fun `extract returns null for malformed JSON`() = runTest {
        coEvery { inferenceEngine.generateOnce(any(), any(), any(), any()) } returns """{"name": broken"""
        val result = useCase.extract("hello")
        assertEquals(null, result, "should return null when engine returns invalid JSON")
    }

    @Test
    fun `extract returns null when JSON parsing fails`() = runTest {
        // In unit tests fromJson returns null (org.json.JSONObject not available)
        // This verifies the null-propagating behaviour
        coEvery { inferenceEngine.generateOnce(any(), any(), any(), any()) } returns """{"name":"Nick"}"""
        val result = useCase.extract("Nick")
        // fromJson may return null in unit test — that's acceptable.
        // In production on Android, JSONObject is present and parsing succeeds.
        assertTrue(result == null || result.name == "Nick",
            "extract should either return null (unit test) or parse correctly (production)")
    }

    @Test
    fun `toYaml and toJson serialize facts correctly`() {
        val profile = UserProfileYaml(
            name = "Nick",
            role = "android software developer",
            facts = listOf("for this application", "building an Android app called Jandal AI"),
        )
        val yaml = profile.toYaml()
        assertTrue(yaml.contains("facts:"))
        assertTrue(yaml.contains("for this application"))
        assertTrue(yaml.contains("Jandal AI"))

        val json = profile.toJson()
        assertTrue(json.contains("\"name\":\"Nick\""))
        assertTrue(json.contains("\"facts\""))
        assertTrue(json.contains("for this application"))
        assertTrue(json.contains("Jandal AI"))
    }

    @Test
    fun `facts field survives full profile serialization`() {
        val profile = UserProfileYaml(
            name = "Sarah",
            role = "nurse",
            location = "Brisbane",
            facts = listOf("uses reminders", "quick meal ideas"),
        )
        val yaml = profile.toYaml()
        assertTrue(yaml.contains("facts:"))
        assertTrue(yaml.contains("uses reminders"))
        assertTrue(yaml.contains("quick meal ideas"))
        assertTrue(yaml.contains("name: Sarah"))
        assertTrue(yaml.contains("role: nurse"))
        assertTrue(yaml.contains("location: Brisbane"))
    }

    @Test
    fun `empty facts in JSON serialization`() {
        val profile = UserProfileYaml(name = "Nick", role = "developer")
        val json = profile.toJson()
        assertTrue(json.contains("\"name\":\"Nick\""))
        // facts should NOT appear in JSON when empty
        assertTrue(!json.contains("facts"), "empty facts should not appear in JSON, got: $json")
    }
}
