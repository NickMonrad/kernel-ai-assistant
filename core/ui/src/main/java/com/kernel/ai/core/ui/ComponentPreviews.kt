package com.kernel.ai.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.KernelAITheme

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    KernelAITheme(darkTheme = isSystemInDarkTheme(), dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@PreviewLightDark
@Composable
private fun StatusChipPreview() = PreviewSurface {
    ChipStatus.entries.forEach { StatusChip(status = it) }
    InfoTag(text = "Recommended", style = InfoTagStyle.Success)
    InfoTag(text = "116 MB", style = InfoTagStyle.Neutral)
}

@PreviewLightDark
@Composable
private fun ChoiceCardPreview() = PreviewSurface {
    SectionLabel(text = "Listening")
    ChoiceGroup(count = 3) {
        ChoiceCard(
            title = "Automatic",
            description = "Works offline on this phone.",
            selected = true,
            onSelect = {},
            index = 0,
            tag = { InfoTag(text = "Recommended", style = InfoTagStyle.Success) },
            subContent = {
                SwitchSubSetting(
                    title = "Use online speech recognition",
                    description = "Sends your voice to your phone's speech service (usually Google).",
                    checked = false,
                    onCheckedChange = {},
                    onMore = {},
                )
            },
        )
        ChoiceCard(
            title = "Android's own listener",
            description = "Your phone's built-in speech recognition.",
            selected = false,
            onSelect = {},
            index = 1,
        )
        ChoiceCard(
            title = "Sherpa-ONNX SenseVoice",
            description = "Offline, multilingual.",
            selected = false,
            onSelect = {},
            index = 2,
            enabled = false,
            tag = { StatusChip(status = ChipStatus.Off, label = "Not downloaded") },
        )
    }
}
