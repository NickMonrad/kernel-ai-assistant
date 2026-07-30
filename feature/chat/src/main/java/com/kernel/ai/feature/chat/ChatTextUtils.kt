package com.kernel.ai.feature.chat

import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.core.inference.JandalPersona
import com.kernel.ai.feature.chat.model.ToolCallInfo

/**
 * Converts LaTeX expressions to Unicode and strips Markdown syntax,
 * producing clean plain text suitable for clipboard output.
 */
internal fun stripMarkdownForClipboard(text: String): String =
    stripMarkdown(convertLatexToUnicode(text))

/**
 * Formats a conversation for the clipboard ("Copy conversation"). User/assistant message content
 * is markdown-stripped for readability. When [includeThinking] / [includeToolCalls] are enabled
 * (#1024), the assistant's thinking blocks and tool-call request/result payloads are appended
 * verbatim (NOT markdown-stripped — debugging needs the raw text/JSON). With both flags off the
 * output is identical to the plain transcript.
 */
internal fun formatConversationForClipboard(
    messages: List<ChatMessage>,
    includeThinking: Boolean,
    includeToolCalls: Boolean,
): String = messages.joinToString("\n") { msg ->
    when (msg.role) {
        ChatMessage.Role.USER -> "You: ${stripMarkdownForClipboard(msg.content)}"
        ChatMessage.Role.ASSISTANT -> {
            val blocks = mutableListOf<String>()
            if (includeThinking && !msg.thinkingText.isNullOrBlank()) {
                blocks += "[Thinking]\n${msg.thinkingText.trim()}\n[End Thinking]"
            }
            if (includeToolCalls && msg.toolCall != null) {
                blocks += formatToolCallForClipboard(msg.toolCall)
            }
            if (blocks.isEmpty()) {
                "Jandal: ${stripMarkdownForClipboard(msg.content)}"
            } else {
                buildString {
                    append("Jandal:")
                    blocks.forEach { append('\n').append(it) }
                    if (msg.content.isNotBlank()) {
                        append('\n').append(stripMarkdownForClipboard(msg.content))
                    }
                }
            }
        }
    }
}

private fun formatToolCallForClipboard(toolCall: ToolCallInfo): String = buildString {
    val status = if (toolCall.isSuccess) "success" else "failed"
    append("[Tool Call: ${toolCall.skillName} — $status]")
    if (toolCall.requestJson.isNotBlank()) append("\nRequest: ${toolCall.requestJson.trim()}")
    if (toolCall.resultText.isNotBlank()) append("\nResult: ${toolCall.resultText.trim()}")
    append("\n[End Tool Call]")
}

/**
 * Returns [text] truncated to at most [maxSentences] sentences (split at `.`, `!`, `?`).
 * If [maxSentences] is 0 or negative the full [text] is returned unchanged (unlimited).
 * If [text] contains fewer than [maxSentences] sentence-ending boundaries the full text
 * is returned so short responses are never cut off.
 */
private val KNOWN_ABBREV = setOf("dr", "mr", "mrs", "ms", "prof", "st", "vs", "etc", "jr", "sr")
private val INITIALS_REGEX = Regex("""([A-Za-z]\.)+""")  // matches "U.S.", "e.g.", "i.e."

private fun isAbbreviationFragment(fragment: String): Boolean {
    val trimmed = fragment.trim()
    if (trimmed.contains(' ')) return false          // multi-word — never an abbreviation fragment
    val withoutTrailingPunct = trimmed.trimEnd('.', '!', '?', '"', '\'', ')')
    return withoutTrailingPunct.lowercase() in KNOWN_ABBREV ||
           INITIALS_REGEX.matches(withoutTrailingPunct + ".")
}

