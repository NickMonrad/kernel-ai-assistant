package com.kernel.ai.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.ui.voice.VOICE_ERROR_ACTION_LABEL
import com.kernel.ai.core.ui.voice.VoiceErrorAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceErrorActionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `refusal action is shown and invokes the callback once when tapped`() {
        var clicks = 0
        composeRule.setContent {
            VoiceErrorAction(onClick = { clicks++ })
        }

        composeRule.onNodeWithText(VOICE_ERROR_ACTION_LABEL).assertIsDisplayed().performClick()

        assertEquals(1, clicks)
    }
}
