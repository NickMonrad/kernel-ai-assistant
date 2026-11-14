package com.kernel.ai.navigation

import com.kernel.ai.core.skills.ContactLookupRecovery
import com.kernel.ai.core.skills.SettingsDeepLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsDeepLinkRoutesTest {

    @Test
    fun `unknown destination maps to no route`() {
        assertNull(settingsDeepLinkRoute(SettingsDeepLink(destination = "not_a_screen", args = emptyMap())))
    }

    @Test
    fun `people destination without args opens plain People and Contacts`() {
        val route = settingsDeepLinkRoute(
            SettingsDeepLink(destination = ContactLookupRecovery.PEOPLE_DESTINATION, args = emptyMap()),
        )
        assertEquals(ROUTE_CONTACT_ALIASES, route)
    }

    @Test
    fun `alias with reserved characters is percent-encoded so args survive the route`() {
        val alias = "Mum & Dad = a 50% deal"
        val route = settingsDeepLinkRoute(
            SettingsDeepLink(
                destination = ContactLookupRecovery.PEOPLE_DESTINATION,
                args = mapOf(ContactLookupRecovery.ARG_ALIAS to alias, ContactLookupRecovery.ARG_ADD to "true"),
            ),
        )
        assertTrue(route!!.startsWith("$ROUTE_CONTACT_ALIASES?"))
        val encoded = route.substringAfter("alias=").substringBefore("&")
        assertTrue(!encoded.contains(" ") && !encoded.contains("&") && !encoded.contains("="))
        assertTrue(encoded.contains("%26") && encoded.contains("%3D") && encoded.contains("%25"))
    }

    @Test
    fun `unicode alias is encoded, not dropped`() {
        val route = settingsDeepLinkRoute(
            SettingsDeepLink(
                destination = ContactLookupRecovery.PEOPLE_DESTINATION,
                args = mapOf(ContactLookupRecovery.ARG_ALIAS to "Zoë"),
            ),
        )
        assertTrue(route!!.contains("alias=Zo%C3%AB"))
    }

    @Test
    fun `important dates sync link opens the screen with sync requested`() {
        val route = settingsDeepLinkRoute(
            SettingsDeepLink(destination = "important_dates", args = mapOf("sync" to "true")),
        )
        assertEquals("$ROUTE_IMPORTANT_DATES?sync=true", route)
    }

    @Test
    fun `important dates add link carries the label, percent-encoded`() {
        val route = settingsDeepLinkRoute(
            SettingsDeepLink(
                destination = "important_dates",
                args = mapOf("add" to "true", "label" to "Mum & Dad's birthday"),
            ),
        )
        assertEquals("$ROUTE_IMPORTANT_DATES?add=true&label=Mum%20%26%20Dad%27s%20birthday", route)
    }

    @Test
    fun `important dates link without args opens plain Important dates`() {
        val route = settingsDeepLinkRoute(SettingsDeepLink(destination = "important_dates"))
        assertEquals(ROUTE_IMPORTANT_DATES, route)
    }

    @Test
    fun `arrival label without add flag does not open the editor`() {
        val link = importantDatesDeepLinkFrom(sync = null, add = null, label = "Mum")
        assertNull(link.addLabel)
        assertFalse(link.syncRequested)
    }

    @Test
    fun `arrival add with blank label does not open the editor`() {
        val link = importantDatesDeepLinkFrom(sync = null, add = "true", label = "   ")
        assertNull(link.addLabel)
    }

    @Test
    fun `arrival add with label prefills the editor`() {
        val link = importantDatesDeepLinkFrom(sync = null, add = "true", label = "Sam's birthday")
        assertEquals("Sam's birthday", link.addLabel)
    }

    @Test
    fun `arrival sync flag only requests calendar sync`() {
        val link = importantDatesDeepLinkFrom(sync = "true", add = null, label = null)
        assertTrue(link.syncRequested)
        assertNull(link.addLabel)
    }
}
