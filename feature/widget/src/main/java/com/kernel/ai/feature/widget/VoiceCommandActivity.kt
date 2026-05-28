package com.kernel.ai.feature.widget

import android.Manifest
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
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "KernelAI"

/**
 * Pass a non-null value to skip STT and route a pre-existing transcript
 * directly through the overlay. Used by the wake word path in [WakeWordService]
 * so the user sees the bottom-sheet overlay (same as long-press) even though
 * the utterance was already recognised in the background.
 */
const val EXTRA_PREFILLED_TRANSCRIPT = "prefilled_transcript"

@AndroidEntryPoint
class VoiceCommandActivity : ComponentActivity() {

    @Inject lateinit var voiceInputController: VoiceInputController
    @Inject lateinit var navigator: WidgetNavigator

    private var toneGenerator: ToneGenerator? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceSession() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Wake word path: transcript already recognised in WakeWordService — skip STT,
        // show transcript in overlay briefly, then hand off to ActionsScreen.
        val prefilled = intent.getStringExtra(EXTRA_PREFILLED_TRANSCRIPT)
        if (prefilled != null) {
            routePrefilledTranscript(prefilled)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // Permission missing — request it. The system dialog will appear over this
            // translucent activity. On grant, startVoiceSession(); on deny, finish().
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        startVoiceSession()
    }

    /**
     * Wake word path: show the recognised transcript in the overlay card for 400ms
     * so the user can see what was heard, then call [WidgetNavigator.navigateToActions]
     * to open the ActionsScreen result card with voice TTS reply.
     */
    private fun routePrefilledTranscript(transcript: String) {
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
        lifecycleScope.launch {
            delay(400) // brief flash so user sees what was heard
            if (!isFinishing) {
                navigator.navigateToActions(this@VoiceCommandActivity, transcript, isVoice = true)
            }
            finish()
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

        lifecycleScope.launch {
            voiceInputController.events
                .onStart { voiceInputController.startListening(VoiceCaptureMode.AlertCommand) }
                .collect { event ->
                    when (event) {
                        is VoiceInputEvent.PartialTranscript -> {
                            partialText = event.text
                        }
                        is VoiceInputEvent.Transcript -> {
                            val transcript = event.text
                            Log.d(TAG, "VoiceCommandActivity: final transcript=\"$transcript\"")
                            if (transcript.isNotBlank() && !isFinishing) {
                                navigator.navigateToActions(this@VoiceCommandActivity, transcript, isVoice = true)
                            }
                            finish()
                        }
                        is VoiceInputEvent.Error -> {
                            Log.w(TAG, "VoiceCommandActivity: voice error — ${event.message}")
                            finish()
                        }
                        else -> Unit
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
