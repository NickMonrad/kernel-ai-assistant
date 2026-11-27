package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * #2155 — a negated or stylistic mention of "list" inside a whole prompt must not route to a list
 * intent, neither via regex (list-name capture running across sentences) nor via the classifier.
 */
class QuickIntentRouterNegatedListTest {

    /** Simulates the one list label the production classifier actually emits (intent_phrases.json). */
    private class AddToListBiasedClassifier : QuickIntentRouter.IntentClassifier {
        override fun classify(input: String): QuickIntentRouter.IntentClassifier.Classification =
            QuickIntentRouter.IntentClassifier.Classification("add_to_list", 0.95f)
    }

    /** Simulates a classifier claiming a list read; used to prove status questions are not vetoed. */
    private class GetListItemsBiasedClassifier : QuickIntentRouter.IntentClassifier {
        override fun classify(input: String): QuickIntentRouter.IntentClassifier.Classification =
            QuickIntentRouter.IntentClassifier.Classification("get_list_items", 0.95f)
    }

    private lateinit var regexOnlyRouter: QuickIntentRouter
    private lateinit var addToListRouter: QuickIntentRouter
    private lateinit var getListItemsRouter: QuickIntentRouter

    @BeforeEach
    fun setUp() {
        regexOnlyRouter = QuickIntentRouter()
        addToListRouter = QuickIntentRouter(classifier = AddToListBiasedClassifier())
        getListItemsRouter = QuickIntentRouter(classifier = GetListItemsBiasedClassifier())
    }

    @Nested
    @DisplayName("Whole prompts with a negated list mention fall through")
    inner class NegatedMentions {

        @ParameterizedTest(name = "regex: \"{0}\" falls through")
        @ValueSource(
            strings = [
                "What's on your mind today? No lists.",
                "Describe New Zealand. No lists.",
                "Read me a short story about a dragon. No lists.",
                "Summarise WW2 in 3 paragraphs. No lists.",
                "No lists.",
                "answer without a list",
                "don't list them",
                "no bullet points or lists please",
                "explain photosynthesis, no lists",
                "no need to add bread to the list",
                "don't remove eggs from my list",
                "don't clear my shopping list",
                "don't add anything to my list",
            ],
        )
        fun `negated mention does not match list regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.FallThrough::class.java, result, "route for '$input'")
        }

        @ParameterizedTest(name = "add_to_list classifier: \"{0}\" falls through")
        @ValueSource(
            strings = [
                "What's on your mind today? No lists.",
                "Describe New Zealand. No lists.",
                "Read me a short story about a dragon. No lists.",
                "Summarise WW2 in 3 paragraphs. No lists.",
                "No lists.",
                "answer without a list",
                "don't list them",
                "no bullet points or lists please",
                "explain photosynthesis, no lists",
                "no need to add bread to the list",
                "don't remove eggs from my list",
                "don't clear my shopping list",
                "don't add anything to my list",
                "I can't add eggs to my shopping list",
                "I won't add eggs to my shopping list",
            ],
        )
        fun `negated mention is not claimed by add_to_list classifier`(input: String) {
            val result = addToListRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.FallThrough::class.java, result, "route for '$input'")
        }
    }

    @Nested
    @DisplayName("Status questions and genuine list commands keep routing")
    inner class Positives {

        @ParameterizedTest(name = "regex: \"{0}\" → get_list_items list \"shopping list\"")
        @ValueSource(strings = ["what's on my shopping list", "show my shopping list"])
        fun `list read commands still match regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
            assertEquals("get_list_items", match.intent.intentName, "intent for '$input'")
            assertEquals("shopping list", match.intent.params["list_name"], "list_name for '$input'")
        }

        @Test
        fun `show my list not the reminders still reaches the list tool`() {
            val result = regexOnlyRouter.route("show my list, not the reminders")
            val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
            assertEquals("get_list_items", match.intent.intentName)
        }

