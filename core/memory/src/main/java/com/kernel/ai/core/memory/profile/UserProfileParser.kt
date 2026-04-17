package com.kernel.ai.core.memory.profile

/**
 * Heuristic parser that extracts structured profile fields from free-text.
 *
 * This is a fast, deterministic first pass. An optional background LLM pass
 * can refine the results later (#374 Phase 2b).
 *
 * Patterns recognised:
 * - Name: "My name is X", "I'm X", "I am X", "Name: X", "Call me X"
 * - Location: "I live in X", "I'm from X", "Location: X", "Based in X"
 * - Role: "I'm a/an X", "I work as X", "Role: X", "I am a X developer/engineer/..."
 * - Environment: "I use X", "I have X", "running X", device/OS/tool mentions
 * - Hobbies: "I play X", "I game on X", "I cook X", "My hobbies include X"
 * - Smart Home: "I use Home Assistant", "My smart home", "I have smart lights"
 * - AI Tools: "I use Copilot", "I prefer local models", "Prioritize local-first"
 * - Rules: "I prefer X", "I like X", "always X", "never X", "don't X", "Prioritize X", "When providing X"
 * - Context: Sentences that don't match the above but contain useful info
 */
object UserProfileParser {

    private val NAME_PATTERNS = listOf(
        // Inline (?i:...) on prefix only — [A-Z] remains strict uppercase so "I'm an Android dev" won't match "an"
        Regex("""(?i:my name is|call me|name:\s*)\s*([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)?)"""),
        // Strict uppercase [A-Z] (no (?i) flag here) prevents matching "I'm an X" → "an"
        Regex("""(?i:i'm|i am)\s+([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)?)"""),
        // Informal: "Nick here" or "This is Nick"
        Regex("""^([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)?)\s+here\b"""),
        Regex("""(?i:this is|it's|hey[,]?\s+i'm)\s+([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)?)"""),
    )

    private val LOCATION_PATTERNS = listOf(
        // Explicit "Location:" label — capture full value until period or newline (NOT comma, so "Brisbane, QLD, Australia" is preserved)
        Regex("""(?i:location:\s*)(.+?)(?:\.|$)"""),
        // Prose patterns — stop before relative clauses (who/which/that/where)
        Regex("""(?i)\b(?:i live in|i'm from|i am from|i'm located in|i'm based in|my (?:city|town|location) is)\s+(.+?)(?:\s+who\b|\s+which\b|\s+that\b|\s+where\b|\.|$)"""),
        Regex("""(?i)\b(?:based in|located in)\s+(.+?)(?:\s+who\b|\s+which\b|\s+that\b|\s+where\b|\.|$)"""),
    )

    private val ROLE_PATTERNS = listOf(
        Regex("""(?i)\b(?:i'm an?|i am an?|i work as an?|role:\s*)\s+(.+?)(?:\.|$)"""),
    )

    private val ROLE_KEYWORDS = setOf(
        "developer", "engineer", "designer", "manager", "analyst", "architect",
        "consultant", "student", "researcher", "programmer", "admin", "devops",
        "writer", "teacher", "professional", "specialist",
        // Common abbreviations and additional titles
        "technologist", "dev", "eng", "cto", "ceo", "vp", "pm", "tpm", "sre", "qa",
        "director", "lead", "principal", "founder", "freelancer", "contractor",
    )

    private val ENVIRONMENT_PATTERNS = listOf(
        Regex("""(?i)\b(?:i use|i have|i run|i'm (?:on|using)|running|device:\s*)\s+(.+?)(?:\.|$)"""),
        // Section-header style: "Systems: X", "Hardware: X", "Network: X", "Homelab: X", "Local AI: X"
        Regex("""(?i)^(?:systems?|hardware|network|homelab|local ai|ai tools?|devices?):\s*(.+)"""),
    )

