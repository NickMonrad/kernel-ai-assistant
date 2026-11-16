package com.kernel.ai.feature.chat.setup
import com.kernel.ai.core.ui.findActivity

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kernel.ai.core.inference.download.DownloadConsent
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.model.availability.ModelRoleCopy
import com.kernel.ai.core.permissions.PermissionRequestStore
import com.kernel.ai.core.permissions.PermissionRoute
import com.kernel.ai.core.permissions.RuntimePermissionRepair
import com.kernel.ai.core.permissions.decidePermissionRoute
import com.kernel.ai.core.permissions.defaultAssistantSettingsIntents
import com.kernel.ai.core.permissions.exactAlarmSettingsIntents
import com.kernel.ai.core.permissions.isSilentDenial
import com.kernel.ai.core.permissions.launchFirstAvailable
import com.kernel.ai.core.permissions.recordPermissionResult
import com.kernel.ai.core.ui.findActivity
import com.kernel.ai.core.ui.rememberDownloadNotificationGate
import com.kernel.ai.feature.chat.R
import com.kernel.ai.core.voice.HonorCameraCoexistence
import androidx.compose.ui.res.stringResource
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.HeyJandalBlock
import com.kernel.ai.core.voice.VoiceDefaultsProvisioner
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.feature.chat.formatBytesShort

private const val FINISH_ID = "finish"
private const val SKIP_ID = "skip"

/** Test tag on the scrolling container of the step content (#1903 large-font test). In compact mode it wraps the whole page. */
internal const val SETUP_WIZARD_CONTENT_TAG = "setup_wizard_content"

/** Below this window height the wizard scrolls as one page instead of pinning its buttons (#1903). */
private val COMPACT_HEIGHT = 480.dp

/**
 * Button callbacks of the Make Jandal yours step (#1855 part 3). Each one runs only on a tap. The screen decides
 * which system prompt or page to open; the view model records choices and reads state.
 */
data class MakeYoursActions(
    val onWakeWordChoiceChanged: (Boolean) -> Unit = {},
    val onAllowMicrophone: () -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onOpenDefaultAssistant: () -> Unit = {},
    val onOpenUsageAccess: () -> Unit = {},
    val onOpenExactAlarm: () -> Unit = {},
)

/**
 * First-run setup wizard (#1855). Step 1 explains how Jandal works; step 2 chooses speech input and output;
 * step 3 is download consent; step 4 is optional Make Jandal yours rows. [onExit] leaves without completing
 * (back on the first step); [onDone] runs after finishing or skipping.
 */