        @Test
        fun `add milk to my list still asks for list slot`() {
            val result = regexOnlyRouter.route("add milk to my list")
            val needsSlot = assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result)
            assertEquals("add_to_list", needsSlot.intent.intentName)
            assertEquals("milk", needsSlot.intent.params["item"])
        }

        @Test
        fun `add milk to my list still reaches add_to_list through the add_to_list classifier`() {
            val result = addToListRouter.route("add milk to my list")
            val needsSlot = assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result)
            assertEquals("add_to_list", needsSlot.intent.intentName)
        }

        @ParameterizedTest(name = "get_list_items classifier: \"{0}\" is not vetoed")
        @ValueSource(strings = ["what's not done on my todo list", "what items are not checked on my list"])
        fun `negated status question is not vetoed for list reads`(input: String) {
            val result = getListItemsRouter.route(input)
            val match = assertInstanceOf(QuickIntentRouter.RouteResult.ClassifierMatch::class.java, result)
            assertEquals("get_list_items", match.intent.intentName, "intent for '$input'")
        }
    }

    @Nested
    @DisplayName("Captures stop only at real sentence ends, so decimals and hyphens survive")
    inner class SentenceEndCaptures {

        @Test
        fun `decimal item in add_to_list is kept whole`() {
            val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, regexOnlyRouter.route("add 2.5 kg flour to my shopping list"))
            assertEquals("add_to_list", match.intent.intentName)
            assertEquals("2.5 kg flour", match.intent.params["item"])
        }

        @Test
        fun `fraction-normalised item in add_to_list is kept whole`() {
            val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, regexOnlyRouter.route("add half a cup of sugar to my shopping list"))
            assertEquals("add_to_list", match.intent.intentName)
            assertEquals("0.5 cup of sugar", match.intent.params["item"])
        }

        @Test
        fun `decimal item in remove_from_list is kept whole`() {
            val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, regexOnlyRouter.route("remove 1.5L milk from my shopping list"))
            assertEquals("remove_from_list", match.intent.intentName)
            assertEquals("1.5L milk", match.intent.params["item"])
        }

        @Test
        fun `decimal list name in create_list is kept whole`() {
            val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, regexOnlyRouter.route("create a list called v2.0 ideas"))
            assertEquals("create_list", match.intent.intentName)
            assertEquals("v2.0 ideas", match.intent.params["list_name"])
        }

        @Test
        fun `hyphenated item starting with no is not treated as negation`() {
            val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, regexOnlyRouter.route("add no-sugar jam to my shopping list"))
            assertEquals("add_to_list", match.intent.intentName)
            assertEquals("no-sugar jam", match.intent.params["item"])
        }
    }

    @Nested
    @DisplayName("Negation is scoped to its clause, so a comma or colon ends it")
    inner class ClauseBreaks {

        @ParameterizedTest(name = "add_to_list classifier: \"{0}\" is not vetoed")
        @ValueSource(strings = ["No worries, add milk to my shopping list", "never mind, add bread to my grocery list"])
        fun `negation before a clause break does not veto add_to_list`(input: String) {
            val result = addToListRouter.route(input)
            assertEquals("add_to_list", intentNameOf(result), "intent for '$input'")
        }
    }

    @Nested
    @DisplayName("\"don't forget\" stays a memory write, not a list write")
    inner class ForgetIsMemory {

        @Test
        fun `don't forget to add milk to my list routes to save_memory`() {
            val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, regexOnlyRouter.route("don't forget to add milk to my list"))
            assertEquals("save_memory", match.intent.intentName)
            assertEquals("to add milk to my list", match.intent.params["content"])
        }
    }

    private fun intentNameOf(result: QuickIntentRouter.RouteResult): String? = when (result) {
        is QuickIntentRouter.RouteResult.RegexMatch -> result.intent.intentName
        is QuickIntentRouter.RouteResult.ClassifierMatch -> result.intent.intentName
        is QuickIntentRouter.RouteResult.NeedsSlot -> result.intent.intentName
        is QuickIntentRouter.RouteResult.FallThrough -> null
    }
}
