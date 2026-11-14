package com.kernel.ai.core.skills

import java.text.Normalizer

/**
 * Turns a failed contact lookup into a [SkillResult.Failure] that carries in-app recovery actions.
 *
 * `error` always carries the text-only guidance ("…in Settings → People & Contacts"), because chat
 * and model replies print only `error`. `actions` are extra buttons for surfaces that render them.
 * Every result sets `technicalDetail`, so chat and Quick Actions show `error` as written instead of
 * adding their "I tried…" / "Action failed:" prefixes.
 *
 * Three cases:
 * - Relationship words ("wife", "mum", "boss") get one action that opens the alias add flow with the
 *   word prefilled.
 * - Other names get add and picker actions.
 * - Near misses ("Laurel" for "Laurelle") get one suggestion per candidate. Choosing a suggestion
 *   opens People & Contacts with the user's query prefilled as the alias and the contact's name as a
 *   hint. The user still picks the contact and saves, so nothing is dialled without confirmation.
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

    /** Display name of a suggested contact, shown as a hint in the add flow. */
    const val ARG_SUGGESTED = "suggested"

    /** `"true"` when the failed request was a call, so the add flow may offer to dial after saving. */
    const val ARG_CALL = "call"

    /** Upper bound on suggestions offered for one failed lookup. */
    const val MAX_SUGGESTIONS = 3

    private const val GUIDANCE = "Add them in Settings → People & Contacts."

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

    /** Same leading words that `ContactAliasRepository.normalise` strips, so alias keys agree. */
    private val LEADING_DETERMINERS = listOf("my ", "the ")

    /**
     * Returns the relationship word for [query] ("my wife" -> "wife"), or null if [query] is not a
     * relationship word.
     */
    fun relationshipWordOf(query: String): String? =
        normaliseQuery(query).takeIf { it in RELATIONSHIP_WORDS }

    /**
     * Sound-alike key for one word. Approximates Double Metaphone for English-ish names:
     * `ph`→`f`, `ck`/`q`→`k`, soft `c`→`s`, `z`→`s`, `x`→`ks`, `h` dropped, then vowels (except
     * the first letter) and repeated letters collapsed.
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
     * Every query token must match some token of the candidate name, in any order, so "Smith"
     * finds "Laurelle Smith" and "Laurel" finds "Laurelle Smith". A token matches when:
     * - it is equal, or
     * - it starts with the same letter as the candidate token and is within an edit distance that
     *   scales with length: exact up to 6 letters, 1 edit for 7–8 letters, 2 edits for 9+, or
     * - one token is a prefix of the other, both are 4+ letters, and they share a [phoneticKey] of
     *   at least 3 consonants (Laurel / Laurelle).
     *
     * Sound-alikes without a shared prefix never match (Brian/Bruno, Robin/Ruben, Gordon/Garden,
     * John/Joan), and nor do different first letters (Rachel/Michel, Hannah/Anna).
     * Relationship words are not matched here; callers handle them first.
     */
    fun nearMatches(query: String, candidateNames: Collection<String>, limit: Int = MAX_SUGGESTIONS): List<String> {
        val queryTokens = tokenise(normaliseQuery(query))
        if (queryTokens.isEmpty()) return emptyList()
        return candidateNames
            .distinct()
            .mapNotNull { name ->
                val candidateTokens = tokenise(name)
                if (candidateTokens.isEmpty()) return@mapNotNull null
                var total = 0
                for (q in queryTokens) {
                    val best = candidateTokens
                        .filter { tokensMatch(q, it) }
                        .minOfOrNull { editDistance(q, it) }
                        ?: return@mapNotNull null
                    total += best
                }
                name to total
            }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .map { it.first }
            .take(limit)
    }

    /**
     * Builds the failure returned when [query] matches no contact.
     *
     * @param nearMatches display names from [nearMatches]; empty when there are none.
     * @param callRequested true when the failed request was a call (`make_call`).
     */
    fun notFoundResult(
        skillName: String,
        query: String,
        nearMatches: List<String> = emptyList(),
        callRequested: Boolean = false,
    ): SkillResult.Failure {
        val displayQuery = query.trim()
        val alias = normaliseQuery(query)
        val callArg = if (callRequested) mapOf(ARG_CALL to "true") else emptyMap()
        val technical = "Unresolved contact: $skillName"
        val addLink = SettingsDeepLink(
            destination = PEOPLE_DESTINATION,
            args = mapOf(ARG_ALIAS to alias, ARG_ADD to "true") + callArg,
        )
        val pickLink = SettingsDeepLink(
            destination = PEOPLE_DESTINATION,
            args = mapOf(ARG_ALIAS to alias, ARG_ADD to "true", ARG_PICK to "true") + callArg,
        )

        relationshipWordOf(query)?.let { word ->
            return SkillResult.Failure(
                skillName = skillName,
                error = "I don't know who your $word is yet. $GUIDANCE",
                technicalDetail = technical,
                actions = listOf(SettingsAction("Add $word in People & Contacts", addLink)),
            )
        }

        if (nearMatches.isNotEmpty()) {
            val single = nearMatches.size == 1
            val message = if (single) {
                "I couldn't find $displayQuery. Closest contact: ${nearMatches.first()}. $GUIDANCE"
            } else {
                "I couldn't find $displayQuery. Closest contacts: ${nearMatches.joinToString(" or ")}. $GUIDANCE"
            }
            val suggestions = nearMatches.map { name ->
                SettingsAction(
                    label = if (single) "Yes, $name" else name,
                    deepLink = SettingsDeepLink(
                        destination = PEOPLE_DESTINATION,
                        args = mapOf(ARG_ALIAS to alias, ARG_ADD to "true", ARG_SUGGESTED to name) + callArg,
                    ),
                )
            }
            return SkillResult.Failure(
                skillName = skillName,
                error = message,
                technicalDetail = technical,
                actions = suggestions + SettingsAction("Pick from contacts", pickLink),
            )
        }

        return SkillResult.Failure(
            skillName = skillName,
            error = "Couldn't find $displayQuery. $GUIDANCE",
            technicalDetail = technical,
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
        if (query.first() != candidate.first()) return false
        // Symmetric: the allowance depends on the shorter name, so A→B and B→A agree.
        val shorter = minOf(query.length, candidate.length)
        val maxDistance = when {
            shorter >= 10 -> 2
            shorter >= 7 -> 1
            else -> 0
        }
        if (maxDistance > 0 && editDistance(query, candidate) <= maxDistance) return true
        if (query.length < 4 || candidate.length < 4) return false
        val prefixRelated = candidate.startsWith(query) || query.startsWith(candidate)
        if (!prefixRelated) return false
        val key = phoneticKey(query)
        return key.length >= 3 && key == phoneticKey(candidate)
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
