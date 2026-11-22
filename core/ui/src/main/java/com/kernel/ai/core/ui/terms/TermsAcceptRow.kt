package com.kernel.ai.core.ui.terms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Label of the Terms of Use checkbox. Tests and the setup step look it up by this text (#1998). */
const val TERMS_CHECKBOX_LABEL = "I agree to the Terms of Use"

/** Label of the link that opens the Terms of Use sheet. */
const val TERMS_LINK_LABEL = "Read the Terms of Use"

/**
 * "I agree to the Terms of Use" checkbox with a link that opens the Terms sheet (#1998). Tapping the label or the
 * box toggles the checkbox; the link is a separate control so opening the Terms never changes the checkbox.
 */
@Composable
fun TermsAcceptRow(
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    onOpenTerms: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = accepted, role = Role.Checkbox, onValueChange = onAcceptedChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = accepted, onCheckedChange = null)
            Text(
                text = TERMS_CHECKBOX_LABEL,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        TermsOfUseLink(onClick = onOpenTerms, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Link-style button that opens the Terms sheet. Keeps the 48dp minimum touch target. */
@Composable
fun TermsOfUseLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text = TERMS_LINK_LABEL, style = MaterialTheme.typography.labelLarge)
    }
}

/** [TermsOfUseLink] that opens the bundled Terms sheet itself. Put it under a download row (#1998). */
@Composable
fun TermsOfUseLinkWithSheet(modifier: Modifier = Modifier) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    TermsOfUseLink(onClick = { sheetOpen = true }, modifier = modifier)
    if (sheetOpen) {
        TermsSheet(termsText = rememberBundledTerms(), onDismiss = { sheetOpen = false })
    }
}
