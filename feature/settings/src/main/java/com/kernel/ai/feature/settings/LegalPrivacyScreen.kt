package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val PRIVACY_POLICY_URL = "https://nickmonrad.github.io/kernel-ai-assistant/privacy/"
internal const val TERMS_OF_USE_URL = "https://nickmonrad.github.io/kernel-ai-assistant/terms/"
private const val NOTICE_ASSET_PATH = "legal/NOTICE.txt"
private const val FUTO_LICENCE_ASSET_PATH = "legal/FUTO-Model-Weights-License-1.0.txt"
internal val RUNTIME_NOTICES = listOf(
    "LiteRT 2.1.0 licence" to "litert-2.1.0-licence.txt",
    "LiteRT-LM 0.19.1 third-party notices" to "litert-lm-0.19.1-third-party-notices.txt",
    "Android NDK r28c libc++ licence" to "ndk-r28c-libcxx-licence.txt",
    "Android NDK r28c libc++abi licence" to "ndk-r28c-libcxxabi-licence.txt",
    "ONNX Runtime 1.24.3 licence" to "onnxruntime-1.24.3-licence.txt",
    "ONNX Runtime 1.24.3 third-party notices" to "onnxruntime-1.24.3-third-party-notices.txt",
    "ExecuTorch 1.5.1 licence" to "executorch-1.5.1-licence.txt",
    "ExecuTorch 1.5.1 third-party notices" to "executorch-1.5.1-third-party-notices.txt",
    "fbjni 0.7.0 licence" to "fbjni-0.7.0-licence.txt",
    "Sherpa-ONNX 1.13.0 Inflect third-party notices" to "sherpa-onnx-1.13.0-inflect-third-party-notices.txt",
    "openWakeWord 0.5.1 notices" to "openwakeword-0.5.1-notices.txt",
    "CC BY-NC-SA 4.0 legal code (Hey Jandal and openWakeWord models)" to "cc-by-nc-sa-4.0-legalcode.txt",
)

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
            HorizontalDivider()
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
    loadAboutLibrariesJson: suspend () -> String,
) {
    val context = LocalContext.current
    val notice by produceState("", context) {
        value = withContext(Dispatchers.IO) {
            context.assets.open(NOTICE_ASSET_PATH).bufferedReader().use { it.readText() }
        }
    }
    val futoLicence by produceState("", context) {
        value = withContext(Dispatchers.IO) {
            context.assets.open(FUTO_LICENCE_ASSET_PATH).bufferedReader().use { it.readText() }
        }
    }
    var selectedNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val noticePages by produceState(emptyList<String>(), context, selectedNotice) {
        value = emptyList()
        val filename = selectedNotice ?: return@produceState
        value = withContext(Dispatchers.IO) {
            // Keep each layout small even for a vendor's multi-megabyte notice.
            context.assets.open("legal/$filename").bufferedReader().useLines { lines ->
                lines.chunked(48) { it.joinToString("\n") }.toList()
            }
        }
    }
    val libraries = com.mikepenz.aboutlibraries.ui.compose.produceLibraries(loadAboutLibrariesJson).value

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
                }
                RUNTIME_NOTICES.forEach { (title, filename) ->
                    item(key = filename) {
                        ListItem(
                            modifier = Modifier.clickable {
                                selectedNotice = if (selectedNotice == filename) null else filename
                            },
                            headlineContent = { Text(title) },
                            supportingContent = { Text(if (selectedNotice == filename) "Hide notice" else "Read offline") },
                        )
                    }
                    if (selectedNotice == filename) {
                        items(noticePages) { page ->
                            SelectionContainer {
                                Text(page, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                }
                item {
                    Text("Resolved release dependencies", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                }
            },
            footer = {
                item {
                    Text("asr4all speech model licence text", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    SelectionContainer {
                        Text(futoLicence, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsVoicesWakeWordScreen(onBack: () -> Unit) {
    val isReleaseBuild = (LocalContext.current.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0
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
            ReleaseAssetAttributionCatalog.entriesFor(isReleaseBuild).forEach { asset ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(asset.name, style = MaterialTheme.typography.titleSmall)
                    Text("Purpose / category: ${asset.purposeCategory}", style = MaterialTheme.typography.bodySmall)
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
