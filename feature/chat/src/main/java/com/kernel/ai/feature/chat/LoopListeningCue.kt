package com.kernel.ai.feature.chat

import androidx.compose.runtime.Composable

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
    // Cue playback is handled by StartListeningCuePlayer in the ViewModel
    // (ChatViewModel, ActionsViewModel) on VoiceInputEvent.ListeningStarted.
    // This composable is retained for potential future visual indicators.
}
