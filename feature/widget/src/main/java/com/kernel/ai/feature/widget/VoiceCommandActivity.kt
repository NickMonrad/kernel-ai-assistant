package com.kernel.ai.feature.widget

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceInputEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "KernelAI"

@AndroidEntryPoint
class VoiceCommandActivity : ComponentActivity() {

    @Inject lateinit var voiceInputController: VoiceInputController

    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Brief boop to indicate listening started
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 80).also {
                it.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            }
        } catch (e: Exception) {
            Log.w(TAG, "VoiceCommandActivity: could not play boop", e)
        }

        var partialText by mutableStateOf("")

        setContent {
            KernelAITheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        tonalElevation = 8.dp,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp),
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
                            )
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            voiceInputController.events
                .onStart { voiceInputController.startListening(VoiceCaptureMode.Command) }
                .collect { event ->
                when (event) {
                    is VoiceInputEvent.PartialTranscript -> {
                        partialText = event.text
                    }
                    is VoiceInputEvent.Transcript -> {
                        val transcript = event.text
                        Log.d(TAG, "VoiceCommandActivity: final transcript=\"$transcript\"")
                        if (transcript.isNotBlank()) {
                            val serviceIntent = Intent(
                                this@VoiceCommandActivity,
                                VoiceCommandService::class.java,
                            ).apply {
                                putExtra(VoiceCommandService.EXTRA_TRANSCRIPT, transcript)
                                putExtra(VoiceCommandService.EXTRA_INPUT_MODE, "voice")
                            }
                            startService(serviceIntent)
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
        toneGenerator?.release()
        toneGenerator = null
    }
}
