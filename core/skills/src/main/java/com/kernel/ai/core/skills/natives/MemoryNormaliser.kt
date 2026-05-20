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
        .replace(Regex("""\bI do\b""", RegexOption.IGNORE_CASE)) { "$name does" }
        // Common regular verbs — append "s" for third-person singular.
        .replace(
            Regex(
                """\bI (prefer|like|want|love|hate|enjoy|use|eat|drink|play|watch|listen|read|own|know|think|believe|feel|speak|drive|live|work|run|support|follow|need|find|see|hear|make|take|keep|put|get|give|bring|buy|sell|build|create|write|design|test|code|manage|lead|help|try|start|stop|send|show|check|set|turn|open|close|hold|leave|move|stay|say|ask|tell|call|visit)\b""",
                RegexOption.IGNORE_CASE,
            ),
        ) { match -> "$name ${match.groupValues[1].lowercase()}s" }
        // Catch-all: remaining "I" subject (verb may not conjugate perfectly, acceptable trade-off).
        .replace(Regex("""\bI\b""")) { name }
        // Possessives.
        .replace(Regex("""\bmy\b""", RegexOption.IGNORE_CASE)) { "${name}'s" }
}