@Composable
fun SetupWizardScreen(
    onExit: () -> Unit,
    onDone: () -> Unit,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestWizardAction = rememberDownloadNotificationGate { id ->
        when (id) {
            FINISH_ID -> viewModel.onContinue(onDone)
            SKIP_ID -> viewModel.onSkip { choice ->
                Toast.makeText(context, skipNote(choice), Toast.LENGTH_LONG).show()
                onDone()
            }
            else -> Unit
        }
    }
    // Every return from a system page or prompt re-reads the phone, so each row shows the state just set.
    RefreshOnResume(onResume = viewModel::refreshPlatform)

    var requestedAt by rememberSaveable { mutableLongStateOf(0L) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        context.recordPermissionResult(
            permission = Manifest.permission.POST_NOTIFICATIONS,
            granted = granted,
            shouldShowRationale = context.findActivity()
                ?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true,
            silentlyDenied = !granted && isSilentDenial(System.currentTimeMillis() - requestedAt),
        )
        viewModel.refreshPlatform()
    }
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        context.recordPermissionResult(
            permission = Manifest.permission.RECORD_AUDIO,
            granted = granted,
            shouldShowRationale = context.findActivity()
                ?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true,
            silentlyDenied = !granted && isSilentDenial(System.currentTimeMillis() - requestedAt),
        )
        viewModel.refreshPlatform()
    }

    val assistantMissing = stringResource(R.string.setup_wizard_assistant_page_missing)
    val usageAccessMissing = stringResource(R.string.setup_wizard_usage_access_page_missing)
    val exactAlarmMissing = stringResource(R.string.setup_wizard_exact_alarm_page_missing)
    val makeYours = MakeYoursActions(
        onWakeWordChoiceChanged = viewModel::onWakeWordChoiceChanged,
        onAllowMicrophone = {
            when (context.permissionRouteFor(Manifest.permission.RECORD_AUDIO)) {
                PermissionRoute.RequestInApp -> {
                    requestedAt = System.currentTimeMillis()
                    microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                else -> openAppInfo(context, Manifest.permission.RECORD_AUDIO)
            }
        },
        onAllowNotifications = {
            when (context.permissionRouteFor(Manifest.permission.POST_NOTIFICATIONS)) {
                PermissionRoute.RequestInApp -> {
                    viewModel.onNotificationsRequested()
                    requestedAt = System.currentTimeMillis()
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> openAppInfo(context, Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onOpenDefaultAssistant = {
            val opened = launchFirstAvailable(defaultAssistantSettingsIntents()) { context.startActivity(it) }
            if (!opened) toast(context, assistantMissing)
        },
        onOpenUsageAccess = {
            val opened = launchFirstAvailable(listOf(HonorCameraCoexistence.usageAccessSettingsIntent(context))) {
                context.startActivity(it)
            }
            if (!opened) toast(context, usageAccessMissing)
        },
        onOpenExactAlarm = {
            val opened = launchFirstAvailable(exactAlarmSettingsIntents(context.packageName)) {
                context.startActivity(it)
            }
            if (!opened) toast(context, exactAlarmMissing)
        },
    )

    BackHandler(enabled = state.loaded && state.step != SetupStep.HOW_IT_WORKS) {
        viewModel.onBack(onExit)
    }
    if (!state.loaded) return
    SetupWizardContent(
        state = state,
        onInputSelected = viewModel::onInputSelected,
        onOutputSelected = viewModel::onOutputSelected,
        onChoiceSelected = viewModel::onChoiceSelected,
        makeYours = makeYours,
        onPrimary = {
            if (state.step == SetupStep.entries.last()) {
                // The notification row already asked in this run, so Finish must not ask a second time (#1902).
                if (state.notificationsAsked) viewModel.onContinue(onDone) else requestWizardAction(FINISH_ID)
            } else {
                viewModel.onContinue(onDone)
            }
        },
        onBack = { viewModel.onBack(onExit) },
        onSkip = {
            // Skipping the last step never shows the notification prompt: that row was not tapped (#1902).
            if (state.step == SetupStep.MAKE_JANDAL_YOURS || state.notificationsAsked) {
                viewModel.onSkip { choice ->
                    Toast.makeText(context, skipNote(choice), Toast.LENGTH_LONG).show()
                    onDone()
                }
            } else {
                requestWizardAction(SKIP_ID)
            }
        },
    )
}

/**
 * The route for a permission the user has not granted (#1880 routing): an in-app request, or App info when
 * Android will not ask again. Called on a tap, never during composition.
 */
private fun Context.permissionRouteFor(permission: String): PermissionRoute = decidePermissionRoute(
    isGranted = false,
    isSpecialAccess = false,
    wasDeniedBefore = PermissionRequestStore.from(this).wasDenied(permission),
    shouldShowRationale = findActivity()?.shouldShowRequestPermissionRationale(permission) == true,
)

private fun openAppInfo(context: Context, permission: String) {
    val opened = launchFirstAvailable(listOf(RuntimePermissionRepair.intentFor(context.packageName, permission))) {
        context.startActivity(it)
    }
    if (!opened) toast(context, context.getString(R.string.setup_wizard_app_info_missing))
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

/**
 * Stateless wizard layout (#1903). Portrait: step content scrolls above a pinned button row. Short windows
 * (landscape phones): the whole page scrolls so the buttons keep a 48 dp minimum instead of being squeezed.
 */
@Composable
internal fun SetupWizardContent(
    state: SetupWizardUiState,
    onInputSelected: (VoiceInputEngine) -> Unit,
    onOutputSelected: (VoiceOutputEngine) -> Unit,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
    makeYours: MakeYoursActions,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val isLast = state.step == SetupStep.entries.last()
    val scrollState = rememberScrollState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxHeight < COMPACT_HEIGHT) {
            // Short windows (landscape phones at large font): a pinned button row cannot keep its 48 dp minimum,
            // so the whole page scrolls and the buttons stay reachable at the end of it.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag(SETUP_WIZARD_CONTENT_TAG),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StepContent(Modifier, state, onInputSelected, onOutputSelected, onChoiceSelected, makeYours)
                WizardButtons(isLast, onPrimary, onBack, onSkip)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .testTag(SETUP_WIZARD_CONTENT_TAG),
                ) {
                    StepContent(Modifier, state, onInputSelected, onOutputSelected, onChoiceSelected, makeYours)
                }
                WizardButtons(isLast, onPrimary, onBack, onSkip)
            }
        }
    }
}

@Composable
private fun StepContent(
    modifier: Modifier,
    state: SetupWizardUiState,
    onInputSelected: (VoiceInputEngine) -> Unit,
    onOutputSelected: (VoiceOutputEngine) -> Unit,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
    makeYours: MakeYoursActions,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.step) {
            SetupStep.HOW_IT_WORKS -> HowItWorksStep()
            SetupStep.VOICE -> VoiceStep(
                inputOptions = state.inputOptions,
                inputReason = state.inputReason,
                inputEngine = state.inputEngine,
                outputOptions = state.outputOptions,
                outputEngine = state.outputEngine,
                onInputSelected = onInputSelected,
                onOutputSelected = onOutputSelected,
            )
            SetupStep.DOWNLOAD -> DownloadStep(
                plannedModels = state.plannedModels,
                plannedVoices = state.plannedVoices,
                choice = state.choice,
                choiceRecorded = state.choiceRecorded,
                onChoiceSelected = onChoiceSelected,
            )
            SetupStep.MAKE_JANDAL_YOURS -> MakeYoursStep(state, makeYours)
        }
    }
}

