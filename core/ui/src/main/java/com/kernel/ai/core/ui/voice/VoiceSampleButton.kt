package com.kernel.ai.core.ui.voice

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.JandalTheme

/**
 * "Hear a sample", or "Stop sample" while this voice plays (#2174). Shared by setup and Settings › Voice. A second
 * tap stops the sample; the caller decides what starting one stops.
 */
@Composable
fun VoiceSampleButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
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
