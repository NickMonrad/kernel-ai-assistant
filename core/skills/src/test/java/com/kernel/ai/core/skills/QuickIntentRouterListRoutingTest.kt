package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class QuickIntentRouterListRoutingTest {

    private lateinit var router: QuickIntentRouter

    @BeforeEach
    fun setUp() {
        router = QuickIntentRouter()
    }

    @Test
    fun `routes whats in my shopping list via regex`() {
        val result = router.route("what's in my shopping list")

        val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
        assertEquals("get_list_items", match.intent.intentName)
        assertEquals("shopping", match.intent.params["list_name"])
    }

    @Test
    fun `routes display list called shopping via regex`() {
        val result = router.route("display list called shopping")

        val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
        assertEquals("get_list_items", match.intent.intentName)
        assertEquals("shopping", match.intent.params["list_name"])
    }
}
