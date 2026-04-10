package com.kernel.ai.feature.chat

/**
 * Converts LaTeX expressions to Unicode and strips Markdown syntax,
 * producing clean plain text suitable for clipboard output.
 */
internal fun stripMarkdownForClipboard(text: String): String =
    stripMarkdown(convertLatexToUnicode(text))

/**
 * Strips common Markdown syntax from text for plain-text clipboard output.
 */
internal fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")             // bold
        .replace(Regex("""\*(.+?)\*"""), "$1")                  // italic
        .replace(Regex("""`{1,3}([\s\S]*?)`{1,3}"""), "$1")    // code blocks/inline (preserves content)
        .replace(Regex("""#{1,6}\s"""), "")                      // headers
        .replace(Regex("""\[(.+?)\]\(.+?\)"""), "$1")           // links
        .trim()
}
