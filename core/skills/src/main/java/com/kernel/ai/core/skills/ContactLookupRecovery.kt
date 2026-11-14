package com.kernel.ai.core.skills

import java.text.Normalizer

/**
 * Turns a failed contact lookup into a [SkillResult.Failure] that carries in-app recovery actions.
 *
 * Three cases:
 * - Relationship words ("wife", "mum", "boss") get "I don't know who your wife is yet." and one
 *   action that opens the alias add flow with the word prefilled.
 * - Other names get "Couldn't find Sam." with add and picker actions.
 * - Sound-alike near misses ("Laurel" for "Laurelle") get one suggestion per candidate. Choosing a
 *   suggestion only opens People & Contacts with that contact prefilled. The user confirms by
 *   saving the alias, so nothing is dialled without confirmation.
 *
 * This object is pure Kotlin with no Android dependencies, so it is unit-testable.
 */
object ContactLookupRecovery {
    /** Destination id for People & Contacts in [SettingsDeepLink.destination]. */
    const val PEOPLE_DESTINATION = "people_contacts"

    /** Prefilled alias text for the add flow. */
    const val ARG_ALIAS = "alias"

    /** `"true"` opens the add-alias dialog. */
    const val ARG_ADD = "add"

    /** `"true"` opens the system contact picker straight away. */
    const val ARG_PICK = "pick"

    /** Display name of a suggested contact. The add flow resolves it to a number for review. */
    const val ARG_SUGGESTED = "suggested"

    /** Upper bound on suggestions offered for one failed lookup. */
    const val MAX_SUGGESTIONS = 3

    private val RELATIONSHIP_WORDS = setOf(
        "wife", "husband", "partner", "girlfriend", "boyfriend", "fiance", "fiancee",
        "mum", "mom", "mother", "mam", "mama", "mummy", "mumma",
        "dad", "father", "pop", "papa", "daddy",
        "son", "daughter", "brother", "sister", "bro", "sis",
        "boss", "manager", "colleague",
        "nan", "nana", "nanny", "grandma", "grandmother", "granny", "grandad", "grandpa", "grandfather",
        "aunt", "auntie", "uncle", "cousin", "nephew", "niece",
        "flatmate", "roommate",
    )

    private val LEADING_DETERMINERS = listOf("my ", "the ", "our ", "your ")

    /**
     * Returns the relationship word for [query] ("my wife" -> "wife"), or null if [query] is not a
     * relationship word.
     */
    fun relationshipWordOf(query: String): String? =
        normaliseQuery(query).takeIf { it in RELATIONSHIP_WORDS }

    /**
     * Sound-alike key for one word. Approximates Double Metaphone for the English-ish names in a
     * contact list: `ph`→`f`, `ck`/`q`→`k`, soft `c`→`s`, `z`→`s`, `x`→`ks`, `h` dropped, then
     * vowels (except the first letter) and repeated letters collapsed.
     * Examples: Laurel and Laurelle → "lrl"; Shaun and Sean → "sn"; Katherine and Kathryn → "ktrn".
     */
    fun phoneticKey(word: String): String {
        val letters = stripMarks(word.lowercase()).filter { it in 'a'..'z' }
        val mapped = letters
            .replace("ph", "f")
            .replace("ck", "k")
            .replace("q", "k")
            .replace("z", "s")
            .replace("x", "ks")
            .replace("h", "")
            .replace(Regex("c(?=[eiy])"), "s")
            .replace("c", "k")
        if (mapped.isEmpty()) return ""
        val head = mapped.first()
        val tail = mapped.drop(1).filter { it !in "aeiouy" }
        val out = StringBuilder()
        for (c in head + tail) {
            if (out.isEmpty() || out.last() != c) out.append(c)
        }
        return out.toString()
    }

