package com.kernel.ai.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kernel.ai.R
import com.kernel.ai.core.ui.components.ErrorBanner
import com.kernel.ai.core.ui.components.FileAttachmentChip
import com.kernel.ai.core.ui.components.MessageBubble
import com.kernel.ai.core.ui.components.MessageInputBar
import com.kernel.ai.core.ui.components.TypingIndicator
import com.kernel.ai.core.ui.components.UserAvatar
import com.kernel.ai.feature.chat.ChatViewModel.VoiceCaptureState
import com.kernel.ai.feature.chat.model.Attachment
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ChatUiState
import com.kernel.ai.feature.chat.model.ToolCallInfo
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val voiceCaptureState by viewModel.voiceCaptureState.collectAsStateWithLifecycle()
    val voiceMode by viewModel.voiceMode.collectAsStateWithLifecycle()

    ChatContent(
        state = state,
        voiceCaptureState = voiceCaptureState,
        voiceMode = voiceMode,
        onSendMessage = viewModel::sendMessage,
        onToggleVoiceMode = viewModel::toggleVoiceMode,
        onBack = onNavigateBack,
        onNavigateToSettings = onNavigateToSettings,
        onRetryDownload = viewModel::retryDownload,
        onSelectWallpaper = viewModel::selectWallpaper,
        onToggleWallpaper = viewModel::toggleWallpaper,
        onClearConversation = viewModel::clearConversation,
        onRenameConversation = viewModel::renameConversation,
        onArchiveConversation = viewModel::archiveConversation,
        onDeleteConversation = viewModel::deleteConversation,
        onTogglePin = viewModel::togglePin,
        onExportConversation = viewModel::exportConversation,
        onShareConversation = viewModel::shareConversation,
        onCopyMessage = viewModel::copyMessage,
        onDeleteMessage = viewModel::deleteMessage,
        onRetryMessage = viewModel::retryMessage,
        onAttachFile = viewModel::attachFile,
        onRemoveAttachment = viewModel::removeAttachment,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatContent(
    state: ChatUiState,
    voiceCaptureState: VoiceCaptureState,
    voiceMode: ChatViewModel.VoiceMode,
    onSendMessage: (String, List<Attachment>) -> Unit,
    onToggleVoiceMode: () -> Unit,
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onRetryDownload: () -> Unit,
    onSelectWallpaper: () -> Unit,
    onToggleWallpaper: () -> Unit,
    onClearConversation: () -> Unit,
    onRenameConversation: (String) -> Unit,
    onArchiveConversation: () -> Unit,
    onDeleteConversation: () -> Unit,
    onTogglePin: () -> Unit,
    onExportConversation: () -> Unit,
    onShareConversation: () -> Unit,
    onCopyMessage: (ChatMessage) -> Unit,
    onDeleteMessage: (ChatMessage) -> Unit,
    onRetryMessage: (ChatMessage) -> Unit,
    onAttachFile: (File) -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showAttachmentPicker by remember { mutableStateOf(false) }
    // LoopListeningCueEffect removed — cue playback is handled by StartListeningCuePlayer
    // in ChatViewModel on VoiceInputEvent.ListeningStarted.

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
    // ---- Visual customisation (#906) wallpaper background ----
    val context = LocalContext.current
    val wallpaperPainter = remember(state.wallpaperType, state.wallpaperImageUri) {
        if (state.wallpaperType != "image") return@remember null
        state.wallpaperImageUri?.let { uri ->
            painterResource(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.conversationTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings))
                    }
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = { showRenameDialog = true; expanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.archive)) },
                            onClick = { onArchiveConversation(); expanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pin)) },
                            onClick = { onTogglePin(); expanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export)) },
                            onClick = { onExportConversation(); expanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            onClick = { onShareConversation(); expanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = { onDeleteConversation(); expanded = false },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            if (state.isModelReady) {
                FloatingActionButton(
                    onClick = onToggleVoiceMode,
                    containerColor = if (voiceMode != ChatViewModel.VoiceMode.None)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondary,
                ) {
                    Icon(
                        if (voiceMode != ChatViewModel.VoiceMode.None)
                            Icons.Default.MicOff
                        else
                            Icons.Default.Mic,
                        stringResource(R.string.voice_mode),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(
                    if (wallpaperPainter != null) Modifier.background(Color.Transparent)
                    else Modifier,
                ),
        ) {
            // Wallpaper background layer
            if (wallpaperPainter != null) {
                AsyncImage(
                    model = state.wallpaperImageUri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.15f),
                    contentScale = ContentScale.Crop,
                )
            }

            PullToRefreshBox(
                isRefreshing = false,
                state = rememberPullToRefreshState(),
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = state.messages,
                        key = { it.id },
                    ) { message ->
                        MessageBubble(
                            message = message,
                            onCopy = { onCopyMessage(message) },
                            onDelete = { onDeleteMessage(message) },
                            onRetry = { onRetryMessage(message) },
                        )
                    }
                    item {
                        if (state.isTyping) {
                            TypingIndicator(modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
            }

            // Scroll-to-bottom button
            if (showScrollToBottom) {
                FloatingActionButton(
                    onClick = { listState.scrollToItem(state.messages.lastIndex) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .padding(bottom = 80.dp),
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.scroll_to_bottom),
                    )
                }
            }

            // Attachment picker
            if (showAttachmentPicker) {
                // TODO: implement attachment picker
            }

            // Rename dialog
            if (showRenameDialog) {
                AlertDialog(
                    onDismissRequest = { showRenameDialog = false },
                    title = { Text(stringResource(R.string.rename_conversation)) },
                    text = {
                        var name by remember { mutableStateOf(state.conversationTitle) }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { showRenameDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = {
                            onRenameConversation(name)
                            showRenameDialog = false
                        }) {
                            Text(stringResource(R.string.save))
                        }
                    },
                )
            }

            // Model download progress
            if (state is ChatUiState.ModelDownloadProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.downloading_model),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            CircularProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(R.string.model_download_progress, (state.progress * 100).toInt()),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onRetryDownload) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }
        }
    }
}
