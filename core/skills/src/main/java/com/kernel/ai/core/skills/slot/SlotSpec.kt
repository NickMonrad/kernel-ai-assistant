package com.kernel.ai.core.skills.slot

/**
 * Describes a required parameter that is missing from a matched intent, together with
 * a template for the clarifying question to ask the user.
 *
 * [promptTemplate] may reference existing params using {key} placeholders:
 *   e.g. "What would you like to say to {contact}?"
 */
data class SlotSpec(
    val name: String,
    val promptTemplate: String,
) {
    fun buildPrompt(existingParams: Map<String, String>): String =
        existingParams.entries.fold(promptTemplate) { acc, (k, v) -> acc.replace("{$k}", v) }
}


fun normalizeSlotReply(text: String, slotName: String): String {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return trimmed

    return when (slotName.lowercase()) {
        "contact" -> trimmed.replace(Regex("^(?:to|for)\\s+", RegexOption.IGNORE_CASE), "").trim()
        "list_name" -> normalizeListSlotReply(trimmed)
        else -> trimmed
    }
}

private fun normalizeListSlotReply(trimmed: String): String {
    val genericAlias = Regex(
        "^(?:(?:to|on|onto|in|into)\\s+)?(?:(?:my|the)\\s+)?(shopping|grocery|groceries|todo|to do|to-do)(?:\\s+list)?$",
        RegexOption.IGNORE_CASE,
    ).matchEntire(trimmed)
    return genericAlias?.groupValues?.get(1)?.lowercase()?.let(::canonicalGenericListAlias) ?: trimmed
}

private fun canonicalGenericListAlias(alias: String): String = when (alias) {
    "shopping", "grocery", "groceries" -> "shopping list"
    "todo", "to do", "to-do" -> "to-do list"
    else -> alias
}