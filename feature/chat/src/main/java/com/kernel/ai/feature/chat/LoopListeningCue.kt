package com.kernel.ai.feature.chat

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal enum class LoopListeningCueState { Idle, Preparing, Listening, Processing }

internal fun shouldPlayLoopListeningCue(
    loopActive: Boolean,
    currentState: LoopListeningCueState,
    previousState: LoopListeningCueState,
): Boolean = loopActive &&
    currentState == LoopListeningCueState.Preparing &&
    previousState != LoopListeningCueState.Preparing &&
    previousState != LoopListeningCueState.Listening

@Composable
internal fun LoopListeningCueEffect(
    loopActive: Boolean,
    captureState: LoopListeningCueState,
) {
    val loopListeningCue = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 65) }
    var previousState by remember { mutableStateOf(LoopListeningCueState.Idle) }

    DisposableEffect(Unit) {
        onDispose { loopListeningCue.release() }
    }

    LaunchedEffect(loopActive, captureState) {
        if (shouldPlayLoopListeningCue(loopActive, captureState, previousState)) {
            loopListeningCue.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
        }
        previousState = captureState
    }
}
