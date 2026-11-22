package com.kernel.ai.core.ui.terms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled copy of the Terms of Use; kept identical to the root TERMS.md by `TermsParityTest`. */
const val TERMS_ASSET_PATH = "legal/TERMS.md"

/**
 * Loads the bundled Terms of Use. Null while loading; an empty string when the asset cannot be read.
 */
@Composable
fun rememberBundledTerms(): String? {
    val context = LocalContext.current
    val terms by produceState<String?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(TERMS_ASSET_PATH).bufferedReader().use { it.readText() } }
                .getOrDefault("")
        }
    }
    return terms
}

/**
 * The Terms of Use as an in-app sheet (#1998). The sheet opens over the current screen and closes back to it, so
 * the caller's step and checkbox state stay as they were. [termsText] null means loading; blank means the asset
 * could not be read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsSheet(termsText: String?, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Terms of Use", style = MaterialTheme.typography.titleLarge)
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                when {
                    termsText == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    termsText.isBlank() -> Text(
                        text = "The Terms of Use could not be loaded. Try again after restarting Jandal.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        TermsDocumentBody(termsText = termsText)
                    }
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Text("Done")
            }
        }
    }
}
