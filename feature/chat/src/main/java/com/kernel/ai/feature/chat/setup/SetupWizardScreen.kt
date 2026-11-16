package com.kernel.ai.feature.chat.setup

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.kernel.ai.core.ui.ChipStatus
import com.kernel.ai.core.ui.ChoiceCard
import com.kernel.ai.core.ui.ChoiceGroup
import com.kernel.ai.core.ui.ExplanationSheet
import com.kernel.ai.core.ui.InfoTag
import com.kernel.ai.core.ui.InfoTagStyle
import com.kernel.ai.core.ui.theme.JandalTheme
import com.kernel.ai.core.ui.SectionLabel
import com.kernel.ai.core.ui.StatusChip
import com.kernel.ai.core.ui.SwitchSubSetting
import com.kernel.ai.core.ui.findActivity
import com.kernel.ai.core.ui.rememberDownloadNotificationGate
import com.kernel.ai.core.voice.HeyJandalBlock
import com.kernel.ai.core.voice.HonorCameraCoexistence
import com.kernel.ai.core.voice.OnlineSpeechCopy
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceDefaultsProvisioner
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.onlineSpeechToggleVisible
import com.kernel.ai.feature.chat.R
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
        onOnlineSpeechChanged = viewModel::onOnlineSpeechChanged,
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
 * Stateless wizard layout (#1903). Portrait: step content scrolls above a pinned footer. Short windows
 * (landscape phones): the whole page scrolls so the buttons keep a 48 dp minimum instead of being squeezed.
 * The scroll position resets on each step change (#1929).
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
    onOnlineSpeechChanged: (Boolean) -> Unit = {},
) {
    val isLast = state.step == SetupStep.entries.last()
    val scrollState = rememberSaveable(state.step, saver = ScrollState.Saver) { ScrollState(0) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxHeight < COMPACT_HEIGHT) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag(SETUP_WIZARD_CONTENT_TAG),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StepContent(state, onInputSelected, onOutputSelected, onChoiceSelected, onOnlineSpeechChanged, makeYours)
                WizardButtons(state.step, isLast, onPrimary, onBack, onSkip)
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
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StepContent(state, onInputSelected, onOutputSelected, onChoiceSelected, onOnlineSpeechChanged, makeYours)
                }
                WizardButtons(state.step, isLast, onPrimary, onBack, onSkip)
            }
        }
    }
}

@Composable
private fun StepContent(
    state: SetupWizardUiState,
    onInputSelected: (VoiceInputEngine) -> Unit,
    onOutputSelected: (VoiceOutputEngine) -> Unit,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
    onOnlineSpeechChanged: (Boolean) -> Unit,
    makeYours: MakeYoursActions,
) {
    when (state.step) {
        SetupStep.HOW_IT_WORKS -> WelcomeStep(state)
        SetupStep.VOICE -> VoiceStep(
            state = state,
            onInputSelected = onInputSelected,
            onOutputSelected = onOutputSelected,
            onOnlineSpeechChanged = onOnlineSpeechChanged,
        )
        SetupStep.DOWNLOAD -> DownloadStep(
            plannedModels = state.plannedModels,
            plannedVoices = state.plannedVoices,
            choice = state.choice,
            choiceRecorded = state.choiceRecorded,
            isRerun = state.isRerun,
            onChoiceSelected = onChoiceSelected,
        )
        SetupStep.MAKE_JANDAL_YOURS -> MakeYoursStep(state, makeYours)
    }
}

/** Footer: the primary action, then Back and Skip. Skip reads "Skip setup" on steps 1–3 and "Skip these" on step 4. */
@Composable
private fun WizardButtons(
    step: SetupStep,
    isLast: Boolean,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val accent = JandalTheme.colors.accent
    Button(
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
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
            colors = ButtonDefaults.textButtonColors(contentColor = accent),
        ) { Text("Back") }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = accent),
        ) { Text(if (step == SetupStep.MAKE_JANDAL_YOURS) "Skip these" else "Skip setup") }
    }
}

