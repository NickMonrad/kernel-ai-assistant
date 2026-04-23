package com.kernel.ai.feature.chat

/**
 * Converts LaTeX expressions to Unicode and strips Markdown syntax,
 * producing clean plain text suitable for clipboard output.
 */
internal fun stripMarkdownForClipboard(text: String): String =
    stripMarkdown(convertLatexToUnicode(text))

/**
 * Strips common Markdown syntax from text for plain-text clipboard output.
 */
internal fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")             // bold
        .replace(Regex("""\*(.+?)\*"""), "$1")                  // italic
        .replace(Regex("""`{1,3}([\s\S]*?)`{1,3}"""), "$1")    // code blocks/inline (preserves content)
        .replace(Regex("""#{1,6}\s"""), "")                      // headers
        .replace(Regex("""\[(.+?)\]\(.+?\)"""), "$1")           // links
        .trim()
}

/**
 * Returns true if [query] looks like it involves a device-native tool action
 * (alarm, list, toggle, memory, etc.) rather than a pure LLM question.
 */
internal fun looksLikeToolQuery(query: String): Boolean {
    val lower = query.lowercase().trim()
    val toolKeywords = listOf(
        "save", "remember", "note that", "don't forget", "store",
        "add to", "put on", "put in", "add .+ to .+ list",
        "create .+ list", "make .+ list", "remove from", "delete from",
        "what's on my", "show my", "read my .+ list",
        "meal plan", "meal planner", "plan meals", "plan my meals", "weekly meals",
        "plan a meal", "plan dinner", "plan dinners", "sort dinners", "sort meals",
        "shopping list", "ingredients list",
        "set alarm", "set a timer", "set timer", "remind me",
        "send email", "send sms", "send a text", "call ",
        "search wikipedia", "look up", "wikipedia",
        "turn on", "turn off", "toggle", "open app",
        "play ", "navigate to", "directions to",
        "what time", "what's the time", "battery", "get battery",
        "meal plan", "plan my meals", "meal planner", "plan meals",
    )
    return toolKeywords.any { keyword ->
        if (keyword.contains(Regex("[.+*?]"))) {
            Regex(keyword, RegexOption.IGNORE_CASE).containsMatchIn(lower)
        } else {
            lower.contains(keyword)
        }
    }
}

/**
 * Returns true if [text] contains an anaphoric reference — i.e. the user says "that",
 * "this", "it", "the above", or similar, implying they need the previous turn's content
 * to resolve the referent.
 *
 * Used alongside [looksLikeToolQuery] to decide whether to inject the last conversation
 * pair as lightweight context even when full RAG is stripped.
 */
internal fun looksLikeAnaphora(text: String): Boolean {
    val lower = text.lowercase().trim()
    return Regex(
        """^(save|remember|store|add|note|keep)\s+(that|this|it)\b|
           \b(look|search|find|check)\s+(that|it|this)\s+(up|out)\b|
           ^(what|how|why|when|where)\s+(is|was|were|did)\s+(that|it|this)\b|
           \bthat\b|\bthe\s+above\b|\bthe\s+previous\b""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
    ).containsMatchIn(lower)
}

/**
 * Returns true if [text] is a short follow-up like "yes", "continue", or "ok let's do it"
 * and the immediately previous exchange was already in a tool-driven flow.
 */
internal fun looksLikeToolFollowUp(
    text: String,
    previousUser: String?,
    previousAssistant: String?,
): Boolean {
    val lower = text.lowercase().trim()
    val isContinuation = Regex(
        """^(yes|yeah|yep|yup|ok|okay|ok lets do it|okay lets do it|let'?s do it|do it|continue|carry on|go on|keep going|sounds good)\b""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(lower)
    if (!isContinuation) return false

    val context = listOfNotNull(previousUser, previousAssistant)
        .joinToString("\n")
        .lowercase()
    if (context.isBlank()) return false

    return looksLikeToolQuery(previousUser.orEmpty()) ||
        listOf(
            "meal plan",
            "recipe",
            "recipes",
            "dietary restrictions",
            "how many people",
            "how many days",
            "protein preferences",
            "full recipes",
            "cooking steps",
            "shopping list",
            "ingredients",
        ).any { context.contains(it) }
}

/**
 * Returns true if [response] looks like the model confirmed a tool action without
 * actually calling any tool — the classic Gemma-4 hallucination pattern.
 *
 * Matches phrases the model uses when it believes it completed an action:
 * "I've saved that", "Added milk to your list", "Done!", "Memory saved" etc.
 * Only checked when no tool was actually called to avoid false positives.
 *
 * On a positive match the caller should replace the response with an honest
 * failure message rather than surfacing fabricated confirmation to the user.
 */
internal fun looksLikeToolConfirmation(response: String): Boolean {
    val lower = response.lowercase()
    val actionPhrases = listOf(
        "i've saved", "i have saved", "saved that", "saved to memory", "saved to your memory",
        "memory saved", "noted that", "i'll remember", "i've noted",
        "added to your", "added that to", "added it to",
        "i've added", "i have added", "item added",
        "created your", "i've created", "list created", "created a new",
        "set an alarm", "alarm set", "timer set", "i've set",
        "turned on", "turned off", "toggled",
        // Catch "I've turned X on/off" where object sits between "turned" and "on/off"
        "i've turned", "i have turned",
        // Kiwi/casual action verbs — "I've flicked the flashlight on", "flicked it on"
        "i've flicked", "i have flicked", "flicked it on", "flicked it off",
        "switched on", "switched off",
        // Torch/light state claims — "the light's on", "flashlight is on", etc.
        "the light's on", "the light's off", "lights are on", "lights are off",
        "torch is on", "torch is off", "flashlight is on", "flashlight is off",
        "i've lit", "i have lit",
        "done!", "all done", "got it, i've", "sure thing",
    )
    return actionPhrases.any { lower.contains(it) }
}
