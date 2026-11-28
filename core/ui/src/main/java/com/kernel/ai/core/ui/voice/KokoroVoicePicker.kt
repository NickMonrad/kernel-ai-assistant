package com.kernel.ai.core.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.voice.kokoro.KokoroVoice

/** Kokoro voice choice, shared by setup step 2 and Settings > Voice (#2191). */
@Composable
fun KokoroVoicePicker(
    selected: KokoroVoice,
    onSelected: (KokoroVoice) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Voice",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        KokoroVoice.entries.forEach { voice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = voice == selected,
                        onClick = { onSelected(voice) },
                        role = Role.RadioButton,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = voice == selected, onClick = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${voice.displayName}: ${voice.description}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
