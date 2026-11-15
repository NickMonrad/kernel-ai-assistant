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
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.ModelDownloadManager
import com.kernel.ai.core.model.availability.ConversationModelReadiness
import com.kernel.ai.core.ui.rememberDownloadNotificationGate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val startAssistantDownload = rememberDownloadNotificationGate { id ->
        id?.let { viewModel.downloadAssistantModel(KernelModel.valueOf(it)) }
    }
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
            onDownloadModel = { model -> startAssistantDownload(model.name) },
            onUseMobileData = viewModel::useMobileData,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@HiltViewModel
class GettingStartedViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager,
) : ViewModel() {

    internal val download: StateFlow<WelcomeDownload> = combine(
        downloadManager.downloadStates,
        ChatsDebugOverrides.simulateDownload,
        downloadManager.waitingForWifi,
    ) { states, simulate, waiting -> downloadFor(states, waiting, simulate) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            downloadFor(downloadManager.downloadStates.value, downloadManager.waitingForWifi.value, simulate = false),
        )

    fun downloadAssistantModel(model: KernelModel) {
        downloadManager.startDownload(model, force = false)
    }

    /** Chooses mobile data for downloads waiting on Wi-Fi, same as the first-run welcome (#1855). */
    fun useMobileData() {
        downloadManager.setDownloadNetworkChoice(DownloadNetworkChoice.MOBILE_DATA_ALLOWED)
    }

    private fun downloadFor(
        states: Map<KernelModel, com.kernel.ai.core.inference.download.DownloadState>,
        waitingForWifi: Set<KernelModel>,
        simulate: Boolean,
    ): WelcomeDownload {
        val readiness: ConversationModelReadiness = if (simulate) {
            ChatsDebugOverrides.SimulatedDownload
        } else {
            ConversationModelReadiness.compute(downloadManager.deviceTier, states)
        }
        return welcomeDownloadFor(readiness, states, waitingForWifi)
    }
}
