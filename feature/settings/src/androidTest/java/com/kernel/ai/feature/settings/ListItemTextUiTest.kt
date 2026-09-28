package com.kernel.ai.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun multilineDescriptionStillOpensStoredUrl() {
        var openedUrl: String? = null
        showItemText(
            text = "Description\nhttps://example.com/a?query=full",
            onUriOpened = { openedUrl = it },
        )

        composeTestRule
            .onNodeWithText("Description\nhttps://example.com/a?query=full")
            .performTouchInput { click(center) }

        assertEquals("https://example.com/a?query=full", openedUrl)
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

    @Test
    fun clippedDescriptionKeepsExplicitLinkAction() {
        var openedUrl: String? = null
        val url = "https://example.com/tallneck/full-description"
        showDescriptionLinks(
            text = "Tallneck prose line one\nline two\nline three\nline four\n$url",
            onUriOpened = { openedUrl = it },
        )

        composeTestRule
            .onNodeWithText("Open link")
            .performClick()

        assertEquals(url, openedUrl)
    }

    @Test
    fun multipleDescriptionUrlsUseBoundedChooser() {
        var openedUrl: String? = null
        showDescriptionLinks(
            text = "Prose before links\nhttps://example.com/one\nhttps://example.com/two",
            onUriOpened = { openedUrl = it },
        )

        composeTestRule
            .onNodeWithText("Open links (2)")
            .performClick()
        composeTestRule
            .onNodeWithText("Link 2: https://example.com/two")
            .performClick()

        assertEquals("https://example.com/two", openedUrl)
    }

    @Test
    fun multiSelectDescriptionLinkActionSelectsWithoutOpening() {
        var selectionCount = 0
        var openedUrl: String? = null
        showDescriptionLinks(
            text = "Prose before https://example.com/tallneck",
            activateLinks = false,
            onClick = { selectionCount++ },
            onUriOpened = { openedUrl = it },
        )

        composeTestRule
            .onNodeWithText("Select link")
            .performClick()

        assertEquals(1, selectionCount)
        assertNull(openedUrl)
    }

    private fun showDescriptionLinks(
        text: String,
        activateLinks: Boolean = true,
        onClick: () -> Unit = {},
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
                    Column {
                        ListItemText(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Black,
                            maxLines = 3,
                            onClick = onClick,
                            onLongClick = {},
                        )
                        DescriptionUrlActions(
                            urls = findListItemUrls(text),
                            activateLinks = activateLinks,
                            onInactiveClick = onClick,
                        )
                    }
                }
            }
        }
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
