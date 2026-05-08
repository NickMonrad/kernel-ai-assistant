package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.SherpaOnnxVoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SetVoiceEmotionSkillTest {

    private lateinit var voiceController: SherpaOnnxVoiceOutputController
    private lateinit var voiceOutputPreferences: VoiceOutputPreferences
    private lateinit var skill: SetVoiceEmotionSkill

    @BeforeEach
    fun setUp() {
        voiceController = mockk(relaxed = true)
        voiceOutputPreferences = mockk()
        // Default: Semaine is selected
        every { voiceOutputPreferences.selectedSherpaVoice } returns
            flowOf(SherpaPiperVoice.SemaineMedium)
        skill = SetVoiceEmotionSkill(voiceController, voiceOutputPreferences)
    }

    // ── isEnabled() ────────────────────────────────────────────────────────

    @Test
    fun `isEnabled returns true when Semaine voice is selected`() {
        every { voiceOutputPreferences.selectedSherpaVoice } returns
            flowOf(SherpaPiperVoice.SemaineMedium)
        assertTrue(skill.isEnabled())
    }

    @Test
    fun `isEnabled returns false when JennyDioco is selected`() {
        every { voiceOutputPreferences.selectedSherpaVoice } returns
            flowOf(SherpaPiperVoice.JennyDioco)
        assertFalse(skill.isEnabled())
    }

    @Test
    fun `isEnabled returns false when VCTK is selected`() {
        every { voiceOutputPreferences.selectedSherpaVoice } returns
            flowOf(SherpaPiperVoice.VctkMedium)
        assertFalse(skill.isEnabled())
    }

    @Test
    fun `isEnabled returns false when any non-Semaine voice is selected`() {
        SherpaPiperVoice.entries
            .filter { it != SherpaPiperVoice.SemaineMedium }
            .forEach { voice ->
                every { voiceOutputPreferences.selectedSherpaVoice } returns flowOf(voice)
                assertFalse(skill.isEnabled(), "Expected isEnabled() = false for ${voice.name}")
            }
    }

    // ── execute() — successful emotion mappings ────────────────────────────

    @Test
    fun `neutral maps to sid 0`() = runTest {
        val result = skill.execute(SkillCall("set_voice_emotion", mapOf("emotion" to "neutral")))
        verify { voiceController.setEmotionOverrideSid(0) }
        assertInstanceOf(SkillResult.Success::class.java, result)
    }

    @Test
    fun `happy maps to sid 1`() = runTest {
        val result = skill.execute(SkillCall("set_voice_emotion", mapOf("emotion" to "happy")))
        verify { voiceController.setEmotionOverrideSid(1) }
        assertInstanceOf(SkillResult.Success::class.java, result)
    }

    @Test
    fun `sad maps to sid 2`() = runTest {
        val result = skill.execute(SkillCall("set_voice_emotion", mapOf("emotion" to "sad")))
        verify { voiceController.setEmotionOverrideSid(2) }
        assertInstanceOf(SkillResult.Success::class.java, result)
    }

    @Test
    fun `worried maps to sid 4`() = runTest {
        val result = skill.execute(SkillCall("set_voice_emotion", mapOf("emotion" to "worried")))
        verify { voiceController.setEmotionOverrideSid(4) }
        assertInstanceOf(SkillResult.Success::class.java, result)
    }

    @Test
    fun `angry maps to sid 3`() = runTest {
        val result = skill.execute(SkillCall("set_voice_emotion", mapOf("emotion" to "angry")))
        verify { voiceController.setEmotionOverrideSid(3) }
        assertInstanceOf(SkillResult.Success::class.java, result)
    }

    @Test
    fun `emotion matching is case-insensitive`() = runTest {
        val result = skill.execute(SkillCall("set_voice_emotion", mapOf("emotion" to "HAPPY")))
        verify { voiceController.setEmotionOverrideSid(1) }
        assertInstanceOf(SkillResult.Success::class.java, result)
    }

    // ── execute() — failure cases ──────────────────────────────────────────

    @Test
    fun `unknown emotion returns Failure`() = runTest {
        val result = skill.execute(SkillCall("set_voice_emotion", mapOf("emotion" to "disgusted")))
        assertInstanceOf(SkillResult.Failure::class.java, result)
        val failure = result as SkillResult.Failure
        assertEquals("set_voice_emotion", failure.skillName)
        assertTrue(failure.error.contains("disgusted"), "Error message should mention the bad emotion")
    }

    @Test
    fun `missing emotion argument returns Failure`() = runTest {
        val result = skill.execute(SkillCall("set_voice_emotion", emptyMap()))
        assertInstanceOf(SkillResult.Failure::class.java, result)
        val failure = result as SkillResult.Failure
        assertEquals("set_voice_emotion", failure.skillName)
    }

    // ── schema ─────────────────────────────────────────────────────────────

    @Test
    fun `skill name is set_voice_emotion`() {
        assertEquals("set_voice_emotion", skill.name)
    }

    @Test
    fun `schema requires emotion parameter`() {
        assertTrue(skill.schema.required.contains("emotion"))
        assertTrue(skill.schema.parameters.containsKey("emotion"))
    }
}
