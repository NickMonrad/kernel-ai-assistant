package com.kernel.ai.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kernel.ai.core.memory.dao.FavouriteShortcutDao
import com.kernel.ai.core.memory.dao.RecentShortcutDao
import com.kernel.ai.core.memory.entity.FavouriteShortcutEntity
import com.kernel.ai.core.memory.entity.RecentShortcutEntity
import com.kernel.ai.core.memory.shortcut.FavouriteShortcutRepository
import com.kernel.ai.core.memory.shortcut.RecentShortcutTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerContentScrollTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun overflowingDrawerContentScrollsToSettingsRow() {
        val favouriteRepository = FavouriteShortcutRepository(
            FakeFavouriteShortcutDao(
                listOf(
                    "lists",
                    "notes",
                    "clock",
                    "convert",
                    "clock.stopwatch",
                    "clock.timer",
                    "clock.alarms",
                    "clock.world_clock",
                    "convert.currency",
                    "convert.unit",
                    "convert.cooking",
                )
            )
        )
        val recentTracker = RecentShortcutTracker(
            FakeRecentShortcutDao(
                listOf(
                    "learn",
                    "user_profile",
                    "memory",
                    "voice",
                    "chat_preferences",
                    "models",
                    "permissions",
                    "about",
                )
            )
        )

        composeTestRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(220.dp)
                        .clipToBounds(),
                ) {
                    val navController = rememberNavController()
                    val drawerState = rememberDrawerState(DrawerValue.Open)
                    val favourites = remember { favouriteRepository }
                    val recents = remember { recentTracker }

                    DrawerContent(
                        navController = navController,
                        drawerState = drawerState,
                        currentBaseRoute = null,
                        currentTab = null,
                        favouriteShortcutRepository = favourites,
                        recentShortcutTracker = recents,
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("drawer_content_scroll")
            .performScrollToNode(hasTestTag("drawer_item_settings"))
        composeTestRule.onNodeWithTag("drawer_item_settings").assertIsDisplayed()
    }
}

private class FakeFavouriteShortcutDao(ids: List<String>) : FavouriteShortcutDao {
    private val entities = MutableStateFlow(
        ids.mapIndexed { index, id ->
            FavouriteShortcutEntity(
                id = id,
                sortOrder = index,
                addedAt = index.toLong(),
            )
        }
    )

    override fun observeAll(): Flow<List<FavouriteShortcutEntity>> = entities

    override suspend fun getAllIds(): List<String> = entities.value.map { it.id }

    override suspend fun isFavourited(id: String): Boolean = entities.value.any { it.id == id }

    override suspend fun insert(entity: FavouriteShortcutEntity) {
        entities.value = entities.value.filterNot { it.id == entity.id } + entity
    }

    override suspend fun delete(id: String): Int {
        val current = entities.value
        entities.value = current.filterNot { it.id == id }
        return current.size - entities.value.size
    }

    override suspend fun deleteAll() {
        entities.value = emptyList()
    }

    override suspend fun count(): Int = entities.value.size
}

private class FakeRecentShortcutDao(ids: List<String>) : RecentShortcutDao {
    private val entities = MutableStateFlow(
        ids.mapIndexed { index, id ->
            RecentShortcutEntity(
                id = id,
                openedAt = (ids.size - index).toLong(),
            )
        }
    )

    override fun observeAll(): Flow<List<RecentShortcutEntity>> = entities

    override suspend fun getAllIds(): List<String> = entities.value.map { it.id }

    override suspend fun upsert(entity: RecentShortcutEntity) {
        entities.value = listOf(entity) + entities.value.filterNot { it.id == entity.id }
    }

    override suspend fun delete(id: String): Int {
        val current = entities.value
        entities.value = current.filterNot { it.id == id }
        return current.size - entities.value.size
    }

    override suspend fun trimToLimit(limit: Int): Int {
        val current = entities.value
        entities.value = current.take(limit)
        return current.size - entities.value.size
    }

    override suspend fun count(): Int = entities.value.size

    override suspend fun deleteAll() {
        entities.value = emptyList()
    }
}
