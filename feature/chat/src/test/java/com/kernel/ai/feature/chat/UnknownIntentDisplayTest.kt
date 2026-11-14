package com.kernel.ai.feature.chat

import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.natives.UnknownIntentReplies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Display rules for run_intent failures: friendly text in bubbles/cards, detail only in chips. */
class UnknownIntentDisplayTest {

    private val unknownCalendar = SkillResult.Failure(
        skillName = "run_intent",
        error = UnknownIntentReplies.CALENDAR_READ_REPLY,
        technicalDetail = "Unknown intent: get_calendar",
    )

    private val unknownNonsense = SkillResult.Failure(
        skillName = "run_intent",
        error = UnknownIntentReplies.GENERIC_REPLY,
        technicalDetail = "Unknown intent: frobnicate_widgets",
    )

    @Test
    fun `chat bubble shows the friendly calendar reply without the generic prefix`() {
        assertEquals(UnknownIntentReplies.CALENDAR_READ_REPLY, failureChatReply(unknownCalendar))
    }

    @Test
    fun `chat bubble shows the generic reply for nonsense names`() {
        assertEquals(UnknownIntentReplies.GENERIC_REPLY, failureChatReply(unknownNonsense))
    }

    @Test
    fun `tool chip shows the technical detail, not the bubble text`() {
        assertEquals("Unknown intent: get_calendar", failureChipText(unknownCalendar))
    }

    @Test
    fun `failures without technicalDetail keep the generic chat prefix and show the error in the chip`() {
        val plain = SkillResult.Failure("run_intent", "Permission denied")

        assertEquals("I tried to do that but something went wrong: Permission denied", failureChatReply(plain))
        assertEquals("Permission denied", failureChipText(plain))
    }

    @Test
    fun `quick actions card has no Action failed prefix when technicalDetail is set`() {
        assertEquals(UnknownIntentReplies.CALENDAR_READ_REPLY, failureCardText(unknownCalendar))
        assertFalse(failureCardText(unknownNonsense).startsWith("Action failed"))
    }

    @Test
    fun `quick actions card keeps the Action failed prefix for plain failures`() {
        assertEquals(
            "Action failed: Permission denied",
            failureCardText(SkillResult.Failure("run_intent", "Permission denied")),
        )
    }

    @Test
    fun `no user-visible text contains the raw Unknown intent string`() {
        listOf(unknownCalendar, unknownNonsense).forEach { result ->
            listOf(failureChatReply(result), failureCardText(result)).forEach { shown ->
                assertFalse(shown.contains("Unknown intent", ignoreCase = true), shown)
            }
        }
    }
}
