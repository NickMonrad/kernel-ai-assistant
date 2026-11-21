package com.kernel.ai.feature.chat.setup

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RawRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.setup.SetupStep
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
import com.kernel.ai.core.ui.SectionLabel
import com.kernel.ai.core.ui.SwitchSubSetting
import com.kernel.ai.core.ui.findActivity
import com.kernel.ai.core.ui.rememberDownloadNotificationGate
import com.kernel.ai.core.ui.theme.JandalTheme
import com.kernel.ai.core.ui.terms.TermsAcceptRow
import com.kernel.ai.core.ui.terms.TermsOfUseLink
import com.kernel.ai.core.ui.terms.TermsSheet
import com.kernel.ai.core.ui.terms.rememberBundledTerms
import com.kernel.ai.core.voice.HonorCameraCoexistence
import com.kernel.ai.core.voice.OnlineSpeechCopy
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.onlineSpeechToggleVisible
import com.kernel.ai.feature.chat.R
import com.kernel.ai.feature.chat.formatBytesShort

private const val FINISH_ID = "finish"
private const val SKIP_ID = "skip"

/** Test tag on the scrolling container of the step content (#1903 large-font test). In compact mode it wraps the whole page. */
internal const val SETUP_WIZARD_CONTENT_TAG = "setup_wizard_content"

/** Test tag on the pinned footer (its top edge is where the step content must end). */
internal const val SETUP_FOOTER_TAG = "setup_wizard_footer"

/** Test tag on the fade and "More below" hint drawn while more step content lies below. */
internal const val SETUP_MORE_BELOW_TAG = "setup_wizard_more_below"

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
    val termsText = rememberBundledTerms()
    if (!state.loaded) return
    SetupWizardContent(
        state = state,
        onInputSelected = viewModel::onInputSelected,
        onOutputSelected = viewModel::onOutputSelected,
        onChoiceSelected = viewModel::onChoiceSelected,
        onOnlineSpeechChanged = viewModel::onOnlineSpeechChanged,
        termsText = termsText,
        onTermsAcceptedChanged = viewModel::onTermsAcceptedChanged,
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
 * Stateless wizard layout (#1903). Portrait: the step header, then step content scrolling above a pinned footer,
 * with a fade and "More below" hint while content lies below. Short windows (landscape phones, large font plus
 * display size): the whole page scrolls so the buttons keep a 48 dp minimum instead of being squeezed. Each step
 * opens scrolled to the top (#1929).
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
    termsText: String? = null,
    onTermsAcceptedChanged: (Boolean) -> Unit = {},
) {
    val stepNumber = state.step.ordinal + 1
    val primaryLabel = if (state.step == SetupStep.entries.last()) "Finish" else "Continue"
    val skipLabel = if (state.step == SetupStep.MAKE_JANDAL_YOURS) "Skip these" else "Skip setup"
    var termsOpen by rememberSaveable { mutableStateOf(false) }
    val openTerms: () -> Unit = { termsOpen = true }
    val scrollState = rememberSaveable(state.step, saver = ScrollState.Saver) { ScrollState(0) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        if (maxHeight < COMPACT_HEIGHT) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag(SETUP_WIZARD_CONTENT_TAG),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SetupStepHeader(step = stepNumber)
                StepContent(
                    state, onInputSelected, onOutputSelected, onChoiceSelected, onOnlineSpeechChanged, makeYours,
                    onOpenTerms = openTerms, onTermsAcceptedChanged = onTermsAcceptedChanged,
                )
                SetupFooterButtons(primaryLabel, skipLabel, onPrimary, onBack, onSkip, actionsEnabled = state.termsAccepted)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                SetupStepHeader(
                    step = stepNumber,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 12.dp),
                )
                // Marks the edge content scrolls under, so a line cut at the top reads as scrolled, not clipped.
                // Always laid out (transparent at the top) so the content does not jump when it appears.
                HorizontalDivider(
                    color = if (scrollState.canScrollBackward) {
                        MaterialTheme.colorScheme.outlineVariant
                    } else {
                        Color.Transparent
                    },
                )
                FadingScrollArea(scrollState = scrollState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    StepContent(
                        state, onInputSelected, onOutputSelected, onChoiceSelected, onOnlineSpeechChanged, makeYours,
                        onOpenTerms = openTerms, onTermsAcceptedChanged = onTermsAcceptedChanged,
                    )
                }
                SetupFooter(
                    primaryLabel = primaryLabel,
                    skipLabel = skipLabel,
                    onPrimary = onPrimary,
                    onBack = onBack,
                    onSkip = onSkip,
                    actionsEnabled = state.termsAccepted,
                )
            }
        }
    }
    if (termsOpen) {
        TermsSheet(termsText = termsText, onDismiss = { termsOpen = false })
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
    onOpenTerms: () -> Unit,
    onTermsAcceptedChanged: (Boolean) -> Unit,
) {
    when (state.step) {
        SetupStep.HOW_IT_WORKS -> WelcomeStep(
            totalBytes = state.welcomeTotalBytes,
            termsAccepted = state.termsAccepted,
            onTermsAcceptedChanged = onTermsAcceptedChanged,
            onOpenTerms = onOpenTerms,
        )
        SetupStep.VOICE -> VoiceStep(
            state = state,
            onInputSelected = onInputSelected,
            onOutputSelected = onOutputSelected,
            onOnlineSpeechChanged = onOnlineSpeechChanged,
        )
        SetupStep.DOWNLOAD -> DownloadStep(state = state, onChoiceSelected = onChoiceSelected, onOpenTerms = onOpenTerms)
        SetupStep.MAKE_JANDAL_YOURS -> MakeYoursStep(state, makeYours)
    }
}