    /**
     * Contact display names that plausibly match [query], best first, at most [limit].
     *
     * Tokens are compared pairwise from the start of each name, so "Laurel" matches "Laurelle
     * Smith". A token matches when it is equal, within a small edit distance (2 for 6+ letters, 1
     * for 4–5, 0 for shorter words), or has the same [phoneticKey] (4+ letters). Relationship words
     * are not matched here; callers handle them first.
     */
    fun nearMatches(query: String, candidateNames: Collection<String>, limit: Int = MAX_SUGGESTIONS): List<String> {
        val queryTokens = tokenise(normaliseQuery(query))
        if (queryTokens.isEmpty()) return emptyList()
        return candidateNames
            .distinct()
            .mapNotNull { name ->
                val candidateTokens = tokenise(name)
                if (candidateTokens.size < queryTokens.size) return@mapNotNull null
                val pairs = queryTokens.indices.map { queryTokens[it] to candidateTokens[it] }
                if (!pairs.all { (q, c) -> tokensMatch(q, c) }) return@mapNotNull null
                val distance = pairs.sumOf { (q, c) -> editDistance(q, c) }
                name to distance
            }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .map { it.first }
            .take(limit)
    }

    /**
     * Builds the failure returned when [query] matches no contact.
     *
     * @param nearMatches display names from [nearMatches]; empty when there are none.
     */
    fun notFoundResult(
        skillName: String,
        query: String,
        nearMatches: List<String> = emptyList(),
    ): SkillResult.Failure {
        val displayQuery = query.trim()
        val alias = normaliseQuery(query)
        val addLink = SettingsDeepLink(
            destination = PEOPLE_DESTINATION,
            args = mapOf(ARG_ALIAS to alias, ARG_ADD to "true"),
        )
        val pickLink = SettingsDeepLink(
            destination = PEOPLE_DESTINATION,
            args = mapOf(ARG_ALIAS to alias, ARG_ADD to "true", ARG_PICK to "true"),
        )

        relationshipWordOf(query)?.let { word ->
            return SkillResult.Failure(
                skillName = skillName,
                error = "I don't know who your $word is yet.",
                actions = listOf(SettingsAction("Add $word in People & Contacts", addLink)),
            )
        }

        if (nearMatches.isNotEmpty()) {
            val single = nearMatches.size == 1
            val message = if (single) {
                "I couldn't find $displayQuery. Did you mean ${nearMatches.first()}?"
            } else {
                "I couldn't find $displayQuery. Did you mean one of these?"
            }
            val suggestions = nearMatches.map { name ->
                SettingsAction(
                    label = if (single) "Yes, $name" else name,
                    deepLink = SettingsDeepLink(
                        destination = PEOPLE_DESTINATION,
                        args = mapOf(ARG_ALIAS to alias, ARG_ADD to "true", ARG_SUGGESTED to name),
                    ),
                )
            }
            return SkillResult.Failure(
                skillName = skillName,
                error = message,
                actions = suggestions + SettingsAction("Pick from contacts", pickLink),
            )
        }

        return SkillResult.Failure(
            skillName = skillName,
            error = "Couldn't find $displayQuery.",
            actions = listOf(
                SettingsAction("Add in People & Contacts", addLink),
                SettingsAction("Pick from contacts", pickLink),
            ),
        )
    }

    private fun normaliseQuery(query: String): String {
        var text = query.trim().lowercase().replace(Regex("\\s+"), " ")
        for (determiner in LEADING_DETERMINERS) {
            if (text.startsWith(determiner)) {
                text = text.removePrefix(determiner).trim()
                break
            }
        }
        return text
    }

    private fun tokenise(text: String): List<String> =
        stripMarks(text.lowercase()).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

    private fun stripMarks(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun tokensMatch(query: String, candidate: String): Boolean {
        if (query == candidate) return true
        val maxDistance = when {
            query.length >= 6 -> 2
            query.length >= 4 -> 1
            else -> 0
        }
        if (editDistance(query, candidate) <= maxDistance) return true
        return query.length >= 4 && candidate.length >= 4 &&
            phoneticKey(query) == phoneticKey(candidate)
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
