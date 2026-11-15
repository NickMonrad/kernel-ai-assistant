package com.kernel.ai.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `navigation_route` extras accepted by MainActivity → KernelNavHost (Lists deep links, #1553 stopwatch). */
class ExternalNavigationRouteTest {

    @Test
    fun `stopwatch notification route is the existing Clock stopwatch destination`() {
        assertEquals("settings/side_panel?tab=stopwatch", ROUTE_CLOCK_STOPWATCH)
        assertEquals(ShortcutRegistry.clockStopwatch.route, ROUTE_CLOCK_STOPWATCH)
    }

    @Test
    fun `stopwatch notification route is accepted`() {
        assertTrue(isAllowedExternalNavigationRoute(ROUTE_CLOCK_STOPWATCH))
    }

    @Test
    fun `lists shortcut and widget deep links are still accepted`() {
        assertTrue(isAllowedExternalNavigationRoute("lists"))
        assertTrue(isAllowedExternalNavigationRoute("lists/42"))
    }

    @Test
    fun `other routes are rejected, including other clock tabs and settings`() {
        for (route in listOf(
            "",
            "chat",
            "lists/abc",
            "lists/42/extra",
            "settings/side_panel",
            "settings/side_panel?tab=timer",
            "settings/side_panel?tab=stopwatch&x=1",
            "settings/model_management?scrollTo=true",
        )) {
            assertFalse(isAllowedExternalNavigationRoute(route), route)
        }
    }
}
