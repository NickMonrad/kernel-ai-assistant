package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.inference.diagnostics.IntentOutcome
import com.kernel.ai.core.skills.SkillResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Outcome categories for intent logging (#1870). DirectReply is a successful list add. */
class NativeIntentOutcomeTest {

    @Test
    fun `direct reply from a list add counts as success`() {
        assertEquals(
            IntentOutcome.SUCCESS,
            NativeIntentHandler.intentOutcomeOf(SkillResult.DirectReply("Added milk to your list.")),
        )
    }

    @Test
    fun `plain success counts as success`() {
        assertEquals(IntentOutcome.SUCCESS, NativeIntentHandler.intentOutcomeOf(SkillResult.Success("done")))
    }

    @Test
    fun `failure categories map to their own outcome`() {
        assertEquals(
            IntentOutcome.FAILURE,
            NativeIntentHandler.intentOutcomeOf(SkillResult.Failure("run_intent", "boom")),
        )
        assertEquals(
            IntentOutcome.PARSE_ERROR,
            NativeIntentHandler.intentOutcomeOf(SkillResult.ParseError("{", "bad json")),
        )
        assertEquals(
            IntentOutcome.UNKNOWN_SKILL,
            NativeIntentHandler.intentOutcomeOf(SkillResult.UnknownSkill("nope")),
        )
    }
}
