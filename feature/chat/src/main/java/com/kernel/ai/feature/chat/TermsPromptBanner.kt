package com.kernel.ai.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.terms.TermsAcceptRow
import com.kernel.ai.core.ui.terms.TermsSheet
import com.kernel.ai.core.ui.terms.rememberBundledTerms

/**
 * Non-blocking prompt for users who finished setup before this version of the Terms of Use (#1998). Ticking the
 * box accepts; "Not now" hides it for this version. Speech downloads stay gated until accepted.
 */
@Composable
internal fun TermsPromptBanner(
    onAccept: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var termsOpen by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "Please review Jandal's Terms of Use",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Speech model downloads need the Terms accepted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Accepting clears this banner: the view model hides it once the acceptance is stored.
            TermsAcceptRow(
                accepted = false,
                onAcceptedChange = onAccept,
                onOpenTerms = { termsOpen = true },
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
    if (termsOpen) {
        TermsSheet(termsText = rememberBundledTerms(), onDismiss = { termsOpen = false })
    }
}
