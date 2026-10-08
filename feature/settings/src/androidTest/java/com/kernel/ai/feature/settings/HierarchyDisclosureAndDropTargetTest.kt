package com.kernel.ai.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.memory.entity.ListItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class HierarchyDisclosureAndDropTargetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun collapsedParentDisclosureExposesExpandActionAndTogglesItsAccessibleState() {
        val expanded = mutableStateOf(false)
        composeTestRule.setContent {
            MaterialTheme {
                HierarchyDisclosureButton(
                    expanded = expanded.value,
                    onClick = { expanded.value = !expanded.value },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand sub-items").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription("Collapse sub-items").assertIsDisplayed()
        assertEquals(true, expanded.value)
    }

    @Test
    fun activeChildInsertionTargetNamesItsHierarchyOutcome() {
        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    HierarchyDropIndicator(
                        target = HierarchyDropTarget.ChildInsertion(parentRowId = 10L, beforeChildRowId = 11L),
                        isChild = true,
                        isActive = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Insert as sub-item").assertIsDisplayed()
    }

    @Test
    fun directParentRowTargetNamesTheLastChildOutcome() {
        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    HierarchyDropIndicator(
                        target = HierarchyDropTarget.ParentRow(parentRowId = 10L),
                        isChild = true,
                        isActive = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Append as last sub-item").assertIsDisplayed()
    }

    @Test
    fun inactiveInsertionTargetKeepsHitboxWithoutPaintingAnIdleSeparator() {
        val target = HierarchyDropTarget.TopLevelInsertion(beforeParentRowId = 42L)
        composeTestRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(Color.Magenta)
                        .testTag("indicator_canvas"),
                ) {
                    HierarchyDropIndicator(target = target, isChild = false, isActive = false)
                }
            }
        }

        val indicator = composeTestRule.onNodeWithTag("drop_top_42")
        indicator.assertIsDisplayed().assertHeightIsEqualTo(6.dp)
        val pixels = composeTestRule.onNodeWithTag("indicator_canvas").captureToImage().toPixelMap()
        assertTrue(
            "An inactive insertion target must retain its touch height without drawing a line",
            (0 until pixels.height).all { y ->
                (0 until pixels.width).all { x -> pixels[x, y] == Color.Magenta }
            },
        )
    }

    @Test
    fun longPressOnDragHandleStartsReorderWithoutTakingRowMultiSelectLongPress() {
        val dragStarts = mutableStateOf(0)
        val rowLongClicks = mutableStateOf(0)
        val item = ListItemEntity(id = 1L, listId = 1L, text = "Disposable row")

        composeTestRule.setContent {
            val listState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(listState) { _, _ -> }
            MaterialTheme {
                LazyColumn(state = listState) {
                    items(listOf(item), key = { it.id }) { row ->
                        ReorderableItem(reorderState, key = row.id) {
                            val handlePressed = remember(row.id) { mutableStateOf(false) }
                            SwipeToChangeDepthRow(
                                handleGesturesEnabled = true,
                                canMakeSubItem = true,
                                canMoveToTopLevel = false,
                                onHandlePointerChanged = { handlePressed.value = it },
                                onMakeSubItem = {},
                                onMoveToTopLevel = {},
                            ) { handleGestureModifier ->
                                ListItemRow(
                                    item = row,
                                    dragHandleModifier = handleGestureModifier.draggableHandle(
                                        onDragStarted = { dragStarts.value += 1 },
                                    ),
                                    showDragHandle = true,
                                    isDragHandlePressed = handlePressed.value,
                                    onToggle = {},
                                    onEdit = {},
                                    onToggleFavourite = {},
                                    onLongClick = { rowLongClicks.value += 1 },
                                )
                            }
                        }
                    }
                }
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Move item", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
                moveTo(center + Offset(0f, 80f))
                advanceEventTime(100L)
                up()
            }

        assertEquals(1, dragStarts.value)
        assertEquals(0, rowLongClicks.value)
        composeTestRule
            .onNodeWithText("Disposable row", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
                up()
            }
        assertEquals(1, rowLongClicks.value)
    }
}
