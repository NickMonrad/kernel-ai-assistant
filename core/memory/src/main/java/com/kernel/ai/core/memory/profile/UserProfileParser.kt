package com.kernel.ai.core.memory.profile

/**
 * Heuristic parser that extracts structured profile fields from free-text.
 *
 * This is a fast, deterministic first pass. An optional background LLM pass
 * can refine the results later (#374 Phase 2b).
 *
 * Patterns recognised:
 * - Name: "My name is X", "I'm X", "I am X", "Name: X", "Call me X"
 * - Role: "I'm a/an X", "I work as X", "Role: X", "I am a X developer/engineer/..."
 * - Environment: "I use X", "I have X", "running X", device/OS/tool mentions
 * - Rules: "I prefer X", "I like X", "always X", "never X", "don't X"
 * - Context: Sentences that don't match the above but contain useful info
 */
object UserProfileParser {

    private val NAME_PATTERNS = listOf(
        Regex("""(?i)\b(?:my name is|i'm|i am|call me|name:\s*)\s*([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)?)"""),
    )

    private val ROLE_PATTERNS = listOf(
        Regex("""(?i)\b(?:i'm an?|i am an?|i work as an?|role:\s*)\s+(.+?)(?:\.|$)"""),
    )

    private val ROLE_KEYWORDS = setOf(
        "developer", "engineer", "designer", "manager", "analyst", "architect",
        "consultant", "student", "researcher", "programmer", "admin", "devops",
        "writer", "teacher", "professional", "specialist",
    )

    private val ENVIRONMENT_PATTERNS = listOf(
        Regex("""(?i)\b(?:i use|i have|i run|i'm (?:on|using)|running|device:\s*)\s+(.+?)(?:\.|$)"""),
    )

    private val RULE_PATTERNS = listOf(
        Regex("""(?i)\b(?:i prefer|i like|i want|always|never|don'?t|please)\s+(.+?)(?:\.|$)"""),
    )

    fun parse(freeText: String): UserProfileYaml {
        if (freeText.isBlank()) return UserProfileYaml()

        var name: String? = null
        var role: String? = null
        val environment = mutableListOf<String>()
        val context = mutableListOf<String>()
        val rules = mutableListOf<String>()

        // Split into sentences
        val sentences = freeText.split(Regex("""(?<=[.!?])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val consumed = mutableSetOf<Int>()

        // Pass 1: Extract name
        for ((i, sentence) in sentences.withIndex()) {
            if (name != null) break
            for (pattern in NAME_PATTERNS) {
                val match = pattern.find(sentence)
                if (match != null) {
                    name = match.groupValues[1].trim()
                    consumed.add(i)
                    break
                }
            }
        }

        // Pass 2: Extract role
        for ((i, sentence) in sentences.withIndex()) {
            if (i in consumed) continue
            if (role != null) break
            // Check if sentence mentions a role keyword
            val lowerSentence = sentence.lowercase()
            val hasRoleKeyword = ROLE_KEYWORDS.any { lowerSentence.contains(it) }
            if (hasRoleKeyword) {
                for (pattern in ROLE_PATTERNS) {
                    val match = pattern.find(sentence)
                    if (match != null) {
                        role = match.groupValues[1].trim().removeSuffix(".")
                        consumed.add(i)
                        break
                    }
                }
            }
        }

        // Pass 3: Extract environment and rules
        for ((i, sentence) in sentences.withIndex()) {
            if (i in consumed) continue
            var matched = false

            for (pattern in RULE_PATTERNS) {
                val match = pattern.find(sentence)
                if (match != null) {
                    rules.add(sentence.trim().removeSuffix("."))
                    consumed.add(i)
                    matched = true
                    break
                }
            }
            if (matched) continue

            for (pattern in ENVIRONMENT_PATTERNS) {
                val match = pattern.find(sentence)
                if (match != null) {
                    environment.add(match.groupValues[1].trim().removeSuffix("."))
                    consumed.add(i)
                    matched = true
                    break
                }
            }
            if (matched) continue
        }

        // Pass 4: Everything else goes to context
        for ((i, sentence) in sentences.withIndex()) {
            if (i in consumed) continue
            if (sentence.length > 5) {
                context.add(sentence.trim().removeSuffix("."))
            }
        }

        return UserProfileYaml(
            name = name,
            role = role,
            environment = environment.take(10),
            context = context.take(10),
            rules = rules.take(10),
        )
    }
}
