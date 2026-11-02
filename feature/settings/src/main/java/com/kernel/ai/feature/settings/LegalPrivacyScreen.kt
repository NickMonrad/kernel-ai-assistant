package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal const val PRIVACY_POLICY_URL = "https://nickmonrad.github.io/kernel-ai-assistant/privacy/"
internal const val TERMS_OF_USE_URL = "https://nickmonrad.github.io/kernel-ai-assistant/terms/"
private const val NOTICE_ASSET_PATH = "legal/NOTICE.txt"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalPrivacyScreen(
    onBack: () -> Unit,
    onOpenSourceLicenses: () -> Unit,
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Legal & privacy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openInAppBrowser(context, PRIVACY_POLICY_URL) },
                headlineContent = { Text("Privacy Policy") },
                supportingContent = { Text("View the Privacy Policy on GitHub Pages") },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openInAppBrowser(context, TERMS_OF_USE_URL) },
                headlineContent = { Text("Terms of Use") },
                supportingContent = { Text("View the Terms of Use on GitHub Pages") },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSourceLicenses),
                headlineContent = { Text("Open-source licences") },
                supportingContent = { Text("Read bundled third-party notices offline") },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notice = remember(context) {
        context.assets.open(NOTICE_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open-source licences") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Bundled third-party notices",
                style = MaterialTheme.typography.titleMedium,
            )
            SelectionContainer {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
