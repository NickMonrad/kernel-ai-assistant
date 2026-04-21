package com.kernel.ai.core.skills

/**
 * Result returned by a [Skill] after execution.
 *
 * ## Choosing between [Success] and [DirectReply]
 *
 * | Variant | When to use | LLM invoked? |
 * |---------|-------------|--------------|
 * | [DirectReply] | Skill returns **structured or numeric data** (weather readings, search results, sensor values, lists). Output is already complete — LLM rephrasing risks corrupting numbers/units. | ❌ Shown verbatim |
 * | [Success] | Skill performs an **action** and the LLM should narrate the outcome naturally (e.g. toggle flashlight, set alarm, save memory). Conversational wrapping adds value. | ✅ Injected as system context |
 *
 * ### Example — why this matters
 * `GetWeatherSkill` originally returned [Success], which injected the formatted string
 * as `[System: ...]` context. The LLM then summarised it and corrupted `16.6°C` → `6.6°C`.
 * Switching to [DirectReply] fixed this by bypassing the LLM entirely.
 *
 * ### UI behaviour
 * Both [Success] and [DirectReply] display an intent chip in the chat UI via [ToolCallInfo].
 * [DirectReply] uses `appendAssistantMessageWithToolCall()` in `ChatViewModel`.
 */
sealed class SkillResult {
    /** Skill ran successfully. [content] is injected back into the conversation as system context
     *  so the LLM can produce a natural conversational wrapper around it. */
    data class Success(
        val content: String,
        val presentation: ToolPresentation? = null,
    ) : SkillResult()
    /** Skill ran successfully and [content] should be shown to the user verbatim — the LLM is
     *  bypassed entirely. Use for skills that return structured data (e.g. weather readings,
     *  sensor values) where LLM rephrasing risks corrupting numbers or units. */
    data class DirectReply(
        val content: String,
        val presentation: ToolPresentation? = null,
    ) : SkillResult()
    /** Skill not found in registry. */
    data class UnknownSkill(val skillName: String) : SkillResult()
    /** Skill found but execution failed. */
    data class Failure(val skillName: String, val error: String) : SkillResult()
    /** Skill output was malformed JSON or failed schema validation. */
    data class ParseError(val rawOutput: String, val reason: String) : SkillResult()
}
