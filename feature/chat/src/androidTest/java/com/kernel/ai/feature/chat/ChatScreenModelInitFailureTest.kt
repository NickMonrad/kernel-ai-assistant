package com.kernel.ai.feature.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatScreenModelInitFailureTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun failureSurfaceShowsErrorAndInvokesRetry() {
        var retryCount = 0
        composeTestRule.setContent {
            MaterialTheme {
                ModelInitializationFailedContent(
                    message = "Failed to load AI model: GPU context lost",
                    onRetry = { retryCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Couldn't start the on-device model").assertIsDisplayed()
        composeTestRule.onNodeWithText("Failed to load AI model: GPU context lost").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry model loading").assertIsDisplayed().performClick()

        assertEquals(1, retryCount)
    }
}