internal fun truncateForSpeech(text: String, maxSentences: Int): String {
    if (maxSentences <= 0) return text
    val sentenceRegex = Regex("""[^.!?]*[.!?]["')]*""")
    val fragments = sentenceRegex.findAll(text).map { it.value }.toList()
    // Only merge fragments that are known abbreviations (Dr., Mr., e.g., U.S.) forward into
    // the next fragment. Single-word complete sentences like "Sure." or "Yes." are real
    // sentence boundaries and must NOT be merged.
    val sentences = buildList {
        var pending = ""
        for (fragment in fragments) {
            if (isAbbreviationFragment(fragment)) {
                pending += fragment
            } else {
                add(pending + fragment)
                pending = ""
            }
        }
        // Any trailing pending text (abbreviation at very end of input) is its own entry.
        if (pending.isNotEmpty()) add(pending)
    }
    if (sentences.isEmpty() || sentences.size <= maxSentences) return text
    return sentences.take(maxSentences).joinToString("").trimEnd()
}

internal fun normalizeChatTextForSpeech(text: String): String =
    stripMarkdownForClipboard(text)
        .replace(Regex("""(?m)^\s*[-*_]{1,3}\s*$"""), "")  // thematic break / standalone dividers (***, ---, *, lone *)
        .replace(Regex("""\r?\n\s*\d+\.\s+"""), ". ")   // numbered list item boundary → sentence break
        .replace(Regex("""(?m)^\s*\d+\.\s+"""), "")      // strip leading numbered marker at start
        .replace(Regex("""(?m)^\s*[-*•]\s*"""), "")       // strip bullet markers at line start (lone * has no trailing ws)
        .replace(Regex("""[•‣◦∙⋅·]\s*"""), "")           // strip any remaining inline bullet chars
        .let(::normalizeFractionsForSpeech)               // expand fractions/units before pronunciation overrides
        .replace(Regex("""(?<!\d):(?!\d)(?!//)\s*"""), ". ")   // non-numeric colons → sentence break (preserves ://)
        .replace(Regex("""[—–]\s*"""), ", ")              // em/en dashes → natural comma pause
        .replace(Regex("""\s*(?:\r?\n){2,}\s*"""), ". ")
        .replace(Regex("""\s*\r?\n\s*"""), ". ")
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""\.{2,}"""), ".")    // collapse consecutive dots (.., ...) from compound transforms
        .replace(Regex("""\.\s+\."""), ".")   // collapse ". ." spaced artifacts from compound transforms
        .let(::applySpeechPronunciationOverrides)
        .trimStart('.')   // strip leading period artifacts (read by espeak as "dot")
        .trim()

internal fun finalizeChatTextForSpeech(text: String): String =
    shapeSpeechChunkForPlayback(
        text = normalizeChatTextForSpeech(text),
        boundaryType = SpeechChunkBoundaryType.FORCED,
    )

internal fun popNextStreamingSpeechChunk(
    buffer: StringBuilder,
    minChunkLength: Int = 72,
    preferredChunkLength: Int = 180,
    force: Boolean = false,
): String? {
    if (buffer.isEmpty()) return null
    val raw = buffer.toString()
    val boundary = findSpeechChunkBoundary(
        text = raw,
        minChunkLength = minChunkLength,
        preferredChunkLength = preferredChunkLength,
        force = force,
    )
    if (boundary == null || boundary.index <= 0) return null

    val chunk = raw.substring(0, boundary.index)
    buffer.delete(0, boundary.index)
    return normalizeChatTextForSpeech(chunk)
        .takeIf { it.isNotBlank() }
        ?.let { shapeSpeechChunkForPlayback(it, boundary.type) }
        ?.takeIf { it.isNotBlank() }
}

private data class SpeechChunkBoundary(
    val index: Int,
    val type: SpeechChunkBoundaryType,
)

private enum class SpeechChunkBoundaryType {
    STRONG,
    SOFT,
    WHITESPACE,
    FORCED,
}

private data class SpeechPronunciationRule(
    val pattern: Regex,
    val replacement: String,
)

// Contractions first — must precede bare \bI\b to avoid partial substitution.
private val PRONOUN_LITERAL_RULES: List<Pair<Regex, String>> = listOf(
    Regex("""\bI'm\b""") to "you're",
    Regex("""\bI've\b""") to "you've",
    Regex("""\bI'll\b""") to "you'll",
    Regex("""\bI'd\b""") to "you'd",
    Regex("""\bI\b""") to "you",
)

