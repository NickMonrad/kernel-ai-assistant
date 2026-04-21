package com.kernel.ai.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.feature.chat.R
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ChatUiState
import com.kernel.ai.feature.chat.model.ChatUiState.ModelDownloadProgress
import com.kernel.ai.feature.chat.model.ToolCallInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String? = null,
    initialQuery: String? = null,
    onBack: () -> Unit = {},
    onNewConversation: () -> Unit = {},
    onNavigateToList: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Auto-send the initial query from Actions tab (runs once per navigation).
    // We only wait for conversation initialisation, NOT for the LLM to be ready.
    // NeedsSlot queries never invoke the model, and sendMessage() triggers model
    // loading internally for FallThrough/LLM queries — so this is safe either way.
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            val ready = withTimeoutOrNull(30_000L) {
                viewModel.isConversationReady.first { it }
            }
            if (ready != null) {
                viewModel.onInputChanged(initialQuery)
                viewModel.sendMessage()
            }
        }
    }

    when (val state = uiState) {
        is ChatUiState.Loading -> LoadingContent()
        is ChatUiState.ModelsNotReady -> OnboardingContent(
            isDownloading = state.isDownloading,
            modelProgress = state.modelProgress,
            onRetry = viewModel::retryDownload,
            onNavigateToSettings = onNavigateToSettings,
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
            snackbarHostState = snackbarHostState,
            onCopyMessage = { content ->
                clipboardManager.setText(AnnotatedString(stripMarkdownForClipboard(content)))
                scope.launch { snackbarHostState.showSnackbar("Message copied") }
            },
            onCopyAll = {
                scope.launch {
                    val text = withContext(Dispatchers.Default) {
                        stripMarkdownForClipboard(viewModel.getConversationAsText())
                    }
                    clipboardManager.setText(AnnotatedString(text))
                    snackbarHostState.showSnackbar("Conversation copied")
                }
            },
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
    snackbarHostState: SnackbarHostState,
    onCopyMessage: (String) -> Unit,
    onCopyAll: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }

    // Auto-scroll when a new message is appended, but only if the user is already
    // near the bottom (within 2 items). If they've scrolled up to read history,
    // the scroll-to-bottom button handles getting back. Uses instant scrollToItem
    // (not animated) so it never holds the scroll mutex long enough to fight gestures.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= state.messages.size - 2) {
                listState.scrollToItem(state.messages.lastIndex, scrollOffset = Int.MAX_VALUE)
            }
        }
    }

    // Show scroll-to-bottom button whenever there is content below the visible area.
    val showScrollToBottom by remember { derivedStateOf { listState.canScrollForward } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.conversationTitle ?: "Jandal",
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
                    IconButton(onClick = onCopyAll) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy conversation")
                    }
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
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            MessageBubble(
                                message = message,
                                onCopy = { content -> onCopyMessage(content) },
                                showThinkingProcess = state.showThinkingProcess,
                            )
                        }
                        if (state.isLoadingModel) {
                            item(key = "model-loading-indicator") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Loading model…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    // Scroll-to-bottom button — fades in/out when content exists below the viewport.
                    val scrollBtnAlpha by animateFloatAsState(
                        targetValue = if (showScrollToBottom) 1f else 0f,
                        label = "scrollBtnAlpha",
                    )
                    if (scrollBtnAlpha > 0f) {
                        Surface(
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(state.messages.lastIndex, scrollOffset = Int.MAX_VALUE)
                                }
                            },
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 4.dp,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 8.dp)
                                .graphicsLayer { alpha = scrollBtnAlpha },
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.cd_scroll_to_bottom),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    onCopy: (String) -> Unit,
    showThinkingProcess: Boolean = true,
) {
    val isUser = message.role == ChatMessage.Role.USER
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val richPresentation = message.toolCall?.presentation
    val surfacedFallbackLinks = if (!isUser && richPresentation == null && message.toolCall != null) {
        collectAdditionalUrls(
            visibleText = message.content,
            message.toolCall.resultText,
            message.toolCall.requestJson,
        )
    } else {
        emptyList()
    }
    val suppressAssistantBubble = !isUser && richPresentation != null && message.toolCall?.isSuccess == true
    var showMenu by remember { mutableStateOf(false) }

    // Both user and assistant messages use combinedClickable for long-press → context menu.
    // For assistant messages, MarkdownContent also passes onLongPress through to its
    // inner text composables so that long-press fires even on text with URL annotations.
    val bubbleModifier = Modifier
        .combinedClickable(onClick = {}, onLongClick = { showMenu = true })
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(label = "Copy message") { showMenu = true; true }
            )
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Thinking bubble — expandable, shows chain-of-thought content when tapped
        if (showThinkingProcess && !message.thinkingText.isNullOrBlank()) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Column(modifier = Modifier.padding(bottom = 4.dp).testTag("think_bubble")) {
                Row(
                    modifier = Modifier.clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Thinking…",
                        style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse thinking" else "Expand thinking",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .widthIn(max = 320.dp),
                    ) {
                        Text(
                            text = message.thinkingText!!,
                            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp).testTag("think_bubble_content"),
                        )
                    }
                }
            }
        }

        if (!isUser && richPresentation != null) {
            ToolPresentationContent(
                presentation = richPresentation,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .widthIn(max = 320.dp),
            )
        }

        // Tool call chip (shown above message bubble for assistant messages)
        if (!isUser && message.toolCall != null) {
            ToolCallChip(
                toolCall = message.toolCall,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .widthIn(max = 300.dp),
            )
        }

        if (!suppressAssistantBubble) {
            Box(modifier = bubbleModifier) {
                Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = if (isUser) 18.dp else 4.dp,
                        topEnd = if (isUser) 4.dp else 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 18.dp,
                    ),
                    modifier = Modifier
                        .widthIn(max = 300.dp),
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
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
                                onLongPress = { showMenu = true },
                            )
                            if (surfacedFallbackLinks.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                ToolLinkList(urls = surfacedFallbackLinks)
                            }
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

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy message") },
                        onClick = {
                            showMenu = false
                            onCopy(message.content)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                        },
                    )
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
                placeholder = { Text("Message Jandal…") },
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
                text = "\uD83E\uDE74",
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = "Kia ora! What can I help with?",
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
    // Icons cycle through the three loading phases: model init → warmup → ready
    val stepIcons = listOf(
        Icons.Outlined.Memory,
        Icons.Outlined.Bolt,
        Icons.Outlined.CheckCircle,
    )
    var step by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        repeat(steps.size - 1) { i ->
            delay(2_500L)
            step = i + 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(400)) +
                        slideInVertically(animationSpec = tween(400)) { it / 2 }) togetherWith
                        (fadeOut(animationSpec = tween(200)) +
                            slideOutVertically(animationSpec = tween(200)) { -it / 2 })
                },
                modifier = Modifier.padding(top = 16.dp),
                label = "loadingStep",
            ) { currentStep ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = stepIcons[currentStep],
                        contentDescription = null,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = steps[currentStep],
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
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
}

