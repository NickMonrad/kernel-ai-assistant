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
        // The action rule explicitly says date/time details route to run_intent(create_calendar_event)
        // NOT save_memory — even when words like 'keep' or 'save' appear in scheduling context
        listOf(
            "DEFAULT" to DEFAULT_SYSTEM_PROMPT,
            "HALF" to HALF_JANDAL_SYSTEM_PROMPT,
            "BORING" to BORING_AI_SYSTEM_PROMPT,
            "MINIMAL" to MINIMAL_SYSTEM_PROMPT,
            "BORING_MINIMAL" to BORING_MINIMAL_SYSTEM_PROMPT,
        ).forEach { (name, prompt) ->
            assertTrue(prompt.contains("do NOT route to save_memory"),
                "Variant '$name' missing calendar→save_memory exclusion")
        }
    }
}