    private val RULE_PATTERNS = listOf(
        Regex("""(?i)\b(?:i prefer|i like|i want|always|never|don'?t|do not|please)\s+(.+?)(?:\.|$)"""),
        Regex("""(?i)\b(?:prioritize|when providing|when asked about|default to|assume)\s+(.+?)(?:\.|$)"""),
        // Imperative commands: "Set my X to Y", "Use X for Y"
        Regex("""(?i)^(?:set my|keep .+ (?:as|at|on)|use .+ for)\s+(.+?)(?:\.|$)"""),
        // Section-header tone/style instructions: "Tone: Prefers X", "Style: X", "AI Instruction Hook: X"
        Regex("""(?i)^(?:tone|style|ai instruction\s*hook|instructions?):\s*(.+)"""),
        // Third-person preferences: "Prefers concise...", "Avoids..."
        Regex("""(?i)^(?:prefers?|avoids?|expects?)\s+(.+?)(?:\.|$)"""),
    )

    private val HOBBY_PATTERNS = listOf(
        Regex("""(?i)\b(?:i play|i game on|i cook|i enjoy|my hobbies|hobby:|hobbies:|i'm into|i practice)\s+(.+?)(?:\.|$)"""),
        // Section-header hobbies: "Gaming: X", "Cooking: X", "Reading: X"
        Regex("""(?i)^(?:gaming|cooking|reading|sports?|music):\s*(.+)"""),
    )

    private val SMART_HOME_PATTERNS = listOf(
        Regex("""(?i)\b(?:i use home assistant|my smart home|i have smart (?:lights?|plugs?|switches?|home|devices?|speakers?|displays?|sensors?|locks?|thermostat)|smart home setup)\s*(.+?)(?:\.|$)"""),
        // Section-header: "Smart Home: X"
        Regex("""(?i)^(?:smart home|home automation):\s*(.+)"""),
    )

    private val AI_PATTERNS = listOf(
        Regex("""(?i)\b(?:i use (?:copilot|chatgpt|claude|gpt|ai)|my ai tools?|i prefer (?:local models?|open[- ]?source)|prioritize local[- ]?first)\s*(.+?)(?:\.|$)"""),
    )

    fun parse(freeText: String): UserProfileYaml {
        if (freeText.isBlank()) return UserProfileYaml()

        var name: String? = null
        var role: String? = null
        var location: String? = null
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

        // Pass 1b: Extract location (don't consume sentence if it also contains role info)
        for ((i, sentence) in sentences.withIndex()) {
            if (i in consumed) continue
            if (location != null) break
            for (pattern in LOCATION_PATTERNS) {
                val match = pattern.find(sentence)
                if (match != null) {
                    location = match.groupValues[1].trim().removeSuffix(".").removeSuffix(",")
                    // Only consume if sentence doesn't also contain role keywords
                    val lowerSentence = sentence.lowercase()
                    if (!ROLE_KEYWORDS.any { lowerSentence.contains(it) }) {
                        consumed.add(i)
                    }
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
                            .replace(Regex("""(?i)\s*(?:,\s*)?(?:based|located) in\s+.+$"""), "").trim()
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

            // Try AI patterns first (add to rules)
            for (pattern in AI_PATTERNS) {
                val match = pattern.find(sentence)
                if (match != null) {
                    rules.add(sentence.trim().removeSuffix("."))
                    consumed.add(i)
                    matched = true
                    break
                }
            }
            if (matched) continue

            // Try rule patterns
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

            // Try hobby patterns (add to context)
            for (pattern in HOBBY_PATTERNS) {
                val match = pattern.find(sentence)
                if (match != null) {
                    context.add(sentence.trim().removeSuffix("."))
                    consumed.add(i)
                    matched = true
                    break
                }
            }
            if (matched) continue

            // Try smart home patterns (add to environment)
            for (pattern in SMART_HOME_PATTERNS) {
                val match = pattern.find(sentence)
                if (match != null) {
                    environment.add(sentence.trim().removeSuffix("."))
                    consumed.add(i)
                    matched = true
                    break
                }
            }
            if (matched) continue

            // Try environment patterns
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
            location = location,
            environment = environment.take(25),
            context = context.take(25),
            rules = rules.take(25),
        )
    }
}
