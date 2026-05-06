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

    @Nested
    @DisplayName("chat speech normalization")
    inner class ChatSpeechNormalizationTests {

        @Test
        fun `applies scoped maori pronunciation overrides for chat speech`() {
            assertEquals(
                "Keeorah and moh-reh-nah",
                normalizeChatTextForSpeech("Kia ora and mōrena"),
            )
            assertEquals(
                "moh-reh-nah everyone",
                normalizeChatTextForSpeech("morena everyone"),
            )
        }

        @Test
        fun `converts bullet list breaks into speakable sentence pauses`() {
            assertEquals(
                "apples. bread",
                normalizeChatTextForSpeech("- apples\n- bread"),
            )
            assertEquals(
                "First thought. Second thought",
                normalizeChatTextForSpeech("First thought\n\nSecond thought"),
            )
        }

        @Test
        fun `converts numbered list items into sentence-break pauses`() {
            assertEquals(
                "Maintain a schedule. Create a routine. Optimise your environment",
                normalizeChatTextForSpeech("1.  Maintain a schedule\n2.  Create a routine\n3.  Optimise your environment"),
            )
        }

        @Test
        fun `strips leading numbered marker at start of text`() {
            assertEquals(
                "Maintain a schedule. Create a routine",
                normalizeChatTextForSpeech("1. Maintain a schedule\n2. Create a routine"),
            )
        }

        @Test
        fun `converts non-numeric colons into sentence-break pauses`() {
            assertEquals(
                "Bedtime Routine. Predictability is key",
                normalizeChatTextForSpeech("Bedtime Routine: Predictability is key"),
            )
        }

        @Test
        fun `preserves colons in times and ratios`() {
            val result = normalizeChatTextForSpeech("Meet at 7:30 PM, ratio 4:1")
            assertTrue(result.contains("7:30"), "Time colons should be preserved, got: $result")
            assertTrue(result.contains("4:1"), "Ratio colons should be preserved, got: $result")
        }

        @Test
        fun `preserves https URL scheme colons`() {
            val result = normalizeChatTextForSpeech("See https://example.com for details")
            assertTrue(result.contains("https://"), "URL scheme should be preserved, got: $result")
        }

        @Test
        fun `preserves http URL scheme colons`() {
            val result = normalizeChatTextForSpeech("Visit http://docs.kernel.ai for more")
            assertTrue(result.contains("http://"), "http URL scheme should be preserved, got: $result")
        }

        @Test
        fun `converts em and en dashes into comma pauses`() {
            assertEquals(
                "a warm bath, like a story, signals bedtime",
                normalizeChatTextForSpeech("a warm bath—like a story—signals bedtime"),
            )
        }
    }

    @Nested
    @DisplayName("streaming speech chunking")
    inner class StreamingSpeechChunkingTests {

        @Test
        fun `adds a soft pause when chunking on whitespace for early streaming`() {
            val buffer = StringBuilder(
                "Kia ora this chunk should break on whitespace so the voice has a short pause before continuing",
            )

            val chunk = popNextStreamingSpeechChunk(
                buffer = buffer,
                minChunkLength = 24,
                preferredChunkLength = 48,
            )

            assertTrue(chunk?.startsWith("Keeorah this chunk should break on whitespace") == true)
            assertTrue(chunk?.endsWith(",") == true)
            assertTrue(buffer.isNotEmpty())
        }

        @Test
        fun `forced final chunk adds sentence punctuation when missing`() {
            assertEquals(
                "moh-reh-nah everyone.",
                finalizeChatTextForSpeech("morena everyone"),
            )
        }

        @Test
        fun `strong punctuation boundary keeps existing sentence ending`() {
            val buffer = StringBuilder("Kia ora everyone. Here is the second sentence")

            val chunk = popNextStreamingSpeechChunk(
                buffer = buffer,
                minChunkLength = 10,
                preferredChunkLength = 24,
            )

            assertEquals("Keeorah everyone.", chunk)
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
                "plan a meal",
                "sort dinners for this week",
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

    @Nested
    @DisplayName("looksLikeToolFollowUp")
    inner class ToolFollowUpTests {

        @Test
        fun `returns true for meal planner continuation after meal planner exchange`() {
            assertTrue(
                looksLikeToolFollowUp(
                    text = "Continue",
                    previousUser = "Plan a meal",
                    previousAssistant = "Ready for the full recipes with cooking steps?",
                ),
            )
        }

        @Test
        fun `returns true for yes after meal planner preference question`() {
            assertTrue(
                looksLikeToolFollowUp(
                    text = "Ok let's do it",
                    previousUser = "Plan a meal",
                    previousAssistant = "How many people, and any dietary restrictions?",
                ),
            )
        }

        @Test
        fun `returns true for discussing preferences during meal planner flow`() {
            assertTrue(
                looksLikeToolFollowUp(
                    text = "Let's discuss preferences",
                    previousUser = "Meal planning",
                    previousAssistant = "Would you like to proceed with the plan for the first day, or would you like to change the preferences first?",
                ),
            )
        }

        @Test
        fun `returns true for asking what the meals are during meal planner flow`() {
            assertTrue(
                looksLikeToolFollowUp(
                    text = "What are the meals",
                    previousUser = "Let's discuss preferences",
                    previousAssistant = "How many people are you planning for, and any dietary restrictions?",
                ),
            )
        }

        @Test
        fun `returns false for generic yes without tool context`() {
            assertFalse(
                looksLikeToolFollowUp(
                    text = "Yes",
                    previousUser = "Tell me a joke",
                    previousAssistant = "Do you want another one?",
                ),
            )
        }
    }

    @Nested
    @DisplayName("looksLikeRawToolCall")
    inner class RawToolCallTests {

        @Test
        fun `returns true for leaked native tool call token`() {
            assertTrue(
                looksLikeRawToolCall(
                    "<|tool_call>call:run_intent{intent_name:<|\"|>meal_planner<|\"|>}",
                ),
            )
        }

        @Test
        fun `returns true for leaked json tool call`() {
            assertTrue(
                looksLikeRawToolCall(
                    """{"name":"load_skill","arguments":{"skill_name":"meal_planner"}}""",
                ),
            )
        }

        @Test
        fun `returns false for normal assistant reply`() {
            assertFalse(looksLikeRawToolCall("Here are the three meals I came up with."))
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TRUNCATE FOR SPEECH
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("truncateForSpeech")
    inner class TruncateForSpeechTests {

        @Test
        fun `zero maxSentences returns full text unchanged`() {
            val text = "First sentence. Second sentence. Third sentence."
            assertEquals(text, truncateForSpeech(text, 0))
        }

        @Test
        fun `negative maxSentences returns full text unchanged`() {
            val text = "First sentence. Second sentence."
            assertEquals(text, truncateForSpeech(text, -1))
        }

        @Test
        fun `takes only the first two sentences from three`() {
            val text = "First sentence. Second sentence. Third sentence."
            val result = truncateForSpeech(text, 2)
            assertTrue(result.contains("First sentence"))
            assertTrue(result.contains("Second sentence"))
            assertFalse(result.contains("Third sentence"))
        }

        @Test
        fun `returns full text when sentence count equals maxSentences`() {
            val text = "First. Second. Third."
            assertEquals(text, truncateForSpeech(text, 3))
        }

        @Test
        fun `returns full text when sentence count is less than maxSentences`() {
            val text = "Only one sentence."
            assertEquals(text, truncateForSpeech(text, 5))
        }

        @Test
        fun `preserves trailing period punctuation`() {
            val text = "Hello world. Goodbye world."
            val result = truncateForSpeech(text, 1)
            assertTrue(result.trimEnd().endsWith("."))
        }

        @Test
        fun `handles exclamation marks as sentence boundaries`() {
            val text = "Hello! World? Great."
            val result = truncateForSpeech(text, 2)
            assertTrue(result.contains("Hello!"))
            assertTrue(result.contains("World?"))
            assertFalse(result.contains("Great"))
        }

        @Test
        fun `text with no sentence boundaries returns full text`() {
            val text = "No punctuation here"
            assertEquals(text, truncateForSpeech(text, 2))
        }

        @Test
        fun `does not split on abbreviation dot — Dr followed by full sentence`() {
            // "Dr." alone is a single-token dot fragment → merged forward into the next fragment,
            // so the first real sentence becomes "Dr. Smith explained the plan."
            val text = "Dr. Smith explained the plan. That's all."
            val result = truncateForSpeech(text, 1)
            assertEquals("Dr. Smith explained the plan.", result.trimEnd())
        }

        @Test
        fun `does not split on sentence-leading e-g abbreviation`() {
            // "E." and "g." are both single-token dot fragments that get merged forward
            // into the following fragment, keeping the first sentence intact.
            val text = "E.g. cats and dogs are common pets. That covers the basics."
            val result = truncateForSpeech(text, 1)
            assertTrue(result.contains("cats and dogs"), "Should include the full first sentence")
            assertFalse(result.contains("basics"), "Should not include the second sentence")
        }

        @Test
        fun `single-word sentences like Sure are real sentence boundaries — not abbreviations`() {
            // "Sure." must NOT be merged into the next fragment; it is a complete sentence.
            val text = "Sure. Here is the answer. More details."
            val result = truncateForSpeech(text, 1)
            assertEquals("Sure.", result.trimEnd())
        }

        @Test
        fun `known abbreviation Dr is still merged correctly`() {
            // "Dr." is a known abbreviation so it merges with the following fragment,
            // making "Dr. Smith explained the plan." the first full sentence.
            val text = "Dr. Smith explained the plan. More here."
            val result = truncateForSpeech(text, 1)
            assertEquals("Dr. Smith explained the plan.", result.trimEnd())
        }
    }
}
