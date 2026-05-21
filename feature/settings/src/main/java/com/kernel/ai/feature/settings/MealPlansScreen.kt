package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.mealplan.FavouriteRecipeBrowserItem
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshot
import com.kernel.ai.core.memory.mealplan.MealPlanSnapshotDay
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class IngredientListRequest(
    val sessionId: String,
    val dayIndex: Int,
    val recipeKey: String,
    val recipeTitle: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealPlansScreen(
    onBack: () -> Unit = {},
    viewModel: MealPlansViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingIngredientListRequest by remember { mutableStateOf<IngredientListRequest?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meal plans & favourites") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TabRow(selectedTabIndex = if (uiState.selectedTab == MealPlansBrowserTab.RECENT_PLANS) 0 else 1) {
                Tab(
                    selected = uiState.selectedTab == MealPlansBrowserTab.RECENT_PLANS,
                    onClick = { viewModel.setTab(MealPlansBrowserTab.RECENT_PLANS) },
                    text = { Text("Recent plans") },
                )
                Tab(
                    selected = uiState.selectedTab == MealPlansBrowserTab.FAVOURITES,
                    onClick = { viewModel.setTab(MealPlansBrowserTab.FAVOURITES) },
                    text = { Text("Favourites") },
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                when (uiState.selectedTab) {
                    MealPlansBrowserTab.RECENT_PLANS -> RecentPlansTab(
                        plans = uiState.recentPlans,
                        query = uiState.recentPlansQuery,
                        expandedPlanIds = uiState.expandedPlanIds,
                        expandedDetailIds = uiState.expandedDetailIds,
                        pendingRecipeKeys = uiState.pendingRecipeKeys,
                        onQueryChange = viewModel::updateRecentPlansQuery,
                        onTogglePlan = viewModel::togglePlanExpanded,
                        onToggleDay = viewModel::toggleDayExpanded,
                        onToggleFavourite = viewModel::toggleDayFavourite,
                        onAddRecipeToLists = viewModel::addRecipeToLists,
                        onAddIngredientsToList = { sessionId, dayIndex, recipeKey, recipeTitle ->
                            pendingIngredientListRequest = IngredientListRequest(
                                sessionId = sessionId,
                                dayIndex = dayIndex,
                                recipeKey = recipeKey,
                                recipeTitle = recipeTitle,
                            )
                        },
                    )

                    MealPlansBrowserTab.FAVOURITES -> FavouriteRecipesTab(
                        favourites = uiState.favouriteRecipes,
                        query = uiState.favouriteRecipesQuery,
                        expandedDetailIds = uiState.expandedDetailIds,
                        pendingRecipeKeys = uiState.pendingRecipeKeys,
                        onQueryChange = viewModel::updateFavouriteRecipesQuery,
                        onToggleExpanded = viewModel::toggleFavouriteExpanded,
                        onRemoveFavourite = viewModel::removeFavourite,
                        onAddRecipeToLists = viewModel::addRecipeToLists,
                        onAddIngredientsToList = { sessionId, dayIndex, recipeKey, recipeTitle ->
                            pendingIngredientListRequest = IngredientListRequest(
                                sessionId = sessionId,
                                dayIndex = dayIndex,
                                recipeKey = recipeKey,
                                recipeTitle = recipeTitle,
                            )
                        },
                    )
                }
            }
        }
    }

    pendingIngredientListRequest?.let { request ->
        IngredientListDialog(
            recipeTitle = request.recipeTitle,
            lists = uiState.availableLists,
            onDismiss = { pendingIngredientListRequest = null },
            onConfirm = { listId ->
                pendingIngredientListRequest = null
                viewModel.addIngredientsToList(
                    sessionId = request.sessionId,
                    dayIndex = request.dayIndex,
                    recipeKey = request.recipeKey,
                    listId = listId,
                )
            },
        )
    }
}

