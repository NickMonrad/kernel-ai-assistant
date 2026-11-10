package com.kernel.ai.core.skills

/** Resolves only explicitly stated item names for list-add requests containing a pronoun. */
object AnaphoricListAddResolver {

    sealed interface Result {
        data class Add(
            val items: List<String>,
            val listName: String,
            val answerPrefix: String? = null,
        ) : Result
        data class Ask(val question: String) : Result
    }

    private data class Request(val pronoun: String, val listName: String?)

    private val ANAPHORIC_ADD_RE = Regex(
        """\b(?:add|put)\s+(it|that|this|them|these|those)\s+(?:to|in|on)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LIST_NAME_RE = Regex(
        """\b(?:to|in|on)\s+(?:(?:my|the|a|an)\s+)?(?:(?<name>[\p{L}\p{N}][\p{L}\p{N}\s'-]*?)\s+)?list\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PLURAL_ITEMS_RE = Regex(
        """\bitems\s+(?:are|:|=)\s*(.*?)(?=\s*(?:,?\s*and\s+)?(?:add|put)\s+(?:it|that|this|them|these|those)\b|[.!?;]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val SINGLE_ITEM_RE = Regex(
        """\b(?:the\s+)?(?:only\s+)?item\s+(?:is|:|=)\s*(.*?)(?=\s*(?:,?\s*and\s+)?(?:add|put)\s+(?:it|that|this|them|these|those)\b|[.!?;]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val FAVOURITE_COFFEE_RE = Regex(
        """\bmy\s+favou?rite\s+coffee\s+(?:is|=)\s*(.*?)(?=\s*(?:,?\s*and\s+)?(?:add|put)\s+(?:it|that|this|them|these|those)\b|[.!?;\n]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val FAVOURITE_COFFEE_QUESTION_RE = Regex(
        """\bwhat(?:'s| is)\s+my\s+favou?rite\s+coffee\b""",
        RegexOption.IGNORE_CASE,
    )
    private val FILLER_RE = Regex(
        """^(?:an?\s+item|something|a\s+thing|it|that|this|them|these|those|one|some)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Returns null for other requests; anaphoric additions always resolve or ask. */
    fun needsMemoryLookup(userMessage: String, previousUserMessage: String?): Boolean =
        ANAPHORIC_ADD_RE.containsMatchIn(userMessage) &&
            FAVOURITE_COFFEE_QUESTION_RE.containsMatchIn(userMessage) &&
            explicitItems(userMessage) == null &&
            previousUserMessage?.let(::explicitItems) == null

    fun resolve(
        userMessage: String,
        previousUserMessage: String?,
        retrievedMemory: String? = null,
    ): Result? {
        val requestMatch = ANAPHORIC_ADD_RE.find(userMessage) ?: return null
        val request = Request(
            pronoun = requestMatch.groupValues[1].lowercase(),
            listName = LIST_NAME_RE.find(userMessage)
                ?.groups
                ?.get("name")
                ?.value
                ?.trim()
                ?.removePrefixIgnoreCase("my ")
                ?.removePrefixIgnoreCase("the ")
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it list" },
        )
        val items = explicitItems(userMessage)
            ?: previousUserMessage?.let(::explicitItems)
            ?: retrievedMemory?.let(::explicitItems)
        if (request.listName == null) {
            val target = if (request.pronoun in PLURAL_PRONOUNS) "those items" else "that item"
            return Result.Ask("Which list should I add $target to?")
        }
        if (items.isNullOrEmpty() || items.any { FILLER_RE.matches(it) }) {
            return Result.Ask(if (request.pronoun in PLURAL_PRONOUNS) "Which items do you mean?" else "Which item do you mean?")
        }
        val referentCountMatches = if (request.pronoun in PLURAL_PRONOUNS) items.size > 1 else items.size == 1
        if (!referentCountMatches) {
            return Result.Ask(if (request.pronoun in PLURAL_PRONOUNS) "Which items do you mean?" else "Which item do you mean?")
        }
        val answerPrefix = if (
            items.size == 1 && FAVOURITE_COFFEE_QUESTION_RE.containsMatchIn(userMessage)
        ) {
            "Your favourite coffee is ${items.single()}."
        } else {
            null
        }
        return Result.Add(items, request.listName, answerPrefix)
    }

    private fun explicitItems(text: String): List<String>? {
        val declarations = buildList {
            PLURAL_ITEMS_RE.findAll(text).forEach { add(splitItems(it.groupValues[1])) }
            SINGLE_ITEM_RE.findAll(text).forEach { match ->
                add(listOf(match.groupValues[1].trimItem()))
            }
            FAVOURITE_COFFEE_RE.findAll(text).forEach { match ->
                val item = match.groupValues[1].trimItem()
                    .removePrefixIgnoreCase("a ")
                    .removePrefixIgnoreCase("an ")
                add(listOf(item))
            }
        }.filter { it.isNotEmpty() }
            .distinct()
        return when (declarations.size) {
            0 -> null
            1 -> declarations.single()
            else -> emptyList()
        }
    }

    private fun splitItems(raw: String): List<String> {
        val clause = raw.trim().trimEnd(',', '.')
        if (clause.isBlank()) return emptyList()
        val commaParts = clause.split(Regex("\\s*,\\s*"))
        val parts = if (commaParts.size > 1) {
            commaParts.flatMap { it.trim().removePrefixIgnoreCase("and ").split(Regex("\\s+and\\s+")) }
        } else {
            clause.split(Regex("\\s+and\\s+"))
        }
        return parts.map { it.trimItem() }.filter(String::isNotBlank)
    }

    private fun String.trimItem(): String = trim().trim('"', '\'', '`').trim()

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this

    private val PLURAL_PRONOUNS = setOf("them", "these", "those")
}
