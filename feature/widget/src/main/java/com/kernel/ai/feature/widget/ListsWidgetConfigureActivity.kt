package com.kernel.ai.feature.widget

import android.appwidget.AppWidgetManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.ui.theme.KernelAITheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Configuration screen for the Lists widget.
 *
 * Android shows this activity when a user drops the Lists widget onto the home screen. They pick
 * exactly one existing local list; the selection is persisted per-widget in [ListsWidgetConfig]
 * and the widget renders that list. The widget never creates its own list — it always binds to a
 * list that already exists in the app.
 *
 * On success it returns [android.appwidget.AppWidgetManager.RESULT_OK] with the widget id so the
 * system proceeds to place the widget and fire its initial `APPWIDGET_UPDATE`. On cancel/back it
 * returns [android.appwidget.AppWidgetManager.RESULT_CANCELED] so no widget is placed.
 */
@AndroidEntryPoint
class ListsWidgetConfigureActivity : ComponentActivity() {

    @Inject
    lateinit var listNameDao: ListNameDao

    private val appWidgetId by lazy {
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default to CANCELED (with the widget id attached) so any early exit — invalid id or an
        // error before a selection — never leaves the launcher with a half-placed, unconfigured widget.
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            KernelAITheme {
                ListsWidgetConfigScreen(
                    listsFlow = listNameDao.observeActiveLists(),
                    onSelect = { persistAndFinish(it) },
                    onCancel = { finishWith(RESULT_CANCELED) },
                )
            }
        }
    }

    private fun persistAndFinish(listId: Long) {
        // Persist synchronously before the required initial render, so a failed render leaves a
        // durable binding without reporting a successful configuration.
        lifecycleScope.launch {
            val result = persistSelectionAndResult(
                ListsWidgetConfig.from(this@ListsWidgetConfigureActivity),
                appWidgetId,
                listId,
            ) {
                val glanceId = GlanceAppWidgetManager(this@ListsWidgetConfigureActivity)
                    .getGlanceIdBy(intent)
                    ?: error("Unable to resolve Glance ID from widget configuration intent")
                ListsWidget().update(this@ListsWidgetConfigureActivity, glanceId)
            }
            finishWith(result)
        }
    }

    private fun finishWith(result: Int) {
        setResult(result, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListsWidgetConfigScreen(
    listsFlow: Flow<List<ListNameEntity>>,
    onSelect: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    val lists by listsFlow.collectAsStateWithLifecycle(emptyList())
    var selectedId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose a list") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { selectedId?.let(onSelect) },
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Add to Home screen")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (lists.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No lists yet. Create one in Lists, then add this widget.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(lists, key = { it.id }) { list ->
                        val isSelected = selectedId == list.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = list.id }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedId = list.id },
                            )
                            Text(
                                text = list.name.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Persists the selected list for [appWidgetId] and returns the config result.
 *
 * The selection is **synchronously committed** to disk before any [render] work, so the binding is
 * durable even if the process dies immediately after the config activity returns. If the commit
 * fails, [android.app.Activity.RESULT_CANCELED] is returned so the launcher never places an
 * unconfigured widget (which would show "Choose a list").
 *
 * The initial render is required for success: if [render] throws, [android.app.Activity.RESULT_CANCELED]
 * is returned after preserving the committed binding. This prevents the launcher from placing a
 * widget that still displays "Choose a list" after successful configuration.
 */
internal suspend fun persistSelectionAndResult(
    config: ListsWidgetConfig,
    appWidgetId: Int,
    listId: Long,
    render: suspend () -> Unit,
): Int {
    val committed = config.setSelectedListIdSync(appWidgetId, listId)
    if (!committed) return Activity.RESULT_CANCELED
    try {
        render()
    } catch (_: Throwable) {
        // Keep the durable binding, but do not report success while the widget still renders stale
        // "Choose a list" content.
        return Activity.RESULT_CANCELED
    }
    return Activity.RESULT_OK
}
