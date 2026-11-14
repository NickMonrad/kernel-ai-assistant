package com.kernel.ai.core.skills.natives

/**
 * Sentences for list add/remove/read replies (#1871).
 *
 * The full sentence from [added] / [removed] / [notFound] / [listed] is shown on screen. Items
 * are joined with commas and a final "and", so an item never runs straight into "your <list>".
 * Each sentence ends with a full stop, which the TTS chunker treats as a pause.
 *
 * Spoken forms ([spokenAdded], [spokenListed]) are capped at [SPOKEN_BUDGET] characters. That
 * matches the 180-char cut in the Actions voice path (`ActionsViewModel.toSpokenSummary`), so a
 * spoken reply is never truncated mid-sentence. When the full sentence does not fit, they keep
 * the list name and summarise with a count.
 *
 * Known ambiguity: an item such as "salt and pepper" is joined like two items, so the sentence
 * can read as "salt and pepper" for either one item or two. Items are stored as typed, so this
 * is not corrected here.
 */
internal object ListConfirmationText {

    /** Maximum length of any spoken confirmation or list read. Matches the Actions 180-char cut. */
    const val SPOKEN_BUDGET = 180

    /** "Added milk to your shopping list." / "Added milk, bread and eggs to your shopping list." */
    fun added(items: List<String>, listName: String): String {
        val names = speakableNames(items)
        return if (names.isEmpty()) {
            "No items added to your $listName."
        } else {
            "Added ${joinWithAnd(names)} to your $listName."
        }
    }

    /**
     * Spoken form of [added]. Uses the full sentence when it fits [SPOKEN_BUDGET], otherwise
     * "Added 1 item to your shopping list." / "Added 10 items to your shopping list.", which keeps
     * the list name.
     */
    fun spokenAdded(items: List<String>, listName: String): String {
        val names = speakableNames(items)
        val full = added(names, listName)
        if (full.length <= SPOKEN_BUDGET) return full
        return "Added ${countOf(names.size)} to your $listName."
    }

    /** "Removed milk from your shopping list." */
    fun removed(item: String, listName: String): String =
        "Removed ${speakableItem(item)} from your $listName."

    /** "I couldn't find milk in your shopping list." */
    fun notFound(item: String, listName: String): String =
        "I couldn't find ${speakableItem(item)} in your $listName."

    /**
     * Spoken list read. Names as many items as fit [SPOKEN_BUDGET], then counts the rest:
     * "Your shopping list has 3 items: milk, eggs and bread."
     * "Your shopping list has 12 items: Grocery item 01, ..., Grocery item 08 and 4 more."
     *
     * [rawItems] is the stored list. The count is always `rawItems.size`, so it agrees with the
     * on-screen header and the stored list. Only the names are filtered (see [speakableName]).
     */
    fun spokenListed(listName: String, rawItems: List<String>): String {
        val count = rawItems.size
        if (count == 0) return "Your $listName is empty."
        val head = "Your $listName has ${countOf(count)}"
        val names = rawItems.mapNotNull(::speakableName)
        for (named in names.size downTo 0) {
            val shown = names.take(named)
            val rest = count - named
            val text = when {
                named == 0 -> "$head."
                rest == 0 -> "$head: ${joinWithAnd(shown)}."
                else -> "$head: ${joinWithAnd(shown + "$rest more")}."
            }
            if (text.length <= SPOKEN_BUDGET) return text
        }
        return "$head."
    }

    /**
     * Display/speech form of a stored item, or null when it has no letters or digits (for example
     * "." or "-"). Callers drop null items from display; counts still come from the stored list.
     */
    fun speakableName(item: String): String? =
        speakableItem(item).takeIf { it.any(Char::isLetterOrDigit) }

    private fun countOf(n: Int): String = if (n == 1) "1 item" else "$n items"

    private fun speakableNames(items: List<String>): List<String> = items.mapNotNull(::speakableName)

    private fun joinWithAnd(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names.single()
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** Trims whitespace and trailing sentence punctuation. */
    private fun speakableItem(item: String): String =
        item.trim().trimEnd('.', ',', ';', ':', '!', '?').trim()
}
