package com.kernel.ai.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KernelNavHostRouteTest {
    @Test
    fun `buildChatRoute returns base route when no extras requested`() {
        assertEquals("chat", buildChatRoute())
    }

    @Test
    fun `buildChatRoute encodes initial query and flags`() {
        assertEquals(
            "chat?initialQuery=plan%20meals%20%26%20snacks&minimalContext=true&speakResponse=true",
            buildChatRoute(
                initialQuery = "plan meals & snacks",
                minimalContext = true,
                speakResponse = true,
            ),
        )
    }

    @Test
    fun `buildNewMealPlanChatRoute starts fresh planner handoff`() {
        assertEquals(
            "chat?initialQuery=plan%20meals&minimalContext=true",
            buildNewMealPlanChatRoute(),
        )
    }
}
