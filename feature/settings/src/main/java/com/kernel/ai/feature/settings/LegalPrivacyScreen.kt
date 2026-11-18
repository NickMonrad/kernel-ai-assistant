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
    onOpenModelsVoicesWakeWord: () -> Unit,
    onOpenDataPrivacySummary: () -> Unit,
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
                supportingContent = { Text("Opens online") },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openInAppBrowser(context, TERMS_OF_USE_URL) },
                headlineContent = { Text("Terms of Use") },
                supportingContent = { Text("Opens online") },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSourceLicenses),
                headlineContent = { Text("Open-source licences") },
                supportingContent = { Text("Licences for third-party software in Jandal") },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenModelsVoicesWakeWord),
                headlineContent = { Text("Models, voices & wake word") },
                supportingContent = { Text("Sources, licences and release status for model assets") },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()

            ListItem(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDataPrivacySummary),
                headlineContent = { Text("Data & Privacy Summary") },
                supportingContent = { Text("How Jandal handles your data") },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(
    onBack: () -> Unit,
    aboutLibrariesJson: String,
) {
    val context = LocalContext.current
    val notice = remember(context) {
        context.assets.open(NOTICE_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    val libraries = com.mikepenz.aboutlibraries.ui.compose.produceLibraries { aboutLibrariesJson }.value

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
        com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            header = {
                item {
                    Text("Third-party software notices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    SelectionContainer {
                        Text(notice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    Text("Resolved release dependencies", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsVoicesWakeWordScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models, voices & wake word") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()),
        ) {
            ReleaseAssetAttributionCatalog.entries.forEach { asset ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(asset.name, style = MaterialTheme.typography.titleSmall)
                    Text("Source: ${asset.sourceText}", style = MaterialTheme.typography.bodySmall)
                    Text("Licence / terms: ${asset.licenceText}", style = MaterialTheme.typography.bodySmall)
                    Text("Lineage: ${asset.lineageText}", style = MaterialTheme.typography.bodySmall)
                    Text("Release status: ${asset.releaseStatus}", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataPrivacySummaryScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data & Privacy Summary") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Text(
            "Data & Privacy Summary content is owned by issue #1260 and is not yet available.",
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
