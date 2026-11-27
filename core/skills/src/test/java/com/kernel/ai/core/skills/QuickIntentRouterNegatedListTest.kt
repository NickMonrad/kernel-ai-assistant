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
}
