package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickIntentRouterMealPlannerTest {
    private val router = QuickIntentRouter()

    @Test
    fun `meal planner phrases route deterministically`() {
        val phrases = listOf(
            "plan meals",
            "let's plan meals",
            "create a meal plan",
            "plan my meals",
            "I'd like to plan meals",
            "I’d like to plan my meals",
            "Make me a meal plan",
            "I need a meal plan",
            "I want a meal plan",
            "I'd like a meal plan",
            "meal plan please",
            "could you make me a meal plan",
            "start meal planning",
            "set up a meal plan",
            "organize the weekly menu",
            "plan the menu",
            "let's plan dinners",
            "map out our meals",
            "start meal prep",
            "prep meals for the week",
        )

        phrases.forEach { input ->
            val result = router.route(input)
            assertTrue(result is QuickIntentRouter.RouteResult.RegexMatch, "Expected regex match for '$input'")
            assertEquals(
                "start_meal_planner",
                (result as QuickIntentRouter.RouteResult.RegexMatch).intent.intentName,
                "intent for '$input'",
            )
        }
    }
}
