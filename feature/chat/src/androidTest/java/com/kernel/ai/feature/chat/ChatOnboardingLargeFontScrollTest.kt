package com.kernel.ai.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.feature.chat.model.ChatUiState
import org.junit.Rule
import org.junit.Test

/**
 * Chat onboarding at font scale 2.0 in a 360 dp wide window (#2111). With enough model rows the content is taller
 * than the screen, so "Manage models" must start outside the viewport and be reachable by scrolling.
 *
 * The forced-size test host has no system bars, so this test cannot check the system-bar inset padding; that is
 * verified on the S23U capture instead.
 */
@OptIn(ExperimentalTestApi::class)
class ChatOnboardingLargeFontScrollTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun manageModelsButtonIsReachableByScrollingAtLargeFont() {
        val conversationModels = setOf(KernelModel.GEMMA_4_E4B, KernelModel.GEMMA_4_E2B)
        val rows = KernelModel.entries
            .filter { it !in conversationModels }
            .map { model ->
                ChatUiState.ModelDownloadProgress(
                    model = model,
                    displayName = model.displayName,
                    sizeLabel = "1 MB",
                    state = DownloadState.NotDownloaded,
                )
            }

        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))
                    .then(DeviceConfigurationOverride.FontScale(2f)),
            ) {
                MaterialTheme {
                    OnboardingContent(
                        isDownloading = false,
                        modelProgress = rows,
                        onRetry = {},
                        onDownloadModel = {},
                        onStartAuth = {},
                        onNavigateToModelManagement = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Manage models").assertIsNotDisplayed()
        composeRule.onNodeWithText("Manage models").performScrollTo().assertIsDisplayed()
    }
}
