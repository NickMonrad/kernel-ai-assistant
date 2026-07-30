package com.kernel.ai.core.inference

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that all system prompt variants encode the required tool-selection rules
 * for #1428 — action requests must route to run_intent, not informational or memory tools.
 *
 * Run with: ./gradlew :core:inference:testDebugUnitTest --tests "*.ModelConfigTest"
 */
class ModelConfigTest {

    @Test
    fun `DEFAULT_SYSTEM_PROMPT contains device action rule`() {
        assertTrue(DEFAULT_SYSTEM_PROMPT.contains("perform a device action"),
            "Missing 'perform a device action'")
        assertTrue(DEFAULT_SYSTEM_PROMPT.contains("use run_intent"),
            "Missing 'use run_intent'")
        assertTrue(DEFAULT_SYSTEM_PROMPT.contains("create_calendar_event"),
            "Missing 'create_calendar_event'")
    }

    @Test
    fun `DEFAULT_SYSTEM_PROMPT contains save_memory rule`() {
        assertTrue(
            DEFAULT_SYSTEM_PROMPT.contains("When the user asks you to save or remember something, you MUST call the saveMemory tool"),
            "Missing saveMemory rule"
        )
    }

    @Test
    fun `HALF_JANDAL_SYSTEM_PROMPT contains device action rule`() {
        assertTrue(HALF_JANDAL_SYSTEM_PROMPT.contains("perform a device action"),
            "Missing 'perform a device action'")
        assertTrue(HALF_JANDAL_SYSTEM_PROMPT.contains("use run_intent"),
            "Missing 'use run_intent'")
    }

    @Test
    fun `BORING_AI_SYSTEM_PROMPT contains device action rule`() {
        assertTrue(BORING_AI_SYSTEM_PROMPT.contains("perform a device action"),
            "Missing 'perform a device action'")
        assertTrue(BORING_AI_SYSTEM_PROMPT.contains("use run_intent"),
            "Missing 'use run_intent'")
    }

    @Test
    fun `MINIMAL_SYSTEM_PROMPT contains device action rule`() {
        assertTrue(MINIMAL_SYSTEM_PROMPT.contains("perform a device action"),
            "Missing 'perform a device action'")
        assertTrue(MINIMAL_SYSTEM_PROMPT.contains("use run_intent"),
            "Missing 'use run_intent'")
        assertTrue(MINIMAL_SYSTEM_PROMPT.contains("create_calendar_event"),
            "Missing 'create_calendar_event'")
    }

    @Test
    fun `MINIMAL_SYSTEM_PROMPT contains save_memory rule`() {
        assertTrue(
            MINIMAL_SYSTEM_PROMPT.contains("When the user asks you to save or remember something, you MUST call the saveMemory tool"),
            "Missing saveMemory rule"
        )
    }

    @Test
    fun `BORING_MINIMAL_SYSTEM_PROMPT contains device action rule`() {
        assertTrue(BORING_MINIMAL_SYSTEM_PROMPT.contains("perform a device action"),
            "Missing 'perform a device action'")
        assertTrue(BORING_MINIMAL_SYSTEM_PROMPT.contains("use run_intent"),
            "Missing 'use run_intent'")
    }

    @Test
    fun `all prompt variants have save_memory rule`() {
        listOf(
            "DEFAULT" to DEFAULT_SYSTEM_PROMPT,
            "HALF" to HALF_JANDAL_SYSTEM_PROMPT,
            "BORING" to BORING_AI_SYSTEM_PROMPT,
            "MINIMAL" to MINIMAL_SYSTEM_PROMPT,
            "BORING_MINIMAL" to BORING_MINIMAL_SYSTEM_PROMPT,
        ).forEach { (name, prompt) ->
            assertTrue(prompt.contains("saveMemory tool"),
                "Variant '$name' missing saveMemory rule")
        }
    }

    @Test
    fun `all prompt variants have device action rule`() {
        listOf(
            "DEFAULT" to DEFAULT_SYSTEM_PROMPT,
            "HALF" to HALF_JANDAL_SYSTEM_PROMPT,
            "BORING" to BORING_AI_SYSTEM_PROMPT,
            "MINIMAL" to MINIMAL_SYSTEM_PROMPT,
            "BORING_MINIMAL" to BORING_MINIMAL_SYSTEM_PROMPT,
        ).forEach { (name, prompt) ->
            assertTrue(prompt.contains("use run_intent"),
                "Variant '$name' missing run_intent rule")
        }
    }

    @Test
    fun `calendar scheduling does not route to save_memory`() {
        // The action rule must still exclude save_memory for calendar content
        listOf(
            "DEFAULT" to DEFAULT_SYSTEM_PROMPT,
            "HALF" to HALF_JANDAL_SYSTEM_PROMPT,
            "BORING" to BORING_AI_SYSTEM_PROMPT,
            "MINIMAL" to MINIMAL_SYSTEM_PROMPT,
            "BORING_MINIMAL" to BORING_MINIMAL_SYSTEM_PROMPT,
        ).forEach { (name, prompt) ->
            assertTrue(prompt.contains("route to save_memory") || prompt.contains("route to save_memory", ignoreCase = true),
                "Variant '$name' missing calendar→save_memory exclusion")
        }
    }

    @Test
    fun `all variants narrow calendar to reservation scheduling not date-words alone`() {
        listOf(
            "DEFAULT" to DEFAULT_SYSTEM_PROMPT,
            "HALF" to HALF_JANDAL_SYSTEM_PROMPT,
            "BORING" to BORING_AI_SYSTEM_PROMPT,
            "MINIMAL" to MINIMAL_SYSTEM_PROMPT,
            "BORING_MINIMAL" to BORING_MINIMAL_SYSTEM_PROMPT,
        ).forEach { (name, prompt) ->
            val hasNarrowing = prompt.contains("date/time words alone", ignoreCase = true) ||
                prompt.contains("Date/time words alone") ||
                prompt.contains("do NOT make a request a calendar action")
            assertTrue(hasNarrowing,
                "Variant '$name' missing calendar narrowing language")
        }
    }

    @Test
    fun `all variants say alarms timers reminders retain their intents`() {
        listOf(
            "DEFAULT" to DEFAULT_SYSTEM_PROMPT,
            "HALF" to HALF_JANDAL_SYSTEM_PROMPT,
            "BORING" to BORING_AI_SYSTEM_PROMPT,
            "MINIMAL" to MINIMAL_SYSTEM_PROMPT,
            "BORING_MINIMAL" to BORING_MINIMAL_SYSTEM_PROMPT,
        ).forEach { (name, prompt) ->
            val hasAll = prompt.contains("Alarms") && prompt.contains("timers") &&
                prompt.contains("reminders") &&
                (prompt.contains("retain their existing") || prompt.contains("retain their existing intents"))
            assertTrue(hasAll,
                "Variant '$name' missing alarms/timers/reminders retention clause. Prompt snippet: " + prompt.take(500).replace("\n", "\\n"))
        }
    }
}
