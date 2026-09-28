package com.kernel.ai.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ListItemTextUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingUrlOpensTheFullStoredUrl() {
        var openedUrl: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) {
                            openedUrl = uri
                        }
                    },
                ) {
                    ListItemText(
                        text = "https://example.com/tasks/today",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Black,
                        onClick = {},
                        onLongClick = {},
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText("https://example.com/tasks/today")
            .performTouchInput { click(center) }

        assertEquals("https://example.com/tasks/today", openedUrl)
    }
}