@Composable
private fun WizardButtons(isLast: Boolean, onPrimary: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit) {
    Button(
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(if (isLast) "Finish" else "Continue")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Back") }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Skip for now") }
    }
}

/** Download-step size line. Zero bytes left means nothing to download, never "About 0 B" (#1903). */
internal fun downloadSizeLine(totalBytes: Long, choiceRecorded: Boolean): String = when {
    totalBytes <= 0L -> "Nothing left to download. Jandal already has every model and voice it needs on this phone."
    choiceRecorded -> "About ${formatBytesShort(totalBytes)} still to download. " +
        "Your network choice applies to downloads in progress."
    else -> "About ${formatBytesShort(totalBytes)} in total. Nothing downloads until you tap Finish or Skip on the last step."
}

private fun skipNote(choice: DownloadNetworkChoice): String = when (choice) {
    DownloadNetworkChoice.WIFI_ONLY -> "Skipped. Models will download on Wi-Fi only. Change this in Model Management."
    DownloadNetworkChoice.MOBILE_DATA_ALLOWED -> "Skipped. Models will download on mobile data too. Change this in Model Management."
}

@Composable
private fun HowItWorksStep() {
    Text(
        text = "Kia ora, welcome to Jandal",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = "Jandal is a private assistant that runs on your phone. Your chats and the things you tell it " +
            "stay on this device.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = "Timers, weather and shopping lists work straight away. Chatting needs model files, " +
            "which Jandal downloads once.",
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun VoiceStep(
    inputOptions: List<VoiceInputEngine>,
    inputReason: String?,
    inputEngine: VoiceInputEngine?,
    outputOptions: List<VoiceOutputEngine>,
    outputEngine: VoiceOutputEngine?,
    onInputSelected: (VoiceInputEngine) -> Unit,
    onOutputSelected: (VoiceOutputEngine) -> Unit,
) {
    Text(
        text = "How should Jandal hear and talk to you?",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = "Pick both now. Nothing downloads until you tap Finish or Skip on the last step.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = "When you talk to Jandal",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    inputReason?.let { reason ->
        Text(text = reason, style = MaterialTheme.typography.bodySmall)
    }
    if (inputEngine == null) {
        Text(text = "Your current speech input stays as it is unless you pick one below.", style = MaterialTheme.typography.bodySmall)
    }
    inputOptions.forEach { engine ->
        ChoiceOption(
            title = inputTitle(engine),
            body = inputBody(engine),
            selected = inputEngine == engine,
            onSelect = { onInputSelected(engine) },
        )
    }
    Text(
        text = "When Jandal talks back",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    outputOptions.forEach { engine ->
        ChoiceOption(
            title = outputTitle(engine),
            body = outputBody(engine),
            selected = outputEngine == engine,
            onSelect = { onOutputSelected(engine) },
        )
    }
    if (outputEngine == null) {
        Text(text = "Your current speech output stays as it is unless you pick one above.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun inputTitle(engine: VoiceInputEngine): String = when (engine) {
    VoiceInputEngine.AndroidNative -> "Android's own listener"
    else -> "Automatic (recommended)"
}

private fun inputBody(engine: VoiceInputEngine): String = when (engine) {
    VoiceInputEngine.AndroidNative -> "Uses the speech recognition built into your phone."
    else -> "Works offline on this phone."
}

private fun outputTitle(engine: VoiceOutputEngine): String = when (engine) {
    VoiceOutputEngine.AndroidTts -> "Your phone's voice"
    else -> "Cori, natural voice (about ${formatBytesShort(SherpaPiperVoice.CoriHigh.approxDownloadBytes)})"
}

private fun outputBody(engine: VoiceOutputEngine): String = when (engine) {
    VoiceOutputEngine.AndroidTts -> "The voice already on your phone. Nothing to download."
    else -> "Sounds more natural and works offline. It downloads with the other files in the next step. " +
        "May be slower on older phones."
}

@Composable
private fun DownloadStep(
    plannedModels: List<KernelModel>,
    plannedVoices: List<SherpaPiperVoice>,
    choice: DownloadNetworkChoice,
    choiceRecorded: Boolean,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
) {
    val totalBytes = DownloadConsent.firstRunEstimateBytes(plannedModels, plannedVoices.sumOf { it.approxDownloadBytes })
    Text(
        text = "Download Jandal's models",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = downloadSizeLine(totalBytes, choiceRecorded),
        style = MaterialTheme.typography.bodyLarge,
    )
    val (zipformer, others) = plannedModels.partition { it in VoiceDefaultsProvisioner.zipformerModels() }
    if (zipformer.isNotEmpty()) {
        Column {
            Text(text = "Speech recognition (Zipformer)", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Turns your voice into text, offline. ${formatBytesShort(zipformer.sumOf { it.approxSizeBytes })}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    others.forEach { model ->
        Column {
            Text(text = ModelRoleCopy.purpose(model), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = ModelRoleCopy.technicalLine(model),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    plannedVoices.forEach { voice ->
        Column {
            Text(text = "${voice.displayName} natural voice", style = MaterialTheme.typography.bodyLarge)
            Text(text = formatBytesShort(voice.approxDownloadBytes), style = MaterialTheme.typography.bodySmall)
        }
    }

    Text(
        text = "Which network should downloads use?",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    ChoiceOption(
        title = "Wi-Fi only (recommended)",
        body = "Downloads wait until you are on Wi-Fi.",
        selected = choice == DownloadNetworkChoice.WIFI_ONLY,
        onSelect = { onChoiceSelected(DownloadNetworkChoice.WIFI_ONLY) },
    )
    ChoiceOption(
        title = "Allow mobile data",
        body = "Downloads may use your mobile data plan.",
        selected = choice == DownloadNetworkChoice.MOBILE_DATA_ALLOWED,
        onSelect = { onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED) },
    )
}

/**
 * Optional Make Jandal yours step (#1855 part 3). Four rows, each with a live status (On, Off or Not
 * available) read from the phone. Nothing here requests a permission or opens a page until its button is tapped.
 */
@Composable
private fun MakeYoursStep(state: SetupWizardUiState, actions: MakeYoursActions) {
    val platform = state.platform
    val wakeBlock = MakeYoursRows.heyJandalBlock(platform)
    val wakeStatus = MakeYoursRows.wakeWord(state.wakeWordStored, platform)
    val wakePending = state.wakeWordChoice != state.wakeWordStored
    val assistantStatus = MakeYoursRows.defaultAssistant(platform)
    val notificationStatus = MakeYoursRows.notifications(platform)
    val exactStatus = MakeYoursRows.exactAlarm(platform)

    Text(
        text = "Make Jandal yours",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = "All of these are optional. Skip any of them and change them later in Settings.",
        style = MaterialTheme.typography.bodyLarge,
    )

    // The blocker's own fix comes first. "Turn off" is offered whenever the stored choice is On.
    val wakeActions = buildList<Pair<String, () -> Unit>> {
        when (wakeBlock) {
            HeyJandalBlock.MicrophoneNotGranted -> add("Allow microphone" to actions.onAllowMicrophone)
            HeyJandalBlock.UsageAccessNeeded -> add("Open Usage Access" to actions.onOpenUsageAccess)
            null -> if (!state.wakeWordChoice) add("Turn on" to { actions.onWakeWordChoiceChanged(true) })
            HeyJandalBlock.NoMicrophone, HeyJandalBlock.ModelNotInstalled -> Unit
        }
        if (state.wakeWordChoice) add("Turn off" to { actions.onWakeWordChoiceChanged(false) })
    }
    // Reason first, then what Finish will do. Never promise a turn-on that a blocker will stop.
    val wakeNote = listOfNotNull(
        MakeYoursRows.wakeWordBlockReason(platform),
        when {
            wakeBlock != null && state.wakeWordChoice -> "Stays off until this is fixed."
            wakePending && state.wakeWordChoice -> "Turns on when you tap Finish."
            wakePending -> "Turns off when you tap Finish."
            else -> null
        },
    ).joinToString(" ").takeIf { it.isNotEmpty() }

    MakeYoursRow(
        id = "hey_jandal",
        title = "Hey Jandal",
        reason = "Say \"Hey Jandal\" and Jandal starts listening, hands-free. The mic stays on for the wake " +
            "word, so it uses a little battery. It works most reliably once Jandal is your default assistant, " +
            "which is the next row." +
            if (state.inputEngine == VoiceInputEngine.AndroidNative) {
                " Hey Jandal also needs the Zipformer speech model, about 72 MB, even though Android's listener " +
                    "is your speech input."
            } else {
                ""
            },
        status = wakeStatus,
        note = wakeNote,
        actions = wakeActions,
    )

    MakeYoursRow(
        id = "default_assistant",
        title = "Default assistant",
        reason = "Makes Jandal the app that opens when you use your phone's assistant button or gesture. " +
            "Hey Jandal listens most reliably with this set. Pick Jandal AI on the system page, then come back. " +
            "This row updates by itself.",
        status = assistantStatus,
        note = if (assistantStatus == MakeYoursStatus.NotAvailable) {
            "This phone has no default assistant setting."
        } else {
            null
        },
        actions = if (assistantStatus == MakeYoursStatus.NotAvailable) {
            emptyList()
        } else {
            listOf("Open default assistant settings" to actions.onOpenDefaultAssistant)
        },
    )

    MakeYoursRow(
        id = "notifications",
        title = "Notifications",
        reason = "Lets Jandal show download progress, alarms and reminders. Downloads keep going either way.",
        status = notificationStatus,
        note = if (notificationStatus == MakeYoursStatus.Off) {
            "If Android won't ask again, Jandal opens App info so you can turn it on there."
        } else {
            null
        },
        actions = if (notificationStatus == MakeYoursStatus.Off) {
            listOf("Allow notifications" to actions.onAllowNotifications)
        } else {
            emptyList()
        },
    )

    MakeYoursRow(
        id = "exact_alarms",
        title = "Alarms and timers",
        reason = "So timers ring on time. Without this, Android can delay them.",
        status = exactStatus,
        note = if (exactStatus == MakeYoursStatus.Off) {
            "Open Alarms & reminders, find Jandal AI, and turn on Allow. Then come back. This row updates by itself."
        } else {
            null
        },
        actions = if (exactStatus == MakeYoursStatus.Off) {
            listOf("Open Alarms & reminders" to actions.onOpenExactAlarm)
        } else {
            emptyList()
        },
    )
}

@Composable
private fun MakeYoursRow(
    id: String,
    title: String,
    reason: String,
    status: MakeYoursStatus,
    note: String?,
    actions: List<Pair<String, () -> Unit>>,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = reason, style = MaterialTheme.typography.bodySmall)
        Text(
            text = "Status: ${MakeYoursRows.statusLabel(status)}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("make_yours_status_$id"),
        )
        note?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        actions.forEach { (label, onClick) ->
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun ChoiceOption(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(text = body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Calls [onResume] on every ON_RESUME, so the phone is re-read after each system page or prompt. */
@Composable
internal fun RefreshOnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnResume by rememberUpdatedState(onResume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latestOnResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