private fun skipNote(choice: DownloadNetworkChoice): String = when (choice) {
    DownloadNetworkChoice.WIFI_ONLY -> "Skipped. Models will download on Wi-Fi only. Change this in Model Management."
    DownloadNetworkChoice.MOBILE_DATA_ALLOWED -> "Skipped. Models will download on mobile data too. Change this in Model Management."
}

/** Shared step heading: a semantic title and a secondary subtitle. */
@Composable
private fun StepHeading(title: String, subtitle: String?) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    subtitle?.let {
        Text(text = it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A rounded icon tile: [container] behind a 24 dp icon tinted [content]. */
@Composable
private fun IconTile(icon: ImageVector, size: Dp, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = content)
    }
}

@Composable
private fun WelcomeRow(icon: ImageVector, title: String, body: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = if (body == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        IconTile(
            icon = icon,
            size = 44.dp,
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            content = MaterialTheme.colorScheme.onSurface,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            body?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Icon of the third welcome row: a download while anything is left, a check once chat is ready. */
internal enum class WelcomeDownloadIcon { Download, Ready }

/** Title, optional body and icon of the third welcome row. */
internal data class WelcomeDownloadCopy(val title: String, val body: String?, val icon: WelcomeDownloadIcon)

/**
 * The third welcome row for [totalBytes] still to download: the spec's "Chat needs a one-time download" with the
 * size while anything is left, otherwise just the title "Chat is ready" with no body and a check icon (owner
 * decision on #1957).
 */
internal fun welcomeDownloadCopy(totalBytes: Long): WelcomeDownloadCopy =
    if (totalBytes > 0L) {
        WelcomeDownloadCopy(
            title = "Chat needs a one-time download",
            body = "About ${formatBytesShort(totalBytes)}. You choose Wi-Fi or mobile data in step 3.",
            icon = WelcomeDownloadIcon.Download,
        )
    } else {
        WelcomeDownloadCopy(title = "Chat is ready", body = null, icon = WelcomeDownloadIcon.Ready)
    }

/**
 * Step 1. [totalBytes] is what is still to download: the default plan for this phone on a first run, or what is
 * left on a re-run from Settings. Nothing left shows "Chat is ready" ([welcomeDownloadCopy]).
 */
@Composable
private fun WelcomeStep(
    totalBytes: Long,
    termsAccepted: Boolean,
    onTermsAcceptedChanged: (Boolean) -> Unit,
    onOpenTerms: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_jandal_mark),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
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
    val download = welcomeDownloadCopy(totalBytes)
    WelcomeRow(
        icon = when (download.icon) {
            WelcomeDownloadIcon.Download -> Icons.Outlined.Download
            WelcomeDownloadIcon.Ready -> Icons.Outlined.CheckCircle
        },
        title = download.title,
        body = download.body,
    )
    TermsAcceptRow(
        accepted = termsAccepted,
        onAcceptedChange = onTermsAcceptedChanged,
        onOpenTerms = onOpenTerms,
        modifier = Modifier.padding(top = 8.dp),
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
    val context = LocalContext.current
    val player = remember { SampleClipPlayer(context) }
    // Leaving the step (Continue, Back, Skip) stops any sample that is still playing.
    DisposableEffect(player) { onDispose { player.release() } }

    StepHeading(
        title = "How should Jandal hear and talk to you?",
        subtitle = "Nothing downloads until the last step.",
    )
    SectionLabel(text = "Listening", icon = Icons.Outlined.Mic)
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
            ChoiceCard(
                title = inputTitle(engine),
                description = inputBody(engine),
                selected = inputEngine == engine,
                onSelect = { onInputSelected(engine) },
                index = index,
                tag = if (engine == VoiceInputEngine.Auto) {
                    { InfoTag(text = "Recommended", style = InfoTagStyle.Success) }
                } else {
                    null
                },
                subContent = if (inputEngine != null && onlineSpeechToggleVisible(inputEngine, engine)) {
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

    SectionLabel(text = "Speaking", icon = Icons.AutoMirrored.Outlined.VolumeUp)
    ChoiceGroup(count = state.outputOptions.size) {
        state.outputOptions.forEachIndexed { index, engine ->
            val clip = sampleClipFor(engine)
            ChoiceCard(
                title = outputTitle(engine),
                description = outputBody(engine),
                selected = outputEngine == engine,
                onSelect = { onOutputSelected(engine) },
                index = index,
                tag = outputTag(state.outputSizes[engine]),
                subContent = clip?.let { res ->
                    { SampleButton(playing = player.playing == res, onClick = { player.toggle(res) }) }
                },
            )
        }
    }
    if (outputEngine == null) {
        Text(
            text = "Your current speech output stays as it is.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Bundled sample of a downloadable voice, or null when the voice has none (its button is then hidden). */
@RawRes
private fun sampleClipFor(engine: VoiceOutputEngine): Int? = when (engine) {
    VoiceOutputEngine.SherpaExperimental -> R.raw.sample_cori
    VoiceOutputEngine.InflectMicroExperimental -> R.raw.sample_inflect
    else -> null
}

/** "Hear a sample", or "Stop sample" while this voice's clip plays. A second tap stops it. */
@Composable
private fun SampleButton(playing: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { stateDescription = if (playing) "Playing" else "" },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = JandalTheme.colors.accent),
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(text = if (playing) "Stop sample" else "Hear a sample", modifier = Modifier.padding(start = 8.dp))
    }
}

/** Online speech switch inside the selected listening card (#1936). Saved on Finish or Skip. */
@Composable
private fun OnlineSpeechRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    var showExplanation by rememberSaveable { mutableStateOf(false) }
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
    state: SetupWizardUiState,
    onChoiceSelected: (DownloadNetworkChoice) -> Unit,
    onOpenTerms: () -> Unit,
) {
    val rows = downloadRows(state.plannedModels, state.plannedVoices)
    val totalBytes = rows.sumOf { it.bytes }
    StepHeading(
        title = "Download Jandal's models",
        subtitle = "A one-time download. Timers, weather and lists work while it runs.",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A FlowRow, so at large font the value moves under "Total" instead of squeezing it.
            FlowRow(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
                Text(
                    text = if (totalBytes > 0L) formatBytesShort(totalBytes) else "Already downloaded",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DownloadRow(row, onOpenTerms)
            }
            if (state.isRerun) {
                Text(
                    text = "Your network choice applies to downloads already in progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        text = "Which network should downloads use?",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
    ChoiceGroup(count = 2) {
        ChoiceCard(
            title = "Wi-Fi only",
            description = "Waits until you're on Wi-Fi.",
            selected = state.choice == DownloadNetworkChoice.WIFI_ONLY,
            onSelect = { onChoiceSelected(DownloadNetworkChoice.WIFI_ONLY) },
            index = 0,
            leadingIcon = Icons.Outlined.Wifi,
            tag = { InfoTag(text = "Recommended", style = InfoTagStyle.Success) },
        )
        ChoiceCard(
            title = "Wi-Fi or mobile data",
            description = "Can use your mobile data plan.",
            selected = state.choice == DownloadNetworkChoice.MOBILE_DATA_ALLOWED,
            onSelect = { onChoiceSelected(DownloadNetworkChoice.MOBILE_DATA_ALLOWED) },
            index = 1,
            leadingIcon = Icons.Outlined.SignalCellularAlt,
        )
    }
}

/** One planned item: icon tile, plain name over the technical name, and its size. The Terms link is its own control. */
@Composable
private fun DownloadRow(row: DownloadRowItem, onOpenTerms: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            icon = row.kind.icon(),
            size = 40.dp,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = row.plainName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = row.technicalName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = formatBytesShort(row.bytes), style = MaterialTheme.typography.bodyMedium)
            }
            TermsOfUseLink(onClick = onOpenTerms)
        }
    }
}

private fun DownloadRowKind.icon(): ImageVector = when (this) {
    DownloadRowKind.Chat -> Icons.Outlined.ChatBubbleOutline
    DownloadRowKind.Memory -> Icons.Outlined.Memory
    DownloadRowKind.SpeechInput -> Icons.Outlined.Mic
    DownloadRowKind.Voice -> Icons.AutoMirrored.Outlined.VolumeUp
    DownloadRowKind.Other -> Icons.Outlined.Download
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
        id = "default_assistant",
        icon = Icons.Outlined.AutoAwesome,
        title = "Default assistant",
        status = assistantStatus.toChipStatus(),
        description = "Opens Jandal from your phone's assistant button or gesture. Hey Jandal works best with this on.",
        primary = CardAction("Open settings", actions.onOpenDefaultAssistant)
            .takeIf { assistantStatus == MakeYoursStatus.Off },
        secondary = CardAction("Change", actions.onOpenDefaultAssistant)
            .takeIf { assistantStatus == MakeYoursStatus.On },
        why = defaultAssistantWhy(assistantStatus),
    )
    SettingCard(
        id = "notifications",
        icon = Icons.Outlined.Notifications,
        title = "Notifications",
        status = notificationStatus.toChipStatus(),
        description = "Download progress, alarms and reminders. Downloads keep going either way.",
        primary = CardAction("Allow", actions.onAllowNotifications)
            .takeIf { notificationStatus == MakeYoursStatus.Off },
        why = notificationsWhy(notificationStatus),
    )
    SettingCard(
        id = "exact_alarms",
        icon = Icons.Outlined.Alarm,
        title = "Alarms and timers",
        status = exactStatus.toChipStatus(),
        description = "So timers ring on time. Without this, Android can delay them.",
        primary = CardAction("Open settings", actions.onOpenExactAlarm)
            .takeIf { exactStatus == MakeYoursStatus.Off },
        why = exactAlarmWhy(exactStatus),
    )
    Spacer(Modifier.height(8.dp))
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
        androidNativeZipformerBytes = state.heyJandalZipformerBytes,
    )
    SettingCard(
        id = "hey_jandal",
        icon = Icons.Outlined.Mic,
        title = "Hey Jandal",
        status = card.status,
        description = card.description,
        caveat = card.caveat,
        primary = card.primary?.cardAction(actions),
        secondary = card.secondary?.cardAction(actions),
        why = card.why,
    )
}

private fun HeyJandalAction.cardAction(actions: MakeYoursActions): CardAction = when (this) {
    HeyJandalAction.TurnOn -> CardAction("Turn on") { actions.onWakeWordChoiceChanged(true) }
    HeyJandalAction.TurnOff -> CardAction("Turn off") { actions.onWakeWordChoiceChanged(false) }
    HeyJandalAction.AllowMicrophone -> CardAction("Allow microphone", actions.onAllowMicrophone)
    HeyJandalAction.OpenUsageAccess -> CardAction("Open Usage Access", actions.onOpenUsageAccess)
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
