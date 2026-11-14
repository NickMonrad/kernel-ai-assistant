package com.kernel.ai.core.skills.natives

/**
 * Confirmation sentences for list add/remove replies (#1871).
 *
 * The same string is shown on screen and spoken by TTS, so each sentence is a complete clause
 * with terminal punctuation and no quoting or colon-separated item lists. TTS reads a full stop
 * as a pause before the list name, so the item never runs into "your <list>".
 */
internal object ListConfirmationText {

    /** "Added milk to your shopping list." / "Added milk, bread and eggs to your shopping list." */
    fun added(items: List<String>, listName: String): String =
        "Added ${joinItems(items)} to your $listName."

    /** "Removed milk from your shopping list." */
    fun removed(item: String, listName: String): String =
        "Removed ${speakableItem(item)} from your $listName."

    /** "I couldn't find milk in your shopping list." */
    fun notFound(item: String, listName: String): String =
        "I couldn't find ${speakableItem(item)} in your $listName."

    private fun joinItems(items: List<String>): String {
        val names = items.map(::speakableItem)
        return when (names.size) {
            0 -> ""
            1 -> names.single()
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    /**
     * Trims whitespace and trailing sentence punctuation so an item such as "milk." or "eggs,"
     * does not produce a doubled or misplaced pause inside the confirmation.
     */
    private fun speakableItem(item: String): String =
        item.trim().trimEnd('.', ',', ';', ':', '!', '?').trim()
}
