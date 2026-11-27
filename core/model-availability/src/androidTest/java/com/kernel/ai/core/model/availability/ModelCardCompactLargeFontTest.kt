package com.kernel.ai.core.model.availability

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Large-font layout for [ModelCardCompact] (#2101). At font scale 2.0 in a 360 dp wide window, a long model
 * title must wrap onto several lines rather than truncate beside the state badge, and the badge must stay visible.
 */
@OptIn(ExperimentalTestApi::class)
class ModelCardCompactLargeFontTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val longTitle = "asr4all Streaming Speech Recognition Model For Long Form Dictation"

    @Test
    fun longTitleWrapsInsteadOfTruncatingAtLargeFont() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))
                    .then(DeviceConfigurationOverride.FontScale(2f)),
            ) {
                MaterialTheme {
                    ModelCardCompact(
                        title = longTitle,
                        description = null,
                        state = ModelAvailabilityState.Ready,
                    )
                }
            }
        }

        val titleNode = composeRule.onNodeWithText(longTitle).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        titleNode.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action?.invoke(layouts)
        val layout = layouts.single()

        assertTrue("title should wrap onto 2+ lines, was ${layout.lineCount}", layout.lineCount >= 2)
        assertFalse("title must not visually overflow", layout.hasVisualOverflow)
        composeRule.onNodeWithText("Ready").assertIsDisplayed()
    }

    @Test
    fun longDescriptionWrapsInsteadOfTruncatingAtLargeFont() {
        val longDescription = "Paraformer int8 · English · Streaming · Offline fallback model tail"
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))
                    .then(DeviceConfigurationOverride.FontScale(2f)),
            ) {
                MaterialTheme {
                    ModelCardCompact(
                        title = "Paraformer",
                        description = longDescription,
                        state = ModelAvailabilityState.Ready,
                    )
                }
            }
        }

        val descriptionNode = composeRule.onNodeWithText(longDescription).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        descriptionNode.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action?.invoke(layouts)
        val layout = layouts.single()

        assertTrue("description should wrap onto 2+ lines, was ${layout.lineCount}", layout.lineCount >= 2)
        assertFalse("description must not visually overflow", layout.hasVisualOverflow)
        composeRule.onNodeWithText("Ready").assertIsDisplayed()
    }
 }