// Case-preserving rules: "My" → "Your", "MY" → "YOUR", "my" → "your".
private val PRONOUN_CASE_PRESERVING_RULES: List<Pair<Regex, String>> = listOf(
    Regex("""\bmy\b""", RegexOption.IGNORE_CASE) to "your",
    Regex("""\bmine\b""", RegexOption.IGNORE_CASE) to "yours",
    Regex("""\bmyself\b""", RegexOption.IGNORE_CASE) to "yourself",
)

/**
 * Converts first-person pronouns to second-person so echoed user phrases sound natural
 * when spoken back by TTS (e.g. "my wife" → "your wife", "I" → "you").
 *
 * Apply only to the TTS path — never to displayed text.
 */
internal fun normalisePronounsForTts(text: String): String {
    val afterLiteral = PRONOUN_LITERAL_RULES.fold(text) { current, (pattern, replacement) ->
        pattern.replace(current, replacement)
    }
    return PRONOUN_CASE_PRESERVING_RULES.fold(afterLiteral) { current, (pattern, replacement) ->
        pattern.replace(current) { match -> matchCase(match.value, replacement) }
    }
}

private val speechPronunciationRules = listOf(
    SpeechPronunciationRule(
        pattern = Regex("""\bkia\s+ora\b""", RegexOption.IGNORE_CASE),
        replacement = "keeorah",
    ),
    SpeechPronunciationRule(
        pattern = Regex("""\bm(?:ō|o)rena\b""", RegexOption.IGNORE_CASE),
        replacement = "moh-reh-nah",
    ),
    SpeechPronunciationRule(
        pattern = Regex("""(?<![a-zA-Z-])aye(?![a-zA-Z-])""", RegexOption.IGNORE_CASE),
        replacement = "A",
    ),
)

private fun findSpeechChunkBoundary(
    text: String,
    minChunkLength: Int,
    preferredChunkLength: Int,
    force: Boolean,
): SpeechChunkBoundary? {
    if (text.isBlank()) {
        return if (force) {
            SpeechChunkBoundary(index = text.length, type = SpeechChunkBoundaryType.FORCED)
        } else {
            null
        }
    }
    if (force) {
        return SpeechChunkBoundary(index = text.length, type = SpeechChunkBoundaryType.FORCED)
    }

    // Scan forward and return at the FIRST suitable boundary rather than the last.
    // The previous forEachIndexed approach kept overwriting boundary positions and returned
    // the last sentence end in the buffer — causing the entire text (e.g. 3000+ chars) to
    // be returned as one chunk instead of the first sentence (~150 chars).
    var softBoundary = -1
    var whitespaceBoundary = -1

    for (index in text.indices) {
        val char = text[index]

        if (char.isWhitespace()) {
            whitespaceBoundary = index + 1
        }
        when (char) {
            '.', '!', '?' -> {
                val next = text.getOrNull(index + 1)
                if (next == null || next.isWhitespace() || next == '"' || next == '\'' || next == ')') {
                    val boundary = index + 1
                    if (boundary >= minChunkLength) {
                        return SpeechChunkBoundary(index = boundary, type = SpeechChunkBoundaryType.STRONG)
                    }
                }
            }
            '\n' -> {
                val boundary = index + 1
                if (boundary >= minChunkLength) {
                    return SpeechChunkBoundary(index = boundary, type = SpeechChunkBoundaryType.STRONG)
                }
            }
            ',', ';', ':' -> {
                softBoundary = index + 1
            }
        }

        // Past preferred length: accept the best soft or whitespace boundary we have so far.
        // Prefer SOFT only if it's at least as recent as the last whitespace; otherwise a stale
        // early comma would be chosen over a whitespace boundary much closer to preferredChunkLength.
        if (index + 1 >= preferredChunkLength) {
            if (softBoundary >= minChunkLength && softBoundary >= whitespaceBoundary) {
                return SpeechChunkBoundary(index = softBoundary, type = SpeechChunkBoundaryType.SOFT)
            }
            if (whitespaceBoundary >= minChunkLength) {
                return SpeechChunkBoundary(index = whitespaceBoundary, type = SpeechChunkBoundaryType.WHITESPACE)
            }
            if (softBoundary >= minChunkLength) {
                return SpeechChunkBoundary(index = softBoundary, type = SpeechChunkBoundaryType.SOFT)
            }
        }
    }

    return null
}

