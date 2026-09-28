package com.kernel.ai.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ListItemTextUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingUrlOpensTheFullStoredUrl() {
        var openedUrl: String? = null
        showItemText(
            text = "https://example.com/tasks/today",
            onUriOpened = { openedUrl = it },
        )

        composeTestRule
            .onNodeWithText("https://example.com/tasks/today")
            .performTouchInput { click(center) }

        assertEquals("https://example.com/tasks/today", openedUrl)
    }

    @Test
    fun multiSelectPlainTextTapRoutesToSelection() {
        var selected = false
        showItemText(
            text = "Select this item",
            activateLinks = false,
            onClick = { selected = true },
        )

        composeTestRule
            .onNodeWithText("Select this item")
            .performTouchInput { click(center) }

        assertTrue(selected)
    }

    @Test
    fun multiSelectUrlTapRoutesToSelectionWithoutOpeningUrl() {
        var selectionCount = 0
        var openedUrl: String? = null
        showItemText(
            text = "https://example.com/tasks/today",
            activateLinks = false,
            onClick = { selectionCount++ },
            onUriOpened = { openedUrl = it },
        )

        composeTestRule
            .onNodeWithText("https://example.com/tasks/today")
            .performTouchInput { click(center) }

        assertEquals(1, selectionCount)
        assertNull(openedUrl)
    }

    private fun showItemText(
        text: String,
        activateLinks: Boolean = true,
        onClick: () -> Unit = {},
        onLongClick: () -> Unit = {},
        onUriOpened: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) {
                            onUriOpened(uri)
                        }
                    },
                ) {
                    ListItemText(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Black,
                        activateLinks = activateLinks,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                }
            }
        }
    }
}
