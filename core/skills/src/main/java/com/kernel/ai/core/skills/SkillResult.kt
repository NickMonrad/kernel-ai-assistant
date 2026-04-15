package com.kernel.ai.core.skills

sealed class SkillResult {
    /** Skill ran successfully. [content] is injected back into the conversation. */
    data class Success(val content: String) : SkillResult()
    /** Skill not found in registry. */
    data class UnknownSkill(val skillName: String) : SkillResult()
    /** Skill found but execution failed. */
    data class Failure(val skillName: String, val error: String) : SkillResult()
    /** Skill output was malformed JSON or failed schema validation. */
    data class ParseError(val rawOutput: String, val reason: String) : SkillResult()
}
