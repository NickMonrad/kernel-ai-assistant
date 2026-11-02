package com.kernel.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kernel.ai.feature.settings.LegalPrivacyScreen
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
    fun legalPrivacyLinksAreAvailable() {
        var openedLicenses = false
        composeRule.setContent {
            MaterialTheme {
                LegalPrivacyScreen(
                    onBack = {},
                    onOpenSourceLicenses = { openedLicenses = true },
                )
            }
        }

        composeRule.onNodeWithText("Privacy Policy").assertIsDisplayed()
        composeRule.onNodeWithText("Terms of Use").assertIsDisplayed()
        composeRule.onNodeWithText("Open-source licences").performClick()
        assertTrue(openedLicenses)
    }

    @Test
    fun openSourceLicensesScreenShowsBundledNotices() {
        composeRule.setContent {
            MaterialTheme {
                OpenSourceLicensesScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("Bundled third-party notices").assertIsDisplayed()
        composeRule.onNodeWithText("openWakeWord", substring = true).assertIsDisplayed()
    }
}
