package com.kernel.ai.navigation

import android.os.Bundle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.kernel.ai.core.skills.ContactLookupRecovery
import com.kernel.ai.core.skills.SettingsDeepLink
import com.kernel.ai.feature.settings.ContactAliasPrefill

/** Query args accepted by [ROUTE_CONTACT_ALIASES]. Names match [ContactLookupRecovery] keys. */
private val CONTACT_ALIAS_ROUTE_ARGS = listOf(
    ContactLookupRecovery.ARG_ALIAS,
    ContactLookupRecovery.ARG_ADD,
    ContactLookupRecovery.ARG_PICK,
    ContactLookupRecovery.ARG_SUGGESTED,
    ContactLookupRecovery.ARG_CALL,
)

/** Route pattern for People & Contacts with optional prefill args (#1859). */
internal val CONTACT_ALIAS_ROUTE_PATTERN: String =
    "$ROUTE_CONTACT_ALIASES?" + CONTACT_ALIAS_ROUTE_ARGS.joinToString("&") { "$it={$it}" }

/** Optional string args, defaulting to empty, so plain navigation to [ROUTE_CONTACT_ALIASES] still matches. */
internal val CONTACT_ALIAS_NAV_ARGS: List<NamedNavArgument> = CONTACT_ALIAS_ROUTE_ARGS.map { key ->
    navArgument(key) {
        type = NavType.StringType
        defaultValue = ""
    }
}

/**
 * Maps a [SettingsDeepLink] from core/skills to an in-app route. Returns null for unknown
 * destinations, so a stale link does nothing rather than crashing.
 */
internal fun settingsDeepLinkRoute(link: SettingsDeepLink): String? = when (link.destination) {
    ContactLookupRecovery.PEOPLE_DESTINATION -> {
        val query = CONTACT_ALIAS_ROUTE_ARGS.mapNotNull { key ->
            link.args[key]?.let { "$key=${encodeRouteQueryValue(it)}" }
        }
        if (query.isEmpty()) ROUTE_CONTACT_ALIASES else "$ROUTE_CONTACT_ALIASES?${query.joinToString("&")}"
    }
    else -> null
}

/** Reads the prefill args from a People & Contacts back-stack entry. */
internal fun contactAliasPrefillFrom(args: Bundle?): ContactAliasPrefill = ContactAliasPrefill(
    alias = args?.getString(ContactLookupRecovery.ARG_ALIAS).orEmpty(),
    openAdd = args?.getString(ContactLookupRecovery.ARG_ADD) == "true",
    openPicker = args?.getString(ContactLookupRecovery.ARG_PICK) == "true",
    suggestedName = args?.getString(ContactLookupRecovery.ARG_SUGGESTED)?.takeIf { it.isNotBlank() },
    callRequested = args?.getString(ContactLookupRecovery.ARG_CALL) == "true",
)
