package com.kernel.ai.feature.settings

import androidx.compose.runtime.saveable.Saver

/**
 * Prefill for [ContactAliasesScreen], usually from a `SettingsDeepLink` raised by a failed contact
 * lookup (#1859). Nothing here dials or saves: the user still picks a contact and taps Save.
 *
 * @property alias text to prefill in the "What do you call them?" field.
 * @property openAdd open the add-alias dialog on arrival.
 * @property openPicker launch the system contact picker as soon as the dialog opens.
 * @property suggestedName display name of a near-match contact, shown as a hint in the dialog.
 * @property callRequested the failed request was a call, so a successful save may offer to dial.
 */
data class ContactAliasPrefill(
    val alias: String = "",
    val openAdd: Boolean = false,
    val openPicker: Boolean = false,
    val suggestedName: String? = null,
    val callRequested: Boolean = false,
)

/**
 * Saves the screen's pending prefill. `null` means consumed, so the restored screen never reuses it.
 */
val ContactAliasPrefillSaver: Saver<ContactAliasPrefill?, Any> = Saver(
    save = { prefill ->
        if (prefill == null) {
            emptyList<Any?>()
        } else {
            listOf(prefill.alias, prefill.openAdd, prefill.openPicker, prefill.suggestedName, prefill.callRequested)
        }
    },
    restore = { saved ->
        val fields = saved as List<*>
        if (fields.isEmpty()) {
            null
        } else {
            ContactAliasPrefill(
                alias = fields[0] as String,
                openAdd = fields[1] as Boolean,
                openPicker = fields[2] as Boolean,
                suggestedName = fields[3] as String?,
                callRequested = fields[4] as Boolean,
            )
        }
    },
)
