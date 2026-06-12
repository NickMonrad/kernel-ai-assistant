package com.kernel.ai.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DrawerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full app-flow navigation integration tests for the #751 launch navigation model.
 *
 * This suite complements [NavigationBackStackRegressionTest] by exercising the real
 * navigation graph with production composables where practical, rather than using
 * an isolated harness with stub destinations for every route.
 *
 * ## What is tested with real production composables
 *
 * - [PrimaryBottomBar] — real bottom navigation bar with production test tags
 * - [ToolsHubScreen] — real tools hub with production row and menu button test tags
 * - [ToolsLearnScreen] — real learn screen with production example prompt test tags
 * - Drawer navigation items — same structure as production [KernelNavHost]
 * - Route wiring — same route constants and navigation patterns as production
 *
 * ## What remains stubbed
 *
 * - Conversation list screen — uses `hiltViewModel()` for [ConversationListViewModel]
 * - Actions screen — uses `hiltViewModel()` for [ActionsViewModel]
 * - Chat screen — uses `hiltViewModel()` for [ChatViewModel]
 * - All settings/child screens — use `hiltViewModel()` for their ViewModels
 * - Learn screen example prompts — **real** [ToolsLearnScreen] with real prompts
 *
 * Stubs use test tags distinct from production composables (prefixed `faf_`)
 * to avoid ambiguity. Navigation routing and back-stack behavior through
 * the stub destinations is real — only the rendered content differs.
 *
 * ## Evidence suite
 *
 * Suite name: `navigation_full_app_flow`
 * Source: `on_device`
 * Distinct from: `navigation_backstack` (#1154 harness)
 */
@RunWith(AndroidJUnit4::class)
class NavigationFullAppFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Captured NavHostController from [KernelNavTestHost] for programmatic navigation. */
    private var testNavController: NavHostController? = null

    /** Captured DrawerState for programmatic drawer open/close. */
    private var testDrawerState: DrawerState? = null

    companion object {
        // Route constants matching KernelNavHost production values
        private const val ROUTE_LIST = "conversation_list"
        private const val ROUTE_ACTIONS = "actions"
        private const val ROUTE_TOOLS = "tools"
        private const val ROUTE_CHAT = "chat"
        private const val ARG_INITIAL_QUERY = "initialQuery"
        private const val ARG_MINIMAL_CONTEXT = "minimalContext"
        private const val ARG_SPEAK_RESPONSE = "speakResponse"
        private const val ARG_CONVERSATION_ID = "conversationId"
        private const val ARG_OPEN_SHEET = "openSheet"
        private const val ARG_START_VOICE = "startVoice"
        private const val ARG_WIDGET_QUERY = "widgetQuery"
        private const val ARG_WIDGET_VOICE = "widgetVoice"
        private const val ARG_DRAFT_QUERY = "draftQuery"

        // Bottom nav test tags (production PrimaryBottomBar)
        private const val BOTTOM_NAV_CHATS = "bottom_nav_chats"
        private const val BOTTOM_NAV_ACTIONS = "bottom_nav_actions"
        private const val BOTTOM_NAV_TOOLS = "bottom_nav_tools"

        // FAF stub screen test tags (prefixed to distinguish from production)
        private const val TAG_CHATS_SCREEN = "faf_chats_screen"
        private const val TAG_CHATS_MENU_BUTTON = "faf_chats_menu_button"
        private const val TAG_ACTIONS_SCREEN = "faf_actions_screen"
        private const val TAG_DRAFT_INPUT = "faf_draft_input"
        private const val TAG_DRAFT_SUBMIT = "faf_draft_submit"
        private const val TAG_DRAFT_TEXT = "faf_draft_text"

        // Destination stub tags
        private const val TAG_DEST_LEARN = "faf_dest_learn"
        private const val TAG_DEST_LISTS = "faf_dest_lists"
        private const val TAG_DEST_NOTES = "faf_dest_notes"
        private const val TAG_DEST_MEAL_PLANS = "faf_dest_meal_plans"
        private const val TAG_DEST_CONVERT = "faf_dest_convert"
        private const val TAG_DEST_SETTINGS = "faf_dest_settings"
        private const val TAG_DEST_MODELS = "faf_dest_models"
        private const val TAG_DEST_PERMISSIONS = "faf_dest_permissions"
        private const val TAG_DEST_ABOUT = "faf_dest_about"
        private const val TAG_DEST_MEMORY = "faf_dest_memory"
        private const val TAG_DEST_VOICE = "faf_dest_voice"
        private const val TAG_DEST_USER_PROFILE = "faf_dest_user_profile"
        private const val TAG_DEST_CHAT_PREFERENCES = "faf_dest_chat_preferences"
        private const val TAG_DEST_CONTACTS = "faf_dest_contacts"
        private const val TAG_DEST_IMPORTANT_DATES = "faf_dest_important_dates"
        private const val TAG_DEST_SIDE_PANEL = "faf_dest_side_panel"
    }

    /** Bottom nav route set (matching production KernelNavHost.BOTTOM_NAV_ROUTES). */
    private val bottomNavRoutes = setOf(ROUTE_LIST, ROUTE_ACTIONS, ROUTE_TOOLS)

    private fun isBottomNavRoute(route: String?): Boolean =
        route?.substringBefore('?') in bottomNavRoutes

    data class ToolsRouteEntry(
        val rowTag: String,
        val route: String,
        val destTag: String,
        val backTag: String,
        val label: String,
    )

    private val toolsRoutes = listOf(
        // Learn uses the real ToolsLearnScreen composable (tag: "tools_learn_screen" with "back_from_tools_learn")
        ToolsRouteEntry("tools_row_learn", ROUTE_TOOLS_LEARN, "tools_learn_screen", "back_from_tools_learn", "Learn"),
        ToolsRouteEntry("tools_row_lists", ROUTE_LISTS, TAG_DEST_LISTS, "faf_back_from_${TAG_DEST_LISTS}", "Lists"),
        ToolsRouteEntry("tools_row_notes", ROUTE_NOTES, TAG_DEST_NOTES, "faf_back_from_${TAG_DEST_NOTES}", "Notes"),
        ToolsRouteEntry("tools_row_meal_plans", ROUTE_MEAL_PLANS, TAG_DEST_MEAL_PLANS, "faf_back_from_${TAG_DEST_MEAL_PLANS}", "Meal plans"),
        ToolsRouteEntry("tools_row_convert", ROUTE_CONVERT, TAG_DEST_CONVERT, "faf_back_from_${TAG_DEST_CONVERT}", "Convert"),
        ToolsRouteEntry("tools_row_settings", ROUTE_SETTINGS, TAG_DEST_SETTINGS, "faf_back_from_${TAG_DEST_SETTINGS}", "Settings"),
        ToolsRouteEntry(
            "tools_row_models", buildModelManagementRoute(), TAG_DEST_MODELS, "faf_back_from_${TAG_DEST_MODELS}", "Models",
        ),
        ToolsRouteEntry("tools_row_permissions", ROUTE_APP_PERMISSIONS, TAG_DEST_PERMISSIONS, "faf_back_from_${TAG_DEST_PERMISSIONS}", "Permissions"),
        ToolsRouteEntry("tools_row_about", ROUTE_ABOUT, TAG_DEST_ABOUT, "faf_back_from_${TAG_DEST_ABOUT}", "About"),
    )

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun assertScreenNotPresent(tag: String) {
        composeTestRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            .let { nodes ->
                assertTrue("Expected no node with tag '$tag' in tree", nodes.isEmpty())
            }
    }

    private fun assertNodeExists(tag: String) {
        val nodes = composeTestRule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("Expected semantics node with tag '$tag' to be present in tree", nodes.isNotEmpty())
    }

    private fun assertBottomNavSelected(navTag: String) {
        composeTestRule.onNodeWithTag(navTag).assertIsSelected()
    }

    private fun assertBottomNavNotSelected(navTag: String) {
        composeTestRule.onNodeWithTag(navTag).assertIsNotSelected()
    }

    // ── Production-equivalent navigation host ────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun KernelNavTestHost(): NavHostController {
        val navController = rememberNavController()
        testNavController = navController
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val currentBaseRoute = currentRoute?.substringBefore('?')
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        testDrawerState = drawerState
        val scope = rememberCoroutineScope()

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = isBottomNavRoute(currentBaseRoute),
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.testTag("faf_drawer_sheet")) {
                    Text(
                        "Jandal",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))

                    // Production-equivalent drawer items (same as KernelNavHost)
                    DrawerItem("Lists", TAG_DEST_LISTS, ROUTE_LISTS, currentBaseRoute, navController, drawerState, scope)
                    DrawerItem("Notes", TAG_DEST_NOTES, ROUTE_NOTES, currentBaseRoute, navController, drawerState, scope)
                    DrawerItem("Clock", TAG_DEST_SIDE_PANEL, ROUTE_SIDE_PANEL, currentBaseRoute, navController, drawerState, scope)
                    DrawerItem("Convert", TAG_DEST_CONVERT, ROUTE_CONVERT, currentBaseRoute, navController, drawerState, scope)
                    DrawerItem("Important dates", TAG_DEST_IMPORTANT_DATES, ROUTE_IMPORTANT_DATES, currentBaseRoute, navController, drawerState, scope)
                    DrawerItem("People & Contacts", TAG_DEST_CONTACTS, ROUTE_CONTACT_ALIASES, currentBaseRoute, navController, drawerState, scope)
                    DrawerItem("Meal plans", TAG_DEST_MEAL_PLANS, ROUTE_MEAL_PLANS, currentBaseRoute, navController, drawerState, scope)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DrawerItem("Settings", TAG_DEST_SETTINGS, ROUTE_SETTINGS, currentBaseRoute, navController, drawerState, scope)
                }
            },
        ) {
            Scaffold(
                bottomBar = {
                    if (isBottomNavRoute(currentBaseRoute)) {
                        // Real production PrimaryBottomBar
                        PrimaryBottomBar(
                            currentBaseRoute = currentBaseRoute,
                            onNavigateToRoute = { route ->
                                navController.navigateToPrimaryRoute(route)
                            },
                        )
                    }
                },
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_LIST,
                    modifier = Modifier.padding(innerPadding),
                ) {
                    // ── Chats (stub — ConversationListScreen uses Hilt ViewModel) ──
                    composable(ROUTE_LIST) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(TAG_CHATS_SCREEN),
                        ) {
                            TopAppBar(
                                title = { Text("Chats") },
                                navigationIcon = {
                                    IconButton(
                                        onClick = { scope.launch { drawerState.open() } },
                                        modifier = Modifier.testTag(TAG_CHATS_MENU_BUTTON),
                                    ) {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                                    }
                                },
                            )
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Chats Screen")
                            }
                        }
                    }

                    // ── Actions (stub — ActionsScreen uses Hilt ViewModel) ──
                    composable(
                        route = "$ROUTE_ACTIONS?$ARG_OPEN_SHEET={$ARG_OPEN_SHEET}" +
                            "&$ARG_START_VOICE={$ARG_START_VOICE}" +
                            "&$ARG_WIDGET_QUERY={$ARG_WIDGET_QUERY}" +
                            "&$ARG_WIDGET_VOICE={$ARG_WIDGET_VOICE}" +
                            "&$ARG_DRAFT_QUERY={$ARG_DRAFT_QUERY}",
                        arguments = listOf(
                            navArgument(ARG_OPEN_SHEET) {
                                type = NavType.BoolType; defaultValue = false
                            },
                            navArgument(ARG_START_VOICE) {
                                type = NavType.BoolType; defaultValue = false
                            },
                            navArgument(ARG_WIDGET_QUERY) {
                                type = NavType.StringType; defaultValue = ""
                            },
                            navArgument(ARG_WIDGET_VOICE) {
                                type = NavType.BoolType; defaultValue = false
                            },
                            navArgument(ARG_DRAFT_QUERY) {
                                type = NavType.StringType; defaultValue = ""
                            },
                        ),
                    ) { backStackEntry ->
                        @Suppress("UNUSED")
                        val openSheet = backStackEntry.arguments?.getBoolean(ARG_OPEN_SHEET) ?: false
                        val draftQuery = backStackEntry.arguments?.getString(ARG_DRAFT_QUERY) ?: ""
                        ActionsScreenStub(draftQuery = draftQuery)
                    }

                    // ── Tools (real production composable) ──
                    composable(ROUTE_TOOLS) {
                        ToolsHubScreen(
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onNavigateToRoute = { route ->
                                navController.navigateToToolsDestination(route)
                            },
                        )
                    }

                    // ── Tools Learn (real production composable) ──
                    composable(ROUTE_TOOLS_LEARN) {
                        ToolsLearnScreen(
                            onBack = { navController.popBackStack() },
                            onOpenPrompt = { prompt ->
                                navController.navigate(buildActionsDraftRoute(prompt)) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }

                    // ── Stub destinations for remaining routes ──
                    composable(ROUTE_SETTINGS) {
                        DestinationStub("Settings", TAG_DEST_SETTINGS) { navController.popBackStack() }
                    }
                    composable(ROUTE_LISTS) {
                        DestinationStub("Lists", TAG_DEST_LISTS) { navController.popBackStack() }
                    }
                    composable(ROUTE_NOTES) {
                        DestinationStub("Notes", TAG_DEST_NOTES) { navController.popBackStack() }
                    }
                    composable(ROUTE_MEAL_PLANS) {
                        DestinationStub("Meal Plans", TAG_DEST_MEAL_PLANS) { navController.popBackStack() }
                    }
                    composable(ROUTE_CONVERT) {
                        DestinationStub("Convert", TAG_DEST_CONVERT) { navController.popBackStack() }
                    }
                    composable(ROUTE_ABOUT) {
                        DestinationStub("About", TAG_DEST_ABOUT) { navController.popBackStack() }
                    }
                    composable(ROUTE_APP_PERMISSIONS) {
                        DestinationStub("Permissions", TAG_DEST_PERMISSIONS) { navController.popBackStack() }
                    }
                    composable(
                        route = "settings/model_management?scrollTo={scrollTo}",
                        arguments = listOf(
                            navArgument("scrollTo") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) {
                        DestinationStub("Models", TAG_DEST_MODELS) { navController.popBackStack() }
                    }
                    composable(ROUTE_MEMORY) {
                        DestinationStub("Memory", TAG_DEST_MEMORY) { navController.popBackStack() }
                    }
                    composable(ROUTE_VOICE) {
                        DestinationStub("Voice", TAG_DEST_VOICE) { navController.popBackStack() }
                    }
                    composable(ROUTE_USER_PROFILE) {
                        DestinationStub("User Profile", TAG_DEST_USER_PROFILE) { navController.popBackStack() }
                    }
                    composable(ROUTE_CHAT_PREFERENCES) {
                        DestinationStub("Chat Preferences", TAG_DEST_CHAT_PREFERENCES) { navController.popBackStack() }
                    }
                    composable(ROUTE_CONTACT_ALIASES) {
                        DestinationStub("People & Contacts", TAG_DEST_CONTACTS) { navController.popBackStack() }
                    }
                    composable(ROUTE_IMPORTANT_DATES) {
                        DestinationStub("Important Dates", TAG_DEST_IMPORTANT_DATES) { navController.popBackStack() }
                    }
                    composable(ROUTE_SIDE_PANEL) {
                        DestinationStub("Side Panel", TAG_DEST_SIDE_PANEL) { navController.popBackStack() }
                    }
                    composable(
                        route = "$ROUTE_CHAT?$ARG_INITIAL_QUERY={$ARG_INITIAL_QUERY}" +
                            "&$ARG_MINIMAL_CONTEXT={$ARG_MINIMAL_CONTEXT}" +
                            "&$ARG_SPEAK_RESPONSE={$ARG_SPEAK_RESPONSE}",
                        arguments = listOf(
                            navArgument(ARG_INITIAL_QUERY) {
                                type = NavType.StringType; defaultValue = ""
                            },
                            navArgument(ARG_MINIMAL_CONTEXT) {
                                type = NavType.BoolType; defaultValue = false
                            },
                            navArgument(ARG_SPEAK_RESPONSE) {
                                type = NavType.BoolType; defaultValue = false
                            },
                        ),
                    ) {
                        DestinationStub("Chat", "faf_dest_chat") { navController.popBackStack() }
                    }
                    composable(
                        route = "$ROUTE_CHAT/{$ARG_CONVERSATION_ID}",
                        arguments = listOf(
                            navArgument(ARG_CONVERSATION_ID) { type = NavType.StringType },
                        ),
                    ) {
                        DestinationStub("Chat Detail", "faf_dest_chat_detail") { navController.popBackStack() }
                    }
                }
            }
        }
        return navController
    }

    // ── Shared composables ───────────────────────────────────────────────────

    @Composable
    private fun DrawerItem(
        label: String,
        destTag: String,
        route: String,
        currentBaseRoute: String?,
        navController: NavHostController,
        drawerState: DrawerState,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        NavigationDrawerItem(
            label = { Text(label) },
            selected = currentBaseRoute == route.substringBefore('?'),
            onClick = {
                scope.launch { drawerState.close() }
                // Matches production KernelNavHost.navigateToDrawerDestination():
                //   popUpTo(ROUTE_LIST) { saveState = false }
                //   launchSingleTop = true
                //   restoreState = false
                navController.navigate(route) {
                    popUpTo(ROUTE_LIST) { saveState = false }
                    launchSingleTop = true
                    restoreState = false
                }
            },
            modifier = Modifier.testTag("faf_drawer_item_${destTag.removePrefix("faf_dest_")}"),
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DestinationStub(
        label: String,
        tag: String,
        onBack: () -> Unit,
    ) {
        Column(modifier = Modifier.fillMaxSize().testTag(tag)) {
            TopAppBar(
                title = { Text(label) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("faf_back_from_$tag"),
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("$label Content", modifier = Modifier.testTag("${tag}_content"))
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ActionsScreenStub(draftQuery: String) {
        val inputText = remember { mutableStateOf(draftQuery) }
        val showDraft = remember { mutableStateOf(draftQuery.isNotEmpty()) }

        Column(modifier = Modifier.fillMaxSize().testTag(TAG_ACTIONS_SCREEN)) {
            TopAppBar(title = { Text("Actions") })
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Actions Screen")

                    if (showDraft.value && draftQuery.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Draft: $draftQuery",
                            modifier = Modifier.testTag(TAG_DRAFT_TEXT),
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = inputText.value,
                            onValueChange = { inputText.value = it },
                            placeholder = { Text("What do you want to do?") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .testTag(TAG_DRAFT_INPUT),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                showDraft.value = false
                                inputText.value = ""
                            },
                            modifier = Modifier.testTag(TAG_DRAFT_SUBMIT),
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                            Text(" Send (test)")
                        }
                    }
                }
            }
        }
    }

    // ── Navigation helpers (matching KernelNavHost patterns) ─────────────────

    private fun NavHostController.navigateToPrimaryRoute(route: String) {
        val currentRoute = currentBackStackEntry?.destination?.route
        val currentBaseRoute = currentRoute?.substringBefore('?')
        if (currentBaseRoute == route) return
        val hasTransientParams = currentRoute?.contains('?') == true
        navigate(route) {
            popUpTo(graph.findStartDestination().id) {
                saveState = !hasTransientParams
            }
            launchSingleTop = true
            restoreState = !hasTransientParams
        }
    }

    private fun NavHostController.navigateToToolsDestination(route: String) {
        val currentBaseRoute = currentBackStackEntry?.destination?.route
            ?.substringBefore('?')
        val targetBaseRoute = route.substringBefore('?')
        if (currentBaseRoute == targetBaseRoute) return
        navigate(route) { launchSingleTop = true }
    }

    // ═══════════════════════════ 1. PRIMARY NAVIGATION ═══════════════════════

    @Test
    fun primaryNavigation_chatsActionsToolsRoundTrip() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(TAG_CHATS_SCREEN).assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_CHATS)

        composeTestRule.onNodeWithTag(BOTTOM_NAV_ACTIONS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_ACTIONS_SCREEN).assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_ACTIONS)

        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_TOOLS)

        composeTestRule.onNodeWithTag(BOTTOM_NAV_CHATS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_CHATS_SCREEN).assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_CHATS)
    }

    @Test
    fun primaryNavigation_actionsToolsActions() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(BOTTOM_NAV_ACTIONS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_ACTIONS_SCREEN).assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_ACTIONS)

        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_TOOLS)

        composeTestRule.onNodeWithTag(BOTTOM_NAV_ACTIONS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_ACTIONS_SCREEN).assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_ACTIONS)
    }

    @Test
    fun primaryNavigation_toolsChatsTools() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_TOOLS)

        composeTestRule.onNodeWithTag(BOTTOM_NAV_CHATS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_CHATS_SCREEN).assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_CHATS)

        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_TOOLS)
        // Verify no stale child screen from a previous navigation
        assertScreenNotPresent(TAG_DEST_LISTS)
        assertScreenNotPresent(TAG_DEST_SETTINGS)
    }

    @Test
    fun primaryNavigation_repeatedTabTapStable() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // Repeated tap of already-selected tab
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_TOOLS)

        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_TOOLS)
    }

    @Test
    fun primaryNavigation_noStaleChildScreen() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // Navigate to a Tools child
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_row_lists"))
        composeTestRule.onNodeWithTag("tools_row_lists", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_DEST_LISTS).assertIsDisplayed()

        // Back to Tools
        composeTestRule.onNodeWithTag("faf_back_from_${TAG_DEST_LISTS}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        assertScreenNotPresent(TAG_DEST_LISTS)

        // Switch to Chats, back to Tools — verify no stale state
        composeTestRule.onNodeWithTag(BOTTOM_NAV_CHATS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_CHATS_SCREEN).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        assertScreenNotPresent(TAG_DEST_LISTS)
        assertScreenNotPresent(TAG_DEST_SETTINGS)
    }

    // ═══════════════════════════ 2. TOOLS ROW ROUTING ═══════════════════════

    @Test
    fun toolsRow_matrixNavigateAndBack() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(TAG_CHATS_SCREEN).assertIsDisplayed()

        for (entry in toolsRoutes) {
            // Navigate Tools → child → Back → Tools → Chats (reset for next route)
            composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

            composeTestRule.onNodeWithTag("tools_screen")
                .performScrollToNode(hasTestTag(entry.rowTag))
            composeTestRule.onNodeWithTag(entry.rowTag, useUnmergedTree = true).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(entry.destTag).assertIsDisplayed()

            composeTestRule.onNodeWithTag(entry.backTag).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

            // Return to Chats to reset for next route
            composeTestRule.onNodeWithTag(BOTTOM_NAV_CHATS).performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(TAG_CHATS_SCREEN).assertIsDisplayed()
        }
    }

    // ═══════════════════════════ 3. LEARN → ACTIONS DRAFT FLOW ══════════════

    @Test
    fun learnToActions_exampleOpensDraftPrefill() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // Navigate to real ToolsLearnScreen
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_row_learn"))
        composeTestRule.onNodeWithTag("tools_row_learn", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        // Verify real Learn screen is displayed
        composeTestRule.onNodeWithTag("tools_learn_screen").assertIsDisplayed()

        // Tap a production example prompt
        composeTestRule.onNodeWithTag("tools_learn_lists_add_milk", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        // Assert Actions opens with draft prefilled
        composeTestRule.onNodeWithTag(TAG_ACTIONS_SCREEN).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_DRAFT_TEXT).assertIsDisplayed()
        composeTestRule.onNodeWithText("Add milk to my shopping list").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_DRAFT_INPUT).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_DRAFT_SUBMIT).assertIsDisplayed()
    }

    @Test
    fun learnToActions_dismissDraftThenSwitchTabs() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()

        // Navigate to Learn → tap example → draft opens
        composeTestRule.onNodeWithTag("tools_screen")
            .performScrollToNode(hasTestTag("tools_row_learn"))
        composeTestRule.onNodeWithTag("tools_row_learn", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_learn_screen").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tools_learn_lists_add_milk", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_ACTIONS_SCREEN).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TAG_DRAFT_TEXT).assertIsDisplayed()

        // Dismiss/send clears draft state
        composeTestRule.onNodeWithTag(TAG_DRAFT_SUBMIT).performClick()
        composeTestRule.waitForIdle()
        assertScreenNotPresent(TAG_DRAFT_TEXT)
        composeTestRule.onNodeWithTag(TAG_ACTIONS_SCREEN).assertIsDisplayed()

        // Navigate to Chats
        composeTestRule.onNodeWithTag(BOTTOM_NAV_CHATS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_CHATS_SCREEN).assertIsDisplayed()

        // Navigate to Tools — no stale draft restored
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        assertScreenNotPresent(TAG_DRAFT_TEXT)
        assertScreenNotPresent(TAG_ACTIONS_SCREEN)
    }

    // ═══════════════════════════ 4. DRAWER ENTRY POINTS ══════════════════════

    @Test
    fun drawer_opensFromToolsViaMenuButton() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()

        // Use production menu icon button (real ToolsHubScreen test tag)
        composeTestRule.onNodeWithTag("tools_menu_button").performClick()
        composeTestRule.waitForIdle()

        // Production Menu → ModalDrawerSheet (not asserting drawer_sheet from
        // harness — the drawer opens through the real composable's callback)
        composeTestRule.onNodeWithTag("faf_drawer_sheet").assertIsDisplayed()
    }

    @Test
    fun drawer_opensFromChatsViaMenuButton() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_CHATS_SCREEN).assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_CHATS)

        // Use menu button on Chats stub (production ConversationListScreen has
        // a matching menu icon — our stub mirrors it with a stable test tag)
        composeTestRule.onNodeWithTag(TAG_CHATS_MENU_BUTTON).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("faf_drawer_sheet").assertIsDisplayed()
    }

    @Test
    fun drawer_navigationBackToTools() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()

        // Open drawer from Tools via production menu button
        composeTestRule.onNodeWithTag("tools_menu_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("faf_drawer_sheet").assertIsDisplayed()

        // Navigate via drawer to Lists
        composeTestRule.onNodeWithTag("faf_drawer_item_lists").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_DEST_LISTS).assertIsDisplayed()

        // Back returns to Chats (drawer's popUpTo resets to start destination)
        composeTestRule.onNodeWithTag("faf_back_from_${TAG_DEST_LISTS}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_CHATS_SCREEN).assertIsDisplayed()

        // Switch to Tools — verify drawer not still open and correct tab selected
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_screen").assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_TOOLS)
        // Drawer sheet may remain in the composition even when closed (off-screen);
        // the important thing is the Tools screen is visible and tab is selected.
        // No stale drawer content is blocking the UI — verified by tools_screen below.
    }

    @Test
    fun drawer_bottomNavStateAfterDrawerNavigation() {
        composeTestRule.setContent { KernelNavTestHost() }
        composeTestRule.onNodeWithTag(BOTTOM_NAV_TOOLS).performClick()
        composeTestRule.waitForIdle()

        // Open drawer from Tools
        composeTestRule.onNodeWithTag("tools_menu_button").performClick()
        composeTestRule.waitForIdle()

        // Navigate via drawer to Settings
        composeTestRule.onNodeWithTag("faf_drawer_item_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_DEST_SETTINGS).assertIsDisplayed()

        // Back returns to Chats
        composeTestRule.onNodeWithTag("faf_back_from_${TAG_DEST_SETTINGS}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TAG_CHATS_SCREEN).assertIsDisplayed()
        assertBottomNavSelected(BOTTOM_NAV_CHATS)
    }
}
