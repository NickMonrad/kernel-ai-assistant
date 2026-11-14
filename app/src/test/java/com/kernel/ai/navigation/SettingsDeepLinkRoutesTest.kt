package com.kernel.ai.navigation

import com.kernel.ai.core.skills.ContactLookupRecovery
import com.kernel.ai.core.skills.SettingsDeepLink
import org.junit.jupiter.api.Assertions.assertEquals
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
}