/**
 * Expands common cooking fractions and unit abbreviations to spoken words so TTS does not
 * read "1/4 tsp" as "one slash four tee ess pee".
 *
 * Order of substitutions:
 * 1. Unicode fraction characters (½ ¼ ¾ ⅓ ⅔ ⅛)
 * 2. Mixed numbers — must run BEFORE simple fractions so "1 1/2" → "one and a half",
 *    not "1 half" via two separate replacements.
 * 3. Simple ASCII fractions (1/2, 1/4, 3/4, 1/3, 2/3, 1/8, 3/8, 1/16)
 * 4. Cooking unit abbreviations (tsp, tbsp) — whole-word, case-insensitive.
 *
 * Unknown fractions (e.g. 5/7, dates written as 15/3) are left untouched to avoid
 * false positives.
 */
private val MIXED_NUMBER_FRACTION_RULES: List<Pair<Regex, String>> = listOf(
    Regex("""\b(\d+)\s+1/2\b""") to "$1 and a half",
    Regex("""\b(\d+)\s+1/4\b""") to "$1 and a quarter",
    Regex("""\b(\d+)\s+3/4\b""") to "$1 and three quarters",
    Regex("""\b(\d+)\s+1/3\b""") to "$1 and one third",
    Regex("""\b(\d+)\s+2/3\b""") to "$1 and two thirds",
    Regex("""\b(\d+)\s+1/8\b""") to "$1 and one eighth",
    Regex("""\b(\d+)\s+3/8\b""") to "$1 and three eighths",
)

// Negative lookahead to prevent matching date-format strings (e.g. "2/3 May", "1/2/2024").
// Rejects: followed by "/" (full date like 2/3/2024) or a digit, or a space + month name.
private val DATE_GUARD = """(?![/\d]|\s+(?i:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\b)"""

private val SIMPLE_FRACTION_RULES: List<Pair<Regex, String>> = listOf(
    Regex("""\b1/2${DATE_GUARD}""") to "half",
    Regex("""\b1/4${DATE_GUARD}""") to "quarter",
    Regex("""\b3/4${DATE_GUARD}""") to "three quarters",
    Regex("""\b1/3${DATE_GUARD}""") to "one third",
    Regex("""\b2/3${DATE_GUARD}""") to "two thirds",
    Regex("""\b1/8${DATE_GUARD}""") to "one eighth",
    Regex("""\b3/8${DATE_GUARD}""") to "three eighths",
    Regex("""\b1/16${DATE_GUARD}""") to "one sixteenth",
)

private val UNIT_ABBREV_RULES: List<Pair<Regex, String>> = listOf(
    Regex("""\b[Tt]bsp\b""") to "tablespoon",
    Regex("""\b[Tt]sp\b""") to "teaspoon",
)

private fun normalizeFractionsForSpeech(text: String): String {
    // Step 1: Unicode fraction characters
    var result = text
        .replace("½", "half")
        .replace("¼", "quarter")
        .replace("¾", "three quarters")
        .replace("⅓", "one third")
        .replace("⅔", "two thirds")
        .replace("⅛", "one eighth")

    // Step 2: Mixed numbers (must precede simple fractions)
    result = MIXED_NUMBER_FRACTION_RULES.fold(result) { current, (pattern, replacement) ->
        pattern.replace(current, replacement)
    }

    // Step 3: Simple ASCII fractions
    result = SIMPLE_FRACTION_RULES.fold(result) { current, (pattern, replacement) ->
        pattern.replace(current, replacement)
    }

    // Step 4: Unit abbreviations
    result = UNIT_ABBREV_RULES.fold(result) { current, (pattern, replacement) ->
        pattern.replace(current, replacement)
    }

    return result
}