private fun skipNote(choice: DownloadNetworkChoice): String = when (choice) {
    DownloadNetworkChoice.WIFI_ONLY -> "Skipped. Models will download on Wi-Fi only. Change this in Model Management."
    DownloadNetworkChoice.MOBILE_DATA_ALLOWED -> "Skipped. Models will download on mobile data too. Change this in Model Management."
}

/** Shared step heading: a semantic title and a plain subtitle. */
@Composable
private fun StepHeading(title: String, subtitle: String?) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    subtitle?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
}

/** A 44 dp icon tile on the primary container, used by the welcome rows and summary rows. */
@Composable
private fun IconTile(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun WelcomeRow(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconTile(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WelcomeStep(state: SetupWizardUiState) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    StepHeading(
        title = "Kia ora, welcome to Jandal",
        subtitle = "A private assistant that runs on your phone.",
    )
    WelcomeRow(
        icon = Icons.Outlined.Lock,
        title = "Private by default",
        body = "Your chats and what you tell Jandal stay on this phone.",
    )
    WelcomeRow(
        icon = Icons.Outlined.Bolt,
        title = "Timers, weather and lists work now",
        body = "No download needed for these.",
    )
    WelcomeRow(
        icon = Icons.Outlined.Download,
        title = "Chat needs a one-time download",
        body = if (state.isRerun) {
            "Already set up on this phone. You choose Wi-Fi or mobile data in step 3."
        } else if (state.welcomeTotalBytes > 0L) {
            "About ${formatBytesShort(state.welcomeTotalBytes)}. You choose Wi-Fi or mobile data in step 3."
        } else {
            "Already downloaded on this phone. You choose Wi-Fi or mobile data in step 3."
        },
    )
}

@Composable
private fun VoiceStep(
    state: SetupWizardUiState,
    onInputSelected: (VoiceInputEngine) -> Unit,
    onOutputSelected: (VoiceOutputEngine) -> Unit,
    onOnlineSpeechChanged: (Boolean) -> Unit,
) {
    val inputEngine = state.inputEngine
    val outputEngine = state.outputEngine
    StepHeading(
        title = "How should Jandal hear and talk to you?",
        subtitle = "Nothing downloads until the last step.",
    )
    SectionLabel(text = "When you talk to Jandal")
    state.inputReason?.let { reason ->
        Text(text = reason, style = MaterialTheme.typography.bodySmall)
    }
    if (inputEngine == null) {
        Text(
            text = "Your current speech input stays as it is.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    ChoiceGroup(count = state.inputOptions.size) {
        state.inputOptions.forEachIndexed { index, engine ->
            val selected = inputEngine == engine
            ChoiceCard(
                title = inputTitle(engine),
                description = inputBody(engine),
                selected = selected,
                onSelect = { onInputSelected(engine) },
                index = index,
                tag = if (engine == VoiceInputEngine.Auto) {
                    { InfoTag(text = "Recommended", style = InfoTagStyle.Success) }
                } else {
                    null
                },
                subContent = if (selected && inputEngine != null && onlineSpeechToggleVisible(inputEngine, engine)) {
                    {
                        OnlineSpeechRow(
                            checked = state.allowOnlineSpeech,
                            onCheckedChange = onOnlineSpeechChanged,
                        )
                    }
                } else {
                    null
                },
            )
        }
    }

    SectionLabel(text = "When Jandal talks back")
    if (outputEngine == null) {
        Text(
            text = "Your current speech output stays as it is.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    ChoiceGroup(count = state.outputOptions.size) {
        state.outputOptions.forEachIndexed { index, engine ->
            ChoiceCard(
                title = outputTitle(engine),
                description = outputBody(engine),
                selected = outputEngine == engine,
                onSelect = { onOutputSelected(engine) },
                index = index,
                tag = outputTag(state.outputSizes[engine]),
            )
        }
    }
}

/** Online speech switch inside the selected listening card (#1936). Saved on Finish or Skip. */
@Composable
private fun OnlineSpeechRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    var showExplanation by remember { mutableStateOf(false) }
    SwitchSubSetting(
        title = OnlineSpeechCopy.TITLE,
        description = OnlineSpeechCopy.LINE,
        checked = checked,
        onCheckedChange = onCheckedChange,
        onMore = { showExplanation = true },
        moreDescription = "More about online speech recognition",
    )
    if (showExplanation) {
        ExplanationSheet(
            title = OnlineSpeechCopy.TITLE,
            body = OnlineSpeechCopy.EXPLANATION,
            onDismiss = { showExplanation = false },
        )
    }
}

/** Size or status tag for a speech output: "Downloaded" at 0 bytes, the size when needed, none otherwise. */
@Composable
private fun outputTag(bytes: Long?): (@Composable () -> Unit)? {
    if (bytes == null) return null
    return {
        if (bytes <= 0L) {
            InfoTag(text = "Downloaded", style = InfoTagStyle.Success)
        } else {
            InfoTag(text = formatBytesShort(bytes), style = InfoTagStyle.Neutral)
        }
    }
}

private fun inputTitle(engine: VoiceInputEngine): String = when (engine) {
    VoiceInputEngine.AndroidNative -> "Android's own listener"
    else -> "Automatic"
}

private fun inputBody(engine: VoiceInputEngine): String = when (engine) {
    VoiceInputEngine.AndroidNative -> "Your phone's built-in speech recognition."
    else -> "Works offline on this phone."
}

private fun outputTitle(engine: VoiceOutputEngine): String = when (engine) {
    VoiceOutputEngine.AndroidTts -> "Phone's built-in voice"
    VoiceOutputEngine.InflectMicroExperimental -> "Inflect Micro"
    else -> "Cori, natural voice"
}

private fun outputBody(engine: VoiceOutputEngine): String = when (engine) {
    VoiceOutputEngine.AndroidTts -> "No download. Sounds more robotic."
    VoiceOutputEngine.InflectMicroExperimental -> "Higher-quality voice. Works offline."
    else -> "Sounds natural and works offline. Can be slower on older phones."
}

@Composable
private fun DownloadStep(
    plannedModels: List<KernelModel>,
    plannedVoices: List<SherpaPiperVoice>,
    choice: DownloadNetworkChoice,
    choiceRecorded: Boolean,
    isRerun: Boolean,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
) {
    val voiceBytes = plannedVoices.sumOf { it.approxDownloadBytes }
    val totalBytes = DownloadConsent.firstRunEstimateBytes(plannedModels, voiceBytes)
    StepHeading(
        title = "Download Jandal's models",
        subtitle = "A one-time download. Timers, weather and lists work while it runs.",
    )
    SetupSummaryCard(totalBytes = totalBytes, plannedModels = plannedModels, plannedVoices = plannedVoices)
    Text(
        text = when {
            totalBytes <= 0L -> "Already downloaded. Jandal has every model and voice it needs on this phone."
            isRerun || choiceRecorded -> "Your network choice applies to downloads already in progress."
            else -> "Nothing downloads until you tap Finish or Skip on the last step."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    SectionLabel(text = "Which network should downloads use?")
    ChoiceGroup(count = 2) {
        ChoiceCard(
            title = "Wi-Fi only",
            description = "Waits until you're on Wi-Fi.",
            selected = choice == DownloadNetworkChoice.WIFI_ONLY,
            onSelect = { onChoiceSelected(DownloadNetworkChoice.WIFI_ONLY) },
            index = 0,
            tag = { InfoTag(text = "Recommended", style = InfoTagStyle.Success) },
        )
        ChoiceCard(
            title = "Wi-Fi or mobile data",
            description = "Can use your mobile data plan.",
            selected = choice == DownloadNetworkChoice.MOBILE_DATA_ALLOWED,
            onSelect = { onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED) },
            index = 1,
        )
    }
}

/**
 * Summary rows for the step-3 plan: a Total, then one row per planned item with its size. Items already on the
 * phone are left out by the view model, so this only lists what is still to download.
 */
@Composable
private fun SetupSummaryCard(
    totalBytes: Long,
    plannedModels: List<KernelModel>,
    plannedVoices: List<SherpaPiperVoice>,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryRow(
                title = "Total",
                detail = null,
                size = if (totalBytes > 0L) "About ${formatBytesShort(totalBytes)}" else "Already downloaded",
                bold = true,
            )
            val (zipformer, others) = plannedModels.partition { it in VoiceDefaultsProvisioner.zipformerModels() }
            if (zipformer.isNotEmpty()) {
                SummaryRow(
                    title = "Offline speech recognition",
                    detail = "Zipformer",
                    size = formatBytesShort(zipformer.sumOf { it.approxSizeBytes }),
                )
            }
            others.forEach { model ->
                SummaryRow(
                    title = ModelRoleCopy.purpose(model),
                    detail = ModelRoleCopy.technicalLine(model),
                    size = formatBytesShort(model.approxSizeBytes),
                )
            }
            plannedVoices.forEach { voice ->
                SummaryRow(
                    title = "${voice.displayName} natural voice",
                    detail = null,
                    size = formatBytesShort(voice.approxDownloadBytes),
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(title: String, detail: String?, size: String, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
            )
            detail?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        }
        Text(text = size, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Optional Make Jandal yours step (#1855 part 3). Each card shows a live status read from the phone. Nothing here
 * requests a permission or opens a page until its button is tapped.
 */
@Composable
private fun MakeYoursStep(state: SetupWizardUiState, actions: MakeYoursActions) {
    val platform = state.platform
    val assistantStatus = MakeYoursRows.defaultAssistant(platform)
    val notificationStatus = MakeYoursRows.notifications(platform)
    val exactStatus = MakeYoursRows.exactAlarm(platform)

    StepHeading(
        title = "Make Jandal yours",
        subtitle = "All optional. Change any of these later in Settings.",
    )
    HeyJandalCard(state, actions)

    SettingCard(
        icon = Icons.Outlined.AutoAwesome,
        title = "Default assistant",
        description = "Opens Jandal from your phone's assistant button or gesture. Hey Jandal works best with this on.",
        status = assistantStatus.toChipStatus(),
        action = when (assistantStatus) {
            MakeYoursStatus.Off -> CardAction("Open settings", actions.onOpenDefaultAssistant, tonal = true)
            MakeYoursStatus.On, MakeYoursStatus.NotAvailable -> null
        },
        secondaryAction = if (assistantStatus == MakeYoursStatus.On) {
            CardAction("Change", actions.onOpenDefaultAssistant, tonal = false)
        } else {
            null
        },
        id = "default_assistant",
        why = defaultAssistantWhy(assistantStatus),
    )
    SettingCard(
        icon = Icons.Outlined.Notifications,
        title = "Notifications",
        description = "Download progress, alarms and reminders. Downloads keep going either way.",
        status = if (notificationStatus == MakeYoursStatus.On) ChipStatus.On else ChipStatus.Off,
        action = if (notificationStatus == MakeYoursStatus.Off) {
            CardAction(label = "Allow", onClick = actions.onAllowNotifications, tonal = true)
        } else {
            null
        },
        id = "notifications",
        why = notificationsWhy(notificationStatus),
    )
    SettingCard(
        icon = Icons.Outlined.Alarm,
        title = "Alarms and timers",
        description = "So timers ring on time. Without this, Android can delay them.",
        status = if (exactStatus == MakeYoursStatus.On) ChipStatus.On else ChipStatus.Off,
        action = if (exactStatus == MakeYoursStatus.Off) {
            CardAction(label = "Open settings", onClick = actions.onOpenExactAlarm, tonal = true)
        } else {
            null
        },
        id = "exact_alarms",
        why = exactAlarmWhy(exactStatus),
    )
}

private fun MakeYoursStatus.toChipStatus(): ChipStatus = when (this) {
    MakeYoursStatus.On -> ChipStatus.On
    MakeYoursStatus.Off -> ChipStatus.Off
    MakeYoursStatus.NotAvailable -> ChipStatus.NotAvailable
}

/** Why? text for a card: today's inline reason, plus the status note that row showed. */
private fun defaultAssistantWhy(status: MakeYoursStatus): String = listOfNotNull(
    "Makes Jandal the app that opens when you use your phone's assistant button or gesture. " +
        "Hey Jandal listens most reliably with this set. Pick Jandal AI on the system page, then come back. " +
        "This row updates by itself.",
    "This phone has no default assistant setting.".takeIf { status == MakeYoursStatus.NotAvailable },
).joinToString(" ")

private fun notificationsWhy(status: MakeYoursStatus): String = listOfNotNull(
    "Lets Jandal show download progress, alarms and reminders. Downloads keep going either way.",
    "If Android won't ask again, Jandal opens App info so you can turn it on there.".takeIf {
        status == MakeYoursStatus.Off
    },
).joinToString(" ")

private fun exactAlarmWhy(status: MakeYoursStatus): String = listOfNotNull(
    "So timers ring on time. Without this, Android can delay them.",
    "Open Alarms & reminders, find Jandal AI, and turn on Allow. Then come back. This row updates by itself."
        .takeIf { status == MakeYoursStatus.Off },
).joinToString(" ")

/** Button on a setting card. [tonal] renders a tonal button; otherwise a text button in the accent colour. */
private data class CardAction(val label: String, val onClick: () -> Unit, val tonal: Boolean)

/**
 * Hey Jandal card (#1931). The copy, chip and buttons come from [MakeYoursRows.heyJandalCard], which is unit-tested
 * row by row against the state table in docs/specs/setup-visual-pass/README.md.
 */
@Composable
private fun HeyJandalCard(state: SetupWizardUiState, actions: MakeYoursActions) {
    val card = MakeYoursRows.heyJandalCard(
        stored = state.wakeWordStored,
        choice = state.wakeWordChoice,
        platform = state.platform,
        extraBytes = state.heyJandalExtraBytes,
        androidNativeInput = state.inputEngine == VoiceInputEngine.AndroidNative,
    )
    SettingCard(
        icon = Icons.Outlined.Mic,
        title = "Hey Jandal",
        description = card.description,
        status = card.status,
        action = card.primary?.cardAction(actions),
        secondaryAction = card.secondary?.cardAction(actions),
        id = "hey_jandal",
        caveat = card.caveat,
        why = card.why,
    )
}

private fun HeyJandalAction.cardAction(actions: MakeYoursActions): CardAction = when (this) {
    HeyJandalAction.TurnOn -> CardAction("Turn on", { actions.onWakeWordChoiceChanged(true) }, tonal = true)
    HeyJandalAction.TurnOff -> CardAction("Turn off", { actions.onWakeWordChoiceChanged(false) }, tonal = false)
    HeyJandalAction.AllowMicrophone -> CardAction("Allow microphone", actions.onAllowMicrophone, tonal = true)
    HeyJandalAction.OpenUsageAccess -> CardAction("Open Usage Access", actions.onOpenUsageAccess, tonal = true)
}

/**
 * One Make Jandal yours card: icon tile, title, status chip, description, optional caveat, then its actions, and
 * **Why?** when [why] is set. The status chip carries the test tag `make_yours_status_<id>` for tests.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingCard(
    icon: ImageVector,
    title: String,
    description: String,
    status: ChipStatus,
    action: CardAction?,
    id: String,
    caveat: String? = null,
    secondaryAction: CardAction? = null,
    why: String? = null,
) {
    var showWhy by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(icon)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                StatusChip(status = status, modifier = Modifier.testTag("make_yours_status_$id"))
            }
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            caveat?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                action?.let { CardButton(it) }
                secondaryAction?.let { CardButton(it) }
                if (why != null) {
                    TextButton(
                        onClick = { showWhy = true },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Why? $title"
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = JandalTheme.colors.accent),
                    ) { Text("Why?") }
                }
            }
        }
    }
    if (showWhy && why != null) {
        ExplanationSheet(title = title, body = why, onDismiss = { showWhy = false })
    }
}

@Composable
private fun CardButton(action: CardAction) {
    if (action.tonal) {
        FilledTonalButton(
            onClick = action.onClick,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) { Text(action.label) }
    } else {
        TextButton(
            onClick = action.onClick,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = JandalTheme.colors.accent),
        ) { Text(action.label) }
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
