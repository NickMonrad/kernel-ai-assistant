package com.kernel.ai.feature.widget

import android.Manifest
import android.content.Intent
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import android.view.HapticFeedbackConstants
import com.kernel.ai.core.voice.MicHintPolicy
import com.kernel.ai.core.voice.VoiceInputPreferences
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.kernel.ai.core.voice.WakeWordHandoff
import com.kernel.ai.core.permissions.recordPermissionResult
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import androidx.core.app.ActivityCompat
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.ui.voice.VoiceErrorAction
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.memory.prefs.USE_SYSTEM_COLORS_DEFAULT
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import com.kernel.ai.core.voice.stopListening
import com.kernel.ai.core.voice.cancelListening
import com.kernel.ai.core.voice.startListening
import com.kernel.ai.core.voice.latestNoticeFor
import com.kernel.ai.core.voice.noticesFor
import com.kernel.ai.core.voice.VoiceRecovery
import com.kernel.ai.core.voice.VoiceRefusal
import com.kernel.ai.core.voice.recoveryFor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.kernel.ai.core.voice.WakeOverlayChannel
import com.kernel.ai.core.voice.WakeOverlayPhase
import com.kernel.ai.core.voice.WakeOverlayState

private const val TAG = "KernelAI"

/**
 * Intent extra key used by [WakeWordService] to pass a pre-recognised transcript.
 *
 * **Security:** this activity is exported=true (assistant eligibility requirement).
 * External callers cannot inject transcripts via this extra because [WakeWordService]
 * also sets [WakeWordHandoff.pendingTranscript] in process memory immediately
 * before launching this activity. [VoiceCommandActivity] only acts on the extra when
 * the in-process token matches and then clears it — external apps cannot write the token.
 */
const val EXTRA_PREFILLED_TRANSCRIPT = "prefilled_transcript"

/** Intent extra set by [WakeWordService] when it opens the overlay for a live Hey Jandal capture (#1930). */
const val EXTRA_WAKE_OVERLAY = "wake_overlay"

/** #1930: token of the live wake capture that opened the overlay; see [WakeOverlayChannel.begin]. */
const val EXTRA_WAKE_TOKEN = "wake_token"

/** #1930: delay before a routed wake overlay closes when its prefilled transcript never arrives. */
internal const val WAKE_ROUTED_FINISH_TIMEOUT_MS = 2_000L
/**
 * Validates a prefilled transcript extra against the in-process [WakeWordHandoff] token.
 *
 * Returns `true` if the extra matches the token (trusted), `false` otherwise.
 * Does NOT modify [WakeWordHandoff.pendingTranscript] — callers decide whether to clear it.
 *
 * This is extracted as a top-level function so it can be unit-tested without Android dependencies.
 */
fun validatePrefilledTranscriptToken(extra: String?): Boolean {
    if (extra == null) return false
    val token = WakeWordHandoff.pendingTranscript
    return token == extra
}

@AndroidEntryPoint
class VoiceCommandActivity : ComponentActivity() {

    @Inject lateinit var voiceInputController: VoiceInputController
    @Inject lateinit var navigator: WidgetNavigator
    @Inject lateinit var voiceInputPreferences: VoiceInputPreferences
    /** Volume key stops Jandal's speech while the voice overlay is open (#1873). */
    @Inject lateinit var speechStopCoordinator: com.kernel.ai.core.voice.SpeechStopCoordinator
    @Inject lateinit var chatPreferences: ChatPreferences

