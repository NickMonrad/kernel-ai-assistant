package com.kernel.ai.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import com.kernel.ai.core.permissions.PermissionDenialClassifier
import com.kernel.ai.core.permissions.DenialOutcome
import com.kernel.ai.core.permissions.MicrophonePermissionReadiness
import com.kernel.ai.core.permissions.MicrophoneReadiness
import com.kernel.ai.core.ui.permissions.DefaultAssistantPrompt
import com.kernel.ai.core.ui.permissions.DefaultAssistantPromptSuccess
import com.kernel.ai.core.ui.permissions.PermissionDialogAction
import com.kernel.ai.core.ui.permissions.PermissionOverlayDialog
import com.kernel.ai.core.ui.terms.TermsOfUseLinkWithSheet
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.voice.HeyJandalGate
import com.kernel.ai.core.voice.HonorCameraCoexistence

import com.kernel.ai.core.voice.KokoroSpeakerGroup
import com.kernel.ai.core.voice.SemaineSpeakerMetadata
import com.kernel.ai.core.voice.SherpaKokoroVoice
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VctkSpeakerMetadata
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.VoicePackDownloadState
import com.kernel.ai.core.model.availability.ModelAvailabilityState
import com.kernel.ai.core.model.availability.ModelCardCompact
import kotlin.math.roundToInt
import com.kernel.ai.core.model.availability.UnavailableReason
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.util.Log
import com.kernel.ai.core.permissions.defaultAssistantSettingsIntents
import com.kernel.ai.core.permissions.launchFirstAvailable
import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import com.kernel.ai.core.permissions.RuntimePermissionRepair
import com.kernel.ai.core.permissions.recordPermissionResult
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    viewModel: VoiceViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val roleManager = context.getSystemService(RoleManager::class.java)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showMicRepairDialog by remember { mutableStateOf(false) }
    var showMicDurableRequiredDialog by remember { mutableStateOf(false) }
    var awaitingMicSettingsReturn by remember { mutableStateOf(false) }
    var showUsageAccessDialog by remember { mutableStateOf(false) }
    var awaitingUsageAccessReturn by remember { mutableStateOf(false) }

    /**
     * #1502: on the Honor device whose camera contends for the microphone, Hey Jandal can only
     * listen with Usage Access. Cached per resume (the check costs an AppOps query) and refreshed
     * on ON_RESUME and on every enable attempt.
     */
    var needsUsageAccess by remember { mutableStateOf(false) }
    val requiresUsageAccess = { HeyJandalGate.usageAccessRequired(context) }
    val enableHeyJandal = {
        needsUsageAccess = requiresUsageAccess()
        if (needsUsageAccess) showUsageAccessDialog = true else viewModel.setHeyJandalEnabled(true)
    }

    var showDefaultAssistantNotSetYet by remember { mutableStateOf(false) }
    var showDefaultAssistantSuccess by remember { mutableStateOf(false) }
    var awaitingAssistantReturn by remember { mutableStateOf(false) }
    val micDenialClassifier = remember { PermissionDenialClassifier() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAssistantStatus(
                    roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true,
                )
                // Back from assistant Settings: role held → success, and drop any "Not set yet" note.
                if ((awaitingAssistantReturn || showDefaultAssistantNotSetYet) &&
                    roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
                ) {
                    awaitingAssistantReturn = false
                    showDefaultAssistantNotSetYet = false
                    showDefaultAssistantSuccess = true
                }
                needsUsageAccess = requiresUsageAccess()

                // 0. Settings-return handling: Usage Access round-trip (#1502). Continue the
                // enable flow only when access was actually granted.
                if (awaitingUsageAccessReturn) {
                    awaitingUsageAccessReturn = false
                    if (needsUsageAccess) {
                        Log.i(
                            "VoiceScreen",
                            "Usage Access still not granted — Hey Jandal left inactive",
                        )
                    } else {
                        viewModel.setHeyJandalEnabled(true)
                    }
                }

                // 1. Settings-return handling: explicit mic repair round-trip.
                if (awaitingMicSettingsReturn) {
                    awaitingMicSettingsReturn = false
                    val micGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (micGranted) {
                        val micReadiness = MicrophonePermissionReadiness.evaluate(context)
                        if (micReadiness == MicrophoneReadiness.Granted) {
                            showMicRepairDialog = false
                            micDenialClassifier.clear(Manifest.permission.RECORD_AUDIO)
                            enableHeyJandal()
                        } else {
                            showMicDurableRequiredDialog = true
                        }
                    } else {
                        showMicRepairDialog = true
                    }
                }

                // 2. General enforcement: if Hey Jandal was enabled and mic
                // is no longer granted, disable it and explain why.
                val wasHeyJandalEnabled = uiState.heyJandalEnabled
                val micReadiness = MicrophonePermissionReadiness.evaluate(context)
                if (wasHeyJandalEnabled && micReadiness != MicrophoneReadiness.Granted) {
                    viewModel.enforceHeyJandalMicReadiness(micReadiness)
                    showMicDurableRequiredDialog = true
                }

                // 2b. #1502 enforcement: Hey Jandal cannot listen without Usage Access on the
                // affected device (revoked externally, or enabled before this build) — turn it off
                // rather than let the UI claim it is listening.
                if (wasHeyJandalEnabled && needsUsageAccess) {
                    viewModel.setHeyJandalEnabled(false)
                    showUsageAccessDialog = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 3. Async load guard: if heyJandalEnabled loads from DataStore as true
    // after ON_RESUME already fired (e.g. after process restart), re-check
    // durability when the state transitions from initial false to persisted true.
    // This handles the case where DataStore hasn't loaded by the time
    // the ON_RESUME lifecycle observer runs, so the durability check at (2)
    // saw wasHeyJandalEnabled = false and did not trigger.
    LaunchedEffect(uiState.heyJandalEnabled) {
        if (uiState.heyJandalEnabled && !awaitingMicSettingsReturn && !awaitingUsageAccessReturn) {
            val micReadiness = MicrophonePermissionReadiness.evaluate(context)
            if (micReadiness != MicrophoneReadiness.Granted) {
                viewModel.enforceHeyJandalMicReadiness(micReadiness)
                showMicDurableRequiredDialog = true
            } else if (needsUsageAccess) {
                viewModel.setHeyJandalEnabled(false)
                showUsageAccessDialog = true
            }
        }
    }

    var showDefaultAssistantPrompt by remember { mutableStateOf(false) }

    val assistantRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // Settings returned. Role held → success; otherwise the short note, never the full prompt.
        awaitingAssistantReturn = false
        if (roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true) {
            showDefaultAssistantNotSetYet = false
            showDefaultAssistantSuccess = true
        } else {
            showDefaultAssistantNotSetYet = true
        }
    }

    // Opens the voice-input settings page (its "Digital assistant app" row sets the role),
    // falling back to default apps. Shows a message if neither page resolves.
    val noSettingsPageMessage = stringResource(com.kernel.ai.core.permissions.R.string.default_assistant_no_settings_page)
    fun openAssistantSettings() {
        awaitingAssistantReturn = true
        val opened = launchFirstAvailable(defaultAssistantSettingsIntents(), assistantRoleLauncher::launch)
        if (!opened) {
            awaitingAssistantReturn = false
            Toast.makeText(context, noSettingsPageMessage, Toast.LENGTH_LONG).show()
        }
    }

    // Permission launcher for Hey Jandal: grants mic then enables wake word.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        context.recordPermissionResult(
            Manifest.permission.RECORD_AUDIO,
            granted,
            shouldShowRationale = !granted && ActivityCompat.shouldShowRequestPermissionRationale(
                context as android.app.Activity,
                Manifest.permission.RECORD_AUDIO,
            ),
        )
        JandalEvents.record("permission", "record_audio", "granted" to granted)
        if (!granted) {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                context as android.app.Activity,
                Manifest.permission.RECORD_AUDIO,
            )
            if (micDenialClassifier.classify(Manifest.permission.RECORD_AUDIO, shouldShowRationale) == DenialOutcome.RepairOnlyDenied) {
                showMicRepairDialog = true
            }
        } else {
            val micReadiness = MicrophonePermissionReadiness.evaluate(context)
            if (micReadiness == MicrophoneReadiness.Granted) {
                enableHeyJandal()
            } else {
                showMicDurableRequiredDialog = true
            }
        }
    }
    VoiceScreenContent(
        uiState = uiState,
        onBack = onBack,
        onRequestAssistantRole = {
            // Show default-assistant role setup prompt (separate from microphone permission).
            showDefaultAssistantPrompt = true
        },
        onHeyJandalEnabledChanged = { enabled ->
            if (!enabled) {
                viewModel.setHeyJandalEnabled(false)
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                val micReadiness = MicrophonePermissionReadiness.evaluate(context)
                if (micReadiness == MicrophoneReadiness.Granted) {
                    enableHeyJandal()
                } else {
                    showMicDurableRequiredDialog = true
                }
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onWakeWordThresholdChanged = viewModel::setWakeWordThreshold,
        onVoiceInputEngineSelected = viewModel::setVoiceInputEngine,
        onAutoStartAlertVoiceCommandsEnabledChanged = viewModel::setAutoStartAlertVoiceCommandsEnabled,
        onAllowOnlineSpeechFallbackChanged = viewModel::setAllowOnlineSpeechFallback,
        onSpokenResponsesEnabledChanged = viewModel::setSpokenResponsesEnabled,
        onVoiceOutputEngineSelected = viewModel::setVoiceOutputEngine,
        onSherpaVoiceSelected = viewModel::setSherpaVoice,
        onSherpaSpeedChanged = viewModel::setSherpaSpeed,
        onSherpaPitchChanged = viewModel::setSherpaPitch,
        onSherpaGainChanged = viewModel::setSherpaGain,
        onAutoSpeakChanged = viewModel::setAutoSpeak,
        onMaxSpokenSentencesChanged = viewModel::setMaxSpokenSentences,
        onActiveSpeakerIdChanged = viewModel::setActiveSpeakerId,
        onKokoroVoiceSelected = viewModel::setKokoroVoice,
        onKokoroActiveSpeakerIdChanged = viewModel::setKokoroActiveSpeakerId,
        onDownloadSherpaStt = viewModel::downloadSherpaStt,
        onCancelSherpaStt = viewModel::cancelSherpaSttDownload,
        onDeleteSherpaStt = viewModel::deleteSherpaStt,
        onAsr4allSizeSelected = viewModel::setAsr4allSize,
        onTermsAcceptedChanged = viewModel::onTermsAcceptedChanged,
        onDownloadSherpaVoice = viewModel::downloadSherpaVoice,
        onCancelSherpaVoice = viewModel::cancelSherpaVoiceDownload,
        onDeleteSherpaVoice = viewModel::deleteSherpaVoice,
        onPlaySherpaSample = viewModel::playSherpaSample,
        onUseMobileDataForVoice = viewModel::useMobileDataForVoice,
        onDownloadKokoroVoice = viewModel::downloadKokoroVoice,
        onCancelKokoroVoice = viewModel::cancelKokoroVoiceDownload,
        onDeleteKokoroVoice = viewModel::deleteKokoroVoice,
        onDownloadInflectMicro = viewModel::downloadInflectMicro,
        onCancelInflectMicro = viewModel::cancelInflectMicroDownload,
        onDeleteInflectMicro = viewModel::deleteInflectMicro,
    )

    if (showMicRepairDialog) {
        PermissionOverlayDialog(
            title = "Microphone permission is blocked",
            body = "Android will not show the Microphone permission prompt. Open system settings to allow voice input for Hey Jandal.",
            actions = listOf(
                PermissionDialogAction(
                    label = "Open Microphone permission settings",
                    testTag = "hey_jandal_mic_open_settings",
                    onClick = {
                        showMicRepairDialog = false
                        awaitingMicSettingsReturn = true
                        runCatching {
                            context.startActivity(
                                RuntimePermissionRepair.intentFor(context, Manifest.permission.RECORD_AUDIO)
                            )
                        }
                    },
                    isPrimary = true,
                ),
                PermissionDialogAction(
                    label = stringResource(com.kernel.ai.core.permissions.R.string.default_assistant_setup_cancel),
                    testTag = "hey_jandal_mic_not_now",
                    onClick = {
                        showMicRepairDialog = false
                        awaitingMicSettingsReturn = false
                    },
                ),
            ),
            dialogTestTag = "hey_jandal_microphone_permission_dialog",
            onDismissRequest = {
                showMicRepairDialog = false
                awaitingMicSettingsReturn = false
            },
        )
    }

    if (showMicDurableRequiredDialog) {
        PermissionOverlayDialog(
            title = "Microphone access was removed",
            body = "Hey Jandal was turned off because Android removed microphone access. Allow Microphone access again to use the wake word.",
            actions = listOf(
                PermissionDialogAction(
                    label = "Open Microphone permission settings",
                    testTag = "hey_jandal_mic_open_settings",
                    onClick = {
                        showMicDurableRequiredDialog = false
                        awaitingMicSettingsReturn = true
                        runCatching {
                            context.startActivity(
                                RuntimePermissionRepair.intentFor(context, Manifest.permission.RECORD_AUDIO)
                            )
                        }
                    },
                    isPrimary = true,
                ),
                PermissionDialogAction(
                    label = stringResource(com.kernel.ai.core.permissions.R.string.default_assistant_setup_cancel),
                    testTag = "hey_jandal_mic_not_now",
                    onClick = {
                        showMicDurableRequiredDialog = false
                        awaitingMicSettingsReturn = false
                    },
                ),
            ),
            dialogTestTag = "hey_jandal_microphone_durability_dialog",
            onDismissRequest = {
                showMicDurableRequiredDialog = false
                awaitingMicSettingsReturn = false
            },
        )
    }


    // #1502 Usage Access prompt: only reachable on the affected Honor device, and only when the
    // user chose to enable Hey Jandal (or when Jandal was already enabled without the access).
    if (showUsageAccessDialog) {
        PermissionOverlayDialog(
            title = "Allow Usage Access for Hey Jandal?",
            body = "On this Honor device, Jandal needs Usage Access so it can pause wake-word " +
                "listening while the Camera is in use. App usage information is processed only " +
                "on this device and is not stored or sent anywhere.",
            actions = listOf(
                PermissionDialogAction(
                    label = "Open Usage Access",
                    testTag = "hey_jandal_usage_access_open_settings",
                    onClick = {
                        showUsageAccessDialog = false
                        awaitingUsageAccessReturn = true
                        runCatching {
                            context.startActivity(HonorCameraCoexistence.usageAccessSettingsIntent(context))
                        }.onFailure {
                            Log.w("VoiceScreen", "Could not open Usage Access settings", it)
                        }
                    },
                    isPrimary = true,
                ),
                PermissionDialogAction(
                    label = stringResource(com.kernel.ai.core.permissions.R.string.default_assistant_setup_cancel),
                    testTag = "hey_jandal_usage_access_not_now",
                    onClick = {
                        showUsageAccessDialog = false
                        awaitingUsageAccessReturn = false
                    },
                ),
            ),
            dialogTestTag = "hey_jandal_usage_access_dialog",
            onDismissRequest = {
                showUsageAccessDialog = false
                awaitingUsageAccessReturn = false
            },
        )
    }

    // Default-assistant role setup prompt (separate from microphone permission).
    if (showDefaultAssistantPrompt) {
        DefaultAssistantPrompt(
            onGrant = {
                showDefaultAssistantPrompt = false
                openAssistantSettings()
            },
            onCancel = {
                showDefaultAssistantPrompt = false
            },
        )
    }

    // Role still not held after returning from Settings: short note with Try again.
    if (showDefaultAssistantNotSetYet) {
        PermissionOverlayDialog(
            title = stringResource(com.kernel.ai.core.permissions.R.string.default_assistant_not_set_title),
            body = stringResource(com.kernel.ai.core.permissions.R.string.default_assistant_not_set_body),
            actions = listOf(
                PermissionDialogAction(
                    label = stringResource(com.kernel.ai.core.permissions.R.string.default_assistant_not_set_try_again),
                    testTag = "default_assistant_not_set_try_again",
                    onClick = {
                        showDefaultAssistantNotSetYet = false
                        openAssistantSettings()
                    },
                    isPrimary = true,
                ),
                PermissionDialogAction(
                    label = stringResource(com.kernel.ai.core.permissions.R.string.default_assistant_setup_cancel),
                    testTag = "default_assistant_not_set_not_now",
                    onClick = {
                        showDefaultAssistantNotSetYet = false
                    },
                ),
            ),
            dialogTestTag = "default_assistant_not_set_yet",
            onDismissRequest = {
                showDefaultAssistantNotSetYet = false
            },
        )
    }

    // Success state after default-assistant role is granted.
    if (showDefaultAssistantSuccess) {
        DefaultAssistantPromptSuccess(
            onOk = {
                showDefaultAssistantSuccess = false
                viewModel.refreshAssistantStatus(
                    roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true,
                )
            },
        )
    }


}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceScreenContent(
    uiState: VoiceUiState,
    onBack: () -> Unit,
    onRequestAssistantRole: () -> Unit,
    onHeyJandalEnabledChanged: (Boolean) -> Unit,
    onWakeWordThresholdChanged: (Float) -> Unit,
    onVoiceInputEngineSelected: (VoiceInputEngine) -> Unit,
    onAutoStartAlertVoiceCommandsEnabledChanged: (Boolean) -> Unit,
    onAllowOnlineSpeechFallbackChanged: (Boolean) -> Unit,
    onSpokenResponsesEnabledChanged: (Boolean) -> Unit,
    onVoiceOutputEngineSelected: (VoiceOutputEngine) -> Unit,
    onSherpaVoiceSelected: (SherpaPiperVoice) -> Unit,
    onSherpaSpeedChanged: (Float) -> Unit,
    onSherpaPitchChanged: (Float) -> Unit,
    onSherpaGainChanged: (Float) -> Unit,
    onAutoSpeakChanged: (Boolean) -> Unit,
    onMaxSpokenSentencesChanged: (Int) -> Unit,
    onActiveSpeakerIdChanged: (Int) -> Unit,
    onKokoroVoiceSelected: (SherpaKokoroVoice) -> Unit,
    onKokoroActiveSpeakerIdChanged: (Int) -> Unit,
    onDownloadSherpaStt: (VoiceInputEngine) -> Unit = { _ -> },
    onCancelSherpaStt: (VoiceInputEngine) -> Unit = { _ -> },
    onDeleteSherpaStt: (VoiceInputEngine) -> Unit = { _ -> },
    onAsr4allSizeSelected: (com.kernel.ai.core.voice.asr4all.Asr4AllSize) -> Unit = { _ -> },
    onTermsAcceptedChanged: (Boolean) -> Unit = {},
    onDownloadSherpaVoice: (SherpaPiperVoice) -> Unit = { _ -> },
    onCancelSherpaVoice: (SherpaPiperVoice) -> Unit = { _ -> },
    onDeleteSherpaVoice: (SherpaPiperVoice) -> Unit = { _ -> },
    onPlaySherpaSample: (SherpaPiperVoice) -> Unit = { _ -> },
    onUseMobileDataForVoice: (SherpaPiperVoice) -> Unit = { _ -> },
    onDownloadKokoroVoice: (SherpaKokoroVoice) -> Unit = { _ -> },
    onCancelKokoroVoice: (SherpaKokoroVoice) -> Unit = { _ -> },
    onDeleteKokoroVoice: (SherpaKokoroVoice) -> Unit = { _ -> },
    onDownloadInflectMicro: () -> Unit = {},
    onCancelInflectMicro: () -> Unit = {},
    onDeleteInflectMicro: () -> Unit = {},
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice") },
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
            // ── Hey Jandal / Default Assistant ────────────────────────────────────
            Text(
                text = "Hey Jandal",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // Assistant role badge
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                leadingContent = {
                    Icon(
                        imageVector = if (uiState.isDefaultAssistant) Icons.Filled.CheckCircle else Icons.Filled.Assistant,
                        contentDescription = null,
                        tint = if (uiState.isDefaultAssistant) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = {
                    Text(if (uiState.isDefaultAssistant) "Jandal is your default assistant" else "Set Jandal as default assistant")
                },
                supportingContent = {
                    Text(
                        if (uiState.isDefaultAssistant) {
                            "Long-press Home to activate. Wake word detection uses this privileged mic path."
                        } else {
                            "Required for hold-Home activation, lock-screen launch, and always-on wake word."
                        },
                    )
                },
                trailingContent = if (!uiState.isDefaultAssistant) {
                    { TextButton(onClick = onRequestAssistantRole) { Text("Set") } }
                } else {
                    null
                },
            )
            HorizontalDivider()

            // "Listen for Hey Jandal" toggle
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.MicNone,
                        contentDescription = null,
                        tint = if (uiState.heyJandalEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = { Text("Listen for \"Hey Jandal\"") },
                supportingContent = {
                    val sizeLine = if (uiState.isWakeWordModelAvailable && !uiState.heyJandalEnabled &&
                        uiState.heyJandalDownloadBytes > 0L
                    ) {
                        " Adds a ${formatBytes(uiState.heyJandalDownloadBytes)} download."
                    } else {
                        ""
                    }
                    Text(
                        when {
                            !uiState.isWakeWordModelAvailable -> "Wake word model unavailable in this build: its assets are excluded pending licence resolution."
                            !uiState.isDefaultAssistant -> "Set Jandal as default assistant first for reliable background mic access."
                            else -> "Say \"Hey Jandal\" to start talking, hands-free."
                        } + sizeLine,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = uiState.heyJandalEnabled,
                        onCheckedChange = onHeyJandalEnabledChanged,
                        enabled = uiState.isWakeWordModelAvailable,
                    )
                },
            )

            // Confidence threshold slider — only shown when wake word model is available
            if (uiState.isWakeWordModelAvailable) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SliderRow(
                        label = "Detection sensitivity",
                        valueLabel = "${(uiState.wakeWordThreshold * 100).toInt()}%",
                        value = uiState.wakeWordThreshold,
                        valueRange = 0.5f..0.95f,
                        steps = 8,
                        onValueChangeFinished = { newVal ->
                            // Round to nearest 5% step for clean display and DataStore writes.
                            onWakeWordThresholdChanged(
                                (newVal * 20).roundToInt() / 20f,
                            )
                        },
                    )
                    Text(
                        text = "Higher values require greater confidence before triggering — fewer false activations, but may miss quiet or accented speech.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            HorizontalDivider()
            SpeechRecognitionSection(
                uiState = uiState,
                onSelect = onVoiceInputEngineSelected,
                onAllowOnline = onAllowOnlineSpeechFallbackChanged,
                onDownload = onDownloadSherpaStt,
                onCancel = onCancelSherpaStt,
                onDelete = onDeleteSherpaStt,
                onAsr4allSize = onAsr4allSizeSelected,
                onTermsAcceptedChanged = onTermsAcceptedChanged,
            )


            HorizontalDivider()

            Text(
                text = "Clock alerts",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Automatically listen when alarms or timers ring") },
                supportingContent = {
                    Text("Start local voice command capture for stop, dismiss, snooze, or add one minute as soon as an alert begins")
                },
                trailingContent = {
                    Switch(
                        checked = uiState.autoStartAlertVoiceCommandsEnabled,
                        onCheckedChange = onAutoStartAlertVoiceCommandsEnabledChanged,
                    )
                },
            )
            HorizontalDivider()

            Text(
                text = "Quick Actions output",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Speak Quick Actions voice responses") },
                supportingContent = {
                    Text("Read spoken prompts and results aloud for voice-triggered quick actions")
                },
                trailingContent = {
                    Switch(
                        checked = uiState.spokenResponsesEnabled,
                        onCheckedChange = onSpokenResponsesEnabledChanged,
                    )
                },
            )
            HorizontalDivider()

            // Auto-speak and max spoken sentences — shown for all TTS engines
            Text(
                text = "Chat voice behaviour",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            ListItem(
                headlineContent = { Text("Auto-speak chat replies") },
                supportingContent = {
                    Text(
                        text = "Automatically speak assistant replies in voice mode.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = uiState.autoSpeak,
                        onCheckedChange = onAutoSpeakChanged,
                    )
                },
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Max spoken sentences") },
                supportingContent = {
                    val label = if (uiState.maxSpokenSentences == 0) "Unlimited" else "${uiState.maxSpokenSentences}"
                    Text(
                        text = "Limit auto-spoken replies to $label sentence${if (uiState.maxSpokenSentences == 1) "" else "s"}. 0 = unlimited.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf(0, 2, 3, 5).forEach { n ->
                            val selected = uiState.maxSpokenSentences == n
                            TextButton(
                                onClick = { onMaxSpokenSentencesChanged(n) },
                                colors = if (selected) {
                                    androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    androidx.compose.material3.ButtonDefaults.textButtonColors()
                                },
                            ) {
                                Text(
                                    text = if (n == 0) "∞" else "$n",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                },
            )
            HorizontalDivider()

            Text(
                text = "How Jandal speaks",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            VoiceOutputSelectionCard(
                selectedEngine = uiState.selectedOutputEngine,
                inflectMicroReady = uiState.isInflectMicroReady,
                sherpaVoiceReady = uiState.isSelectedSherpaVoiceDownloaded,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            uiState.availableOutputEngines.forEach { engine ->
                val engineSelected = uiState.selectedOutputEngine == engine
                val engineReady = when (engine) {
                    VoiceOutputEngine.InflectMicroExperimental -> uiState.isInflectMicroReady
                    VoiceOutputEngine.SherpaExperimental -> uiState.isSelectedSherpaVoiceDownloaded
                    else -> true
                }
                val engineActive = engineSelected && engineReady
                val selectedVoiceDownloading = uiState.sherpaVoices
                    .firstOrNull { it.voice == uiState.selectedSherpaVoice }?.downloadState is VoicePackDownloadState.Downloading
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(engine.displayName) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(engine.description)
                            Text(
                                text = when {
                                    engineActive -> "Active"
                                    engineSelected && engine == VoiceOutputEngine.SherpaExperimental &&
                                        selectedVoiceDownloading -> "Setting up"
                                    engineSelected && engine == VoiceOutputEngine.SherpaExperimental ->
                                        "Needs ${uiState.selectedSherpaVoice.displayName}"
                                    engineSelected -> "Selected, setup needed"
                                    else -> "Inactive"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (engineActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                    trailingContent = {
                        RadioButton(
                            selected = engineSelected,
                            enabled = true,
                            onClick = { onVoiceOutputEngineSelected(engine) },
                        )
                    },
                )
                if (engine == VoiceOutputEngine.SherpaExperimental && engineSelected) {
                    Text(
                        text = sherpaVoiceStatusLine(
                            voice = uiState.selectedSherpaVoice,
                            state = uiState.sherpaVoices
                                .firstOrNull { it.voice == uiState.selectedSherpaVoice }?.downloadState,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                HorizontalDivider()
            }

            val piperVoiceDownloadState = uiState.sherpaVoices
                .firstOrNull { it.voice == uiState.selectedSherpaVoice }?.downloadState
            if (
                uiState.selectedOutputEngine != VoiceOutputEngine.SherpaExperimental &&
                piperVoiceDownloadState is VoicePackDownloadState.Downloading
            ) {
                Text(
                    text = sherpaVoiceStatusLine(uiState.selectedSherpaVoice, piperVoiceDownloadState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                // While the Inflect row is preparing, it owns this Cori download and shows the one Cancel.
                if (uiState.inflectMicroAvailability !is ModelAvailabilityState.Preparing) {
                    OutlinedButton(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        onClick = { onCancelSherpaVoice(uiState.selectedSherpaVoice) },
                    ) {
                        Text("Cancel")
                    }
                }
            }

            Text(
                text = "Choose how Jandal speaks. Only the selected option speaks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val inflectSelected =
                uiState.selectedOutputEngine == VoiceOutputEngine.InflectMicroExperimental
            val sherpaSelected =
                uiState.selectedOutputEngine == VoiceOutputEngine.SherpaExperimental
            val showSherpaVoiceControls = sherpaSelected
            if (showSherpaVoiceControls) {
                Text(
                    text = "Voice",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                val selectedSherpaName = uiState.selectedSherpaVoice.displayName
                val sherpaHelpText = when {
                    !uiState.hasDownloadedSherpaVoice ->
                        "Download a voice below before this option can speak."
                    !uiState.isSelectedSherpaVoiceDownloaded ->
                        "Your saved voice is not downloaded on this device. Download it again or choose another installed voice below."
                    else ->
                        "Only downloaded voices can be selected. Android TTS speaks only while it is selected above."
                }
                VoiceInfoCard(
                    title = if (uiState.isSelectedSherpaVoiceDownloaded) {
                        "$selectedSherpaName is selected"
                    } else {
                        "$selectedSherpaName is not downloaded"
                    },
                    message = sherpaHelpText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )


                // Tracks whether the speaker selector is expanded; resets to true on voice change.
                var speakerSelectorExpanded by rememberSaveable(uiState.selectedSherpaVoice) {
                    mutableStateOf(true)
                }

                val sortedVoices = uiState.sherpaVoices
                    .sortedByDescending { it.voice == uiState.selectedSherpaVoice }
                sortedVoices.forEach { voiceRow ->
                    val isSelected = uiState.selectedSherpaVoice == voiceRow.voice
                    val isDownloaded = voiceRow.downloadState is VoicePackDownloadState.Downloaded
                    val isMultiSpeaker = voiceRow.voice == SherpaPiperVoice.VctkMedium ||
                        voiceRow.voice == SherpaPiperVoice.SemaineMedium

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModelCardCompact(
                            title = voiceRow.voice.displayName,
                            description = voiceRow.voice.description,
                            state = uiState.sherpaVoiceAvailability[voiceRow.voice]
                                ?: ModelAvailabilityState.Unavailable(UnavailableReason.NotBundled),
                            modifier = Modifier.weight(1f),
                        )
                        RadioButton(
                            selected = isSelected,
                            onClick = { if (isDownloaded) onSherpaVoiceSelected(voiceRow.voice) },
                            enabled = isDownloaded,
                        )
                    }
                    TermsOfUseLinkWithSheet()
                    if (!isDownloaded) {
                        Text(
                            text = "About ${formatBytes(voiceRow.voice.approxDownloadBytes)} download",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                    val voiceRowState = voiceRow.downloadState
                    when (voiceRowState) {
                        is VoicePackDownloadState.NotDownloaded -> {
                            Button(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                onClick = { onDownloadSherpaVoice(voiceRow.voice) },
                            ) {
                                Text("Download")
                            }
                        }
                        is VoicePackDownloadState.Downloading -> {
                            if (voiceRowState.waitingForWifi) {
                                Text(
                                    text = "Waiting for Wi-Fi. Keep Wi-Fi on, or use mobile data for this voice only.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                )
                                Button(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                    onClick = { onUseMobileDataForVoice(voiceRow.voice) },
                                ) {
                                    Text("Use mobile data")
                                }
                            }
                            OutlinedButton(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                onClick = { onCancelSherpaVoice(voiceRow.voice) },
                            ) {
                                Text("Cancel")
                            }
                        }
                        is VoicePackDownloadState.Downloaded -> {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isSelected) {
                                    Button(onClick = { onPlaySherpaSample(voiceRow.voice) }) {
                                        Text("Play sample")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                OutlinedButton(onClick = { onDeleteSherpaVoice(voiceRow.voice) }) {
                                    Text("Delete")
                                }
                            }
                        }
                        is VoicePackDownloadState.Error -> {
                            OutlinedButton(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                onClick = { onDownloadSherpaVoice(voiceRow.voice) },
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                    HorizontalDivider()
                    if (isSelected && isDownloaded && isMultiSpeaker) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Speaker",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp),
                            )
                            IconButton(onClick = { speakerSelectorExpanded = !speakerSelectorExpanded }) {
                                Icon(
                                    imageVector = if (speakerSelectorExpanded) {
                                        Icons.Default.ExpandLess
                                    } else {
                                        Icons.Default.ExpandMore
                                    },
                                    contentDescription = if (speakerSelectorExpanded) {
                                        "Collapse speaker selector"
                                    } else {
                                        "Expand speaker selector"
                                    },
                                )
                            }
                        }
                        AnimatedVisibility(visible = speakerSelectorExpanded) {
                            when (voiceRow.voice) {
                                SherpaPiperVoice.VctkMedium -> VctkSpeakerSelector(
                                    activeSpeakerId = uiState.activeSpeakerId,
                                    onSpeakerSelected = onActiveSpeakerIdChanged,
                                )
                                SherpaPiperVoice.SemaineMedium -> SemaineSpeakerSelector(
                                    activeSpeakerId = uiState.activeSpeakerId,
                                    onSpeakerSelected = onActiveSpeakerIdChanged,
                                )
                                else -> Unit
                            }
                        }
                    }
                }
                uiState.sherpaSampleError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                // Speech rate slider — range 0.5–1.5 in steps of 0.05 (19 discrete steps)
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SliderRow(
                        label = "Speech rate",
                        valueLabel = "%.2fx".format(uiState.sherpaSpeed),
                        value = uiState.sherpaSpeed,
                        valueRange = 0.5f..1.5f,
                        steps = 19,
                        onValueChangeFinished = { newVal ->
                            onSherpaSpeedChanged(
                                (newVal * 20).roundToInt() / 20f
                            )
                        },
                    )
                }

                // Pitch slider — range 0.5–2.0 in steps of 0.05 (29 discrete steps)
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SliderRow(
                        label = "Pitch",
                        valueLabel = "%.2fx".format(uiState.sherpaPitch),
                        value = uiState.sherpaPitch,
                        valueRange = 0.5f..2.0f,
                        steps = 29,
                        onValueChangeFinished = { newVal ->
                            onSherpaPitchChanged(
                                (newVal * 20).roundToInt() / 20f
                            )
                        },
                    )
                }

                // Volume boost slider — range 0.5–3.0 in steps of 0.25 (9 discrete steps)
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SliderRow(
                        label = "Volume boost",
                        valueLabel = "%.2fx".format(uiState.sherpaGain),
                        value = uiState.sherpaGain,
                        valueRange = 0.5f..3.0f,
                        steps = 9,
                        onValueChangeFinished = { newVal ->
                            onSherpaGainChanged(
                                (newVal * 4).roundToInt() / 4f
                            )
                        },
                    )
                }
            }

            val inflectModelState = uiState.inflectMicroAvailability
            val coriDownloading = uiState.sherpaVoices
                .firstOrNull { it.voice == uiState.selectedSherpaVoice }?.downloadState is VoicePackDownloadState.Downloading
            val inflectGraphsReady = uiState.inflectMicroStates.isNotEmpty() &&
                uiState.inflectMicroStates.values.all {
                    it is com.kernel.ai.core.inference.download.DownloadState.Downloaded
                }
            if (
                VoiceOutputEngine.InflectMicroExperimental in uiState.availableOutputEngines &&
                (
                    inflectModelState !is ModelAvailabilityState.Ready ||
                        uiState.selectedOutputEngine == VoiceOutputEngine.InflectMicroExperimental
                    )
            ) {
                val inflectReady = inflectSelected && uiState.isInflectMicroReady
                Text(
                    text = "Inflect Micro model",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                VoiceInfoCard(
                    title = when {
                        inflectReady -> "Inflect Micro is active"
                        inflectSelected -> "Inflect Micro selected — setup required"
                        else -> "Inflect Micro is available"
                    },
                    message = when {
                        inflectReady ->
                            "Inflect Micro is ready to speak. Android TTS remains the fallback if synthesis is unavailable."
                        inflectSelected ->
                            "Inflect Micro is selected — setup required. Download the Inflect setup below before it can speak."
                        else ->
                            "Download Inflect Micro to enable this higher-quality local speech path."
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                ModelCardCompact(
                    title = "Inflect Micro",
                    description = "Higher-quality local TTS with all required setup assets.",
                    state = inflectModelState,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                TermsOfUseLinkWithSheet(modifier = Modifier.padding(horizontal = 16.dp))
                when {
                    inflectModelState is ModelAvailabilityState.Preparing -> {
                        OutlinedButton(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            onClick = onCancelInflectMicro,
                        ) {
                            Text("Cancel")
                        }
                    }
                    inflectModelState is ModelAvailabilityState.Ready -> {
                        OutlinedButton(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            onClick = onDeleteInflectMicro,
                        ) {
                            Text("Delete")
                        }
                    }
                    inflectModelState is ModelAvailabilityState.ActionRequired -> {
                        OutlinedButton(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            onClick = onDownloadInflectMicro,
                        ) {
                            Text("Retry")
                        }
                    }
                    inflectModelState is ModelAvailabilityState.NotDisplayed &&
                        inflectGraphsReady &&
                        coriDownloading &&
                        uiState.selectedOutputEngine == VoiceOutputEngine.SherpaExperimental -> {
                        Text(
                            text = "The Piper voice above is still downloading. Inflect is ready once it finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    else -> {
                        Button(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            onClick = onDownloadInflectMicro,
                        ) {
                            Text("Download")
                        }
                    }
                }
                HorizontalDivider()
            }

            if (uiState.selectedOutputEngine == VoiceOutputEngine.KokoroExperimental) {
                Text(
                    text = "Kokoro voice",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                val kokoroHelpText = if (!uiState.isSelectedKokoroVoiceDownloaded) {
                    "Download the Kokoro voice pack below before Kokoro can speak."
                } else {
                    "Kokoro (Experimental) is active. Android TTS is inactive while Kokoro is selected."
                }
                VoiceInfoCard(
                    title = "Kokoro (Experimental) is active",
                    message = kokoroHelpText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                // Speech rate slider — shared with Piper; 0.5–1.5 in steps of 0.05
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SliderRow(
                        label = "Speech rate",
                        valueLabel = "%.2fx".format(uiState.sherpaSpeed),
                        value = uiState.sherpaSpeed,
                        valueRange = 0.5f..1.5f,
                        steps = 19,
                        onValueChangeFinished = { newVal ->
                            onSherpaSpeedChanged(
                                (newVal * 20).roundToInt() / 20f
                            )
                        },
                    )
                }

                // Pitch slider — shared with Piper; 0.5–2.0 in steps of 0.05
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SliderRow(
                        label = "Pitch",
                        valueLabel = "%.2fx".format(uiState.sherpaPitch),
                        value = uiState.sherpaPitch,
                        valueRange = 0.5f..2.0f,
                        steps = 29,
                        onValueChangeFinished = { newVal ->
                            onSherpaPitchChanged(
                                (newVal * 20).roundToInt() / 20f
                            )
                        },
                    )
                }

                // Volume boost slider — shared with Piper; 0.5–3.0 in steps of 0.25
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SliderRow(
                        label = "Volume boost",
                        valueLabel = "%.2fx".format(uiState.sherpaGain),
                        value = uiState.sherpaGain,
                        valueRange = 0.5f..3.0f,
                        steps = 9,
                        onValueChangeFinished = { newVal ->
                            onSherpaGainChanged(
                                (newVal * 4).roundToInt() / 4f
                            )
                        },
                    )
                }

                uiState.kokoroVoices.forEach { voiceRow ->
                    val isSelected = uiState.selectedKokoroVoice == voiceRow.voice
                    val isDownloaded = voiceRow.downloadState is VoicePackDownloadState.Downloaded

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModelCardCompact(
                            title = voiceRow.voice.displayName,
                            description = voiceRow.voice.description,
                            state = uiState.kokoroVoiceAvailability[voiceRow.voice]
                                ?: ModelAvailabilityState.Unavailable(UnavailableReason.NotBundled),
                            modifier = Modifier.weight(1f),
                        )
                        RadioButton(
                            selected = isSelected,
                            onClick = { if (isDownloaded) onKokoroVoiceSelected(voiceRow.voice) },
                            enabled = isDownloaded,
                        )
                    }
                    TermsOfUseLinkWithSheet()
                    val kokoroRowState = voiceRow.downloadState
                    when (kokoroRowState) {
                        is VoicePackDownloadState.NotDownloaded -> {
                            Button(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                onClick = { onDownloadKokoroVoice(voiceRow.voice) },
                            ) {
                                Text("Download")
                            }
                        }
                        is VoicePackDownloadState.Downloading -> {
                            OutlinedButton(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                onClick = { onCancelKokoroVoice(voiceRow.voice) },
                            ) {
                                Text("Cancel")
                            }
                        }
                        is VoicePackDownloadState.Downloaded -> {
                            OutlinedButton(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                onClick = { onDeleteKokoroVoice(voiceRow.voice) },
                            ) {
                                Text("Delete")
                            }
                        }
                        is VoicePackDownloadState.Error -> {
                            OutlinedButton(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                onClick = { onDownloadKokoroVoice(voiceRow.voice) },
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                    HorizontalDivider()

                    if (isSelected && isDownloaded && voiceRow.voice.speakerCount > 1) {
                        KokoroSpeakerSelector(
                            voice = voiceRow.voice,
                            activeSpeakerId = uiState.kokoroActiveSpeakerId,
                            onSpeakerSelected = onKokoroActiveSpeakerIdChanged,
                        )
                    }
                }
            }

        }
    }

}

/**
 * Speaker selector for the `en_GB-vctk-medium` multi-speaker voice.
 *
 * Shows gender filter chips to narrow the list, then lists matching speakers as radio rows.
 * The outer [VoiceScreen] already wraps everything in a verticalScroll Column, so this composable
 * uses a plain (non-lazy) Column — safe for 109 items since they are lightweight rows.
 */
@Composable
private fun VctkSpeakerSelector(
    activeSpeakerId: Int,
    onSpeakerSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // "All" represented as null; "Male" / "Female" as strings
    var genderFilter by remember { mutableStateOf<String?>(null) }

    val filtered = remember(genderFilter) {
        if (genderFilter == null) VctkSpeakerMetadata.speakers
        else VctkSpeakerMetadata.speakers.filter { it.gender == genderFilter }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "VCTK speaker",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Text(
            text = "VCTK contains 109 British English speakers with varied accents. Selected: ${VctkSpeakerMetadata.displayLabel(activeSpeakerId)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        // Gender filter chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            listOf(null to "All", "Female" to "Female", "Male" to "Male").forEach { (value, label) ->
                FilterChip(
                    selected = genderFilter == value,
                    onClick = { genderFilter = value },
                    label = { Text(label) },
                )
            }
        }

        HorizontalDivider()

        filtered.forEach { speaker ->
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(speaker.speakerCode) },
                supportingContent = { Text("${speaker.gender} · ${speaker.accent}") },
                trailingContent = {
                    RadioButton(
                        selected = activeSpeakerId == speaker.sid,
                        onClick = { onSpeakerSelected(speaker.sid) },
                    )
                },
            )
            HorizontalDivider()
        }
    }
}

/**
 * Speaker selector for the `en_GB-semaine-medium` multi-speaker voice.
 *
 * Exposes all 4 speakers: Prudence, Spike, Obadiah, and Poppy.
 */
@Composable
private fun SemaineSpeakerSelector(
    activeSpeakerId: Int,
    onSpeakerSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Clamp to valid Semaine range so UI stays consistent with effectiveSid() in the controller.
    // A stale VCTK sid (e.g. 50) would otherwise show no RadioButton selected while TTS plays
    // sid=3 (Poppy) and the description text names Prudence.
    val effectiveId = activeSpeakerId.coerceIn(0, SherpaPiperVoice.SemaineMedium.speakerCount - 1)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Semaine speaker",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Text(
            text = "Semaine has 4 speakers with distinct characters. Selected: ${SemaineSpeakerMetadata.displayLabel(effectiveId)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        HorizontalDivider()

        SemaineSpeakerMetadata.speakers.forEach { speaker ->
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text(speaker.displayName) },
                supportingContent = { Text(speaker.description) },
                trailingContent = {
                    RadioButton(
                        selected = effectiveId == speaker.sid,
                        onClick = { onSpeakerSelected(speaker.sid) },
                    )
                },
            )
            HorizontalDivider()
        }
    }
}



/**
 * Speaker selector for Kokoro multi-speaker model (103 speakers, sid 0–102).
 * Shows a numeric input and slider since there are no labelled speaker names in the model card.
 */
@Composable
private fun KokoroSpeakerSelector(
    voice: SherpaKokoroVoice,
    activeSpeakerId: Int,
    onSpeakerSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectiveId = activeSpeakerId.coerceIn(0, voice.speakerCount - 1)

    // Three tabs: American English (sids 0–19), British English (20–27), Other (28–52)
    val americanRange = KokoroSpeakerGroup.AmericanFemale.sidRange.first..KokoroSpeakerGroup.AmericanMale.sidRange.last
    val britishRange  = KokoroSpeakerGroup.BritishFemale.sidRange.first..KokoroSpeakerGroup.BritishMale.sidRange.last
    val otherRange    = KokoroSpeakerGroup.Multilingual.sidRange

    fun sidToTabIndex(sid: Int): Int = when {
        sid in americanRange -> 0
        sid in britishRange  -> 1
        else                 -> 2
    }

    val initialTabIndex = sidToTabIndex(effectiveId)
    var selectedTabIndex by rememberSaveable(effectiveId) { mutableIntStateOf(initialTabIndex) }

    // Slider state for the Other tab (25 speakers, 0-based index within the range)
    val initialOtherIndex = if (effectiveId in otherRange) effectiveId - otherRange.first else 0
    var otherSliderValue by remember(effectiveId) { mutableIntStateOf(initialOtherIndex) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Kokoro speaker",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = "Selected: ${voice.speakerNameForSid(effectiveId)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        TabRow(selectedTabIndex = selectedTabIndex) {
            Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text("🇺🇸 American") })
            Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text("🇬🇧 British") })
            Tab(selected = selectedTabIndex == 2, onClick = { selectedTabIndex = 2 }, text = { Text("🌍 Other") })
        }

        when (selectedTabIndex) {
            // American tab: 20 named speakers (sids 0–19)
            0 -> americanRange.forEach { sid ->
                val name = voice.speakerNameForSid(sid)
                val subtitle = if (name.startsWith("af_")) "American Female" else "American Male"
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(name) },
                    supportingContent = { Text(subtitle) },
                    trailingContent = {
                        RadioButton(
                            selected = effectiveId == sid,
                            onClick = { onSpeakerSelected(sid) },
                        )
                    },
                )
                HorizontalDivider()
            }

            // British tab: 8 named speakers (sids 20–27)
            1 -> britishRange.forEach { sid ->
                val name = voice.speakerNameForSid(sid)
                val subtitle = if (name.startsWith("bf_")) "British Female" else "British Male"
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    headlineContent = { Text(name) },
                    supportingContent = { Text(subtitle) },
                    trailingContent = {
                        RadioButton(
                            selected = effectiveId == sid,
                            onClick = { onSpeakerSelected(sid) },
                        )
                    },
                )
                HorizontalDivider()
            }

            // Other tab: slider over 25 multilingual speakers (sids 28–52)
            else -> {
                val steps = otherRange.last - otherRange.first  // 24 steps → 25 values
                val currentSid = otherRange.first + otherSliderValue
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = voice.speakerNameForSid(currentSid),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Slider(
                        value = otherSliderValue.toFloat(),
                        onValueChange = { otherSliderValue = it.roundToInt() },
                        onValueChangeFinished = {
                            onSpeakerSelected(otherRange.first + otherSliderValue)
                        },
                        valueRange = 0f..steps.toFloat(),
                        steps = steps - 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceInfoCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}



@Composable
private fun VoiceOutputSelectionCard(
    selectedEngine: VoiceOutputEngine,
    inflectMicroReady: Boolean,
    sherpaVoiceReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val selectedEngineReady = when (selectedEngine) {
        VoiceOutputEngine.InflectMicroExperimental -> inflectMicroReady
        VoiceOutputEngine.SherpaExperimental -> sherpaVoiceReady
        else -> true
    }
    val message = when (selectedEngine) {
        VoiceOutputEngine.AndroidTts ->
            "Android TTS speaks. Piper voices below stay inactive until you select Piper."
        VoiceOutputEngine.SherpaExperimental ->
            if (selectedEngineReady) {
                "Piper speaks. Android TTS is inactive until you select Android TTS."
            } else {
                "Piper is selected, but its voice is not ready yet. Android TTS speaks until it is."
            }
        VoiceOutputEngine.KokoroExperimental ->
            "Kokoro (Experimental) speaks. Android TTS is inactive until you select Android TTS."
        VoiceOutputEngine.InflectMicroExperimental ->
            if (selectedEngineReady) {
                "Inflect Micro is selected and ready to speak. Android TTS remains the fallback if synthesis is unavailable."
            } else {
                "Inflect Micro is selected — setup required. Download the Inflect setup below before it can speak."
            }
    }

    VoiceInfoCard(
        title = if (selectedEngineReady) {
            "Active: ${selectedEngine.displayName}"
        } else {
            "Selected: ${selectedEngine.displayName}"
        },
        message = message,
        modifier = modifier,
    )
}

/**
 * A labelled slider row with an editable text field for precise input.
 * Duplicated locally from [ModelSettingsScreen] to avoid cross-module coupling.
 */
internal fun shouldCommitTextOnFocusLost(wasFocused: Boolean, isFocused: Boolean): Boolean =
    wasFocused && !isFocused

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Float) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    var textValue by remember(value) { mutableStateOf("%.2f".format(value)) }
    var textFieldWasFocused by remember { mutableStateOf(false) }
    fun commitText(raw: String) {
        val parsed = raw.trim().toFloatOrNull()
        if (parsed != null) {
            val clamped = parsed.coerceIn(valueRange.start, valueRange.endInclusive)
            sliderValue = clamped
            textValue = "%.2f".format(clamped)
            onValueChangeFinished(clamped)
        } else {
            textValue = "%.2f".format(sliderValue)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = sliderValue,
                onValueChange = { newVal ->
                    sliderValue = newVal
                    textValue = "%.2f".format(newVal)
                },
                onValueChangeFinished = { onValueChangeFinished(sliderValue) },
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                modifier = Modifier
                    .width(76.dp)
                    .onFocusChanged { focusState ->
                        val lostFocus = shouldCommitTextOnFocusLost(
                            wasFocused = textFieldWasFocused,
                            isFocused = focusState.isFocused,
                        )
                        textFieldWasFocused = focusState.isFocused
                        if (lostFocus) commitText(textValue)
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitText(textValue)
                        focusManager.clearFocus()
                    },
                ),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "${"%.1f".format(bytes / 1_000_000_000.0)} GB"
    bytes >= 1_000_000L -> "${"%.0f".format(bytes / 1_000_000.0)} MB"
    bytes >= 1_000L -> "${"%.0f".format(bytes / 1_000.0)} KB"
    else -> "$bytes B"
}

@Preview(showBackground = true)
@Composable
private fun VoiceScreenPreview() {
    MaterialTheme {
        VoiceScreenContent(
            uiState = VoiceUiState(
                selectedOutputEngine = VoiceOutputEngine.SherpaExperimental,
                selectedSherpaVoice = SherpaPiperVoice.NorthernEnglishMale,
                hasDownloadedSherpaVoice = true,
                isSelectedSherpaVoiceDownloaded = false,
                sherpaSpeed = 0.85f,
                sherpaVoices = listOf(
                    SherpaVoiceRowUiState(
                        voice = SherpaPiperVoice.JennyDioco,
                        downloadState = VoicePackDownloadState.Downloaded("/data/user/0/ai.jandal.assistant.debug/files/sherpa-tts/vits-piper-en_GB-jenny_dioco-medium"),
                    ),
                    SherpaVoiceRowUiState(
                        voice = SherpaPiperVoice.SouthernEnglishFemale,
                        downloadState = VoicePackDownloadState.Downloading(progress = 0.42f, bytesPerSecond = 3_500_000L, remainingMs = 15_000L),
                    ),
                    SherpaVoiceRowUiState(
                        voice = SherpaPiperVoice.NorthernEnglishMale,
                        downloadState = VoicePackDownloadState.NotDownloaded,
                    ),
                ),
            ),
            onBack = {},
            onRequestAssistantRole = {},
            onHeyJandalEnabledChanged = {},
            onWakeWordThresholdChanged = {},
            onVoiceInputEngineSelected = {},
            onAutoStartAlertVoiceCommandsEnabledChanged = {},
            onAllowOnlineSpeechFallbackChanged = {},
            onSpokenResponsesEnabledChanged = {},
            onVoiceOutputEngineSelected = {},
            onSherpaVoiceSelected = {},
            onSherpaSpeedChanged = {},
            onSherpaPitchChanged = {},
            onSherpaGainChanged = {},
            onAutoSpeakChanged = {},
            onMaxSpokenSentencesChanged = {},
            onActiveSpeakerIdChanged = {},
            onKokoroVoiceSelected = {},
            onKokoroActiveSpeakerIdChanged = {},
        )
    }
}

/** One-line status for the selected Piper voice, shown under the Piper option. */
private fun sherpaVoiceStatusLine(voice: SherpaPiperVoice, state: VoicePackDownloadState?): String =
    when (state) {
        is VoicePackDownloadState.Downloaded -> "${voice.displayName} · Ready"
        is VoicePackDownloadState.Downloading ->
            if (state.waitingForWifi) {
                "${voice.displayName} · Waiting for Wi-Fi"
            } else {
                "${voice.displayName} · Downloading ${(state.progress * 100).toInt()}%"
            }
        is VoicePackDownloadState.Error -> "${voice.displayName} · Download failed"
        else -> "${voice.displayName} · Not downloaded"
    }