@Composable
private fun OnboardingContent(
    isDownloading: Boolean,
    modelProgress: List<ModelDownloadProgress>,
    onRetry: (KernelModel) -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Text(text = "🧠", style = MaterialTheme.typography.displayLarge)
            Text(
                text = "Kia ora, welcome to Jandal",
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
                        ModelProgressRow(item, onRetry = onRetry, onNavigateToSettings = onNavigateToSettings)
                    }
                }
            } else if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

@Composable
private fun ModelProgressRow(
    item: ModelDownloadProgress,
    onRetry: (KernelModel) -> Unit,
    onNavigateToSettings: () -> Unit,
) {
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
                if (item.model.isGated) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Sign in to HuggingFace to download",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = onNavigateToSettings,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Sign in", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
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
}

private fun formatEta(remainingMs: Long): String {
    val totalSecs = remainingMs / 1000
    return when {
        totalSecs < 60 -> "~${totalSecs}s remaining"
        totalSecs < 3600 -> "~${totalSecs / 60}m ${totalSecs % 60}s remaining"
        else -> "~${totalSecs / 3600}h ${(totalSecs % 3600) / 60}m remaining"
    }
}

@Composable
private fun ToolCallChip(toolCall: ToolCallInfo, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val toolLinks = remember(toolCall.requestJson, toolCall.resultText) {
        collectAdditionalUrls(
            visibleText = "",
            toolCall.resultText,
            toolCall.requestJson,
        )
    }
    val clipboardManager = LocalClipboardManager.current
    Surface(
        modifier = modifier.fillMaxWidth().testTag("tool_chip"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔧", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (toolCall.isSuccess) toolCall.skillName else "⚠ ${toolCall.skillName}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Request: ${toolCall.requestJson}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Result: ${toolCall.resultText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (toolLinks.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ToolLinkList(urls = toolLinks)
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = {
                            val text = "[Tool: ${toolCall.skillName}]\nRequest: ${toolCall.requestJson}\nResult: ${toolCall.resultText}"
                            clipboardManager.setText(AnnotatedString(text))
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy tool call",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
