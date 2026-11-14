 package com.kernel.ai.core.skills.natives

/**
 * Sentences for list add/remove/read replies (#1871).
 *
 * The full sentence from [added] / [removed] / [notFound] is shown on screen. Its items are
 * joined with commas and a final "and", so an item never runs straight into "your <list>".
 * Each sentence ends with a full stop, which the TTS chunker treats as a pause.
 *
 * Voice output uses the `spokenSummary` forms ([spokenAdded], [spokenListed]) when the full
 * text would be long: they summarise the count instead of naming every item, so the
 * 180-character fallback in the Actions voice path never cuts a sentence mid-list.
 *
 * Known ambiguity: an item such as "salt and pepper" is joined like two items, so the sentence
 * can read as "salt and pepper" for either one item or two. Items are stored as typed, so this
 * is not corrected here.
 */
internal object ListConfirmationText {

    /** Above this many items the spoken form summarises instead of naming each item. */
    const val SPOKEN_ITEM_LIMIT = 4

    /** "Added milk to your shopping list." / "Added milk, bread and eggs to your shopping list." */
    fun added(items: List<String>, listName: String): String {
        val names = speakableItems(items)
        return if (names.isEmpty()) {
            "No items added to your $listName."
        } else {
            "Added ${joinWithAnd(names)} to your $listName."
        }
    }

    /**
     * "Added milk, bread and eggs to your shopping list." for up to [SPOKEN_ITEM_LIMIT] items,
     * otherwise "Added 10 items to your shopping list."
     */
    fun spokenAdded(items: List<String>, listName: String): String {
        val names = speakableItems(items)
        return if (names.size > SPOKEN_ITEM_LIMIT) {
            "Added ${names.size} items to your $listName."
        } else {
            added(names, listName)
        }
    }

    fun removed(item: String, listName: String): String =
        "Removed ${speakableItem(item)} from your $listName."

    /** "I couldn't find milk in your shopping list." */
    fun notFound(item: String, listName: String): String =
        "I couldn't find ${speakableItem(item)} in your $listName."

    /**
     * "Your shopping list has 3 items: milk, eggs and bread." For more than [SPOKEN_ITEM_LIMIT]
     * items the first [SPOKEN_ITEM_LIMIT] are named and the rest are counted:
     * "Your shopping list has 10 items: milk, eggs, bread, rice and 6 more."
     */
    fun spokenListed(listName: String, items: List<String>): String {
        val names = speakableItems(items)
        return when {
            names.isEmpty() -> "Your $listName is empty."
            names.size == 1 -> "Your $listName has 1 item: ${names.single()}."
            names.size <= SPOKEN_ITEM_LIMIT ->
                "Your $listName has ${names.size} items: ${joinWithAnd(names)}."
            else -> {
                val remaining = names.size - SPOKEN_ITEM_LIMIT
                val named = names.take(SPOKEN_ITEM_LIMIT) + "$remaining more"
                "Your $listName has ${names.size} items: ${joinWithAnd(named)}."
            }
        }
    }

    private fun joinWithAnd(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names.single()
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /**
     * Trims whitespace and trailing sentence punctuation, and drops items with no letters or
     * digits (for example "." or "-") so they never become empty words in a confirmation.
     */
    private fun speakableItems(items: List<String>): List<String> =
        items.map(::speakableItem).filter { it.any(Char::isLetterOrDigit) }

    private fun speakableItem(item: String): String =
        item.trim().trimEnd('.', ',', ';', ':', '!', '?').trim()
}
