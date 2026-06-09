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

    @Test
    fun `buildActionsDraftRoute contains openSheet and draftQuery`() {
        val route = buildActionsDraftRoute("test query")
        assertEquals("actions?openSheet=true&draftQuery=test%20query", route)
    }

    @Test
    fun `buildActionsDraftRoute does not contain widgetQuery`() {
        val route = buildActionsDraftRoute("anything")
        assertEquals(false, route.contains("widgetQuery"))
    }

    @Test
    fun `buildActionsDraftRoute encodes special characters`() {
        val route = buildActionsDraftRoute("add milk & eggs")
        assertEquals("actions?openSheet=true&draftQuery=add%20milk%20%26%20eggs", route)
    }

    @Test
    fun `buildActionsDraftRoute encodes question mark`() {
        val route = buildActionsDraftRoute("What's the weather?")
        assertEquals(
            "actions?openSheet=true&draftQuery=What%27s%20the%20weather%3F",
            route,
        )
    }

    @Test
    fun `encodeRouteQueryValue encodes spaces as percent-20`() {
        assertEquals("hello%20world", encodeRouteQueryValue("hello world"))
    }

    @Test
    fun `encodeRouteQueryValue encodes ampersand`() {
        assertEquals("a%20%26%20b", encodeRouteQueryValue("a & b"))
    }
}
