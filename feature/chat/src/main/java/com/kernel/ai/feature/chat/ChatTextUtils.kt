package com.kernel.ai.feature.chat

/**
 * Strips common Markdown syntax from text for plain-text clipboard output.
 */
internal fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")   // bold
        .replace(Regex("\\*(.+?)\\*"), "$1")           // italic
        .replace(Regex("`{1,3}[^`]*`{1,3}"), "")      // code blocks/inline
        .replace(Regex("#{1,6}\\s"), "")               // headers
        .replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1") // links
        .trim()
}
