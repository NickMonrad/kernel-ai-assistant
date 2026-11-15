package com.kernel.ai.feature.settings

import com.kernel.ai.core.voice.VoiceInputEngine
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnlineSpeechToggleVisibilityTest {
    @Test
    fun `toggle shows under Automatic when Automatic is selected`() {
        assertTrue(onlineSpeechToggleVisible(selected = VoiceInputEngine.Auto, row = VoiceInputEngine.Auto))
    }

    @Test
    fun `toggle shows under Android native when Android native is selected`() {
        assertTrue(
            onlineSpeechToggleVisible(
                selected = VoiceInputEngine.AndroidNative,
                row = VoiceInputEngine.AndroidNative,
            ),
        )
    }

    @Test
    fun `toggle is hidden under Automatic when another engine is selected`() {
        VoiceInputEngine.entries.filter { it != VoiceInputEngine.Auto }.forEach { selected ->
            assertFalse(onlineSpeechToggleVisible(selected = selected, row = VoiceInputEngine.Auto))
        }
    }

    @Test
    fun `toggle is hidden under Android native when another engine is selected`() {
        VoiceInputEngine.entries.filter { it != VoiceInputEngine.AndroidNative }.forEach { selected ->
            assertFalse(onlineSpeechToggleVisible(selected = selected, row = VoiceInputEngine.AndroidNative))
        }
    }

    @Test
    fun `toggle never shows under engines it does not affect`() {
        val unaffected = VoiceInputEngine.entries.filter {
            it != VoiceInputEngine.Auto && it != VoiceInputEngine.AndroidNative
        }
        VoiceInputEngine.entries.forEach { selected ->
            unaffected.forEach { row ->
                assertFalse(onlineSpeechToggleVisible(selected = selected, row = row))
            }
        }
    }
}