private fun applySpeechPronunciationOverrides(text: String): String =
    speechPronunciationRules.fold(text) { current, rule ->
        rule.pattern.replace(current) { match ->
            matchCase(match.value, rule.replacement)
        }
    }

private fun matchCase(source: String, replacement: String): String {
    val firstLetter = source.firstOrNull { it.isLetter() }
    return when {
        source.any { it.isLetter() } && source.filter(Char::isLetter).all(Char::isUpperCase) ->
            replacement.uppercase()
        firstLetter?.isUpperCase() == true ->
            replacement.replaceFirstChar { it.uppercase() }
        else -> replacement
    }
}

private fun shapeSpeechChunkForPlayback(
    text: String,
    boundaryType: SpeechChunkBoundaryType,
): String {
    val normalized = text.trim()
    if (normalized.isBlank()) return ""
    if (hasTerminalPause(normalized)) return normalized

    return when (boundaryType) {
        SpeechChunkBoundaryType.WHITESPACE -> appendSpeechPause(normalized, ",")
        SpeechChunkBoundaryType.FORCED -> appendSpeechPause(normalized, ".")
        SpeechChunkBoundaryType.STRONG,
        SpeechChunkBoundaryType.SOFT -> normalized
    }
}

private fun hasTerminalPause(text: String): Boolean =
    Regex("""[.!?,;:]["')\]]*$""").containsMatchIn(text)

private fun appendSpeechPause(text: String, punctuation: String): String {
    val trailingClosers = text.takeLastWhile { it == '"' || it == '\'' || it == ')' || it == ']' }
    if (trailingClosers.isEmpty()) return text + punctuation
    return text.removeSuffix(trailingClosers) + punctuation + trailingClosers
}

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
        "system info", "device info",
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

