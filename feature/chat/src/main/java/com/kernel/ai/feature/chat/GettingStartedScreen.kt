package com.kernel.ai.feature.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.model.availability.ConversationModelReadiness
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Full-screen Getting started, reopened from the Chats menu, the Chats download banner, or Settings → About.
 * Replaces the earlier bottom sheet so system back returns to the screen that opened it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GettingStartedScreen(
    onBack: () -> Unit,
    onStartChatting: () -> Unit,
    onExamplePrompt: (String) -> Unit,
    viewModel: GettingStartedViewModel = hiltViewModel(),
) {
    val download by viewModel.download.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Getting started") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        GettingStartedContent(
            download = download,
            onStartChatting = onStartChatting,
            onExamplePrompt = onExamplePrompt,
            onDownloadModel = viewModel::downloadAssistantModel,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@HiltViewModel
class GettingStartedViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager,
) : ViewModel() {

    internal val download: StateFlow<WelcomeDownload> = downloadManager.downloadStates
        .map { states -> welcomeDownloadFor(readinessFor(states), states) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            welcomeDownloadFor(readinessFor(downloadManager.downloadStates.value), downloadManager.downloadStates.value),
        )

    fun downloadAssistantModel(model: KernelModel) {
        downloadManager.startDownload(model, force = false)
    }

    private fun readinessFor(states: Map<KernelModel, com.kernel.ai.core.inference.download.DownloadState>): ConversationModelReadiness =
        ConversationModelReadiness.compute(downloadManager.deviceTier, states)
}
