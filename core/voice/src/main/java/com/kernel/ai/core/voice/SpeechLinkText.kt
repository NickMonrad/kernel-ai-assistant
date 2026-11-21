package com.kernel.ai.core.voice

/**
 * Keeps page links out of spoken text (#2041). A link stays on screen (chat bubble, link card);
 * spoken aloud it is read character by character. Wikipedia replies end with a "Read more: <url>"
 * line, and a title such as "Kiwi_(bird)" is read as "Kiwi, bird" once its URL form leaks through.
 *
 * Shared by every speech route: [SpeechCardText.speakable] runs it for all voice engines, and chat
 * speech normalisation runs it before Markdown and LaTeX conversion (LaTeX turns "_(" into a subscript
 * fallback). Patterns avoid syntax that Android's ICU regex rejects (#1968): no inline flag groups such
 * as `(?U:...)` and no possessive quantifiers.
 */
object SpeechLinkText {
    /** A URL. One level of balanced parentheses stays ("…/wiki/Kiwi_(bird)"); trailing punctuation does not. */
    private const val URL =
        """(?:https?://|www\.)(?:[^\s()<>]|\([^\s()<>]*\))*(?:[^\s()<>.,;:!?'"]|\([^\s()<>]*\))"""

    /** The "(url)" part of a Markdown link, with the same parenthesis rule as [URL]. */
    private const val MARKDOWN_LINK_TARGET =
        """\(\s*(?:https?://|www\.)(?:[^\s()<>]|\([^\s()<>]*\))*\s*\)"""

    /** A whole line that only points at a page: "Read more: <url>", "Source: <url>", "Link: [title](<url>)". */
    private val linkLine = Regex(
        """^[ \t]*(?:read\s+more|sources?|links?|more\s+info(?:rmation)?)[ \t]*[:\-–—]?[ \t]*(?:$URL|\[[^\]\n]*]$MARKDOWN_LINK_TARGET)[ \t]*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val markdownLink = Regex("""\[([^\]\n]*)]$MARKDOWN_LINK_TARGET""")
    private val bareUrl = Regex("""[ \t]*$URL""")
    /** "Kiwi_(bird)", "Kiwi_Bird": a title's underscore is a space, not a subscript or a pause. */
    private val titleUnderscore = Regex("""(?<=[\p{L}\d])_+(?=[\p{L}\d(])""")
    private val spaceRun = Regex("""[ \t]{2,}""")

    /**
     * Returns [text] with link lines and bare or Markdown URLs removed, and title underscores
     * turned into spaces. The link's label is kept for Markdown links. Nothing else changes.
     */
    fun strip(text: String): String {
        var value = linkLine.replace(text, "")
        value = markdownLink.replace(value) { it.groupValues[1] }
        value = bareUrl.replace(value, "")
        value = titleUnderscore.replace(value, " ")
        return spaceRun.replace(value, " ").trim()
    }
}
