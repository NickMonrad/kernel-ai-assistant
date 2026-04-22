package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for ChatTextUtils — stripMarkdown, looksLikeAnaphora,
 * looksLikeToolConfirmation, and looksLikeToolQuery.
 *
 * Run with: ./gradlew :feature:chat:test --tests "*.ChatTextUtilsTest"
 */
class ChatTextUtilsTest {

    // ═════════════════════════════════════════════════════════════════════════
    // STRIP MARKDOWN
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("stripMarkdown")
    inner class StripMarkdownTests {

        @Test
        fun removesBold() {
            assertEquals("hello world", stripMarkdown("**hello** world"))
            assertEquals("hello world", stripMarkdown("hello **world**"))
            assertEquals("hello world", stripMarkdown("**hello world**"))
        }

        @Test
        fun removesItalic() {
            assertEquals("hello world", stripMarkdown("*hello* world"))
            assertEquals("hello world", stripMarkdown("hello *world*"))
        }

        @Test
        fun removesInlineCodeAndPreservesContent() {
            assertEquals("Use foo() to call", stripMarkdown("Use `foo()` to call"))
        }

        @Test
        fun removesHeaders() {
            assertEquals("Hello", stripMarkdown("# Hello"))
            assertEquals("Hello", stripMarkdown("## Hello"))
            assertEquals("Hello", stripMarkdown("### Hello"))
        }

        @Test
        fun removesLinks() {
            assertEquals("Click here", stripMarkdown("[Click here](https://example.com)"))
        }

        @Test
        fun trimsWhitespace() {
            assertEquals("hello", stripMarkdown("  hello  "))
        }

        @Test
        fun returnsPlainTextUnchanged() {
            assertEquals("hello world", stripMarkdown("hello world"))
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ANAPHORA DETECTION
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("looksLikeAnaphora")
    inner class AnaphoraTests {

        @ParameterizedTest(name = "Positive: \"{0}\"")
        @ValueSource(
            strings = [
                "save that",
                "remember that",
                "store that",
                "add that",
                "note that",
                "keep this",
                "look it up",
                "look that up",
                "search it up",
                "find this out",
                "check that out",
                "what was that again",
                "what is that",
                "how did that work",
                "the above",
                "the previous one",
                "save it for later",
                "what was that",
            ],
        )
        fun `returns true for anaphoric references`(input: String) {
            assertTrue(looksLikeAnaphora(input), "Expected true for '$input'")
        }

        @ParameterizedTest(name = "Negative: \"{0}\"")
        @ValueSource(
            strings = [
                "set an alarm for 7am",
                "what time is it",
                "tell me a joke",
                "play some music",
                "how is the weather",
            ],
        )
        fun `returns false for non-anaphoric queries`(input: String) {
            assertFalse(looksLikeAnaphora(input), "Expected false for '$input'")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TOOL CONFIRMATION (HALLUCINATION GUARD)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("looksLikeToolConfirmation")
    inner class ToolConfirmationTests {

        @ParameterizedTest(name = "Hallucination: \"{0}\"")
        @ValueSource(
            strings = [
                "I've set your alarm for 7am.",
                "Done! I've added that to your list.",
                "I've saved that to memory.",
                "Memory saved!",
                "I've saved that for you.",
                "Alarm set for 7:00 AM.",
                "Timer set for 5 minutes.",
                "I've created your grocery list.",
                "Added to your shopping list.",
                "I've noted that for you.",
                "All done! Your brightness has been turned on.",
                "Sure thing, I've toggled the flashlight.",
                "I've added milk to your shopping list.",
                "I have saved your preference.",
                "Done! I've set a timer for 10 minutes.",
                "Got it, I've updated your settings.",
                "Item added to your list.",
                "List created for you.",
                "Created a new todo list.",
                "Turned on wifi for you.",
                "Turned off bluetooth.",
            ],
        )
        fun `returns true for hallucinated confirmations`(response: String) {
            assertTrue(looksLikeToolConfirmation(response), "Expected true for '$response'")
        }

        @ParameterizedTest(name = "Not a hallucination: \"{0}\"")
        @ValueSource(
            strings = [
                "What time?",
                "I can help with that.",
                "Sure!",
                "What would you like me to do?",
                "I don't understand the question.",
                "Let me think about that.",
                "Here's what I found:",
                "The weather is sunny today.",
            ],
        )
        fun `returns false for normal responses`(response: String) {
            assertFalse(looksLikeToolConfirmation(response), "Expected false for '$response'")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TOOL QUERY DETECTION
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("looksLikeToolQuery")
    inner class ToolQueryTests {

        @ParameterizedTest(name = "Tool query: \"{0}\"")
        @ValueSource(
            strings = [
                "save my notes",
                "remember my birthday",
                "add milk to my shopping list",
                "set alarm for 7am",
                "set a timer for 5 minutes",
                "remind me to call mum",
                "turn on wifi",
                "turn off bluetooth",
                "what time is it",
                "what's the time",
                "battery level",
                "get battery",
                "play some music",
                "navigate to the airport",
                "directions to work",
                "send email to John",
                "send sms to mum",
                "call dad",
                "look up quantum physics",
                "plan my meals for the week",
                "make me a meal plan",
                "save this meal plan to my shopping list",
                "open app settings",
                "toggle flashlight",
                "note that my password is 1234",
                "don't forget the meeting",
                "store my preference",
                "put on my shopping list",
                "what's on my list",
                "show my todo list",
                "create a grocery list",
                "remove from my list",
                "delete from shopping list",
                "meal plan for 5 days",
                "plan my meals",
                "meal planner",
                "plan meals vegetarian",
            ],
        )
        fun `returns true for tool-related queries`(query: String) {
            assertTrue(looksLikeToolQuery(query), "Expected true for '$query'")
        }

        @ParameterizedTest(name = "Not a tool query: \"{0}\"")
        @ValueSource(
            strings = [
                "tell me a joke",
                "explain quantum physics",
                "write me a poem",
                "how do I cook pasta",
                "what is the meaning of life",
            ],
        )
        fun `returns false for non-tool queries`(query: String) {
            assertFalse(looksLikeToolQuery(query), "Expected false for '$query'")
        }
    }
}
