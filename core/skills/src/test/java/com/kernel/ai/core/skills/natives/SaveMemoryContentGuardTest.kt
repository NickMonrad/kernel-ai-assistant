package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SaveMemoryContentGuardTest {
    @Test
    fun `asks for clarification on unresolved anaphora paraphrase`() {
        assertEquals(
            "What would you like me to remember?",
            clarificationPromptForSaveMemory("Nick wants to remember that this is important.", "Nick"),
        )
    }

    @Test
    fun `asks recipe specific clarification for short recipe label`() {
        assertEquals(
            "Do you want me to remember the full recipe, or a specific fact about it?",
            clarificationPromptForSaveMemory("the pancakes recipe", "Nick"),
        )
    }

    @Test
    fun `allows concrete personal facts through`() {
        assertNull(clarificationPromptForSaveMemory("Nick prefers dark mode", "Nick"))
    }

    @Test
    fun `asks for clarification on blank content`() {
        assertEquals(
            "What would you like me to remember?",
            clarificationPromptForSaveMemory("   ", "Nick"),
        )
    }
}
