package com.kernel.ai.core.skills.natives

/**
 * Converts first-person pronouns in a memory content string to third-person using the given name.
 *
 * Example: normaliseSaveContent("I prefer tea over coffee", "Nick")
 *       → "Nick prefers tea over coffee"
 *
 * Applied on both the regex (NativeIntentHandler) and E4B (SaveMemorySkill) code paths so that
 * saved facts are always in third person regardless of how the save was triggered.
 *
 * Returns [raw] unchanged when [userName] is null or blank.
 */
internal fun normaliseSaveContent(raw: String, userName: String?): String {
    if (userName.isNullOrBlank()) return raw
    val name = userName.trim()
    return raw
        // Specific irregular / special-case verbs first (order matters — more specific before general).
        .replace(Regex("""\bI(?:'m| am)\b""", RegexOption.IGNORE_CASE)) { "$name is" }
        .replace(Regex("""\bI have\b""", RegexOption.IGNORE_CASE)) { "$name has" }
        .replace(Regex("""\bI go\b""", RegexOption.IGNORE_CASE)) { "$name goes" }
        // Negation before bare "do": "I don't" → "Nick doesn't" (must precede "I do" → "Nick does")
        .replace(Regex("""\bI don't\b""", RegexOption.IGNORE_CASE)) { "$name doesn't" }
        .replace(Regex("""\bI do\b""", RegexOption.IGNORE_CASE)) { "$name does" }
        // -ch/-sh/-x/-o endings need "-es" (not "-s")
        .replace(Regex("""\bI watch\b""", RegexOption.IGNORE_CASE)) { "$name watches" }
        // "-y" after consonant → "-ies"
        .replace(Regex("""\bI try\b""", RegexOption.IGNORE_CASE)) { "$name tries" }
        // Common regular verbs — append "s" for third-person singular.
        .replace(
            Regex(
                """\bI (prefer|like|want|love|hate|enjoy|use|eat|drink|play|listen|read|own|know|think|believe|feel|speak|drive|live|work|run|support|follow|need|find|see|hear|make|take|keep|put|get|give|bring|buy|sell|build|create|write|design|test|code|manage|lead|help|start|stop|send|show|check|set|turn|open|close|hold|leave|move|stay|say|ask|tell|call|visit)\b""",
                RegexOption.IGNORE_CASE,
            ),
        ) { match -> "$name ${match.groupValues[1].lowercase()}s" }
        // Catch-all: remaining "I" subject — require at least one more token to avoid storing bare name
        // (e.g. a truncated "remember that I" → content="I" would otherwise save the username alone).
        .replace(Regex("""\bI(?=\s+\S)""")) { name }
        // Possessives.
        .replace(Regex("""\bmy\b""", RegexOption.IGNORE_CASE)) { "${name}'s" }
}