@Composable
private fun RecentPlansTab(
    plans: List<MealPlanSnapshot>,
    query: String,
    expandedPlanIds: Set<String>,
    expandedDetailIds: Set<String>,
    pendingRecipeKeys: Set<String>,
    onQueryChange: (String) -> Unit,
    onTogglePlan: (String) -> Unit,
    onToggleDay: (String, Int) -> Unit,
    onToggleFavourite: (String, MealPlanSnapshotDay) -> Unit,
    onAddRecipeToLists: (String, Int, String) -> Unit,
    onAddIngredientsToList: (String, Int, String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BrowserSearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = "Search recent recipes",
        )
        Box(modifier = Modifier.weight(1f)) {
            when {
                plans.isEmpty() && query.isNotBlank() -> EmptyMealPlansState(
                    title = "No matching recipes",
                    body = "Try a different recipe name to search your recent meal plans.",
                )

                plans.isEmpty() -> EmptyMealPlansState(
                    title = "No completed meal plans yet",
                    body = "Finish a meal plan in chat and it will appear here for browsing and favouriting later.",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(plans, key = { it.sessionId }) { plan ->
                        val expanded = plan.sessionId in expandedPlanIds
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTogglePlan(plan.sessionId) },
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(plan.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = planSummary(plan),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (plan.dietaryRestrictions.isNotEmpty()) {
                                            Text(
                                                text = plan.dietaryRestrictions.joinToString(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (expanded) "Collapse plan" else "Expand plan",
                                    )
                                }

                                if (expanded) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    plan.days.forEachIndexed { index, day ->
                                        RecentPlanDayCard(
                                            sessionId = plan.sessionId,
                                            day = day,
                                            expanded = MealPlansViewModel.dayDetailId(plan.sessionId, day.dayIndex) in expandedDetailIds,
                                            isPending = day.recipeKey != null && day.recipeKey in pendingRecipeKeys,
                                            onToggleExpanded = { onToggleDay(plan.sessionId, day.dayIndex) },
                                            onToggleFavourite = { onToggleFavourite(plan.sessionId, day) },
                                            onAddRecipeToLists = day.recipeKey?.let { recipeKey ->
                                                { onAddRecipeToLists(plan.sessionId, day.dayIndex, recipeKey) }
                                            },
                                            onAddIngredientsToList = day.recipeKey?.let { recipeKey ->
                                                {
                                                    onAddIngredientsToList(
                                                        plan.sessionId,
                                                        day.dayIndex,
                                                        recipeKey,
                                                        day.currentRecipe?.title ?: day.title ?: "Recipe",
                                                    )
                                                }
                                            },
                                        )
                                        if (index != plan.days.lastIndex) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentPlanDayCard(
    sessionId: String,
    day: MealPlanSnapshotDay,
    expanded: Boolean,
    isPending: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleFavourite: () -> Unit,
    onAddRecipeToLists: (() -> Unit)?,
    onAddIngredientsToList: (() -> Unit)?,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Day ${day.dayIndex + 1}: ${day.title ?: "Meal"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    day.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (day.proteinTags.isNotEmpty()) {
                        Text(
                            text = day.proteinTags.joinToString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (day.recipeKey != null) {
                    IconButton(onClick = onToggleFavourite, enabled = !isPending) {
                        Icon(
                            imageVector = if (day.isFavouriteRecipe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (day.isFavouriteRecipe) "Unfavourite recipe" else "Favourite recipe",
                            tint = if (day.isFavouriteRecipe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (day.currentRecipe != null) {
                    IconButton(onClick = onToggleExpanded) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Hide recipe" else "Show recipe",
                        )
                    }
                }
            }

            val recipe = day.currentRecipe
            if (expanded && recipe != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                RecipeDetails(
                    recipe = recipe,
                    actionsEnabled = !isPending,
                    onAddRecipeToLists = onAddRecipeToLists,
                    onAddIngredientsToList = onAddIngredientsToList,
                )
            }
        }
    }
}

@Composable
private fun FavouriteRecipesTab(
    favourites: List<FavouriteRecipeBrowserItem>,
    query: String,
    expandedDetailIds: Set<String>,
    pendingRecipeKeys: Set<String>,
    onQueryChange: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onRemoveFavourite: (String) -> Unit,
    onAddRecipeToLists: (String, Int, String) -> Unit,
    onAddIngredientsToList: (String, Int, String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BrowserSearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = "Search favourite recipes",
        )
        Box(modifier = Modifier.weight(1f)) {
            when {
                favourites.isEmpty() && query.isNotBlank() -> EmptyMealPlansState(
                    title = "No matching favourites",
                    body = "Try a different recipe name to search your favourites.",
                )

                favourites.isEmpty() -> EmptyMealPlansState(
                    title = "No favourite recipes yet",
                    body = "Favourite a recipe from a recent meal plan and it will be collected here.",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(favourites, key = { it.recipeKey }) { favourite ->
                        val expanded = MealPlansViewModel.favouriteDetailId(favourite.recipeKey) in expandedDetailIds
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = favourite.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        favourite.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                                            Text(
                                                text = summary,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (favourite.proteinTags.isNotEmpty()) {
                                            Text(
                                                text = favourite.proteinTags.joinToString(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (favourite.recentPlanDisplayName != null) {
                                            val dayLabel = favourite.recentDayIndex?.let { " · Day ${it + 1}" }.orEmpty()
                                            Text(
                                                text = "Saved from ${favourite.recentPlanDisplayName}$dayLabel",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        } else if (favourite.recipe == null) {
                                            Text(
                                                text = "Recipe details are only available while the source meal plan is still in your recent history.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onRemoveFavourite(favourite.recipeKey) },
                                        enabled = favourite.recipeKey !in pendingRecipeKeys,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = "Remove favourite",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    if (favourite.recipe != null) {
                                        IconButton(onClick = { onToggleExpanded(favourite.recipeKey) }) {
                                            Icon(
                                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = if (expanded) "Hide recipe" else "Show recipe",
                                            )
                                        }
                                    }
                                }

                                val recipe = favourite.recipe
                                if (expanded && recipe != null) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    val isPending = favourite.recipeKey in pendingRecipeKeys
                                    val sessionId = favourite.recentPlanSessionId
                                    val dayIndex = favourite.recentDayIndex
                                    RecipeDetails(
                                        recipe = recipe,
                                        actionsEnabled = !isPending,
                                        onAddRecipeToLists = if (sessionId != null && dayIndex != null) {
                                            { onAddRecipeToLists(sessionId, dayIndex, favourite.recipeKey) }
                                        } else {
                                            null
                                        },
                                        onAddIngredientsToList = if (sessionId != null && dayIndex != null) {
                                            {
                                                onAddIngredientsToList(
                                                    sessionId,
                                                    dayIndex,
                                                    favourite.recipeKey,
                                                    favourite.title,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeDetails(
    recipe: RecipeDraft,
    actionsEnabled: Boolean,
    onAddRecipeToLists: (() -> Unit)?,
    onAddIngredientsToList: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Serves ${recipe.servings}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onAddRecipeToLists != null || onAddIngredientsToList != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                onAddRecipeToLists?.let { action ->
                    Button(
                        onClick = action,
                        enabled = actionsEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text("Add recipe to Lists")
                    }
                }
                onAddIngredientsToList?.let { action ->
                    Button(
                        onClick = action,
                        enabled = actionsEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text("Add ingredients to list")
                    }
                }
            }
        }
        Text("Ingredients", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        recipe.ingredients.forEach { ingredient ->
            Text("• ${ingredient.originalText}", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Method", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        recipe.methodSteps.forEach { step ->
            Text("${step.stepNumber}. ${step.text}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BrowserSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(placeholder) },
    )
}

@Composable
private fun IngredientListDialog(
    recipeTitle: String,
    lists: List<ListNameEntity>,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var selectedListId by remember(recipeTitle) { mutableStateOf(lists.firstOrNull()?.id) }
    LaunchedEffect(lists) {
        selectedListId = when {
            lists.isEmpty() -> null
            selectedListId == null -> lists.first().id
            lists.none { it.id == selectedListId } -> lists.first().id
            else -> selectedListId
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ingredients to list") },
        text = {
            if (lists.isEmpty()) {
                Text("Create a list first, then you can add the ingredients from $recipeTitle into it.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = recipeTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    lists.forEach { list ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedListId = list.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedListId == list.id,
                                onClick = { selectedListId = list.id },
                            )
                            Text(list.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedListId?.let(onConfirm) },
                enabled = lists.any { it.id == selectedListId },
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (lists.isEmpty()) "Close" else "Cancel")
            }
        },
    )
}

@Composable
private fun EmptyMealPlansState(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Bookmarks,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun planSummary(plan: MealPlanSnapshot): String = buildString {
    val completedLabel = plan.completedAt?.let(::formatDate) ?: formatDate(plan.updatedAt)
    val totalMeals = plan.daysCount ?: plan.days.size
    append(completedLabel)
    append(" · ")
    append(totalMeals)
    append(if (totalMeals == 1) " meal" else " meals")
    plan.peopleCount?.let {
        append(" · ")
        append(it)
        append(if (it == 1) " person" else " people")
    }
}

private fun formatDate(epochMillis: Long): String = DATE_FORMATTER.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
)

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")