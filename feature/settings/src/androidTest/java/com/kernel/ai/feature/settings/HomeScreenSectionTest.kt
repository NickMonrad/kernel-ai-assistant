package com.kernel.ai.feature.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.feature.settings.HomeWidgetPinning.PinResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pins the Settings → Home screen section (#2194): each row requests its own pin, an accepted or
 * declined request is reported as a message, and an unsupported launcher opens the how-to dialog.
 */
class HomeScreenSectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val requested = mutableListOf<HomeItem>()
    private val messages = mutableListOf<String>()

    @Test
    fun showsAllThreeRowsWithTheirTitles() {
        setSection(PinResult.Requested)

        composeTestRule.onNodeWithText("Add Jandal widget").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add Lists widget").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add Lists shortcut").assertIsDisplayed()
    }

    @Test
    fun tappingEachRowRequestsThatItem() {
        setSection(PinResult.Requested)

        HomeItem.entries.forEach { item ->
            composeTestRule.onNodeWithTag(homeRowTestTag(item)).performClick()
        }

        assertEquals(HomeItem.entries, requested)
    }

    @Test
    fun acceptedRequestReportsRequestSent() {
        setSection(PinResult.Requested)

        composeTestRule.onNodeWithTag(homeRowTestTag(HomeItem.LISTS_WIDGET)).performClick()

        assertEquals(
            listOf(HomeWidgetPinning.resultMessage(HomeItem.LISTS_WIDGET, PinResult.Requested)),
            messages,
        )
    }

    @Test
    fun declinedRequestReportsRejectionWithoutHowTo() {
        setSection(PinResult.Rejected)

        composeTestRule.onNodeWithTag(homeRowTestTag(HomeItem.JANDAL_WIDGET)).performClick()

        assertEquals(
            listOf(HomeWidgetPinning.resultMessage(HomeItem.JANDAL_WIDGET, PinResult.Rejected)),
            messages,
        )
        composeTestRule.onAllNodesWithText(HomeItem.JANDAL_WIDGET.fallbackHowTo).assertCountEquals(0)
    }

    @Test
    fun unsupportedLauncherOpensHowToDialogForThatItem() {
        setSection(PinResult.Unsupported)

        composeTestRule.onNodeWithTag(homeRowTestTag(HomeItem.JANDAL_WIDGET)).performClick()

        composeTestRule.onNodeWithText(HomeItem.JANDAL_WIDGET.fallbackHowTo).assertIsDisplayed()
        assertEquals(emptyList<String>(), messages)
    }

    private fun setSection(result: PinResult) {
        composeTestRule.setContent {
            KernelAITheme {
                HomeScreenSection(
                    onRequestPin = { item ->
                        requested += item
                        result
                    },
                    onMessage = { messages += it },
                )
            }
        }
    }
}
