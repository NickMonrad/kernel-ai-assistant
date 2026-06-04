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

    @Test
    fun `routes add bread to my list as missing list slot`() {
        val result = router.route("add bread to my list")
        val match = assertInstanceOf(QuickIntentRouter.RouteResult.NeedsSlot::class.java, result)
        assertEquals("add_to_list", match.intent.intentName)
        assertEquals("bread", match.intent.params["item"])
        assertEquals("list_name", match.missingSlot.name)
    }

    @Test
    fun `routes add to explicit shopping list with trailing please`() {
        val result = router.route("add milk to my shopping list please")
        val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
        assertEquals("add_to_list", match.intent.intentName)
        assertEquals("milk", match.intent.params["item"])
        assertEquals("shopping", match.intent.params["list_name"])
    }

    @Test
    fun `routes add to explicit shopping list with trailing punctuation`() {
        val result = router.route("add milk to my shopping list.")
        val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
        assertEquals("add_to_list", match.intent.intentName)
        assertEquals("milk", match.intent.params["item"])
        assertEquals("shopping", match.intent.params["list_name"])
    }

    @Test
    fun `routes add to explicit shopping list with comma please`() {
        val result = router.route("add milk to my shopping list, please")
        val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
        assertEquals("add_to_list", match.intent.intentName)
        assertEquals("milk", match.intent.params["item"])
        assertEquals("shopping", match.intent.params["list_name"])
    }

    @Test
    fun `routes put to explicit shopping list with comma please`() {
        val result = router.route("put milk on my shopping list, please")
        val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
        assertEquals("add_to_list", match.intent.intentName)
        assertEquals("milk", match.intent.params["item"])
        assertEquals("shopping", match.intent.params["list_name"])
    }

    @Test
    fun `routes informal add to explicit shopping list with comma please`() {
        val result = router.route("chuck milk on my shopping list, please")
        val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
        assertEquals("add_to_list", match.intent.intentName)
        assertEquals("milk", match.intent.params["item"])
        assertEquals("shopping", match.intent.params["list_name"])
    }

    @Test
    fun `routes add 111 over 70 to my blood pressure lost via list-tail mishear fix`() {
        // #982 — "lost" at end of list command → "list" by LIST_NAME_TAIL_MISHEAR_RE
        val result = router.route("add 111 over 70 to my blood pressure lost")

        val match = assertInstanceOf(QuickIntentRouter.RouteResult.RegexMatch::class.java, result)
        assertEquals("add_to_list", match.intent.intentName)
        assertEquals("111 over 70", match.intent.params["item"])
        assertEquals("blood pressure", match.intent.params["list_name"])
    }
}