package com.kernel.ai.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.ImeAction
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.skills.ToolPresentationJson
import com.kernel.ai.core.voice.VoiceCaptureMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(
    autoOpenSheet: Boolean = false,
    autoStartVoiceCommand: Boolean = false,
    initialQuery: String? = null,
    adbSlotReply: String? = null,
    onNavigateToChat: (query: String) -> Unit = {},
    onNewConversation: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    viewModel: ActionsViewModel = hiltViewModel(),
) {
    val actions by viewModel.actions.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val pendingSlot by viewModel.pendingSlot.collectAsStateWithLifecycle()
    val voiceCaptureState by viewModel.voiceCaptureState.collectAsStateWithLifecycle()
    val voicePlaybackState by viewModel.voicePlaybackState.collectAsStateWithLifecycle()
    val currentVoiceCaptureState = voiceCaptureState
    val isCommandVoiceActive = when (currentVoiceCaptureState) {
        is ActionsViewModel.VoiceCaptureState.Preparing -> currentVoiceCaptureState.mode == VoiceCaptureMode.Command
        is ActionsViewModel.VoiceCaptureState.Listening -> currentVoiceCaptureState.mode == VoiceCaptureMode.Command
        is ActionsViewModel.VoiceCaptureState.Processing -> currentVoiceCaptureState.mode == VoiceCaptureMode.Command
        ActionsViewModel.VoiceCaptureState.Idle -> false
    }
    val voiceOverlayTranscript = when (currentVoiceCaptureState) {
        is ActionsViewModel.VoiceCaptureState.Listening -> currentVoiceCaptureState.transcript
        is ActionsViewModel.VoiceCaptureState.Processing -> currentVoiceCaptureState.transcript
        else -> ""
    }

    val context = LocalContext.current
    var showBottomSheet by rememberSaveable { mutableStateOf(false) }
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    var pendingPermissionMode by rememberSaveable { mutableStateOf<VoiceCaptureMode?>(null) }
    val listState = rememberLazyListState()

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val mode = pendingPermissionMode
        pendingPermissionMode = null
        if (!granted) {
            viewModel.onMicrophonePermissionDenied()
            return@rememberLauncherForActivityResult
        }
        when (mode) {
            VoiceCaptureMode.Command -> viewModel.startVoiceCommand()
            VoiceCaptureMode.SlotReply -> viewModel.startVoiceSlotReply()
            null -> Unit
        }
    }

    fun requestVoiceCapture(mode: VoiceCaptureMode) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            when (mode) {
                VoiceCaptureMode.Command -> viewModel.startVoiceCommand()
                VoiceCaptureMode.SlotReply -> viewModel.startVoiceSlotReply()
            }
            return
        }
        pendingPermissionMode = mode
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Auto-scroll to top when a new action result arrives (#607).
    LaunchedEffect(actions.firstOrNull()?.id) {
        if (actions.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // Auto-open the quick action sheet when navigated here via the FAB shortcut.
    // LaunchedEffect(Unit) ensures this runs once on initial composition only —
    // avoids re-opening the sheet on recomposition or after process death/restore.
    LaunchedEffect(Unit) {
        if (autoOpenSheet) showBottomSheet = true
    }

    LaunchedEffect(autoStartVoiceCommand) {
        if (autoStartVoiceCommand) {
            requestVoiceCapture(VoiceCaptureMode.Command)
        }
    }

    // ADB harness: auto-execute query when quick_action_input extra is provided.
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) viewModel.executeAction(initialQuery)
    }

    // ADB harness: deliver slot reply when slot_reply_input extra is provided.
    // onSlotReply guards internally — no-op if no slot is pending.
    LaunchedEffect(adbSlotReply) {
        if (!adbSlotReply.isNullOrBlank()) viewModel.onSlotReply(adbSlotReply)
    }

    // Collect one-shot navigation events from the ViewModel.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ActionsViewModel.UiEvent.NavigateToChat -> onNavigateToChat(event.query)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Actions") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    if (actions.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirmation = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear history")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = onNewConversation,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New conversation")
                }
                SmallFloatingActionButton(
                    onClick = {
                        if (isCommandVoiceActive) {
                            viewModel.stopVoiceCapture()
                        } else {
                            requestVoiceCapture(VoiceCaptureMode.Command)
                        }
                    },
                    containerColor = if (isCommandVoiceActive) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ) {
                    VoiceMicIcon(
                        active = isCommandVoiceActive,
                        contentDescription = if (isCommandVoiceActive) "Stop voice action" else "Start voice action",
                    )
                }
                FloatingActionButton(
                    onClick = { showBottomSheet = true },
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = "Run quick action")
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Loading/executing indicator
            AnimatedVisibility(
                visible = uiState != ActionsViewModel.UiState.Idle,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = when (uiState) {
                            is ActionsViewModel.UiState.Executing -> "Running action…"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // Error banner
            error?.let { errorMessage ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            if (voiceCaptureState != ActionsViewModel.VoiceCaptureState.Idle) {
                VoiceCaptureCard(
                    state = voiceCaptureState,
                    onStop = viewModel::stopVoiceCapture,
                )
            }

            if (voicePlaybackState is ActionsViewModel.VoicePlaybackState.Speaking) {
                val speaking = voicePlaybackState as ActionsViewModel.VoicePlaybackState.Speaking
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Speaking response…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::stopVoiceOutput) {
                            Text("Stop")
                        }
                    }
                    if (speaking.text.isNotBlank()) {
                        Text(
                            text = speaking.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize(),
            ) {
                // Action history or empty state
                if (actions.isEmpty() && uiState == ActionsViewModel.UiState.Idle) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚡", style = MaterialTheme.typography.displayMedium)
                            Text(
                                text = "No actions yet",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                text = "Tap ⚡ to run a quick command",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp,
                            vertical = 8.dp,
                        ),
                    ) {
                        items(actions, key = { it.id }) { action ->
                            ActionHistoryCard(
                                action = action,
                                onDelete = { viewModel.deleteAction(action.id) },
                            )
                        }
                    }
                }

                if (voiceOverlayTranscript.isNotBlank()) {
                    VoiceTranscriptOverlay(
                        state = currentVoiceCaptureState,
                        transcript = voiceOverlayTranscript,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    // Bottom sheet for quick command input
    if (showBottomSheet) {
        QuickActionBottomSheet(
            uiState = uiState,
            voiceCaptureState = voiceCaptureState,
            onDismiss = { showBottomSheet = false },
            onSubmit = { query ->
                viewModel.executeAction(query)
                showBottomSheet = false
            },
            onVoiceAction = {
                showBottomSheet = false
                requestVoiceCapture(VoiceCaptureMode.Command)
            },
            onStopVoiceAction = viewModel::stopVoiceCapture,
        )
    }

    // Slot-fill sheet — shown when QIR needs a missing parameter.
    // Swipe-down or cancel = silent dismiss, no log entry.
    pendingSlot?.let { slot ->
        SlotFillBottomSheet(
            promptMessage = slot.request.promptMessage,
            inputMode = slot.inputMode,
            uiState = uiState,
            voiceCaptureState = voiceCaptureState,
            onDismiss = { viewModel.cancelSlotFill() },
            onSubmit = { reply -> viewModel.onSlotReply(reply) },
            onVoiceReply = { requestVoiceCapture(VoiceCaptureMode.SlotReply) },
            onStopVoiceReply = viewModel::stopVoiceCapture,
        )
    }

    // Clear history confirmation
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear action history?") },
            text = { Text("All quick action history will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearConfirmation = false
                    }
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotFillBottomSheet(
    promptMessage: String,
    inputMode: InputMode,
    uiState: ActionsViewModel.UiState,
    voiceCaptureState: ActionsViewModel.VoiceCaptureState,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onVoiceReply: () -> Unit,
    onStopVoiceReply: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var inputText by rememberSaveable { mutableStateOf("") }
    // Guards against submit() and onDismissRequest firing simultaneously (e.g. tap Send + swipe).
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    val slotReplyCaptureState = when (voiceCaptureState) {
        is ActionsViewModel.VoiceCaptureState.Preparing -> voiceCaptureState.takeIf { it.mode == VoiceCaptureMode.SlotReply }
        is ActionsViewModel.VoiceCaptureState.Listening -> voiceCaptureState.takeIf { it.mode == VoiceCaptureMode.SlotReply }
        is ActionsViewModel.VoiceCaptureState.Processing -> voiceCaptureState.takeIf { it.mode == VoiceCaptureMode.SlotReply }
        ActionsViewModel.VoiceCaptureState.Idle -> null
    }
    val isVoiceReplyActive = slotReplyCaptureState != null

    fun submit() {
        val text = inputText.trim()
        if (text.isNotBlank() && !isSubmitting) {
            isSubmitting = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { cause ->
                if (cause == null) onSubmit(text)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = promptMessage,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (inputMode == InputMode.Voice) {
                Text(
                    text = "You can type a reply or tap the mic to answer by voice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Your answer…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    if (uiState == ActionsViewModel.UiState.Executing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (inputMode == InputMode.Voice) {
                                IconButton(
                                    onClick = {
                                        if (isVoiceReplyActive) {
                                            onStopVoiceReply()
                                        } else {
                                            onVoiceReply()
                                        }
                                    },
                                ) {
                                    VoiceMicIcon(
                                        active = isVoiceReplyActive,
                                        contentDescription = if (isVoiceReplyActive) "Stop voice reply" else "Reply by voice",
                                    )
                                }
                            }
                            IconButton(
                                onClick = { submit() },
                                enabled = inputText.isNotBlank(),
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Submit")
                            }
                        }
                    }
                },
            )

            when (slotReplyCaptureState) {
                is ActionsViewModel.VoiceCaptureState.Preparing -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Preparing offline voice input…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is ActionsViewModel.VoiceCaptureState.Listening -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (slotReplyCaptureState.transcript.isBlank()) {
                            "Listening for your reply…"
                        } else {
                            "Heard so far: ${slotReplyCaptureState.transcript}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is ActionsViewModel.VoiceCaptureState.Processing -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Processing: ${slotReplyCaptureState.transcript}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ActionsViewModel.VoiceCaptureState.Idle, null -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionBottomSheet(
    uiState: ActionsViewModel.UiState,
    voiceCaptureState: ActionsViewModel.VoiceCaptureState,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onVoiceAction: () -> Unit,
    onStopVoiceAction: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var inputText by rememberSaveable { mutableStateOf("") }
    val commandCaptureState = when (voiceCaptureState) {
        is ActionsViewModel.VoiceCaptureState.Preparing -> voiceCaptureState.takeIf { it.mode == VoiceCaptureMode.Command }
        is ActionsViewModel.VoiceCaptureState.Listening -> voiceCaptureState.takeIf { it.mode == VoiceCaptureMode.Command }
        is ActionsViewModel.VoiceCaptureState.Processing -> voiceCaptureState.takeIf { it.mode == VoiceCaptureMode.Command }
        ActionsViewModel.VoiceCaptureState.Idle -> null
    }
    val isVoiceCommandActive = commandCaptureState != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Quick Action",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Type a command or tap the mic for a voice action.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("What do you want to do?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    val isLoading = uiState != ActionsViewModel.UiState.Idle
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (isVoiceCommandActive) {
                                        onStopVoiceAction()
                                    } else {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion { cause ->
                                            if (cause == null) onVoiceAction()
                                        }
                                    }
                                },
                            ) {
                                VoiceMicIcon(
                                    active = isVoiceCommandActive,
                                    contentDescription = if (isVoiceCommandActive) "Stop voice action" else "Start voice action",
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (inputText.isNotBlank()) {
                                        val query = inputText.trim()
                                        inputText = ""
                                        scope.launch { sheetState.hide() }
                                        onSubmit(query)
                                    }
                                },
                                enabled = inputText.isNotBlank(),
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send")
                            }
                        }
                    }
                },
            )

            when (commandCaptureState) {
                is ActionsViewModel.VoiceCaptureState.Preparing -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Preparing offline voice input…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is ActionsViewModel.VoiceCaptureState.Listening -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (commandCaptureState.transcript.isBlank()) {
                            "Listening for your quick action…"
                        } else {
                            "Heard so far: ${commandCaptureState.transcript}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is ActionsViewModel.VoiceCaptureState.Processing -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Processing: ${commandCaptureState.transcript}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ActionsViewModel.VoiceCaptureState.Idle, null -> Unit
            }

            if (uiState == ActionsViewModel.UiState.Executing) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Running action…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceMicIcon(
    active: Boolean,
    contentDescription: String,
) {
    val pulse = if (active) {
        rememberInfiniteTransition(label = "voice-mic-pulse").animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "voice-mic-alpha",
        ).value
    } else {
        1f
    }
    Icon(
        imageVector = if (active) Icons.Default.Clear else Icons.Default.Mic,
        contentDescription = contentDescription,
        modifier = Modifier.size(if (active) 22.dp else 20.dp),
        tint = if (active) {
            MaterialTheme.colorScheme.error.copy(alpha = pulse)
        } else {
            androidx.compose.material3.LocalContentColor.current
        },
    )
}

@Composable
private fun VoiceTranscriptOverlay(
    state: ActionsViewModel.VoiceCaptureState,
    transcript: String,
    modifier: Modifier = Modifier,
) {
    val title = when (state) {
        is ActionsViewModel.VoiceCaptureState.Listening -> "Hearing"
        is ActionsViewModel.VoiceCaptureState.Processing -> "Processing"
        else -> "Voice"
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = transcript,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VoiceCaptureCard(
    state: ActionsViewModel.VoiceCaptureState,
    onStop: () -> Unit,
) {
    val title = when (state) {
        is ActionsViewModel.VoiceCaptureState.Preparing -> "Preparing offline voice input…"
        is ActionsViewModel.VoiceCaptureState.Listening -> when (state.mode) {
            VoiceCaptureMode.Command -> "Ready — speak your quick action"
            VoiceCaptureMode.SlotReply -> "Ready — speak your reply"
        }
        is ActionsViewModel.VoiceCaptureState.Processing -> "Processing speech…"
        ActionsViewModel.VoiceCaptureState.Idle -> return
    }
    val detail = when (state) {
        is ActionsViewModel.VoiceCaptureState.Listening -> state.transcript.ifBlank { "" }
        is ActionsViewModel.VoiceCaptureState.Processing -> state.transcript
        else -> ""
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (detail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onStop) {
                Text("Stop")
            }
        }
    }
}

@Composable
private fun ActionHistoryCard(
    action: QuickActionEntity,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var isOverflowing by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val presentation = remember(action.presentationJson) {
        ToolPresentationJson.fromJsonString(action.presentationJson)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (action.isSuccess) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            // Header: icon + query + delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (action.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (action.isSuccess) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = action.userQuery,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    action.skillName?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(
                                AnnotatedString(formatActionHistoryClipboardText(action)),
                            )
                        },
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy history item",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            if (presentation != null && action.isSuccess) {
                ToolPresentationContent(
                    presentation = presentation,
                    compact = true,
                )
            } else {
                val resultLinks = remember(action.resultText) { extractUrls(action.resultText) }
                Text(
                    text = action.resultText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    onTextLayout = { result -> if (result.hasVisualOverflow) isOverflowing = true },
                )
                if (resultLinks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    ToolLinkList(urls = resultLinks)
                }
                if (isOverflowing || expanded) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            text = if (expanded) "Show less" else "Show more",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            // Timestamp
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatActionTimestamp(action.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun formatActionHistoryClipboardText(action: QuickActionEntity): String = buildString {
    appendLine("Heard: ${action.userQuery}")
    action.skillName?.takeIf { it.isNotBlank() }?.let { appendLine("Action: $it") }
    append("Result: ${action.resultText}")
}

private fun formatActionTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(millis))
}
