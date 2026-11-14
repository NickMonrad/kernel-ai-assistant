package com.kernel.ai.core.skills

import android.util.Log
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Refs #1875. Drives KernelAIToolSet with calendar content in every reply and asserts that none of it
 * is written to any android.util.Log level. Calendar content is never logged, so this holds for
 * canonical names, variant names and multi-tool turns alike.
 */
class KernelAIToolSetCalendarLogTest {

    private val secretTitle = "Dentist with Dr Zebra"
    private val secretLocation = "Level 9 Wanganui Clinic"
    private val secretKeyword = "dentist"
    private val logged = mutableListOf<String>()
    private lateinit var registry: SkillRegistry
    private lateinit var toolSet: KernelAIToolSet

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } answers { logged += secondArg<String>(); 0 }
        every { Log.i(any<String>(), any<String>()) } answers { logged += secondArg<String>(); 0 }
        every { Log.w(any<String>(), any<String>()) } answers { logged += secondArg<String>(); 0 }
        every { Log.e(any<String>(), any<String>()) } answers { logged += secondArg<String>(); 0 }
        every { Log.v(any<String>(), any<String>()) } answers { logged += secondArg<String>(); 0 }
        registry = mockk()
        toolSet = KernelAIToolSet(
            object : Lazy<SkillRegistry> {
                override fun get(): SkillRegistry = registry
            },
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun assertNoCalendarContent() {
        val joined = logged.joinToString("\n")
        assertFalse(joined.contains(secretTitle), "event title reached logs")
        assertFalse(joined.contains(secretLocation), "event location reached logs")
        assertFalse(joined.contains(secretKeyword), "keyword reached logs")
    }

    @Test
    @DisplayName("a variant-cased calendar read via run_intent logs no event title, location or keyword")
    fun variantCalendarReadLogsNothing() = runTest {
        val reply = "1. $secretTitle at 9:00 AM, $secretLocation"
        val runIntentSkill = mockk<Skill>()
        every { runIntentSkill.name } returns "run_intent"
        coEvery { runIntentSkill.execute(any()) } returns SkillResult.DirectReply(reply)
        every { registry.get("run_intent") } returns runIntentSkill

        toolSet.resetTurnState(userMessage = "what's on with the dentist")
        toolSet.runIntent("READ_CALENDAR_EVENTS", """{"keyword":"$secretKeyword"}""")

        assertTrue(logged.isNotEmpty(), "expected tool-call logging to be captured")
        assertNoCalendarContent()
    }

    @Test
    @DisplayName("a calendar call followed by another tool in the same turn still logs no calendar content")
    fun calendarThenAnotherToolLogsNothing() = runTest {
        val calendarSkill = mockk<Skill>()
        every { calendarSkill.name } returns "run_intent"
        coEvery { calendarSkill.execute(any()) } returns SkillResult.DirectReply("1. $secretTitle at $secretLocation")
        every { registry.get("run_intent") } returns calendarSkill

        toolSet.resetTurnState(userMessage = "list my events then remind me")
        toolSet.runIntent("read_calendar_events", """{"keyword":"$secretKeyword"}""")
        toolSet.runIntent("set_timer", """{"minutes":"5"}""")

        assertTrue(logged.isNotEmpty(), "expected tool-call logging to be captured")
        assertNoCalendarContent()
    }
}
