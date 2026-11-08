package com.kernel.ai.feature.widget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.kernel.ai.core.voice.WakeWordHandoff
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import com.kernel.ai.core.voice.VoiceInputStartResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    /** Cancellable job for the 400 ms prefilled-transcript navigation delay. */
    private var prefilledNavJob: Job? = null
    private var toneGenerator: ToneGenerator? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceSession() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (handlePrefilledTranscript(intent)) return

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
        handlePrefilledTranscript(intent)
        // If there was no prefilled transcript this is an OS assistant re-trigger; ignore —
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
        routePrefilledTranscript(extra)
        return true
    }

    /**
     * Wake word path: show the recognised transcript in the overlay card for 400 ms
     * so the user can see what was heard, then call [WidgetNavigator.navigateToActions]
     * to open the ActionsScreen result card with voice TTS reply.
     */
    internal fun routePrefilledTranscript(transcript: String) {
        setContent {
            KernelAITheme {
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

    private fun startVoiceSession() {
        // Brief boop to indicate listening started
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80).also {
                it.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            }
        } catch (e: Exception) {
            Log.w(TAG, "VoiceCommandActivity: could not play boop", e)
        }

        var dismissEnabled by mutableStateOf(false)
        var partialText by mutableStateOf("")

        setContent {
            KernelAITheme {
                // Unlock backdrop tap-to-dismiss after 800ms. Prevents accidental
                // dismiss when the overlay appears mid-screen-wake (Side key path)
                // before the user is ready. The card's X button is always active.
                LaunchedEffect(Unit) {
                    delay(800)
                    dismissEnabled = true
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(enabled = dismissEnabled) { finish() },
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
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
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Listening",
                                modifier = Modifier
                                    .size(28.dp)
                                    .scale(pulse),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = partialText.ifEmpty { "Listening…" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { finish() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        var retryAttempted = false
        lifecycleScope.launch {
            suspend fun showUnavailableAndFinish(message: String) {
                val errorText = message.ifBlank { "Voice commands are unavailable right now." }
                Log.w(TAG, "VoiceCommandActivity: voice unavailable — $errorText")
                partialText = errorText
                delay(2_000)
                if (!isFinishing) finish()
            }

            val eventCollectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
                voiceInputController.events.collect { event ->
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
                            } else if (!retryAttempted) {
                                retryAttempted = true
                                partialText = ""
                                Log.d(TAG, "VoiceCommandActivity: blank transcript — retrying")
                                when (val result = voiceInputController.startListening(VoiceCaptureMode.AlertCommand)) {
                                    is VoiceInputStartResult.Started -> Unit
                                    is VoiceInputStartResult.Unavailable -> showUnavailableAndFinish(result.message)
                                }
                            } else {
                                finish()
                            }
                        }
                        is VoiceInputEvent.Error -> {
                            Log.w(TAG, "VoiceCommandActivity: voice error — ${event.message}")
                            if (!retryAttempted) {
                                retryAttempted = true
                                partialText = ""
                                Log.d(TAG, "VoiceCommandActivity: retrying after error")
                                when (val result = voiceInputController.startListening(VoiceCaptureMode.AlertCommand)) {
                                    is VoiceInputStartResult.Started -> Unit
                                    is VoiceInputStartResult.Unavailable -> showUnavailableAndFinish(result.message)
                                }
                            } else {
                                showUnavailableAndFinish(event.message)
                            }
                        }
                        else -> Unit
                    }
                }
            }

            when (val startResult = voiceInputController.startListening(VoiceCaptureMode.AlertCommand)) {
                is VoiceInputStartResult.Started -> Unit
                is VoiceInputStartResult.Unavailable -> {
                    eventCollectorJob.cancel()
                    showUnavailableAndFinish(startResult.message)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceInputController.stopListening()
        toneGenerator?.release()
        toneGenerator = null
    }
}
