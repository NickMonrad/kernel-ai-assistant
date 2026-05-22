package com.kernel.ai.core.skills.natives

private val SAVE_MEMORY_META_RE = Regex(
    """\b(?:wants|asked|would\s+like|wants\s+me)\s+to\s+(?:remember|save|store|keep)\b""",
    RegexOption.IGNORE_CASE,
)
private val SAVE_MEMORY_UNRESOLVED_RE = Regex(
    """^(?:this|that|it|these|those|them)\b|\b(?:this|that|it|these|those|them)\s+is\s+important\b""",
    RegexOption.IGNORE_CASE,
)
private val SAVE_MEMORY_SHORT_RECIPE_RE = Regex(
    """^(?:(?:the|this|that)\s+)?(?:[\p{L}\d'’.-]+\s+){0,2}recipe(?:\s+to\s+memory)?$""",
    RegexOption.IGNORE_CASE,
)

internal fun clarificationPromptForSaveMemory(rawContent: String, userName: String?): String? {
    val trimmed = rawContent.trim().trim('"')
    if (trimmed.isBlank()) return "What would you like me to remember?"

    val normalized = normaliseSaveContent(trimmed, userName).trim()
    val lower = normalized.lowercase()

    if (SAVE_MEMORY_META_RE.containsMatchIn(lower)) {
        return "What would you like me to remember?"
    }
    if (SAVE_MEMORY_UNRESOLVED_RE.containsMatchIn(lower)) {
        return "What would you like me to remember?"
    }

    val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size <= 4 && SAVE_MEMORY_SHORT_RECIPE_RE.matches(lower)) {
        return "Do you want me to remember the full recipe, or a specific fact about it?"
    }

    return null
}
