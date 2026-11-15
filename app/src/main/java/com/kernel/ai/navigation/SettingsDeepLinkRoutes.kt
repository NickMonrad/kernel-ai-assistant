package com.kernel.ai.navigation

import android.os.Bundle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.kernel.ai.core.skills.ContactLookupRecovery
import com.kernel.ai.core.skills.ImportantDateRecovery
import com.kernel.ai.core.skills.SettingsDeepLink
import com.kernel.ai.feature.settings.ContactAliasPrefill
import com.kernel.ai.feature.settings.ImportantDatesDeepLink

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

/** Query args accepted by [ROUTE_IMPORTANT_DATES]. Names match [ImportantDateRecovery] keys (#1860). */
private val IMPORTANT_DATES_ROUTE_ARGS = listOf(
    ImportantDateRecovery.ARG_SYNC,
    ImportantDateRecovery.ARG_ADD,
    ImportantDateRecovery.ARG_LABEL,
)

/** Route pattern for Important dates with optional deep-link args. */
internal val IMPORTANT_DATES_ROUTE_PATTERN: String =
    "$ROUTE_IMPORTANT_DATES?" + IMPORTANT_DATES_ROUTE_ARGS.joinToString("&") { "$it={$it}" }

/** Optional string args, defaulting to empty, so plain navigation to [ROUTE_IMPORTANT_DATES] still matches. */
internal val IMPORTANT_DATES_NAV_ARGS: List<NamedNavArgument> = IMPORTANT_DATES_ROUTE_ARGS.map { key ->
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
    ContactLookupRecovery.PEOPLE_DESTINATION -> routeWithQuery(ROUTE_CONTACT_ALIASES, CONTACT_ALIAS_ROUTE_ARGS, link)
    ImportantDateRecovery.IMPORTANT_DATES_DESTINATION -> routeWithQuery(ROUTE_IMPORTANT_DATES, IMPORTANT_DATES_ROUTE_ARGS, link)
    else -> null
}

/** Builds `route?key=value&...` from the [keys] present in [link], percent-encoding every value. */
private fun routeWithQuery(route: String, keys: List<String>, link: SettingsDeepLink): String {
    val query = keys.mapNotNull { key -> link.args[key]?.let { "$key=${encodeRouteQueryValue(it)}" } }
    return if (query.isEmpty()) route else "$route?${query.joinToString("&")}"
}

/** Reads the prefill args from a People & Contacts back-stack entry. */
internal fun contactAliasPrefillFrom(args: Bundle?): ContactAliasPrefill = ContactAliasPrefill(
    alias = args?.getString(ContactLookupRecovery.ARG_ALIAS).orEmpty(),
    openAdd = args?.getString(ContactLookupRecovery.ARG_ADD) == "true",
    openPicker = args?.getString(ContactLookupRecovery.ARG_PICK) == "true",
    suggestedName = args?.getString(ContactLookupRecovery.ARG_SUGGESTED)?.takeIf { it.isNotBlank() },
    callRequested = args?.getString(ContactLookupRecovery.ARG_CALL) == "true",
)

/** Reads the arrival requests from an Important dates back-stack entry (#1860). */
internal fun importantDatesDeepLinkFrom(args: Bundle?): ImportantDatesDeepLink = importantDatesDeepLinkFrom(
    sync = args?.getString(ImportantDateRecovery.ARG_SYNC),
    add = args?.getString(ImportantDateRecovery.ARG_ADD),
    label = args?.getString(ImportantDateRecovery.ARG_LABEL),
)

/**
 * Pure form of [importantDatesDeepLinkFrom]: `sync` and `add` are the raw `"true"` flags, and a blank
 * [label] never opens the editor with an empty name.
 */
internal fun importantDatesDeepLinkFrom(sync: String?, add: String?, label: String?): ImportantDatesDeepLink =
    ImportantDatesDeepLink(
        syncRequested = sync == "true",
        addLabel = label?.takeIf { add == "true" && it.isNotBlank() },
    )
