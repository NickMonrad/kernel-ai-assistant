package com.kernel.ai.feature.chat

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.ImeAction
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.skills.ToolPresentationJson
import com.kernel.ai.core.permissions.RuntimePermissionRepair
import com.kernel.ai.core.ui.permissions.PermissionDialogAction
import com.kernel.ai.core.ui.permissions.PermissionOverlayDialog
import com.kernel.ai.core.permissions.VoicePermissionEntryPoint
import com.kernel.ai.core.permissions.VoicePermissionPromptFactory
import com.kernel.ai.core.permissions.VoicePermissionPromptState
import com.kernel.ai.core.ui.permissions.VoicePermissionPrompt
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.feature.chat.InputMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ACTIONS_SCREEN_TAG = "KernelAI"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsScreen(
    autoOpenSheet: Boolean = false,
    autoStartVoiceCommand: Boolean = false,
    initialQuery: String? = null,
    initialQueryIsVoice: Boolean = false,
    adbSlotReply: String? = null,
    draftQuery: String? = null,
    onDraftQueryConsumed: () -> Unit = {},
    onAutoOpenSheetConsumed: () -> Unit = {},
    onAutoStartVoiceConsumed: () -> Unit = {},
    onInitialQueryConsumed: () -> Unit = {},
    onNavigateToChat: (query: String, speakResponse: Boolean) -> Unit = { _, _ -> },
    onNewConversation: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAppPermissions: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    viewModel: ActionsViewModel = hiltViewModel(),
) {
    val actions by viewModel.actions.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val pendingSlot by viewModel.pendingSlot.collectAsStateWithLifecycle()
    val voiceCaptureState by viewModel.voiceCaptureState.collectAsStateWithLifecycle()
    val voicePlaybackState by viewModel.voicePlaybackState.collectAsStateWithLifecycle()
    val slotReplyAutoRearmArmed by viewModel.slotReplyAutoRearmArmed.collectAsStateWithLifecycle()
    val slotPromptPlaybackStarted by viewModel.slotPromptPlaybackStarted.collectAsStateWithLifecycle()
    val handsFreeCallingState by viewModel.handsFreeCallingState.collectAsStateWithLifecycle()
    val dndState by viewModel.dndState.collectAsStateWithLifecycle()
    val writeSettingsState by viewModel.writeSettingsState.collectAsStateWithLifecycle()
    val weatherLocationState by viewModel.weatherLocationState.collectAsStateWithLifecycle()
    val contactPermissionState by viewModel.contactPermissionState.collectAsStateWithLifecycle()
    val calendarPermissionState by viewModel.calendarPermissionState.collectAsStateWithLifecycle()
    val microphoneState by viewModel.microphoneState.collectAsStateWithLifecycle()
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

    fun openRuntimePermissionRepair(permission: String) {
        runCatching {
            context.startActivity(RuntimePermissionRepair.intentFor(context, permission))
        }
    }
    var initialSheetText by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    var showBottomSheet by rememberSaveable { mutableStateOf(false) }
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var showVoicePermissionPrompt by remember { mutableStateOf(false) }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.d(ACTIONS_SCREEN_TAG, "ActionsScreen: microphone permission result granted=$granted")
        if (!granted) {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                context as android.app.Activity,
                Manifest.permission.RECORD_AUDIO,
            )
            viewModel.onMicrophonePermissionDenied(shouldShowRationale)
            return@rememberLauncherForActivityResult
        }
        viewModel.onMicrophonePermissionGranted()
    }
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onPhonePermissionGranted()
        } else {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                context as android.app.Activity,
                Manifest.permission.CALL_PHONE,
            )
            viewModel.onPhonePermissionDenied(shouldShowRationale)
        }
    }

    val weatherLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onWeatherLocationPermissionGranted()
        } else {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                context as android.app.Activity,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            viewModel.onWeatherLocationPermissionDenied(shouldShowRationale)
        }
    }

    val contactPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onContactPermissionGranted()
        } else {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                context as android.app.Activity,
                Manifest.permission.READ_CONTACTS,
            )
            viewModel.onContactPermissionDenied(shouldShowRationale)
        }
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onCalendarPermissionGranted()
        } else {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                context as android.app.Activity,
                Manifest.permission.READ_CALENDAR,
            )
            viewModel.onCalendarPermissionDenied(shouldShowRationale)
        }
    }

    fun requestVoiceCapture(mode: VoiceCaptureMode) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(
            ACTIONS_SCREEN_TAG,
            "ActionsScreen: requestVoiceCapture mode=$mode alreadyGranted=$alreadyGranted",
        )
        if (alreadyGranted) {
            when (mode) {
                VoiceCaptureMode.Command -> viewModel.startVoiceCommand()
                VoiceCaptureMode.SlotReply -> viewModel.startVoiceSlotReply()
                VoiceCaptureMode.AlertCommand -> Unit
            }
            return
        }
        viewModel.onVoiceCaptureRequiresPermission(mode)
    }

    // Auto-scroll to top when a new action result arrives (#607).
    LaunchedEffect(actions.firstOrNull()?.id) {
        if (actions.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // Auto-open the quick action sheet when navigated here via the FAB shortcut.
    // LaunchedEffect(Unit) ensures this runs once on initial composition only —
    // avoids re-opening the sheet on recomposition or after process death/restore.
    LaunchedEffect(autoOpenSheet) {
        if (autoOpenSheet) showBottomSheet = true
        if (autoOpenSheet) onAutoOpenSheetConsumed()
    }

    // Tools example prompt: prefill the Quick Action sheet with draft text.
    // The sheet is already opened by autoOpenSheet (same route includes openSheet=true).
    // This only sets the draft text; no action is executed automatically.
    LaunchedEffect(draftQuery) {
        if (!draftQuery.isNullOrBlank()) {
            initialSheetText = draftQuery
            if (!showBottomSheet) showBottomSheet = true
            onDraftQueryConsumed()
        }
    }

    LaunchedEffect(autoStartVoiceCommand) {
        if (autoStartVoiceCommand) {
            onAutoStartVoiceConsumed()
            requestVoiceCapture(VoiceCaptureMode.Command)
        }
    }

    // Widget/ADB: auto-execute query when widgetQuery nav arg or quick_action_input extra is delivered.
    // onInitialQueryConsumed is called after executeAction so savedStateHandle prevents re-execution
    // if the composable is recomposed (e.g. after process-death restore).
    LaunchedEffect(initialQuery, initialQueryIsVoice) {
        if (!initialQuery.isNullOrBlank()) {
            val inputMode = if (initialQueryIsVoice) InputMode.Voice else InputMode.Text
            viewModel.executeAction(initialQuery, inputMode)
            onInitialQueryConsumed()
        }
    }

    // ADB harness: deliver slot reply when slot_reply_input extra is provided.
    // onSlotReply guards internally — no-op if no slot is pending.
    // Small delay (50ms) mitigates a race: when slot_reply_input arrives in the same
    // composition frame as quick_action_input, executeAction's viewModelScope.launch
    // coroutine (which primes _pendingSlot) hasn't run yet. Yielding the Main thread
    // via delay lets the queued coroutine set _pendingSlot before onSlotReply checks it.
    // Without this, onSlotReply sees _pendingSlot == null and silently drops the reply.
    LaunchedEffect(adbSlotReply) {
        if (!adbSlotReply.isNullOrBlank()) {
            delay(50L)
            viewModel.onSlotReply(adbSlotReply)
        }
    }

    // Collect one-shot navigation events from the ViewModel.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ActionsViewModel.UiEvent.NavigateToChat -> {
                    viewModel.pauseTransientVoiceUi(reason = "navigateToChat")
                    onNavigateToChat(event.query, event.speakResponse)
                }
                ActionsViewModel.UiEvent.RequestMicrophonePermission ->
                    showVoicePermissionPrompt = true
                ActionsViewModel.UiEvent.RequestPhonePermission ->
                    phonePermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                is ActionsViewModel.UiEvent.LaunchDialer -> {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:${Uri.encode(event.phoneNumber)}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    runCatching {
                        context.startActivity(dialIntent)
                    }
                }
                ActionsViewModel.UiEvent.NavigateToAppPermissions ->
                    onNavigateToAppPermissions()
                ActionsViewModel.UiEvent.RepairPhonePermission ->
                    openRuntimePermissionRepair(Manifest.permission.CALL_PHONE)
                ActionsViewModel.UiEvent.RepairLocationPermission ->
                    openRuntimePermissionRepair(Manifest.permission.ACCESS_COARSE_LOCATION)
                ActionsViewModel.UiEvent.RepairContactsPermission ->
                    openRuntimePermissionRepair(Manifest.permission.READ_CONTACTS)
                ActionsViewModel.UiEvent.RepairCalendarPermission ->
                    openRuntimePermissionRepair(Manifest.permission.READ_CALENDAR)
                ActionsViewModel.UiEvent.RepairMicrophonePermission ->
                    openRuntimePermissionRepair(Manifest.permission.RECORD_AUDIO)
                ActionsViewModel.UiEvent.OpenDndSettings -> {
                    val dndSettingsIntent = Intent(
                        android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    runCatching {
                        context.startActivity(dndSettingsIntent)
                    }
                }
                ActionsViewModel.UiEvent.OpenWriteSettings -> {
                    val writeSettingsIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    ).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    runCatching {
                        context.startActivity(writeSettingsIntent)
                    }
                }
                ActionsViewModel.UiEvent.RequestWeatherLocationPermission ->
                    weatherLocationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                ActionsViewModel.UiEvent.RequestReadContactsPermission ->
                    contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                ActionsViewModel.UiEvent.RequestReadCalendarPermission ->
                    calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasDndAccess = (context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager)?.isNotificationPolicyAccessGranted == true
                viewModel.onDndResumeCheck(hasDndAccess)
                viewModel.onWriteSettingsResumeCheck(
                    hasAccess = android.provider.Settings.System.canWrite(context),
                )
                viewModel.onPhoneRepairResumeCheck(
                    hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CALL_PHONE,
                    ) == PackageManager.PERMISSION_GRANTED,
                )
                viewModel.onLocationRepairResumeCheck(
                    hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED,
                )
                viewModel.onContactsRepairResumeCheck(
                    hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_CONTACTS,
                    ) == PackageManager.PERMISSION_GRANTED,
                )
                viewModel.onCalendarRepairResumeCheck(
                    hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_CALENDAR,
                    ) == PackageManager.PERMISSION_GRANTED,
                )
                viewModel.onMicrophoneRepairResumeCheck(
                    hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED,
                )
            }
            if (event == Lifecycle.Event.ON_STOP) {
                Log.d(
                    ACTIONS_SCREEN_TAG,
                    "ActionsScreen: lifecycle ON_STOP pendingSlot=${pendingSlot != null} " +
                        "showBottomSheet=$showBottomSheet voiceCaptureState=$voiceCaptureState " +
                        "voicePlaybackState=$voicePlaybackState",
                )
                viewModel.pauseTransientVoiceUi(reason = "lifecycleOnStop")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Clean up any stale bottom-sheet state when the Actions screen is
            // disposed (e.g. navigating to Tools via bottom nav). This prevents
            // a remembered-showBottomSheet=true from persisting through saveState
            // and interfering with the new destination.
            showBottomSheet = false
            initialSheetText = ""
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
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.testTag("top_bar_settings_button")) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                        TextButton(
                            onClick = viewModel::stopVoiceOutput,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                        ) {
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
                            Text("🩴", style = MaterialTheme.typography.displayMedium)
                            Text(
                                text = "No actions yet. Try asking me to set a timer or check the weather.",
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
            onDismiss = {
                showBottomSheet = false
                initialSheetText = ""
            },
            initialText = initialSheetText,
            onSubmit = { query ->
                viewModel.executeAction(query)
                showBottomSheet = false
                initialSheetText = ""
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
        PendingSlotBottomSheet(
            slot = slot,
            uiState = uiState,
            voiceCaptureState = voiceCaptureState,
            autoVoiceReplyArmed = slotReplyAutoRearmArmed,
            slotPromptPlaybackStarted = slotPromptPlaybackStarted,
            onDismiss = { viewModel.cancelSlotFill() },
            onSubmit = { reply -> viewModel.onSlotReply(reply) },
            onVoiceReply = { requestVoiceCapture(VoiceCaptureMode.SlotReply) },
            onStopVoiceReply = viewModel::stopVoiceCapture,
        )
    }
    // Hands-free calling contextual permission surface
    handsFreeCallingState?.let { state ->
        PermissionOverlayDialog(
            title = if (state.isPermanentlyDenied) {
                "Phone permission is blocked"
            } else {
                "Allow hands-free calling?"
            },
            body = if (state.isPermanentlyDenied) {
                "Android will not show the Phone permission prompt. Open system settings to allow hands-free calling, " +
                    "or use the dialer for this call."
            } else {
                "Jandal needs Phone permission to place calls directly. You can open the dialer for this call " +
                    "without granting permission."
            },
            actions = if (state.isPermanentlyDenied) {
                listOf(
                    PermissionDialogAction(
                        label = "Open Phone permission settings",
                        testTag = "permission_dialog_hands_free_open_app_permissions",
                        onClick = { viewModel.onHandsFreeCallingOpenAppPermissions() },
                        isPrimary = true,
                    ),
                    PermissionDialogAction(
                        label = "Open dialer this time",
                        testTag = "permission_dialog_hands_free_open_dialer",
                        onClick = { viewModel.onHandsFreeCallingDialerFallback() },
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_hands_free_not_now",
                        onClick = { viewModel.dismissHandsFreeCallingDialog() },
                    ),
                )
            } else {
                listOf(
                    PermissionDialogAction(
                        label = "Allow hands-free calling",
                        testTag = "permission_dialog_hands_free_allow",
                        onClick = { viewModel.onHandsFreeCallingRequestPermission() },
                        isPrimary = true,
                    ),
                    PermissionDialogAction(
                        label = "Open dialer this time",
                        testTag = "permission_dialog_hands_free_open_dialer",
                        onClick = { viewModel.onHandsFreeCallingDialerFallback() },
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_hands_free_not_now",
                        onClick = { viewModel.dismissHandsFreeCallingDialog() },
                    ),
                )
            },
            dialogTestTag = "permission_dialog_hands_free_calling",
            onDismissRequest = { viewModel.dismissHandsFreeCallingDialog() },
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

    // DND special-access contextual surface
    dndState?.let { state ->
        PermissionOverlayDialog(
            title = if (state.isAccessBlocked) {
                "Do Not Disturb access is blocked"
            } else {
                "Allow Do Not Disturb control?"
            },
            body = if (state.isAccessBlocked) {
                "Jandal still does not have Do Not Disturb access. Grant access in Android settings, then return " +
                    "to Jandal to continue."
            } else {
                "Android requires special access before Jandal can turn Do Not Disturb on or off."
            },
            actions = listOf(
                PermissionDialogAction(
                    label = "Open DND access settings",
                    testTag = "permission_dialog_dnd_open_settings",
                    onClick = { viewModel.onDndOpenSettings() },
                    isPrimary = true,
                ),
                PermissionDialogAction(
                    label = "Not now",
                    testTag = "permission_dialog_dnd_not_now",
                    onClick = { viewModel.dismissDndDialog() },
                ),
            ),
            dialogTestTag = "permission_dialog_dnd",
            onDismissRequest = { viewModel.dismissDndDialog() },
        )
    }

    // Write-settings special-access contextual surface
    writeSettingsState?.let { state ->
        PermissionOverlayDialog(
            title = if (state.isAccessBlocked) {
                "Settings access is blocked"
            } else {
                "Allow settings changes?"
            },
            body = if (state.isAccessBlocked) {
                "Jandal still does not have access to modify system settings."
            } else {
                "Android requires special access before Jandal can change settings such as screen brightness."
            },
            actions = listOf(
                PermissionDialogAction(
                    label = "Open settings access",
                    testTag = "permission_dialog_write_settings_open_settings",
                    onClick = { viewModel.onWriteSettingsOpenSettings() },
                    isPrimary = true,
                ),
                PermissionDialogAction(
                    label = "Not now",
                    testTag = "permission_dialog_write_settings_not_now",
                    onClick = { viewModel.dismissWriteSettingsDialog() },
                ),
            ),
            dialogTestTag = "permission_dialog_write_settings",
            onDismissRequest = { viewModel.dismissWriteSettingsDialog() },
        )
    }

    // Weather-location contextual permission surface
    weatherLocationState?.let { state ->
        PermissionOverlayDialog(
            title = if (state.isPermanentlyDenied) {
                "Location permission is blocked"
            } else {
                "Use your location for local weather?"
            },
            body = if (state.isPermanentlyDenied) {
                "Android will not show the Location permission prompt. Open system settings to allow local weather, " +
                    "or ask for weather in a named place instead."
            } else {
                "Jandal can use approximate location to answer weather questions for where you are now. " +
                    "You can also type a place instead."
            },
            actions = if (state.isPermanentlyDenied) {
                listOf(
                    PermissionDialogAction(
                        label = "Open Location permission settings",
                        testTag = "permission_dialog_open_app_permissions",
                        onClick = { viewModel.onWeatherLocationOpenAppPermissions() },
                        isPrimary = true,
                    ),
                    PermissionDialogAction(
                        label = "Use a named location",
                        testTag = "permission_dialog_location_type_place",
                        onClick = { viewModel.onWeatherLocationTypePlace() },
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_location_not_now",
                        onClick = { viewModel.dismissWeatherLocationDialog() },
                    ),
                )
            } else {
                listOfNotNull(
                    PermissionDialogAction(
                        label = "Use my location",
                        testTag = "permission_dialog_location_use_my_location",
                        onClick = { viewModel.onWeatherLocationRequestPermission() },
                        isPrimary = true,
                    ),
                    PermissionDialogAction(
                        label = "Use a named location",
                        testTag = "permission_dialog_location_type_place",
                        onClick = { viewModel.onWeatherLocationTypePlace() },
                    ),
                    if (state.hasSavedLocation) {
                        PermissionDialogAction(
                            label = "Use saved location",
                            testTag = "permission_dialog_location_use_saved_location",
                            onClick = { viewModel.onWeatherLocationUseSavedLocation() },
                        )
                    } else {
                        null
                    },
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_location_not_now",
                        onClick = { viewModel.dismissWeatherLocationDialog() },
                    ),
                )
            },
            dialogTestTag = "permission_dialog_location",
            onDismissRequest = { viewModel.dismissWeatherLocationDialog() },
        )
    }

    // Contact-permission contextual surface
    contactPermissionState?.let { state ->
        PermissionOverlayDialog(
            title = if (state.isPermanentlyDenied) {
                "Contacts permission is blocked"
            } else {
                "Allow contact lookup?"
            },
            body = if (state.isPermanentlyDenied) {
                "Android will not show the Contacts permission prompt. Open system settings to allow contact lookup, " +
                    "or enter the details manually."
            } else {
                "Jandal needs Contacts access to find people by name. You can also enter the phone number or " +
                    "email address manually."
            },
            actions = if (state.isPermanentlyDenied) {
                listOf(
                    PermissionDialogAction(
                        label = "Open Contacts permission settings",
                        testTag = "permission_dialog_open_app_permissions",
                        onClick = { viewModel.onContactOpenAppPermissions() },
                        isPrimary = true,
                    ),
                    PermissionDialogAction(
                        label = "Enter manually",
                        testTag = "permission_dialog_contacts_enter_manually",
                        onClick = { viewModel.onContactEnterManually() },
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_contacts_not_now",
                        onClick = { viewModel.dismissContactPermissionDialog() },
                    ),
                )
            } else {
                listOf(
                    PermissionDialogAction(
                        label = "Allow contact lookup",
                        testTag = "permission_dialog_contacts_allow",
                        onClick = { viewModel.onContactRequestPermission() },
                        isPrimary = true,
                    ),
                    PermissionDialogAction(
                        label = "Enter manually",
                        testTag = "permission_dialog_contacts_enter_manually",
                        onClick = { viewModel.onContactEnterManually() },
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_contacts_not_now",
                        onClick = { viewModel.dismissContactPermissionDialog() },
                    ),
                )
            },
            dialogTestTag = "permission_dialog_contacts",
            onDismissRequest = { viewModel.dismissContactPermissionDialog() },
        )
    }

    // Calendar-permission contextual surface
    calendarPermissionState?.let { state ->
        PermissionOverlayDialog(
            title = if (state.isPermanentlyDenied) {
                "Calendar permission is blocked"
            } else {
                "Allow calendar lookup?"
            },
            body = if (state.isPermanentlyDenied) {
                "Android will not show the Calendar permission prompt. Open system settings to allow calendar lookup, " +
                    "or add the date manually."
            } else {
                "Jandal can read your calendar to find synced birthdays and important dates. You can also add " +
                    "important dates manually."
            },
            actions = if (state.isPermanentlyDenied) {
                listOf(
                    PermissionDialogAction(
                        label = "Open Calendar permission settings",
                        testTag = "permission_dialog_open_app_permissions",
                        onClick = { viewModel.onCalendarOpenAppPermissions() },
                        isPrimary = true,
                    ),
                    PermissionDialogAction(
                        label = "Add manually",
                        testTag = "permission_dialog_calendar_add_manually",
                        onClick = { viewModel.onCalendarAddManually() },
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_calendar_not_now",
                        onClick = { viewModel.dismissCalendarPermissionDialog() },
                    ),
                )
            } else {
                listOf(
                    PermissionDialogAction(
                        label = "Allow calendar lookup",
                        testTag = "permission_dialog_calendar_allow",
                        onClick = { viewModel.onCalendarRequestPermission() },
                        isPrimary = true,
                    ),
                    PermissionDialogAction(
                        label = "Add manually",
                        testTag = "permission_dialog_calendar_add_manually",
                        onClick = { viewModel.onCalendarAddManually() },
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_calendar_not_now",
                        onClick = { viewModel.dismissCalendarPermissionDialog() },
                    ),
                )
            },
            dialogTestTag = "permission_dialog_calendar",
            onDismissRequest = { viewModel.dismissCalendarPermissionDialog() },
        )
    }

    // Contextual voice permission prompt (shared with ChatScreen, widget, settings).
    if (showVoicePermissionPrompt) {
        val promptConfig = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.ACTIONS_VOICE,
            VoicePermissionPromptState.Missing,
        )
        VoicePermissionPrompt(
            config = promptConfig,
            onGrant = {
                showVoicePermissionPrompt = false
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRetry = {
                showVoicePermissionPrompt = false
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onOpenSettings = {
                showVoicePermissionPrompt = false
                viewModel.onMicrophoneOpenAppPermissions()
                openRuntimePermissionRepair(Manifest.permission.RECORD_AUDIO)
            },
            onCancel = {
                showVoicePermissionPrompt = false
                val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                    context as android.app.Activity,
                    Manifest.permission.RECORD_AUDIO,
                )
                viewModel.onMicrophonePermissionDenied(shouldShowRationale)
            },
        )
    }
    // Microphone-permission contextual surface
    microphoneState?.let { state ->
        PermissionOverlayDialog(
            title = if (state.isPermanentlyDenied) {
                "Microphone permission is blocked"
            } else {
                "Allow voice input?"
            },
            body = if (state.isPermanentlyDenied) {
                "Android will not show the Microphone permission prompt. Open system settings to allow voice input, " +
                    "or keep typing."
            } else {
                "Jandal needs Microphone permission to listen for voice input. You can keep typing instead."
            },
            actions = if (state.isPermanentlyDenied) {
                listOf(
                    PermissionDialogAction(
                        label = "Open Microphone permission settings",
                        testTag = "permission_dialog_microphone_open_settings",
                        onClick = { viewModel.onMicrophoneOpenAppPermissions() },
                        isPrimary = true,
                    ),
                    PermissionDialogAction(
                        label = "Keep typing",
                        testTag = "permission_dialog_microphone_keep_typing",
                        onClick = { viewModel.onMicrophoneKeepTyping() },
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_microphone_not_now",
                        onClick = { viewModel.dismissMicrophoneDialog() },
                    ),
                )
            } else {
                listOf(
                    PermissionDialogAction(
                        label = "Allow voice input",
                        testTag = "permission_dialog_microphone_allow",
                        onClick = { viewModel.onMicrophoneRequestPermission() },
                        isPrimary = true,
                    ),
                    PermissionDialogAction(
                        label = "Keep typing",
                        testTag = "permission_dialog_microphone_keep_typing",
                        onClick = { viewModel.onMicrophoneKeepTyping() },
                    ),
                    PermissionDialogAction(
                        label = "Not now",
                        testTag = "permission_dialog_microphone_not_now",
                        onClick = { viewModel.dismissMicrophoneDialog() },
                    ),
                )
            },
            dialogTestTag = "permission_dialog_microphone",
            onDismissRequest = { viewModel.dismissMicrophoneDialog() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingSlotBottomSheet(
    slot: ActionsViewModel.PendingSlotState,
    uiState: ActionsViewModel.UiState,
    voiceCaptureState: ActionsViewModel.VoiceCaptureState,
    autoVoiceReplyArmed: Boolean,
    slotPromptPlaybackStarted: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onVoiceReply: () -> Unit,
    onStopVoiceReply: () -> Unit,
) {
    key(
        slot.request.intentName,
        slot.request.missingSlot.name,
        slot.request.promptMessage,
        slot.request.existingParams.toList(),
        slot.inputMode,
    ) {
        SlotFillBottomSheet(
            promptMessage = slot.request.promptMessage,
            inputMode = slot.inputMode,
            uiState = uiState,
            voiceCaptureState = voiceCaptureState,
            autoVoiceReplyArmed = autoVoiceReplyArmed,
            slotPromptPlaybackStarted = slotPromptPlaybackStarted,
            onDismiss = onDismiss,
            onSubmit = onSubmit,
            onVoiceReply = onVoiceReply,
            onStopVoiceReply = onStopVoiceReply,
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
    autoVoiceReplyArmed: Boolean,
    slotPromptPlaybackStarted: Boolean,
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
    // LoopListeningCueEffect removed — cue playback is handled by StartListeningCuePlayer
    // in ActionsViewModel on VoiceInputEvent.ListeningStarted.

    LaunchedEffect(promptMessage, inputMode, autoVoiceReplyArmed) {
        Log.d(
            ACTIONS_SCREEN_TAG,
            "ActionsScreen: SlotFillBottomSheet shown inputMode=$inputMode autoVoiceReplyArmed=$autoVoiceReplyArmed " +
                "prompt=\"$promptMessage\"",
        )
    }

    LaunchedEffect(promptMessage, inputMode, autoVoiceReplyArmed, isVoiceReplyActive, slotPromptPlaybackStarted) {
        if (inputMode != InputMode.Voice || !autoVoiceReplyArmed || isVoiceReplyActive) return@LaunchedEffect
        if (!slotPromptPlaybackStarted) {
            // TTS synthesis guard: audio hasn't started yet. Wait up to 10s for SpeakingStarted.
            // This coroutine is cancelled and restarted when slotPromptPlaybackStarted → true.
            Log.d(
                ACTIONS_SCREEN_TAG,
                "ActionsScreen: waiting for slot TTS to start prompt=\"$promptMessage\"",
            )
            delay(10_000L)
            if (!isVoiceReplyActive && autoVoiceReplyArmed) {
                Log.w(
                    ACTIONS_SCREEN_TAG,
                    "ActionsScreen: TTS never started — forcing slot voice fallback prompt=\"$promptMessage\"",
                )
                onVoiceReply()
            }
            return@LaunchedEffect
        }
        // Playback safety net: TTS is playing. Primary rearm is SpeakingStopped → 350ms → startVoiceCapture.
        // This fires only if SpeakingStopped never arrives (TTS failure). Voice-speed-agnostic.
        Log.d(
            ACTIONS_SCREEN_TAG,
            "ActionsScreen: slot TTS playing — safety net armed prompt=\"$promptMessage\"",
        )
        delay(15_000L)
        if (!isVoiceReplyActive && autoVoiceReplyArmed) {
            Log.w(
                ACTIONS_SCREEN_TAG,
                "ActionsScreen: slot voice fallback firing (playback safety net) prompt=\"$promptMessage\"",
            )
            onVoiceReply()
        }
    }

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
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("slot_reply_input"),
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
                                modifier = Modifier.testTag("slot_reply_submit_button"),
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
    initialText: String = "",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var inputText by rememberSaveable(initialText) { mutableStateOf(initialText) }
    // When initialText changes externally (new draft from Tools example),
    // replace the text field value. Preserves user-typed text across
    // recomposition and configuration changes while the sheet is open.
    LaunchedEffect(initialText) {
        if (initialText.isNotBlank()) {
            inputText = initialText
        }
    }
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
            if (initialText.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Example loaded — review or edit before running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("quick_action_example_hint"),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("What do you want to do?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("quick_action_input"),
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
                                modifier = Modifier.testTag("quick_action_submit_button"),
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
            VoiceCaptureMode.AlertCommand -> "Listening for alert command…"
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
