package com.kernel.ai.core.skills

import com.kernel.ai.core.inference.diagnostics.IntentRoute
import com.kernel.ai.core.inference.diagnostics.IntentOutcome
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.skills.natives.NativeIntentHandler

/**
 * Records one outcome event per skill call (#1870). Every path that runs a skill goes through
 * [execute] or [record], so direct chat/actions calls and LLM tool calls produce the same event.
 *
 * `run_intent` is skipped: [NativeIntentHandler] records the intent it dispatches, with the route.
 */
object SkillOutcomeEvents {

    suspend fun execute(skill: Skill, arguments: Map<String, String>, route: IntentRoute): SkillResult {
        val call = SkillCall(skill.name, arguments, route = route)
        return skill.execute(call).also { record(call, it) }
    }

    fun record(call: SkillCall, result: SkillResult) {
        if (call.skillName == RUN_INTENT) return
        val outcome = NativeIntentHandler.intentOutcomeOf(result)
        JandalEvents.record(
            "skill",
            call.skillName,
            "route" to call.route,
            "success" to (outcome == IntentOutcome.SUCCESS),
            "outcome" to outcome,
        )
    }

    private const val RUN_INTENT = "run_intent"
}
