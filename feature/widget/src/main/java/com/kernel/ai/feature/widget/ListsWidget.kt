package com.kernel.ai.feature.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.clickable
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.DayNightColorProvider
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import com.kernel.ai.core.ui.theme.CharcoalDark
import com.kernel.ai.core.ui.theme.FernGreen
import com.kernel.ai.core.ui.theme.FernGreenLight
import com.kernel.ai.core.ui.theme.SandLight
import dagger.hilt.android.EntryPointAccessors

@SuppressLint("RestrictedApi")
private val SurfaceColor = DayNightColorProvider(day = SandLight.copy(alpha = 0.94f), night = CharcoalDark.copy(alpha = 0.94f))
@SuppressLint("RestrictedApi")
private val OnSurfaceColor = DayNightColorProvider(day = CharcoalDark, night = SandLight)
@SuppressLint("RestrictedApi")
private val HintColor = DayNightColorProvider(day = CharcoalDark.copy(alpha = 0.6f), night = SandLight.copy(alpha = 0.6f))
@SuppressLint("RestrictedApi")
private val AccentColor = DayNightColorProvider(day = FernGreen, night = FernGreenLight)

/**
 * Home-screen Glance widget bound to one existing local Jandal list.
 *
 * The widget never holds its own copy of the list data: on every render (including refreshes
 * triggered by [ListsWidgetReceiver] after a list mutation) it reads straight from the shared
 * Room store via [ListsWidgetDataEntryPoint]. There is no polling and no periodic worker — the
 * only refresh trigger is the `LISTS_DATA_CHANGED` broadcast from a successful mutation.
 */
class ListsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = (id as AppWidgetId).appWidgetId
        val config = ListsWidgetConfig.from(context)
        val entry = widgetDataEntryPoint(context)
        val initialProjection = loadProjection(config, id, entry)
        val projectionFlow = observeListsWidgetProjection(
            selectedListIds = config.observeSelectedListId(appWidgetId),
            listMetadata = entry.listNameDao().observeAll(),
            listItems = entry.listItemDao()::observeByList,
        )

        provideContent {
            val projection = projectionFlow.collectAsState(initial = initialProjection).value
            ListsWidgetContent(projection)
        }
    }

    private fun widgetDataEntryPoint(context: Context): ListsWidgetDataEntryPoint =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ListsWidgetDataEntryPoint::class.java,
        )

    @SuppressLint("RestrictedApi")
    private suspend fun loadProjection(
        config: ListsWidgetConfig,
        id: GlanceId,
        entry: ListsWidgetDataEntryPoint,
    ): ListsWidgetProjection {
        val appWidgetId = (id as AppWidgetId).appWidgetId
        val selectedListId = config.getSelectedListId(appWidgetId)

        return if (selectedListId > 0L) {
            val name = entry.listNameDao().getById(selectedListId)
            val items = entry.listItemDao().getByList(selectedListId)
            projectListsWidget(selectedListId, name, items)
        } else {
            ListsWidgetProjection.NotConfigured
        }
    }
    /** Clear per-widget config when the instance is deleted so storage stays bounded. */
    @SuppressLint("RestrictedApi")
    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        val appWidgetId = (glanceId as AppWidgetId).appWidgetId
        ListsWidgetConfig.from(context).clear(appWidgetId)
    }
}

/**
 * Glance action callback fired when a Lists widget is tapped.
 *
 * Glance 1.1.1 widget clicks cannot carry intent extras, but an [ActionCallback] receives the
 * widget's [GlanceId] (which is its [AppWidgetId]); from that we resolve the bound list and either
 * start the existing list detail route or reopen the configuration activity for this widget
 * instance when its bound list is unavailable. This keeps the per-widget route local to the widget
 * and out of any global state, so multiple widgets route to their own lists.
 */
internal sealed interface ListsWidgetLaunchDestination {
    data class Detail(val route: String) : ListsWidgetLaunchDestination
    data class Configure(val appWidgetId: Int) : ListsWidgetLaunchDestination
}

class ListsWidgetLaunchCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        @SuppressLint("RestrictedApi")
        val appWidgetId = (glanceId as AppWidgetId).appWidgetId
        val selectedListId = ListsWidgetConfig.from(context).getSelectedListId(appWidgetId)
        val listExists = if (selectedListId > 0L) {
            val entry = EntryPointAccessors.fromApplication(
                context.applicationContext,
                ListsWidgetDataEntryPoint::class.java,
            )
            entry.listNameDao().getById(selectedListId) != null
        } else {
            false
        }
        when (val destination = launchDestinationFor(selectedListId, listExists, appWidgetId)) {
            is ListsWidgetLaunchDestination.Detail -> {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setClassName(context.packageName, "com.kernel.ai.MainActivity")
                    putExtra("navigation_route", destination.route)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(intent)
            }
            is ListsWidgetLaunchDestination.Configure -> {
                val configureIntent = Intent().apply {
                    setClassName(context.packageName, ListsWidgetConfigureActivity::class.java.name)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, destination.appWidgetId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(configureIntent)
            }
        }
    }
    companion object {
        /** Detail route for an existing bound list, or configuration otherwise. */
        internal fun launchDestinationFor(
            selectedListId: Long,
            listExists: Boolean,
            appWidgetId: Int,
        ): ListsWidgetLaunchDestination =
            if (selectedListId > 0L && listExists) {
                ListsWidgetLaunchDestination.Detail("lists/$selectedListId")
            } else {
                ListsWidgetLaunchDestination.Configure(appWidgetId)
            }
    }
}

@Composable
private fun ListsWidgetContent(projection: ListsWidgetProjection) {
    GlanceTheme {
        val widgetSize = LocalSize.current
        val activeItemCount = when (projection) {
            is ListsWidgetProjection.Configured -> projection.activeItems.size
            is ListsWidgetProjection.Archived -> projection.activeItems.size
            else -> 0
        }
        val itemLayout = calculateActiveItemLayout(widgetSize.height.value, activeItemCount)
        val rootAction = actionRunCallback(ListsWidgetLaunchCallback::class.java)

        val title = when (projection) {
            is ListsWidgetProjection.Configured -> projection.listName
            is ListsWidgetProjection.Empty -> projection.listName
            is ListsWidgetProjection.Archived -> projection.listName
            is ListsWidgetProjection.Missing -> "List unavailable"
            is ListsWidgetProjection.NotConfigured -> "Choose a list"
        }

        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(widgetSize.height)
                .background(SurfaceColor)
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(rootAction),
        ) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OnSurfaceColor),
                    modifier = GlanceModifier.fillMaxWidth(),
                )

                Spacer(GlanceModifier.height(8.dp))

                when (projection) {
                    is ListsWidgetProjection.Configured -> ActiveItems(projection.activeItems, itemLayout)
                    is ListsWidgetProjection.Archived -> {
                        Text("Archived", style = TextStyle(fontSize = 12.sp, color = HintColor))
                        Spacer(GlanceModifier.height(4.dp))
                        ActiveItems(projection.activeItems, itemLayout, accent = true)
                    }
                    is ListsWidgetProjection.Empty ->
                        Text("No active items", style = TextStyle(fontSize = 14.sp, color = HintColor))
                    is ListsWidgetProjection.Missing ->
                        Text(
                            "This list was deleted. Open Lists to choose another.",
                            style = TextStyle(fontSize = 14.sp, color = HintColor),
                        )
                    is ListsWidgetProjection.NotConfigured ->
                        Text("Tap to choose a list.", style = TextStyle(fontSize = 14.sp, color = HintColor))
                }
            }
        }
    }
}

@Composable
private fun ActiveItems(
    items: List<String>,
    layout: ActiveItemLayout,
    accent: Boolean = false,
) {
    items.take(layout.visibleCount).forEach { item ->
        Text(
            text = "• $item",
            style = TextStyle(
                fontSize = 14.sp,
                color = if (accent) AccentColor else OnSurfaceColor,
            ),
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
    if (layout.overflowCount > 0) {
        Text(
            text = "+${layout.overflowCount} more",
            style = TextStyle(fontSize = 12.sp, color = HintColor),
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
}
