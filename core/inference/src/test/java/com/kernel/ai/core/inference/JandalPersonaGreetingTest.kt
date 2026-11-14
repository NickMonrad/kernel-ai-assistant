package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #1606: the first-reply greeting must yield to an explicit user output contract. */
class JandalPersonaGreetingTest {

    private fun instruction(isFirstReply: Boolean, mode: PersonaMode, hour: Int = 15) =
        JandalPersona.greetingInstruction(isFirstReply, mode, hour)

    @Test
    fun `ordinary first reply still opens with the persona greeting`() {
        assertTrue(instruction(true, PersonaMode.FULL).contains("Open your reply with 'Kia ora'"))
        assertTrue(instruction(true, PersonaMode.HALF).contains("Open your reply with 'Kia ora'"))
        assertTrue(instruction(true, PersonaMode.FULL, hour = 8).contains("Open your reply with 'Morena'"))
        assertTrue(instruction(true, PersonaMode.BORING).contains("Open your reply with 'Hello' or 'Hi'"))
    }

    @Test
    fun `first-reply greeting yields to exact-format requests in every persona mode`() {
        for (mode in PersonaMode.entries) {
            for (hour in listOf(8, 15)) {
                val text = instruction(true, mode, hour)
                assertTrue(text.endsWith(EXACT_FORMAT_GREETING_EXCEPTION), "mode=$mode hour=$hour: $text")
            }
        }
    }

    @Test
    fun `exception names the reported reply-only, name-only and JSON-only contracts`() {
        assertTrue(EXACT_FORMAT_GREETING_EXCEPTION.contains("'Reply only OK'"))
        assertTrue(EXACT_FORMAT_GREETING_EXCEPTION.contains("'Reply only with the name'"))
        assertTrue(EXACT_FORMAT_GREETING_EXCEPTION.contains("'Return only the JSON object'"))
        assertTrue(EXACT_FORMAT_GREETING_EXCEPTION.contains("skip the greeting"))
        assertTrue(EXACT_FORMAT_GREETING_EXCEPTION.contains("no greeting, name or extra text before or after"))
    }

    @Test
    fun `follow-up reply greeting suppression from #290 is unchanged`() {
        for (mode in PersonaMode.entries) {
            val text = instruction(false, mode)
            assertEquals(
                "Do NOT start this reply with a greeting — you already greeted the user earlier in the conversation. " +
                    "Use the user's name occasionally for warmth, not as a prefix on every response.",
                text,
            )
            assertFalse(text.contains(EXACT_FORMAT_GREETING_EXCEPTION))
        }
    }

    @Test
    fun `tool-path minimal prompts carry the same exception right after their greeting rule`() {
        for ((name, prompt) in listOf("MINIMAL" to MINIMAL_SYSTEM_PROMPT, "BORING_MINIMAL" to BORING_MINIMAL_SYSTEM_PROMPT)) {
            val greeting = prompt.indexOf("If this is the opening message, greet the user naturally")
            val exception = prompt.indexOf(EXACT_FORMAT_GREETING_EXCEPTION)
            assertTrue(greeting >= 0 && exception > greeting, "$name: exception must follow the greeting rule")
        }
    }
}