internal fun prefersImmediateConversationContext(text: String): Boolean {
    val lower = text.lowercase().trim()
    if (lower.length > 80) return false
    if (Regex("""^what\s+(?:time|date|day)\s+is\s+(?:it|today)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
        return false
    }
    return Regex(
        """^(?:what|who|how|why|when|where|which)\b.*\b(?:it|they|them|that|this|those|these)\b""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(lower)
}

private val CULTURAL_CONTEXT_CUE_REGEX = Regex(
    """\b(?:new\s+zealand|aotearoa|kiwi|kiwis|m[āa]ori|te\s+reo|n\.?z\.?)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * Returns true when [text] references New Zealand / Māori culture (e.g. "what's it called in
 * New Zealand?", "the Māori name", "in NZ"). Used to decide whether an otherwise
 * immediate-context follow-up should still pull the NZ cultural corpus (#kumara recall).
 */
internal fun hasCulturalContextCue(text: String): Boolean =
    CULTURAL_CONTEXT_CUE_REGEX.containsMatchIn(text)

private val BARE_WIKIPEDIA_ANAPHORA_REGEX = Regex(
    """^(?:it|this|that|these|those|him|her|them|there)\b(?:\s+(?:please|thanks))?$""",
    RegexOption.IGNORE_CASE,
)

internal fun extractExplicitWikipediaQuery(text: String): String? {
    val patterns = listOf(
        Regex("""^\s*(?:look\s+up|search)\s+wikipedia\s+(?:for\s+)?(.+?)\s*[?!.]*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*(?:look\s+up|search)\s+(.+?)\s+on\s+wikipedia\s*[?!.]*$""", RegexOption.IGNORE_CASE),
        Regex("""^\s*wikipedia\s+(.+?)\s*[?!.]*$""", RegexOption.IGNORE_CASE),
    )
    return patterns.firstNotNullOfOrNull { regex ->
        regex.matchEntire(text)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() && !BARE_WIKIPEDIA_ANAPHORA_REGEX.matches(it) }
    }
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
    val isContextualFollowUp = listOf(
        Regex("""\b(?:discuss|change|review)\s+(?:the\s+)?preferences?\b""", RegexOption.IGNORE_CASE),
        Regex("""\blet'?s\s+discuss\s+preferences?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhat(?:'s| is| are)?\s+(?:the\s+)?(?:meals?|recipes?|plan)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bshow\s+(?:me\s+)?(?:the\s+)?(?:meals?|recipes?|plan)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:dietary restrictions?|protein preferences?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:full recipes?|cooking steps|ingredients|shopping list)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:day\s+\d+|day\s+one|day\s+two|day\s+three|day\s+four|day\s+five|first day|next day)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:start over|try again|go back)\b""", RegexOption.IGNORE_CASE),
    ).any { it.containsMatchIn(lower) }
    if (!isContinuation && !isContextualFollowUp) return false

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

internal fun toolTurnInstruction(isFirstReply: Boolean): String? =
    if (isFirstReply) {
        null
    } else {
        "Do NOT start this reply with a greeting. This is a follow-up tool turn, so answer directly with the tool result."
    }

internal fun nonToolTurnInstruction(): String =
"This looks like a normal conversational or reasoning reply. Prefer answering directly from your own knowledge and reasoning. " +
        "Only call tools if the user is clearly asking for current, external, or retrieved information."

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
        // Calendar/diary hallucinations — "I've put that in the diary", "put it on your calendar"
        "i've put", "i have put", "put that in", "put it on your",
        // Torch/light state claims — "the light's on", "flashlight is on", etc.
        "the light's on", "the light's off", "lights are on", "lights are off",
        "torch is on", "torch is off", "flashlight is on", "flashlight is off",
        "i've lit", "i have lit",
        "done!", "all done", "got it, i've", "sure thing",
    )
    return actionPhrases.any { lower.contains(it) }
}

/**
 * Returns true when the model leaked raw tool-call syntax into chat instead of executing it.
 */
internal fun looksLikeRawToolCall(response: String): Boolean {
    if (response.contains("<|tool_call>") || response.contains("<tool_call|>")) return true

    val lower = response.lowercase()
    if (
        ("instructions:" in lower && "tool format:" in lower && "runjs(" in lower) ||
        Regex(
            """^[a-z_]+:\s+.*wikipedia""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
            .containsMatchIn(response)
    ) {
        return true
    }

    // Detect leaked skill instruction payloads (run_intent, load_skill output printed as text)
    if (
        "available intents:" in lower ||
        "parameters (pass as json" in lower ||
        ("run_intent:" in lower && "perform a native android" in lower) ||
        ("instructions:" in lower && "intent_name" in lower)
    ) return true

    return Regex(
        """\bcall:(?:load[_ ]?skill|run[_ ]?intent|run[_ ]?js|get[_ ]?weather|save[_ ]?memory|search[_ ]?memory|get[_ ]?system[_ ]?info)\b|
           \{\s*"name"\s*:\s*"(?:load_skill|run_intent|run_js|get_weather|save_memory|search_memory|get_system_info)"""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
    ).containsMatchIn(response)
}

/**
 * Returns true when [text] is an explicit anaphoric save-memory request such as
 * "remember that", "can you remember this", "save that", "keep that in memory" with
 * no factual content following the pronoun — i.e. the referent must be resolved from
 * the previous user turn.
 *
 * Used by ChatViewModel to short-circuit the LLM for the "I don't like aubergines" →
 * "Can you remember that" pattern (#958).
 */
