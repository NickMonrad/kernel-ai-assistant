package com.kernel.ai.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ClockVoiceFloatingActionButtonTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sharedClockVoiceFabIsVisibleAndClickable() {
        var clickCount = 0

        composeTestRule.setContent {
            MaterialTheme {
                ClockVoiceFloatingActionButton(onClick = { clickCount++ })
            }
        }

        composeTestRule.onNodeWithTag(CLOCK_VOICE_FAB_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Voice input").assertIsDisplayed()
        composeTestRule.onNodeWithTag(CLOCK_VOICE_FAB_TEST_TAG).performClick()

        assertEquals(1, clickCount)
    }
}
