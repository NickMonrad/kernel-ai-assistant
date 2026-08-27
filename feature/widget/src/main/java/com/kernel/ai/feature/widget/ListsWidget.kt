package com.kernel.ai.feature.widget

import android.content.ComponentName
import android.annotation.SuppressLint
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.DayNightColorProvider
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import com.kernel.ai.core.ui.theme.CharcoalDark
import com.kernel.ai.core.ui.theme.FernGreen
import com.kernel.ai.core.ui.theme.FernGreenLight
import com.kernel.ai.core.ui.theme.SandLight
import dagger.hilt.android.EntryPointAccessors

/** Maximum number of active items rendered before a "+N more" hint. */
private const val MAX_ITEMS = 8

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

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = (id as AppWidgetId).appWidgetId
        val selectedListId = ListsWidgetConfig.from(context).getSelectedListId(appWidgetId)

        val projection = if (selectedListId > 0L) {
            val entry = EntryPointAccessors.fromApplication(
                context.applicationContext,
                ListsWidgetDataEntryPoint::class.java,
            )
            val name = entry.listNameDao().getById(selectedListId)
            val items = entry.listItemDao().getByList(selectedListId)
            projectListsWidget(selectedListId, name, items)
        } else {
            ListsWidgetProjection.NotConfigured
        }

        ListsWidgetConfig.from(context).setLastRoute(routeFor(projection))
        provideContent { ListsWidgetContent(projection) }
    }
}

/**
 * Deep-link route for a widget tap: the bound list when configured, else the Lists overview.
 *
 * Glance 1.1.1 widget clicks can only launch an Activity by class (no intent extras), so the
 * route is persisted via [ListsWidgetConfig.setLastRoute] and consumed by MainActivity on launch.
 */
private fun routeFor(projection: ListsWidgetProjection): String =
    if (projection is ListsWidgetProjection.Configured ||
        projection is ListsWidgetProjection.Empty ||
        projection is ListsWidgetProjection.Archived
    ) {
        "lists/${projection.listId}"
    } else {
        "lists"
    }

@Composable
private fun ListsWidgetContent(projection: ListsWidgetProjection) {
    GlanceTheme {
        val context = LocalContext.current
        val rootAction = actionStartActivity(ComponentName(context.packageName, "com.kernel.ai.MainActivity"))

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
                .wrapContentHeight()
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
                    is ListsWidgetProjection.Configured -> ActiveItems(projection.activeItems)
                    is ListsWidgetProjection.Archived -> {
                        Text("Archived", style = TextStyle(fontSize = 12.sp, color = HintColor))
                        Spacer(GlanceModifier.height(4.dp))
                        ActiveItems(projection.activeItems, accent = true)
                    }
                    is ListsWidgetProjection.Empty ->
                        Text("No active items", style = TextStyle(fontSize = 14.sp, color = HintColor))
                    is ListsWidgetProjection.Missing ->
                        Text(
                            "This list was deleted. Open Lists to choose another.",
                            style = TextStyle(fontSize = 14.sp, color = HintColor),
                        )
                    is ListsWidgetProjection.NotConfigured ->
                        Text("Tap to open your lists.", style = TextStyle(fontSize = 14.sp, color = HintColor))
                }
            }
        }
    }
}

@Composable
private fun ActiveItems(items: List<String>, accent: Boolean = false) {
    items.take(MAX_ITEMS).forEach { item ->
        Text(
            text = "• $item",
            style = TextStyle(
                fontSize = 14.sp,
                color = if (accent) AccentColor else OnSurfaceColor,
            ),
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
    if (items.size > MAX_ITEMS) {
        Text(
            text = "+${items.size - MAX_ITEMS} more",
            style = TextStyle(fontSize = 12.sp, color = HintColor),
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
}