internal fun isAnaphoricSaveRequest(text: String): Boolean {
    val lower = text.lowercase().trim()
    return Regex(
        """^(?:(?:can|could|would)\s+you\s+|please\s+)?(?:save|store|keep|remember)\s+(?:that|this|it)(?:\s+in\s+memory)?\s*[.!?]*$|
           ^(?:yes[,.]?\s+)?(?:please\s+)?remember\s+that\s*[.!?]*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
    ).containsMatchIn(lower)
}
private val IMPORTANT_DATE_FACT_RE = Regex(
    """^(?:my|our)\b.*\b(?:birthday|anniversary|wedding anniversary)\b|
       ^i\s+have\s+(?:a\s+)?(?:birthday|anniversary)\b|
       ^[\p{L}'’.-]+(?:'s)?\s+(?:birthday|anniversary)\b""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
)

private val IMPORTANT_DATE_VALUE_RE = Regex(
    """\b(?:
           \d{4}-\d{2}-\d{2}|
           \d{1,2}(?:st|nd|rd|th)?(?:\s+of)?\s+(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)|
           (?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+\d{1,2}(?:st|nd|rd|th)?
       )\b""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
)

internal fun looksLikeImportantDateFact(text: String): Boolean {
    if (text.isBlank()) return false
    val lower = text.lowercase().trim()
    return IMPORTANT_DATE_FACT_RE.containsMatchIn(lower) && IMPORTANT_DATE_VALUE_RE.containsMatchIn(lower)
}

/**
 * Returns true when [text] looks like a short concrete personal fact that a user would
 * reasonably want stored in memory — e.g. "I don't like aubergines", "My dog is called Biscuit".
 *
 * Filters out:
 * - Long generated content (recipes, lists)
 * - Questions
 * - Messages starting with assistant attribution
 */
internal fun looksLikePersonalFact(text: String): Boolean {
    if (text.isBlank() || text.length > 160) return false
    val lower = text.lowercase().trim()
    if (lower.endsWith("?")) return false
    if (looksLikeImportantDateFact(lower)) return false
    return Regex(
        """^i\s+(?:am|'m|have|love|hate|like|dislike|prefer|can'?t|cannot|am not)\b|
           ^i\s+(?:do\s+not|don'?t)\s+(?:like|love|hate|prefer|eat)\b|
           ^my\s+(?:name|dog|cat|partner|child|spouse|sibling|mother|father|brother|sister|son|daughter|husband|wife|girlfriend|boyfriend)\b|
           ^i\s+\w+\s+(?:allergic|intolerant|vegetarian|vegan|gluten|lactose)\b""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS),
    ).containsMatchIn(lower)
}

/**
 * Deterministically checks if [text] mentions a known NZ truth memory term.
 * Returns the first matching [JandalPersona.NzTruthEntry] from [nzTruths], or null if no match.
 *
 * This is used as a deterministic pre-model check so that known NZ/Māori cultural terms get
 * their seeded NZ context before the model can choose query_wikipedia (#1074).
 *
 * Matching is case-insensitive and uses word-boundary checks on the canonical term name.
 * After STT normalisation ([com.kernel.ai.core.voice.TranscriptNormaliser]), voice-input
 * aliases have already been replaced with canonical terms, so only canonical forms are matched.
 */
internal fun detectKnownNzTerm(
    text: String,
    nzTruths: List<JandalPersona.NzTruthEntry>,
): JandalPersona.NzTruthEntry? {
    if (text.isBlank()) return null
    val lower = text.lowercase()
    return nzTruths.firstOrNull { entry ->
        val term = entry.term.lowercase()
        term.length >= 3 && Regex("""\b${Regex.escape(term)}\b""").containsMatchIn(lower)
    }
}

/**
 * Builds a deterministic assistant reply from a seeded NZ truth entry.
 * Used by [com.kernel.ai.feature.chat.ChatViewModel] to answer known NZ/Māori
 * cultural terms locally without calling query_wikipedia or the inference engine.
 *
 * @param entry the matched NZ truth entry with term and definition.
 * @return a complete assistant reply string.
 */
internal fun buildKnownNzContextReply(entry: JandalPersona.NzTruthEntry): String {
    val definition = entry.definition.trim().trimEnd('.')
    return "${entry.term}: $definition."
}
