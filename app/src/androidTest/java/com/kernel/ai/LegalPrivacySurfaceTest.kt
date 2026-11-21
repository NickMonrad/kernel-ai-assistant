package com.kernel.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kernel.ai.feature.settings.LegalPrivacyScreen
import com.kernel.ai.feature.settings.DataPrivacySummaryScreen
import com.kernel.ai.feature.settings.ModelsVoicesWakeWordScreen
import com.kernel.ai.feature.settings.OpenSourceLicensesScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegalPrivacySurfaceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun legalPrivacyExposesAllRequiredDestinations() {
        var openedLicenses = false
        var openedAssets = false
        var openedPrivacySummary = false
        composeRule.setContent {
            MaterialTheme {
                LegalPrivacyScreen(
                    onBack = {},
                    onOpenSourceLicenses = { openedLicenses = true },
                    onOpenModelsVoicesWakeWord = { openedAssets = true },
                    onOpenDataPrivacySummary = { openedPrivacySummary = true },
                )
            }
        }

        composeRule.onNodeWithText("Privacy Policy").assertIsDisplayed()
        composeRule.onNodeWithText("Terms of Use").assertIsDisplayed()
        composeRule.onNodeWithText("Open-source licences").performClick()
        composeRule.onNodeWithText("Models, voices & wake word").performClick()
        composeRule.onNodeWithText("Data & Privacy Summary").performClick()
        assertTrue(openedLicenses && openedAssets && openedPrivacySummary)
    }

    @Test
    fun wakeWordAssetsAreBundledInReleaseWithCcByNcSaStatus() {
        composeRule.setContent {
            MaterialTheme {
                ModelsVoicesWakeWordScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("openWakeWord melspectrogram stage").assertExists()
        // The three wake-word entries (melspectrogram, embedding, Hey Jandal) carry this release status.
        // The screen is a verticalScroll Column (not LazyColumn), so rows below the fold are composed and counted.
        // Not executed on a device in this PR; compile-checked only.
        composeRule.onAllNodesWithText("Bundled in release · CC BY-NC-SA 4.0", substring = true).assertCountEquals(3)
        // Stage 1/2 terms stay flagged as unresolved at file level (PR #2020).
        composeRule.onAllNodesWithText("file-level terms unresolved", substring = true).assertCountEquals(2)
    }

    @Test
    fun dataPrivacyScreenIdentifiesItsOwnerAndMissingContent() {
        composeRule.setContent {
            MaterialTheme {
                DataPrivacySummaryScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("Data & Privacy Summary content is owned by issue #1260 and is not yet available.").assertIsDisplayed()
    }

    @Test
    fun openSourceLicensesScreenShowsOriginalNoticeText() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val aboutLibrariesJson = context.resources.openRawResource(R.raw.aboutlibraries)
            .bufferedReader()
            .use { it.readText() }
        val originalNotice = context.assets.open("legal/NOTICE.txt")
            .bufferedReader()
            .use { it.readText() }
        composeRule.setContent {
            MaterialTheme {
                OpenSourceLicensesScreen(onBack = {}, loadAboutLibrariesJson = { aboutLibrariesJson })
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(originalNotice).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(originalNotice).assertExists()
        composeRule.onNodeWithText("openWakeWord", substring = true).assertExists()
        composeRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("asr4all speech model licence text"))
        composeRule.onNodeWithText("asr4all speech model licence text").assertIsDisplayed()
    }

}