    private val speechStopKeys by lazy {
        com.kernel.ai.core.voice.SpeechStopKeyTracker(speechStopCoordinator::requestStop)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (speechStopKeys.onKeyDown(keyCode, event.repeatCount)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (speechStopKeys.onKeyUp(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** Cancellable job for the 400 ms prefilled-transcript navigation delay. */
    private var prefilledNavJob: Job? = null
    private var toneGenerator: ToneGenerator? = null
    /** Observer of [WakeOverlayChannel] while a Hey Jandal overlay is shown (#1930). */
    private var wakeOverlayJob: Job? = null
    /** True when launched as a Hey Jandal wake overlay (#1930); decides cancel-on-destroy. */
    private var wakeIntentSeen = false
    private var cancelledVoiceStart = false
    /** Token of the wake capture this overlay belongs to (#1930). */
    private var wakeToken = 0L
    private var voiceStartAttemptId = 0L
    private var ownedCaptureSessionId: Long? = null
    /** Back closes a wake overlay as X does; enabled only while a wake overlay is shown. */
    private val wakeBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = dismissWakeOverlay()
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        recordPermissionResult(
            Manifest.permission.RECORD_AUDIO,
            granted,
            shouldShowRationale = !granted && ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.RECORD_AUDIO,
            ),
        )
        JandalEvents.record("permission", "record_audio", "granted" to granted)
        if (granted) startVoiceSession() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, wakeBackCallback)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (handlePrefilledTranscript(intent)) return
        if (intent.getBooleanExtra(EXTRA_WAKE_OVERLAY, false)) {
            // #1930: a wake intent whose capture already ended must not fall through to a new session.
            if (!handleWakeOverlayIntent(intent)) finish()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // Permission missing — request it and return. The activity must not fall through
            // to startVoiceSession() until the permission result callback returns granted;
            // startVoiceSession() is called from the callback on grant, or finish() on deny.
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        startVoiceSession()
    }

    /**
     * `launchMode="singleTask"`: a second wake-word trigger while this activity is
     * already running is delivered here, not to a fresh [onCreate]. Handle it identically.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handlePrefilledTranscript(intent)) handleWakeOverlayIntent(intent)
        // Non-live wake intents and OS assistant re-triggers (no prefilled transcript) are ignored;
        // the existing voice session or overlay is still valid.
    }

    /**
     * Validates and consumes a prefilled-transcript from [WakeWordService].
     *
     * Returns `true` if the transcript was accepted (caller should skip normal STT setup).
     * Returns `false` if the extra is absent or the in-process token does not match
     * (external caller or stale delivery — fall through to normal voice session).
     */
    internal fun handlePrefilledTranscript(intent: Intent): Boolean {
        val extra = intent.getStringExtra(EXTRA_PREFILLED_TRANSCRIPT) ?: return false
        // Validate against the in-process token set by WakeWordService immediately before
        // startActivity. External apps cannot write this JVM field.
        val token = WakeWordHandoff.pendingTranscript
        if (token != extra) {
            Log.w(TAG, "VoiceCommandActivity: rejected prefilled transcript — token mismatch (external caller?)")
            // Do NOT clear pendingTranscript — the live token may belong to a legitimate
            // trigger whose intent arrives next; clearing it here would invalidate it.
            return false
        }
        // Token matched: cancel any in-flight navigation before routing the newer transcript.
        // Only cancel after validation — an unrecognised re-entry must not cancel a live overlay.
        prefilledNavJob?.cancel()
        prefilledNavJob = null
        WakeWordHandoff.pendingTranscript = null
        wakeOverlayJob?.cancel()
        wakeOverlayJob = null
        routePrefilledTranscript(extra)
        return true
    }

    /**
     * #1930: a Hey Jandal wake opens this overlay while [WakeWordService] captures speech. Only a
     * live capture (matching token, [WakeOverlayState.Active]) may open it, so an external caller
     * cannot show a listening overlay with no capture behind it. Returns false and does nothing
     * otherwise, leaving the intent to the normal flow.
     */
    internal fun handleWakeOverlayIntent(intent: Intent): Boolean {
        if (!intent.getBooleanExtra(EXTRA_WAKE_OVERLAY, false)) return false
        val token = intent.getLongExtra(EXTRA_WAKE_TOKEN, 0L)
        if (!WakeOverlayChannel.isLive(token)) return false
        wakeIntentSeen = true
        wakeToken = token
        // #1930: a wake-launched overlay turns the screen on so it is visible with the screen off.
        setTurnScreenOn(true)
        startWakeOverlay()
        return true
    }

    /**
     * Renders [WakeOverlayChannel] state: Listening, live partials, then Processing. The capture is
     * owned by [WakeWordService], so its timing and mic-ready cue are unchanged and this activity
     * never calls startListening. Closes when the capture ends without a routed transcript.
     */
    private fun startWakeOverlay() {
        wakeOverlayJob?.cancel()
        wakeBackCallback.isEnabled = true
        var phase by mutableStateOf(VoiceCommandPhase.Listening)
        var partialText by mutableStateOf("")
        var dismissEnabled by mutableStateOf(false)
        setContent {
            val useSystemColors = chatPreferences.useSystemColors
                .collectAsState(initial = USE_SYSTEM_COLORS_DEFAULT).value
            KernelAITheme(dynamicColor = useSystemColors) {
                LaunchedEffect(Unit) {
                    delay(800)
                    dismissEnabled = true
                }
                VoiceCommandOverlay(
                    phase = phase,
                    partialText = partialText,
                    failed = false,
                    hintVisible = false,
                    inputLevel = voiceInputController.inputLevel.collectAsState(),
                    dismissEnabled = dismissEnabled,
                    onDismiss = { dismissWakeOverlay() },
                )
            }
        }
        wakeOverlayJob = lifecycleScope.launch {
            WakeOverlayChannel.state.collect { state ->
                when (state) {
                    is WakeOverlayState.Active -> {
                        phase = when (state.phase) {
                            WakeOverlayPhase.Listening -> VoiceCommandPhase.Listening
                            WakeOverlayPhase.Processing -> VoiceCommandPhase.Processing
                        }
                        partialText = state.partialText
                    }
                    is WakeOverlayState.Ended -> when {
                        isFinishing -> Unit
                        !state.routed -> finish()
                        // Routed: the prefilled transcript cancels this observer. If it never
                        // arrives, close rather than leave the overlay hanging.
                        else -> {
                            delay(WAKE_ROUTED_FINISH_TIMEOUT_MS)
                            finish()
                        }
                    }
                    WakeOverlayState.Idle -> Unit
                }
            }
        }
    }

    /** #1930: Home or an app switch closes the wake overlay and cancels its live capture. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (wakeOverlayJob?.isActive == true) dismissWakeOverlay()
    }

    /**
     * #1930: X, backdrop, Back and Home cancel the live capture before finishing, so a transcript
     * that arrives before onDestroy runs cannot be routed. onDestroy stays as a backstop.
     */
    private fun dismissWakeOverlay() {
        if (wakeIntentSeen) WakeOverlayChannel.requestCancel(wakeToken)
        finish()
    }

    /**
     * Wake word path: show the recognised transcript in the overlay card for 400 ms
     * so the user can see what was heard, then call [WidgetNavigator.navigateToActions]
     * to open the ActionsScreen result card with voice TTS reply.
     */
    internal fun routePrefilledTranscript(transcript: String) {
        setContent {
            val useSystemColors = chatPreferences.useSystemColors
                .collectAsState(initial = USE_SYSTEM_COLORS_DEFAULT).value
            KernelAITheme(dynamicColor = useSystemColors) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                ) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        tonalElevation = 8.dp,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = transcript,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        prefilledNavJob = lifecycleScope.launch {
            try {
                delay(400) // brief flash so user sees what was heard
                if (!isFinishing) {
                    navigator.navigateToActions(this@VoiceCommandActivity, transcript, isVoice = true)
                }
                finish()
            } catch (e: CancellationException) {
                // Superseded by a newer trigger — do NOT finish the activity here.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "VoiceCommandActivity: failed to route prefilled transcript", e)
                finish()
            }
        }

    }

    /** Start cue, played when the recognizer reports Listening (#1850), not when the overlay opens. */
    private fun playStartCue() {
        try {
            toneGenerator?.release()
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80).also {
                it.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            }
        } catch (e: Exception) {
            Log.w(TAG, "VoiceCommandActivity: could not play boop", e)
        }
    }

    private fun startVoiceSession() {
        cancelledVoiceStart = false
        var dismissEnabled by mutableStateOf(false)
        var partialText by mutableStateOf("")
        var phase by mutableStateOf(VoiceCommandPhase.Starting)
        var hintVisible by mutableStateOf(false)
        var failed by mutableStateOf(false)
        var refusal by mutableStateOf<VoiceRefusal?>(null)

        setContent {
            val useSystemColors = chatPreferences.useSystemColors
                .collectAsState(initial = USE_SYSTEM_COLORS_DEFAULT).value
            KernelAITheme(dynamicColor = useSystemColors) {
                // Unlock backdrop tap-to-dismiss after 800ms. Prevents accidental
                // dismiss when the overlay appears mid-screen-wake (Side key path)
                // before the user is ready. The card's X button is always active.
                LaunchedEffect(Unit) {
                    delay(800)
                    dismissEnabled = true
                }
                val inputLevel = voiceInputController.inputLevel.collectAsState()

                VoiceCommandOverlay(
                    phase = phase,
                    partialText = partialText,
                    failed = failed,
                    hintVisible = hintVisible,
                    inputLevel = inputLevel,
                    dismissEnabled = dismissEnabled,
                    onDismiss = { finish() },
                    voiceRecovery = recoveryFor(partialText, refusal),
                    onOpenVoiceSettings = { openVoiceSettingsTerms() },
                )
            }
        }

        lifecycleScope.launch {
            // #1850: shared first-use counter with chat; the hint stops after MicHintPolicy.MAX_HINT_USES taps.
            hintVisible = MicHintPolicy.shouldShowHint(voiceInputPreferences.recordMicUseStarted())
        }
        var retryAttempted = false
        var attemptId = ++voiceStartAttemptId
        ownedCaptureSessionId = null
        var noticeJob: Job? = null
        lifecycleScope.launch {
            suspend fun showUnavailableAndFinish(message: String, recovery: VoiceRecovery? = null) {
                val errorText = message.ifBlank { "Voice commands are unavailable right now." }
                Log.w(TAG, "VoiceCommandActivity: voice unavailable — $errorText")
                failed = true
                partialText = errorText
                // A refusal with a fix stays up so the user can open Settings › Voice (#2049).
                if (recovery != null) {
                    refusal = VoiceRefusal(errorText, recovery)
                    return
                }
                delay(2_000)
                if (!isFinishing) finish()
            }

            // #1853/#1866: notes for this capture (a native hand-off to asr4all) are shown as a toast, so
            // they stay visible if the overlay navigates or finishes.
            fun bindCapture(sessionId: Long, ownerAttempt: Long) {
                if (ownerAttempt != voiceStartAttemptId) return
                noticeJob?.cancel()
                ownedCaptureSessionId = sessionId
                noticeJob = launch {
                    voiceInputController.noticesFor(sessionId).collect { note ->
                        if (ownerAttempt == voiceStartAttemptId && ownedCaptureSessionId == sessionId) {
                            Toast.makeText(this@VoiceCommandActivity, note, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            fun mayRetry(): Boolean =
                !retryAttempted && ownedCaptureSessionId?.let { voiceInputController.latestNoticeFor(it) } == null

            val eventCollectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
                voiceInputController.events.collect { event ->
                    if (ownedCaptureSessionId == null || event.captureSessionId != ownedCaptureSessionId) return@collect
                    when (event) {
                        is VoiceInputEvent.PartialTranscript -> {
                            partialText = event.text
                        }
                        is VoiceInputEvent.Transcript -> {
                            val transcript = event.text
                            Log.d(TAG, "VoiceCommandActivity: final transcript=\"$transcript\"")
                            if (transcript.isNotBlank() && !isFinishing) {
                                navigator.navigateToActions(this@VoiceCommandActivity, transcript, isVoice = true)
                                finish()
                            } else if (mayRetry()) {
                                retryAttempted = true
                                partialText = ""
                                ownedCaptureSessionId = null
                                attemptId = ++voiceStartAttemptId
                                phase = VoiceCommandPhase.Starting
                                Log.d(TAG, "VoiceCommandActivity: blank transcript — retrying")
                                val retryAttempt = attemptId
                                when (val result = voiceInputController.startListening(VoiceCaptureMode.AlertCommand) {
                                    bindCapture(it, retryAttempt)
                                }) {
                                    is VoiceInputStartResult.Started -> Unit
                                    is VoiceInputStartResult.Unavailable -> if (retryAttempt == voiceStartAttemptId) {
                                        showUnavailableAndFinish(result.message, result.recovery)
                                    }
                                    is VoiceInputStartResult.Cancelled -> if (retryAttempt == voiceStartAttemptId) {
                                        cancelledVoiceStart = true
                                        finish()
                                    }
                                }
                            } else {
                                finish()
                            }
                        }
                        is VoiceInputEvent.ListeningStarted -> {
                            phase = phaseAfter(phase, event)
                            // #1850: haptic once per recognizer Listening event, not on recomposition.
                            playStartCue()
                            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        }
                        is VoiceInputEvent.SpeechEnded -> phase = phaseAfter(phase, event)
                        is VoiceInputEvent.ListeningStopped -> {
                            noticeJob?.cancel()
                            ownedCaptureSessionId = null
                            cancelledVoiceStart = event.cancelled
                            finish()
                        }
                        is VoiceInputEvent.Error -> {
                            phase = phaseAfter(phase, event)
                            Log.w(TAG, "VoiceCommandActivity: voice error — ${event.message}")
                            if (restartsAfterError(event, mayRetry())) {
                                retryAttempted = true
                                partialText = ""
                                ownedCaptureSessionId = null
                                attemptId = ++voiceStartAttemptId
                                Log.d(TAG, "VoiceCommandActivity: retrying after error")
                                val retryAttempt = attemptId
                                when (val result = voiceInputController.startListening(VoiceCaptureMode.AlertCommand) {
                                    bindCapture(it, retryAttempt)
                                }) {
                                    is VoiceInputStartResult.Started -> Unit
                                    is VoiceInputStartResult.Unavailable -> if (retryAttempt == voiceStartAttemptId) {
                                        showUnavailableAndFinish(result.message, result.recovery)
                                    }
                                    is VoiceInputStartResult.Cancelled -> if (retryAttempt == voiceStartAttemptId) {
                                        cancelledVoiceStart = true
                                        finish()
                                    }
                                }
                            } else {
                                showUnavailableAndFinish(event.message)
                            }
                        }
                        else -> Unit
                    }
                }
            }

            val initialAttempt = attemptId
            when (
                val startResult = voiceInputController.startListening(VoiceCaptureMode.AlertCommand) { sessionId ->
                    bindCapture(sessionId, initialAttempt)
                }
            ) {
                is VoiceInputStartResult.Started -> Unit
                is VoiceInputStartResult.Unavailable -> {
                    if (initialAttempt == voiceStartAttemptId) {
                        eventCollectorJob.cancel()
                        showUnavailableAndFinish(startResult.message, startResult.recovery)
                    }
                }
                is VoiceInputStartResult.Cancelled -> if (initialAttempt == voiceStartAttemptId) {
                    cancelledVoiceStart = true
                    eventCollectorJob.cancel()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        val ownerSessionId = ownedCaptureSessionId
        ownedCaptureSessionId = null
        voiceStartAttemptId++
        super.onDestroy()
        if (wakeIntentSeen) {
            // #1930: backstop; the dismiss paths already requested cancel. A configuration change
            // keeps the capture running.
            if (!isChangingConfigurations) WakeOverlayChannel.requestCancel(wakeToken)
        } else if (!cancelledVoiceStart) {
            ownerSessionId?.let(voiceInputController::cancelListening)
        }
        toneGenerator?.release()
        toneGenerator = null
    }
}

/** Route the refusal's action opens. Must equal KernelNavHost's ROUTE_VOICE_TERMS (pinned by ExternalNavigationRouteTest). */
const val VOICE_SETTINGS_TERMS_ROUTE = "settings/voice?scrollTo=true"

/** Opens MainActivity at Settings › Voice Terms and closes the overlay (#2049). */
private fun Activity.openVoiceSettingsTerms() {
    startActivity(Intent().apply {
        component = ComponentName(packageName, "com.kernel.ai.MainActivity")
        putExtra("navigation_route", VOICE_SETTINGS_TERMS_ROUTE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    })
    finish()
}
/**
 * Live voice overlay card shared by the widget mic, the assistant session and the Hey Jandal wake
 * overlay (#1930). The backdrop dismisses once [dismissEnabled]; the card's X always dismisses.
 */
@Composable
private fun VoiceCommandOverlay(
    phase: VoiceCommandPhase,
    partialText: String,
    failed: Boolean,
    hintVisible: Boolean,
    inputLevel: State<Float>,
    dismissEnabled: Boolean,
    onDismiss: () -> Unit,
    voiceRecovery: VoiceRecovery? = null,
    onOpenVoiceSettings: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = dismissEnabled) { onDismiss() },
        )

        Surface(
            onClick = {},  // consume taps so backdrop doesn't fire
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
                    val pulse by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.25f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "mic_scale",
                    )
                    val ringColor = MaterialTheme.colorScheme.primary
                    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        // #1850: accent ring driven by the live capture level; drawn only while Listening.
                        if (phase == VoiceCommandPhase.Listening) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                drawCircle(
                                    color = ringColor.copy(alpha = 0.35f),
                                    radius = size.minDimension / 2f * (0.6f + 0.4f * inputLevel.value),
                                )
                            }
                        }
                        if (phase == VoiceCommandPhase.Starting && !failed) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = ringColor,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Listening",
                                modifier = Modifier
                                    .size(28.dp)
                                    .scale(if (phase == VoiceCommandPhase.Listening) pulse else 1f),
                                tint = ringColor,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    val (mainText, caption) = voiceOverlayLabels(phase, partialText, failed)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mainText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        caption?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (voiceRecovery != null) {
                    VoiceErrorAction(onClick = onOpenVoiceSettings)
                }
                if (hintVisible && phase == VoiceCommandPhase.Listening) {
                    Text(
                        text = "Your words are being captured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
