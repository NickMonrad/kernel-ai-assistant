package com.kernel.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun modelsAndPrivacySummaryNameThePendingReviewAndOwnerContent() {
        composeRule.setContent {
            MaterialTheme {
                ModelsVoicesWakeWordScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("Models, voices & wake word").assertIsDisplayed()
        composeRule.onNodeWithText("Under review", substring = true).assertExists()
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
                OpenSourceLicensesScreen(onBack = {}, aboutLibrariesJson = aboutLibrariesJson)
            }
        }
        composeRule.onNodeWithText(originalNotice).assertExists()
        composeRule.onNodeWithText("openWakeWord", substring = true).assertExists()
    }

}
