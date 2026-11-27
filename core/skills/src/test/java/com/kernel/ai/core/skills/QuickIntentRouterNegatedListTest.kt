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
 * #2155 — a negated or stylistic mention of "list" inside a prompt ("No lists.") must not
 * route to get_list_items, neither via regex nor via the classifier.
 */
class QuickIntentRouterNegatedListTest {

    /** Simulates the classifier that routed "No lists." to get_list_items on device. */
    private class ListBiasedClassifier : QuickIntentRouter.IntentClassifier {
        override fun classify(input: String): QuickIntentRouter.IntentClassifier.Classification =
            QuickIntentRouter.IntentClassifier.Classification("get_list_items", 0.95f)
    }

    private lateinit var regexOnlyRouter: QuickIntentRouter
    private lateinit var hybridRouter: QuickIntentRouter

    @BeforeEach
    fun setUp() {
        regexOnlyRouter = QuickIntentRouter()
        hybridRouter = QuickIntentRouter(classifier = ListBiasedClassifier())
    }

    @Nested
    @DisplayName("Negated or stylistic list mentions fall through")
    inner class NegatedMentions {

        @ParameterizedTest(name = "regex: \"{0}\" must not route to get_list_items")
        @ValueSource(
            strings = [
                "No lists.",
                "answer without a list",
                "don't list them",
                "no bullet points or lists please",
                "explain photosynthesis, no lists",
            ],
        )
        fun `negated mention does not match list regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.FallThrough::class.java, result, "route for '$input'")
        }

        @ParameterizedTest(name = "classifier: \"{0}\" must not route to get_list_items")
        @ValueSource(
            strings = [
                "No lists.",
                "answer without a list",
                "don't list them",
                "no bullet points or lists please",
                "explain photosynthesis, no lists",
            ],
        )
        fun `negated mention is not claimed by list classifier`(input: String) {
            val result = hybridRouter.route(input)
            assertInstanceOf(QuickIntentRouter.RouteResult.FallThrough::class.java, result, "route for '$input'")
        }
    }

    @Nested
    @DisplayName("Genuine list commands keep routing")
    inner class Positives {

        @ParameterizedTest(name = "regex: \"{0}\" → get_list_items")
        @ValueSource(strings = ["what's on my shopping list", "show my todo list"])
        fun `list read commands still match regex`(input: String) {
            val result = regexOnlyRouter.route(input)
            val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
            assertEquals("get_list_items", match.intent.intentName, "intent for '$input'")
        }

        @Test
        fun `show my lists still matches list read regex`() {
            val result = regexOnlyRouter.route("show my lists")
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
    }
}
