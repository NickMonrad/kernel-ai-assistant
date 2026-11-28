package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.entity.ListItemEntity

/** Enter-adds after which the quick-entry hint retires without a dismissal. */
internal const val QUICK_ENTRY_HINT_RETIRE_AFTER_ENTER_ADDS = 3

/** Hint shown under the item box before the first Enter-add. */
internal const val QUICK_ENTRY_HINT_TEXT = "Press Enter to add this and type the next one"

private val LIST_LINE_BREAK = Regex("\r\n|\r|\n")
private val LEADING_LIST_MARKER = Regex("^\\s*(?:[-*•·–]|\\d{1,3}[.)])\\s+")

/**
 * Add-flow behaviour for [ItemDetailsSheet]. Null for Edit, which keeps its multi-line name.
 *
 * [onAddNext] adds one item and leaves the sheet open; [onEnterAdded] is reported only for
 * Enter-adds, because those count toward hint retirement.
 */
internal data class ItemQuickEntry(
    val hintRetired: Boolean,
    val onAddNext: (ListItemEntity) -> Unit,
    val onEnterAdded: () -> Unit,
    val onHintDismissed: () -> Unit,
)

/** Whether the Enter hint is hidden for good: dismissed, or Enter has been used on enough adds. */
internal fun quickEntryHintRetired(enterAdds: Int, dismissed: Boolean): Boolean =
    dismissed || enterAdds >= QUICK_ENTRY_HINT_RETIRE_AFTER_ENTER_ADDS

/** One entry per non-blank line of [raw], with leading bullet or number markers removed. */
internal fun splitPastedLines(raw: String): List<String> =
    raw.split(LIST_LINE_BREAK)
        .map { it.replace(LEADING_LIST_MARKER, "").trim() }
        .filter { it.isNotEmpty() }

/** Single-line form of a name: line breaks become spaces. */
internal fun collapseNameLineBreaks(raw: String): String = raw.replace(LIST_LINE_BREAK, " ")

/**
 * The full candidate list for a multi-line paste into the single-line name box, or null when
 * [newText] is ordinary typing. Includes text already in the box.
 *
 * A pasted line break may reach [newText] directly, or Compose may have turned it into a space.
 * In the second case the clipboard is the evidence: it must have two or more lines and its
 * non-whitespace characters must appear, in order, in [newText].
 */
internal fun pastedNameLines(oldText: String, newText: String, clipboardText: String?): List<String>? {
    if (LIST_LINE_BREAK.containsMatchIn(newText)) {
        return splitPastedLines(newText).takeIf { it.size >= 2 }
    }
    if (newText.length - oldText.length < 2 || clipboardText == null) return null
    val clipLines = splitPastedLines(clipboardText)
    if (clipLines.size < 2) return null
    val squashedNew = newText.filterNot { it.isWhitespace() }
    val squashedClip = clipboardText.filterNot { it.isWhitespace() }
    if (!squashedNew.contains(squashedClip)) return null
    return splitPastedLines(oldText) + clipLines
}

/** Counter under the item box once something has been added in this session. */
internal fun quickEntryCounterText(added: Int, descriptionFocused: Boolean): String {
    val nextHint = if (descriptionFocused) "tap Add & next to keep going" else "↵ for the next one"
    return "$added added · $nextHint"
}
