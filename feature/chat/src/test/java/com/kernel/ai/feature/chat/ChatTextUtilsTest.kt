package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import com.kernel.ai.core.inference.JandalPersona
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

    private val sampleEntries = listOf(
        JandalPersona.NzTruthEntry(
            id = "nz_141",
            term = "Wharepaku",
            category = "culture",
            definition = "Wharepaku is the te reo Māori term for a toilet, restroom, or bathroom. It's a compound word literally meaning 'small house' (whare = building/house, paku = small). Standard term found on public signage, schools, and government buildings across New Zealand.",
            triggerContext = "When the user asks about wharepaku, bathroom, toilet, or restroom",
            vibeLevel = 3,
            vectorText = "Wharepaku. Toilet. Restroom. Bathroom. Māori language. New Zealand. Small house. Public signage. Māori culture. Te reo Māori.",
            metadataJson = """{}""",
        ),
        JandalPersona.NzTruthEntry(
            id = "nz_142",
            term = "Chocka",
            category = "culture",
            definition = "Chocka (or chock-a-block) is a Kiwi colloquialism meaning full, packed, or crowded. Common usage: 'the pub was chocka', 'my schedule is chocka', or 'the car is chocka with gear'. Derived from nautical terminology where a block-and-tackle rig is fully extended ('chock-a-block').",
            triggerContext = "When the user asks about chocka, chock-a-block, or New Zealand slang",
            vibeLevel = 2,
            vectorText = "Chocka. Chock-a-block. Full. Packed. Crowded. Kiwi colloquialism. New Zealand slang. Nautical origin.",
            metadataJson = """{}""",
        ),
        JandalPersona.NzTruthEntry(
            id = "nz_143",
            term = "Taniwha",
            category = "culture",
            definition = "Taniwha are powerful supernatural beings in Māori mythology that dwell in deep rivers, dark caves, lakes, or the ocean. They can be protective guardians (kaitiaki) for an iwi or dangerous predatory monsters that kidnap women or eat people. They are complex figures that enforce respect for natural boundaries and waterways.",
            triggerContext = "When the user asks about taniwha, Māori mythology, or supernatural beings in New Zealand",
            vibeLevel = 4,
            vectorText = "Taniwha. Māori mythology. Supernatural beings. Kaitiaki. Guardians. Water spirits. New Zealand. Māori culture. Iwi. Natural boundaries. Waterways.",
            metadataJson = """{}""",
        ),
        JandalPersona.NzTruthEntry(
            id = "nz_144",
            term = "Kumara",
            category = "culture",
            definition = "Kumara (sweet potato) is a root vegetable brought to Aotearoa by early Māori. It's a staple of both traditional Māori hāngī and the classic Kiwi Sunday roast. Varieties include red (Owairaka), gold (Toka Toka), and orange (Beauregard). It holds significant cultural importance in New Zealand.",
            triggerContext = "When the user asks about kumara, sweet potato, hāngī, or traditional Māori food",
            vibeLevel = 3,
            vectorText = "Kumara. Sweet potato. Māori food. Hāngī. Sunday roast. New Zealand. Aotearoa. Owairaka. Toka Toka. Beauregard. Root vegetable.",
            metadataJson = """{}""",
        ),
    )

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
        fun `applies scoped pronunciation overrides for chat speech`() {
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
        fun `normalises standalone aye to the spoken letter A`() {
            assertEquals(
                "A, sounds good",
                normalizeChatTextForSpeech("Aye, sounds good"),
            )
            assertEquals(
                "Maybe A means yes",
                normalizeChatTextForSpeech("Maybe aye means yes"),
            )
            assertEquals(
                "A, A A CAPTAIN",
                normalizeChatTextForSpeech("AYE, AYE AYE CAPTAIN"),
            )
            assertEquals(
                "ayes stay unchanged",
                normalizeChatTextForSpeech("ayes stay unchanged"),
            )
            assertEquals(
                "aye-aye unchanged",
                normalizeChatTextForSpeech("aye-aye unchanged"),
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
        fun `strips inline unicode bullet characters that would be vocalised as dot`() {
            // • not at line start (e.g. single-line bullet run)
            assertEquals(
                "apples bread oranges",
                normalizeChatTextForSpeech("apples • bread • oranges"),
            )
            // Triangular bullet
            assertEquals(
                "item one. item two",
                normalizeChatTextForSpeech("item one\n‣ item two"),
            )
            // White bullet ◦
            assertEquals(
                "first. second",
                normalizeChatTextForSpeech("first\n◦ second"),
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
        fun `colons followed by numbered list do not produce double dot artifact`() {
            // "big cats:\n1. Tigers" → colon→". " and \n1.→". " both fire; must dedup to single "."
            assertEquals(
                "big cats. Tigers",
                normalizeChatTextForSpeech("big cats:\n1. Tigers"),
            )
        }

        @Test
        fun `standalone asterisk divider is stripped and not vocalised as dot`() {
            // LLM outputs *** as a thematic break; stripMarkdown reduces *** → * (italic middle char);
            // the lone * must then be silently removed rather than passed to espeak as "dot".
            assertEquals("", normalizeChatTextForSpeech("***"))
            assertEquals("", normalizeChatTextForSpeech("*"))
            assertEquals("", normalizeChatTextForSpeech("---"))
        }

        @Test
        fun `standalone asterisk divider between sections is stripped`() {
            // Full essay pattern: paragraph, blank line, ***, blank line, next section.
            val input = "Comparing big cats is fascinating.\n\n***\n\nThe Big Cat Family Tree"
            val result = normalizeChatTextForSpeech(input)
            assertFalse(result.contains("*"), "Asterisk divider must not appear in speech text, got: $result")
            assertTrue(result.contains("Comparing big cats is fascinating"), "Preceding text must be preserved")
            assertTrue(result.contains("The Big Cat Family Tree"), "Following text must be preserved")
        }

        @Test
        fun `numbered section header after sentence does not produce double dot`() {
            // "habitat.\n\n1. Tigers" — sentence ends with period, then numbered header follows;
            // compound transform must not yield ".." which espeak reads as "dot Tigers".
            val result = normalizeChatTextForSpeech("Their habitat.\n\n1. Tigers vs. Lions")
            assertFalse(result.contains(".."), "Double period must be collapsed, got: $result")
            assertFalse(result.startsWith("dot", ignoreCase = true), "Must not start with 'dot', got: $result")
            assertTrue(result.contains("Tigers vs. Lions"), "Section content must be preserved, got: $result")
        }

        @Test
        fun `leading period at start of normalised text is stripped`() {
            // Chunk starting with ". Tigers" (period injected by compound transforms) must not
            // be read by espeak-ng as "dot Tigers".
            assertEquals(
                "Tigers",
                normalizeChatTextForSpeech(". Tigers"),
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

        // ── #912 fraction and unit abbreviation normalization ──────────────────

        @Test
        fun `bullet with quarter fraction — no minus or slash`() {
            val result = normalizeChatTextForSpeech("- 1/4 cup breadcrumbs")
            assertFalse(result.contains("minus"), "should not contain 'minus', got: $result")
            assertFalse(result.contains("/"), "should not contain '/', got: $result")
            assertTrue(result.contains("quarter"), "should contain 'quarter', got: $result")
        }

        @Test
        fun `bullet with half fraction and tsp abbreviation`() {
            val result = normalizeChatTextForSpeech("- 1/2 tsp garlic")
            assertTrue(result.contains("half"), "should contain 'half', got: $result")
            assertTrue(result.contains("teaspoon"), "should contain 'teaspoon', got: $result")
        }

        @Test
        fun `bullet with three quarters fraction`() {
            val result = normalizeChatTextForSpeech("- 3/4 cup")
            assertTrue(result.contains("three quarters"), "should contain 'three quarters', got: $result")
        }

        @Test
        fun `mixed number one and a half cups`() {
            val result = normalizeChatTextForSpeech("1 1/2 cups")
            assertTrue(result.contains("and a half"), "should contain 'and a half', got: $result")
            assertFalse(result.contains("/"), "should not contain '/', got: $result")
        }

        @Test
        fun `unicode half cup`() {
            val result = normalizeChatTextForSpeech("½ cup")
            assertTrue(result.contains("half"), "should contain 'half', got: $result")
        }

        @Test
        fun `fraction before month name is not converted — date guard`() {
            val result = normalizeChatTextForSpeech("meeting on 2/3 May")
            assertFalse(result.contains("two thirds"), "should not convert date fraction, got: $result")
        }

        @Test
        fun `fraction before lowercase month name is not converted — date guard`() {
            val result = normalizeChatTextForSpeech("meeting on 2/3 may")
            assertFalse(result.contains("two thirds"), "should not convert date fraction, got: $result")
        }

        @Test
        fun `fraction before lowercase full month is not converted — date guard`() {
            val result = normalizeChatTextForSpeech("deadline 1/2 january")
            assertFalse(result.contains("half"), "should not convert date fraction, got: $result")
        }

        @Test
        fun `fraction in dd-mm-yyyy format is not converted — date guard`() {
            val result = normalizeChatTextForSpeech("deadline 2/3/2024")
            assertFalse(result.contains("two thirds"), "should not convert date fraction, got: $result")
        }

        @Test
        fun `TSP all-caps acronym is not converted to teaspoon`() {
            val result = normalizeChatTextForSpeech("TSP contribution limits")
            assertFalse(result.contains("teaspoon"), "should not convert acronym TSP, got: $result")
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

    @Nested
    @DisplayName("prefersImmediateConversationContext")
    inner class ImmediateContextTests {
        @Test
        fun `returns true for short pronoun follow up questions`() {
            assertTrue(prefersImmediateConversationContext("What are they"))
            assertTrue(prefersImmediateConversationContext("How do they work?"))
        }

        @Test
        fun `returns false for long or non pronoun queries`() {
            assertFalse(prefersImmediateConversationContext("Tell me everything you remember about sweet potatoes and their nutritional profile"))
            assertFalse(prefersImmediateConversationContext("What time is it"))
        }
    }

    @Nested
    @DisplayName("extractExplicitWikipediaQuery")
    inner class ExplicitWikipediaQueryTests {
        @Test
        fun `preserves identifier text for look up wikipedia for command`() {
            assertEquals("SM-918B", extractExplicitWikipediaQuery("Look up Wikipedia for SM-918B"))
        }

        @Test
        fun `preserves mixed query for on wikipedia command`() {
            assertEquals("Samsung sm-918b", extractExplicitWikipediaQuery("Look up Samsung sm-918b on Wikipedia"))
        }

        @Test
        fun `returns null for non explicit wikipedia queries`() {
            assertEquals(null, extractExplicitWikipediaQuery("What is sm-918b"))
        }

        @Test
        fun `returns null for bare anaphora wikipedia commands`() {
            assertEquals(null, extractExplicitWikipediaQuery("Search Wikipedia for it"))
            assertEquals(null, extractExplicitWikipediaQuery("Look up this on Wikipedia"))
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
                // Calendar-specific (#1428 finding 3)
                "I've put that in the diary for you.",
                "I have put that in the diary.",
                "I've put it on your calendar.",
                "I have put it in your calendar.",
                "Put that in your calendar for you.",
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
                // Calendar false positives — Must NOT match (#1428 finding 3)
                "I've put together the comparison below.",
                "Put that in context with the earlier result.",
                "I've put a lot of thought into this.",
                "I've put the documents in the folder.",
                "Put it on the table.",
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
                "what's the current system info",
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
    @DisplayName("turn instructions")
    inner class TurnInstructionTests {

        @Test
        fun `tool turn instruction is omitted on first reply`() {
            assertEquals(null, toolTurnInstruction(isFirstReply = true))
        }

        @Test
        fun `tool turn instruction suppresses greeting on follow up`() {
            assertEquals(
                "Do NOT start this reply with a greeting. This is a follow-up tool turn, so answer directly with the tool result.",
                toolTurnInstruction(isFirstReply = false),
            )
        }

        @Test
        fun `non tool instruction softly prefers reasoning`() {
            assertEquals(
                "This looks like a normal conversational or reasoning reply. Prefer answering directly from your own knowledge and reasoning. Only call tools if the user is clearly asking for current, external, or retrieved information.",
                nonToolTurnInstruction(),
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
        fun `returns true for leaked skill instructions`() {
            assertTrue(
                looksLikeRawToolCall(
                    """
                    query_wikipedia: Look up a topic on Wikipedia and return grounded factual context.

                    Instructions:
                    - Call the run_js tool with the format below.

                    Tool format:
                    - Call runJs with a single 'parameters' argument.
                    """.trimIndent(),
                ),
            )
        }

        @Test
        fun `returns false for normal assistant reply`() {
            assertFalse(looksLikeRawToolCall("Here are the three meals I came up with."))
        }

        @Test
        fun `returns false for normal response mentioning wikipedia with colon`() {
            assertFalse(
                looksLikeRawToolCall(
                    "On Wikipedia: the week is a unit of time equal to seven days.",
                ),
            )
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
            val text = "Dr. Smith explained the plan. That's all."
            val result = truncateForSpeech(text, 1)
            assertEquals("Dr. Smith explained the plan.", result.trimEnd())
        }

        @Test
        fun `does not split on sentence-leading e-g abbreviation`() {
            val text = "E.g. cats and dogs are common pets. That covers the basics."
            val result = truncateForSpeech(text, 1)
            assertTrue(result.contains("cats and dogs"))
            assertFalse(result.contains("basics"))
        }

        @Test
        fun `single-word sentences like Sure are real sentence boundaries — not abbreviations`() {
            val text = "Sure. Here is the answer. More details."
            val result = truncateForSpeech(text, 1)
            assertEquals("Sure.", result.trimEnd())
        }

        @Test
        fun `known abbreviation Dr is still merged correctly`() {
            val text = "Dr. Smith explained the plan. More here."
            val result = truncateForSpeech(text, 1)
            assertEquals("Dr. Smith explained the plan.", result.trimEnd())
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NORMALISE PRONOUNS FOR TTS
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("normalisePronounsForTts")
    inner class NormalisePronounsForTtsTests {

        @Test
        fun `my is replaced with your`() {
            assertEquals("Sending a message to your wife", normalisePronounsForTts("Sending a message to my wife"))
        }

        @Test
        fun `My is replaced with Your preserving case`() {
            assertEquals("Your wife", normalisePronounsForTts("My wife"))
        }

        @Test
        fun `MY is replaced with YOUR preserving caps`() {
            assertEquals("YOUR WIFE", normalisePronounsForTts("MY WIFE"))
        }

        @Test
        fun `mine is replaced with yours`() {
            assertEquals("That is yours", normalisePronounsForTts("That is mine"))
        }

        @Test
        fun `myself is replaced with yourself`() {
            assertEquals("Did you hurt yourself", normalisePronounsForTts("Did I hurt myself"))
        }

        @Test
        fun `bare I is replaced with you`() {
            assertEquals("What would you like to say", normalisePronounsForTts("What would I like to say"))
        }

        @Test
        fun `I'm is replaced with you're`() {
            assertEquals("you're going to love this", normalisePronounsForTts("I'm going to love this"))
        }

        @Test
        fun `I've is replaced with you've`() {
            assertEquals("you've done well", normalisePronounsForTts("I've done well"))
        }

        @Test
        fun `I'll is replaced with you'll`() {
            assertEquals("you'll get a confirmation", normalisePronounsForTts("I'll get a confirmation"))
        }

        @Test
        fun `I'd is replaced with you'd`() {
            assertEquals("you'd prefer that", normalisePronounsForTts("I'd prefer that"))
        }

        @Test
        fun `me as object is not replaced (rule removed — Jandal self-reference must be preserved)`() {
            assertEquals(
                "What would you like to say to me",
                normalisePronounsForTts("What would you like to say to me"),
            )
        }

        @Test
        fun `word boundaries prevent partial word replacement`() {
            assertEquals("Emailing Myra", normalisePronounsForTts("Emailing Myra"))
            assertEquals("sending email", normalisePronounsForTts("sending email"))
            assertEquals("a minefield of options", normalisePronounsForTts("a minefield of options"))
        }

        @Test
        fun `multiple pronouns in one string are all replaced`() {
            assertEquals(
                "What would you like to say to your mum",
                normalisePronounsForTts("What would I like to say to my mum"),
            )
        }

        @Test
        fun `text with no pronouns is unchanged`() {
            val text = "Sending a message to Sarah"
            assertEquals(text, normalisePronounsForTts(text))
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ANAPHORIC SAVE SAFEGUARDS
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isAnaphoricSaveRequest")
    inner class IsAnaphoricSaveRequestTests {

        @Test
        fun `returns true for remember that`() {
            assertTrue(isAnaphoricSaveRequest("remember that"))
        }

        @Test
        fun `returns true for save that`() {
            assertTrue(isAnaphoricSaveRequest("save that"))
        }

        @Test
        fun `returns true for remember it`() {
            assertTrue(isAnaphoricSaveRequest("remember it"))
        }

        @Test
        fun `returns true for save it`() {
            assertTrue(isAnaphoricSaveRequest("save it"))
        }

        @Test
        fun `returns true for remember this`() {
            assertTrue(isAnaphoricSaveRequest("remember this"))
        }

        @Test
        fun `returns true for save this`() {
            assertTrue(isAnaphoricSaveRequest("save this"))
        }

        @Test
        fun `returns true for can you remember this`() {
            assertTrue(isAnaphoricSaveRequest("can you remember this"))
        }

        @Test
        fun `returns true for please save it`() {
            assertTrue(isAnaphoricSaveRequest("please save it"))
        }

        @Test
        fun `returns false for remember my birthday`() {
            assertFalse(isAnaphoricSaveRequest("remember my birthday"))
        }

        @Test
        fun `returns false for save my notes`() {
            assertFalse(isAnaphoricSaveRequest("save my notes"))
        }

        @Test
        fun `returns false for remember to call mum`() {
            assertFalse(isAnaphoricSaveRequest("remember to call mum"))
        }

        @Test
        fun `returns true for remember that bare`() {
            assertTrue(isAnaphoricSaveRequest("remember that"))
        }

        @Test
        fun `returns true for keep that in memory`() {
            assertTrue(isAnaphoricSaveRequest("keep that in memory"))
        }

        @Test
        fun `returns false for remember that I prefer dark mode`() {
            assertFalse(isAnaphoricSaveRequest("remember that I prefer dark mode"))
        }
    }

    @Nested
    @DisplayName("looksLikePersonalFact")
    inner class LooksLikePersonalFactTests {

        @Test
        fun `returns true for personal preference statement`() {
            assertTrue(
                looksLikePersonalFact(
                    "I prefer dark mode for reading",
                ),
            )
        }

        @Test
        fun `returns false for birthday because important dates handle it`() {
            assertFalse(
                looksLikePersonalFact(
                    "My birthday is March 15th",
                ),
            )
        }

        @Test
        fun `returns false for important date phrased as I have`() {
            assertFalse(looksLikePersonalFact("I have a birthday on March 15th"))
        }

        @Test
        fun `returns false for family important date fact`() {
            assertFalse(looksLikePersonalFact("My wife's birthday is March 15th"))
        }

        @Test
        fun `returns true for personal preference about food`() {
            assertTrue(
                looksLikePersonalFact(
                    "I don't eat gluten",
                ),
            )
        }

        @Test
        fun `returns false for factual encyclopedic content`() {
            assertFalse(
                looksLikePersonalFact(
                    "The capital of France is Paris",
                ),
            )
        }

        @Test
        fun `returns false for weather content`() {
            assertFalse(
                looksLikePersonalFact(
                    "It is sunny today",
                ),
            )
        }

        @Test
        fun `returns true for I dont like something`() {
            assertTrue(looksLikePersonalFact("I don't like aubergines"))
        }
        @Test
        fun `returns true for I do not like something`() {
            assertTrue(looksLikePersonalFact("I do not like aubergines"))
        }

        @Test
        fun `returns true for I prefer something`() {
            assertTrue(looksLikePersonalFact("I prefer dark mode"))
        }

        @Test
        fun `returns false for generic greeting`() {
            assertFalse(
                looksLikePersonalFact(
                    "Hello, how are you?",
                ),
            )
        }

        @Test
        fun `returns false for I need something`() {
            assertFalse(looksLikePersonalFact("I need a recipe for dinner"))
        }

        @Test
        fun `returns false for I want something`() {
            assertFalse(looksLikePersonalFact("I want to buy a new phone"))
        }

        @Test
        fun `returns false for My shopping list has eggs`() {
            assertFalse(looksLikePersonalFact("My shopping list has eggs"))
        }

        @Test
        fun `returns true for I have a dog`() {
            assertTrue(looksLikePersonalFact("I have a dog"))
        }

        @Test
        fun `returns true for My name is John`() {
            assertTrue(looksLikePersonalFact("My name is John"))
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CULTURAL CONTEXT CUE (#kumara recall)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("hasCulturalContextCue")
    inner class CulturalContextCueTests {

        @ParameterizedTest
        @ValueSource(strings = [
            "What are they called in New Zealand",
            "what's the name in NZ?",
            "Is there a Māori word for it",
            "what do the maori call it",
            "the te reo name",
            "what's it called in Aotearoa",
            "do kiwis have a word for that",
        ])
        fun `returns true for NZ cultural cues`(text: String) {
            assertTrue(hasCulturalContextCue(text), "Expected cue in: $text")
        }

        @ParameterizedTest
        @ValueSource(strings = [
            "What are they called",
            "what's the name for it",
            "tell me about sweet potato",
            "how do you cook it",
        ])
        fun `returns false without NZ cultural cue`(text: String) {
            assertFalse(hasCulturalContextCue(text), "Did not expect cue in: $text")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CLIPBOARD CONVERSATION FORMATTING (#1024)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("formatConversationForClipboard")
    inner class ClipboardFormattingTests {

        private fun user(text: String) = com.kernel.ai.feature.chat.model.ChatMessage(
            id = "u", role = com.kernel.ai.feature.chat.model.ChatMessage.Role.USER, content = text,
        )

        private fun assistant(
            text: String,
            thinking: String? = null,
            tool: com.kernel.ai.feature.chat.model.ToolCallInfo? = null,
        ) = com.kernel.ai.feature.chat.model.ChatMessage(
            id = "a",
            role = com.kernel.ai.feature.chat.model.ChatMessage.Role.ASSISTANT,
            content = text,
            thinkingText = thinking,
            toolCall = tool,
        )

        @Test
        fun `both flags off reproduces plain transcript`() {
            val messages = listOf(
                user("**hello**"),
                assistant("hi there", thinking = "secret", tool = null),
            )
            val result = formatConversationForClipboard(messages, includeThinking = false, includeToolCalls = false)
            assertEquals("You: hello\nJandal: hi there", result)
        }

        @Test
        fun `includes thinking block when enabled`() {
            val messages = listOf(assistant("answer", thinking = "my reasoning"))
            val result = formatConversationForClipboard(messages, includeThinking = true, includeToolCalls = false)
            assertEquals("Jandal:\n[Thinking]\nmy reasoning\n[End Thinking]\nanswer", result)
        }

        @Test
        fun `includes tool call when enabled`() {
            val tool = com.kernel.ai.feature.chat.model.ToolCallInfo(
                skillName = "search_memory",
                requestJson = "{\"query\":\"x\"}",
                resultText = "found it",
                isSuccess = true,
            )
            val messages = listOf(assistant("here you go", tool = tool))
            val result = formatConversationForClipboard(messages, includeThinking = false, includeToolCalls = true)
            assertEquals(
                "Jandal:\n[Tool Call: search_memory — success]\n" +
                    "Request: {\"query\":\"x\"}\nResult: found it\n[End Tool Call]\nhere you go",
                result,
            )
        }

        @Test
        fun `thinking not included when flag off`() {
            val messages = listOf(assistant("answer", thinking = "hidden"))
            val result = formatConversationForClipboard(messages, includeThinking = false, includeToolCalls = false)
            assertEquals("Jandal: answer", result)
        }

        @Test
        fun `failed tool call marked failed`() {
            val tool = com.kernel.ai.feature.chat.model.ToolCallInfo(
                skillName = "reminder",
                requestJson = "{}",
                resultText = "error",
                isSuccess = false,
            )
            val messages = listOf(assistant("oops", tool = tool))
            val result = formatConversationForClipboard(messages, includeThinking = false, includeToolCalls = true)
            assertTrue(result.contains("[Tool Call: reminder — failed]"), result)
        }
    }


    // ═════════════════════════════════════════════════════════════════════════
    // DETERMINISTIC NZ TERM DETECTION (#1074)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("detectKnownNzTerm")
    inner class KnownNzTermDetectionTests {


        @Test
        fun `detects wharepaku in question`() {
            val result = detectKnownNzTerm("what is a wharepaku", sampleEntries)
            assertEquals("Wharepaku", result?.term)
        }

        @Test
        fun `detects chocka in sentence`() {
            val result = detectKnownNzTerm("the pub is chocka tonight", sampleEntries)
            assertEquals("Chocka", result?.term)
        }

        @Test
        fun `detects taniwha in query`() {
            val result = detectKnownNzTerm("tell me about taniwha", sampleEntries)
            assertEquals("Taniwha", result?.term)
        }

        @Test
        fun `detects kumara in cooking question`() {
            val result = detectKnownNzTerm("how do you cook kumara", sampleEntries)
            assertEquals("Kumara", result?.term)
        }

        @Test
        fun `detects term with capital letters`() {
            val result = detectKnownNzTerm("Tell me about Taniwha", sampleEntries)
            assertEquals("Taniwha", result?.term)
        }

        @Test
        fun `returns first match when multiple terms present`() {
            val result = detectKnownNzTerm("kumara and taniwha", sampleEntries)
            // Should find Kumara first (it's earlier in the list)
            assertNotNull(result)
            assertTrue(result?.term == "Kumara" || result?.term == "Taniwha")
        }

        @Test
        fun `returns null for non-NZ text`() {
            val result = detectKnownNzTerm("What is the capital of France?", sampleEntries)
            assertNull(result)
        }

        @Test
        fun `returns null for non-NZ query that mentions Wikipedia`() {
            // This proves detectKnownNzTerm does not itself short-circuit on
            // the word "Wikipedia" — Battle of Hastings is simply not an NZ term.
            val result = detectKnownNzTerm("look up the Battle of Hastings on Wikipedia", sampleEntries)
            assertNull(result)
        }

        @Test
        fun `detects wharepaku even when Wikipedia is explicitly requested`() {
            // detectKnownNzTerm does not understand Wikipedia intent — it only
            // checks term presence. The Wikipedia bypass is handled by the caller
            // (ChatViewModel) which checks extractExplicitWikipediaQuery first.
            val result = detectKnownNzTerm("look up wharepaku on Wikipedia", sampleEntries)
            assertEquals("Wharepaku", result?.term)
        }

        @Test
        fun `detects taniwha even when Wikipedia is explicitly requested`() {
            val result = detectKnownNzTerm("look up taniwha on Wikipedia", sampleEntries)
            assertEquals("Taniwha", result?.term)
        }

        @Test
        fun `returns null for blank text`() {
            val result = detectKnownNzTerm("", sampleEntries)
            assertNull(result)
        }

        @Test
        fun `returns null for whitespace text`() {
            val result = detectKnownNzTerm("   ", sampleEntries)
            assertNull(result)
        }

        @Test
        fun `detects wharepaku in STT normalised follow-up`() {
            // After TranscriptNormaliser, "fattybaku" becomes "wharepaku",
            // so the downstream detection should find "wharepaku".
            val result = detectKnownNzTerm("where is the wharepaku", sampleEntries)
            assertEquals("Wharepaku", result?.term)
        }

        @Test
        fun `does not match short terms under 3 chars`() {
            val shortEntry = JandalPersona.NzTruthEntry(
                id = "nz_short",
                term = "NZ",
                category = "culture",
                definition = "New Zealand abbreviation.",
                triggerContext = "When the user says NZ.",
                vibeLevel = 1,
                vectorText = "NZ. New Zealand.",
                metadataJson = "{}",
            )
            val result = detectKnownNzTerm("I live in NZ", listOf(shortEntry))
            assertNull(result)
        }
    }

    @Nested
    inner class BuildKnownNzContextReplyTests {

        @Test
        fun `builds reply for wharepaku entry`() {
            val entry = sampleEntries.first { it.term == "Wharepaku" }
            val result = buildKnownNzContextReply(entry)
            assertTrue(result.contains("Wharepaku"))
            assertTrue(result.contains("toilet") || result.contains("restroom") || result.contains("bathroom"))
            assertTrue(result.endsWith("."))
        }

        @Test
        fun `builds reply for chocka entry`() {
            val entry = sampleEntries.first { it.term == "Chocka" }
            val result = buildKnownNzContextReply(entry)
            assertTrue(result.contains("Chocka"))
            assertTrue(result.contains("full") || result.contains("packed") || result.contains("chock-a-block"))
        }

        @Test
        fun `builds reply for taniwha entry`() {
            val entry = sampleEntries.first { it.term == "Taniwha" }
            val result = buildKnownNzContextReply(entry)
            assertTrue(result.contains("Taniwha"))
            assertTrue(result.contains("guardian") || result.contains("kaitiaki"))
        }

        @Test
        fun `builds reply for kumara entry`() {
            val entry = sampleEntries.first { it.term == "Kumara" }
            val result = buildKnownNzContextReply(entry)
            assertTrue(result.contains("Kumara"))
            assertTrue(result.contains("sweet potato"))
        }
    }
}
