package com.kernel.ai.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ChatUiState
import com.kernel.ai.feature.chat.model.ChatUiState.ModelDownloadProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String? = null,
    onBack: () -> Unit = {},
    onNewConversation: () -> Unit = {},
    onNavigateToList: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is ChatUiState.Loading -> LoadingContent()
        is ChatUiState.ModelsNotReady -> OnboardingContent(
            isDownloading = state.isDownloading,
            modelProgress = state.modelProgress,
            onRetry = viewModel::retryDownload,
        )
        is ChatUiState.Ready -> ChatContent(
            state = state,
            onInputChanged = viewModel::onInputChanged,
            onSend = viewModel::sendMessage,
            onCancel = viewModel::cancelGeneration,
            onBack = onBack,
            onNewConversation = {
                viewModel.startNewConversation()
                onNewConversation()
            },
            onRenameConversation = viewModel::renameConversation,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    state: ChatUiState.Ready,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onNewConversation: () -> Unit,
    onRenameConversation: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.conversationTitle ?: "Kernel",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { showRenameDialog = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNewConversation) {
                        Icon(Icons.Default.Add, contentDescription = "New conversation")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            if (state.messages.isEmpty()) {
                EmptyConversationHint(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                }
            }

            AnimatedVisibility(visible = state.error != null) {
                state.error?.let { err ->
                    val errorQuip = remember(err) { LoadingMessages.randomError() }
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = errorQuip,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            InputBar(
                text = state.inputText,
                isGenerating = state.isGenerating,
                onTextChanged = onInputChanged,
                onSend = onSend,
                onCancel = onCancel,
            modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    // Rename dialog triggered by tapping the title in the top bar.
    if (showRenameDialog) {
        var renameText by rememberSaveable(state.conversationTitle) { mutableStateOf(state.conversationTitle ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { Text("Enter a title…") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameText.trim()
                        if (trimmed.isNotBlank()) onRenameConversation(trimmed)
                        showRenameDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.USER
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Thinking text (collapsed, italics)
        if (!message.thinkingText.isNullOrBlank()) {
            Text(
                text = "Thinking…",
                style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }

        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = if (isUser) 18.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            if (isUser) {
                // User messages: plain text, no link/code parsing needed.
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            } else {
                // Assistant messages: render full Markdown with inline + block support.
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    val contentColor = LocalContentColor.current
                    MarkdownContent(
                        text  = message.content,
                        style = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
                    )
                    if (message.isStreaming) {
                        val generatingMessage = remember { LoadingMessages.randomGenerating() }
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = generatingMessage,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontStyle = FontStyle.Italic,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    text: String,
    isGenerating: Boolean,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = text,
                onValueChange = onTextChanged,
                placeholder = { Text("Message Kernel…") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { if (!isGenerating) onSend() }),
            )

            AnimatedVisibility(visible = isGenerating, enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Stop generation")
                }
            }

            AnimatedVisibility(visible = !isGenerating && text.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = onSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun EmptyConversationHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "👋",
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = "Hi, I'm Kernel. How can I help?",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    val theme = remember { LoadingMessages.randomTheme() }
    val steps = listOf(theme.first, theme.second, theme.third)
    var step by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        repeat(steps.size - 1) { i ->
            delay(2_500L)
            step = i + 1
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            CircularProgressIndicator()
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(400)) +
                        slideInVertically(animationSpec = tween(400)) { it / 2 }) togetherWith
                        (fadeOut(animationSpec = tween(200)) +
                            slideOutVertically(animationSpec = tween(200)) { -it / 2 })
                },
                modifier = Modifier.padding(top = 12.dp),
                label = "loadingStep",
            ) { currentStep ->
                Text(
                    text = steps[currentStep],
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = "${step + 1} / ${steps.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun OnboardingContent(
    isDownloading: Boolean,
    modelProgress: List<ModelDownloadProgress>,
    onRetry: (KernelModel) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Text(text = "🧠", style = MaterialTheme.typography.displayLarge)
            Text(
                text = "Welcome to Kernel",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = if (isDownloading) {
                    "Downloading AI models… please stay connected to Wi-Fi."
                } else {
                    "On-device AI models are required and will download automatically."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )

            if (modelProgress.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .padding(top = 28.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    modelProgress.forEach { item ->
                        ModelProgressRow(item, onRetry = onRetry)
                    }
                }
            } else if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

@Composable
private fun ModelProgressRow(item: ModelDownloadProgress, onRetry: (KernelModel) -> Unit) {
    val state = item.state
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = when (state) {
                    is DownloadState.Downloaded -> "✓ Ready"
                    is DownloadState.Downloading -> {
                        val pct = (state.progress * 100).toInt()
                        if (state.bytesPerSecond > 0) {
                            val mbps = state.bytesPerSecond / 1_048_576.0
                            "$pct% · ${"%.1f".format(mbps)} MB/s"
                        } else "$pct%"
                    }
                    is DownloadState.Error -> "Error"
                    is DownloadState.NotDownloaded -> item.sizeLabel
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (state) {
                    is DownloadState.Downloaded -> MaterialTheme.colorScheme.primary
                    is DownloadState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        when (state) {
            is DownloadState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                if (state.remainingMs > 0) {
                    val etaText = formatEta(state.remainingMs)
                    Text(
                        text = etaText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            is DownloadState.Downloaded -> {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
            is DownloadState.Error -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Download failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { onRetry(item.model) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("Retry", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            is DownloadState.NotDownloaded -> {
                Text(
                    text = "Queued",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun formatEta(remainingMs: Long): String {
    val totalSecs = remainingMs / 1000
    return when {
        totalSecs < 60 -> "~${totalSecs}s remaining"
        totalSecs < 3600 -> "~${totalSecs / 60}m ${totalSecs % 60}s remaining"
        else -> "~${totalSecs / 3600}h ${(totalSecs % 3600) / 60}m remaining"
    }
}
