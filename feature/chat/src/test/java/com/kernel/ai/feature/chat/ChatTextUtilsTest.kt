package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatTextUtilsTest {

    @Test
    fun stripMarkdown_removesBold() {
        assertEquals("hello world", stripMarkdown("**hello** world"))
        assertEquals("hello world", stripMarkdown("hello **world**"))
        assertEquals("hello world", stripMarkdown("**hello world**"))
    }

    @Test
    fun stripMarkdown_removesItalic() {
        assertEquals("hello world", stripMarkdown("*hello* world"))
        assertEquals("hello world", stripMarkdown("hello *world*"))
    }

    @Test
    fun stripMarkdown_removesInlineCode() {
        val input = "Use `foo()` to call"
        val result = stripMarkdown(input)
        assertEquals("Use  to call", result)
    }

    @Test
    fun stripMarkdown_removesHeaders() {
        assertEquals("Hello", stripMarkdown("# Hello"))
        assertEquals("Hello", stripMarkdown("## Hello"))
        assertEquals("Hello", stripMarkdown("### Hello"))
    }

    @Test
    fun stripMarkdown_removesLinks() {
        assertEquals("Click here", stripMarkdown("[Click here](https://example.com)"))
    }

    @Test
    fun stripMarkdown_trimsWhitespace() {
        assertEquals("hello", stripMarkdown("  hello  "))
    }

    @Test
    fun stripMarkdown_returnsPlainTextUnchanged() {
        assertEquals("hello world", stripMarkdown("hello world"))
    }
}
