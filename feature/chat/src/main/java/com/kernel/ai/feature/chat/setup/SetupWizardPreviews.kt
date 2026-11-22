package com.kernel.ai.feature.chat.setup

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.core.voice.InflectMicroSetup
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import com.kernel.ai.core.voice.wakeVerifierKernelModels

// Previews for docs/specs/setup-visual-pass compose-notes §5. They also serve the README's Hey Jandal state
// screenshots (pending, Needs access, Not available) on phones without those states.

private val SamplePlan = listOf(
    KernelModel.GEMMA_4_E4B,
    KernelModel.ARCTIC_EMBED_M_V1_5,
    KernelModel.ARCTIC_EMBED_V1_5_VOCAB,
)

private val ReadyPlatform = MakeYoursPlatform(
    hasMicrophone = true,
    micGranted = true,
    wakeModelAvailable = true,
    assistantRoleAvailable = true,
)

private fun previewState(
    step: SetupStep,
    plannedModels: List<KernelModel> = SamplePlan,
    plannedVoices: List<SherpaPiperVoice> = listOf(SherpaPiperVoice.CoriHigh),
    platform: MakeYoursPlatform = ReadyPlatform,
    wakeWordStored: Boolean = false,
    wakeWordChoice: Boolean = false,
) = SetupWizardUiState(
    loaded = true,
    step = step,
    inputOptions = listOf(VoiceInputEngine.Auto, VoiceInputEngine.AndroidNative),
    inputEngine = VoiceInputEngine.Auto,
    outputOptions = listOf(
        VoiceOutputEngine.SherpaExperimental,
        VoiceOutputEngine.InflectMicroExperimental,
        VoiceOutputEngine.AndroidTts,
    ),
    outputEngine = VoiceOutputEngine.SherpaExperimental,
    choice = DownloadNetworkChoice.WIFI_ONLY,
    plannedModels = plannedModels,
    plannedVoices = plannedVoices,
    platform = platform,
    wakeWordStored = wakeWordStored,
    wakeWordChoice = wakeWordChoice,
    outputSizes = mapOf(
        VoiceOutputEngine.SherpaExperimental to SherpaPiperVoice.CoriHigh.approxDownloadBytes,
        VoiceOutputEngine.InflectMicroExperimental to InflectMicroSetup.kernelModels().sumOf { it.approxSizeBytes } +
            SherpaPiperVoice.CoriHigh.approxDownloadBytes,
    ),
    heyJandalExtraBytes = wakeVerifierKernelModels().sumOf { it.approxSizeBytes },
)

@Composable
private fun WizardPreview(state: SetupWizardUiState) {
    KernelAITheme(darkTheme = isSystemInDarkTheme(), dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SetupWizardContent(
                state = state,
                onInputSelected = {},
                onOutputSelected = {},
                onChoiceSelected = {},
                makeYours = MakeYoursActions(),
                onPrimary = {},
                onBack = {},
                onSkip = {},
            )
        }
    }
}

@PreviewLightDark
@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun Step1Preview() = WizardPreview(previewState(SetupStep.HOW_IT_WORKS))

@PreviewLightDark
@Composable
private fun Step2Preview() = WizardPreview(previewState(SetupStep.VOICE))

@Preview(fontScale = 2f, widthDp = 360, heightDp = 640)
@Composable
private fun Step2LargeFontPreview() = WizardPreview(previewState(SetupStep.VOICE))

@PreviewLightDark
@Composable
private fun Step3Preview() = WizardPreview(previewState(SetupStep.DOWNLOAD))

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Step3NothingLeftPreview() =
    WizardPreview(previewState(SetupStep.DOWNLOAD, plannedModels = emptyList(), plannedVoices = emptyList()))

@PreviewLightDark
@Composable
private fun Step4Preview() = WizardPreview(previewState(SetupStep.MAKE_JANDAL_YOURS))

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Step4PendingPreview() =
    WizardPreview(previewState(SetupStep.MAKE_JANDAL_YOURS, wakeWordStored = false, wakeWordChoice = true))

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Step4PendingOffPreview() =
    WizardPreview(previewState(SetupStep.MAKE_JANDAL_YOURS, wakeWordStored = true, wakeWordChoice = false))

@PreviewLightDark
@Preview(fontScale = 2f, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Step4HonorPreview() = WizardPreview(
    previewState(
        SetupStep.MAKE_JANDAL_YOURS,
        platform = ReadyPlatform.copy(usageAccessRequired = true),
        wakeWordChoice = true,
    ),
)

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Step4MicPreview() = WizardPreview(
    previewState(SetupStep.MAKE_JANDAL_YOURS, platform = ReadyPlatform.copy(micGranted = false), wakeWordChoice = true),
)

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Step4NotAvailablePreview() = WizardPreview(
    previewState(SetupStep.MAKE_JANDAL_YOURS, platform = ReadyPlatform.copy(hasMicrophone = false)),
)

@Preview(widthDp = 915, heightDp = 412, fontScale = 2f)
@Composable
private fun Step4CompactPreview() = WizardPreview(previewState(SetupStep.MAKE_JANDAL_YOURS))
