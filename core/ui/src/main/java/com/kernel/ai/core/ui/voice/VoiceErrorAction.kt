package com.kernel.ai.core.ui.voice

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Tappable fix beside a voice error that has one (#2049). Opens Settings › Voice. */
@Composable
fun VoiceErrorAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
    ) {
        Text(VOICE_ERROR_ACTION_LABEL)
    }
}

/** Label for the Settings › Voice fix. Shared so every surface names the action the same way. */
const val VOICE_ERROR_ACTION_LABEL = "Open Voice settings"
