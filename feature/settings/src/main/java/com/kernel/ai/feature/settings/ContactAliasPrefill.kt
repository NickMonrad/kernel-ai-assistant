package com.kernel.ai.feature.settings

/**
 * Prefill for [ContactAliasesScreen], usually from a `SettingsDeepLink` raised by a failed contact
 * lookup (#1859). Nothing here dials or saves: the user still picks a contact and taps Save.
 *
 * @property alias text to prefill in the "What do you call them?" field.
 * @property openAdd open the add-alias dialog on arrival.
 * @property openPicker launch the system contact picker as soon as the dialog opens.
 * @property suggestedName display name of a near-match contact, shown as a hint in the dialog.
 */
data class ContactAliasPrefill(
    val alias: String = "",
    val openAdd: Boolean = false,
    val openPicker: Boolean = false,
    val suggestedName: String? = null,
)
