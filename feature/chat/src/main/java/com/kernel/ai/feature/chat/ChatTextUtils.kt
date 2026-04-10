package com.kernel.ai.feature.chat

/**
 * Strips common Markdown syntax from text for plain-text clipboard output.
 *
 * Declared without an explicit visibility modifier (public by default) so that
 * unit tests in the same Gradle module can call it directly.
 */
fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")          // bold
        .replace(Regex("""\*(.+?)\*"""), "$1")                    // italic
        .replace(Regex("""`{1,3}([\s\S]*?)`{1,3}"""), "$1")    // code blocks/inline (preserves content)
        .replace(Regex("""#{1,6}\s"""), "")                          // headers
        .replace(Regex("""\[(.+?)\]\(.+?\)"""), "$1")      // links
        .trim()
}
