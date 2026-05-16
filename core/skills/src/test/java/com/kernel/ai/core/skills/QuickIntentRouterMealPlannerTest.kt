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
