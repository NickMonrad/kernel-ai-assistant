package com.kernel.ai.feature.settings

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/** Home-screen items the app can offer to pin (#2194). */
enum class HomeItem(val title: String, val description: String, val fallbackHowTo: String) {
    JANDAL_WIDGET(
        title = "Jandal widget",
        description = "Voice and text quick actions for Jandal",
        fallbackHowTo = "Long-press an empty spot on your home screen, tap Widgets, then pick Jandal.",
    ),
    LISTS_WIDGET(
        title = "Lists widget",
        description = "Items from one of your lists at a glance",
        fallbackHowTo = "Long-press an empty spot on your home screen, tap Widgets, pick Jandal, then Lists.",
    ),
    LISTS_SHORTCUT(
        title = "Lists shortcut",
        description = "Open Lists straight from a home-screen icon",
        fallbackHowTo = "Long-press the Jandal icon in your app drawer and drag Lists onto your home screen.",
    ),
}

/**
 * Requests to pin Jandal's home-screen items. Widgets go through
 * [AppWidgetManager.requestPinAppWidget] with a success callback broadcast to
 * [HomeWidgetPinConfirmedReceiver]; the shortcut reuses [ListsShortcut.requestPin].
 */
object HomeWidgetPinning {
    const val ACTION_PIN_CONFIRMED = "com.kernel.ai.feature.settings.action.HOME_WIDGET_PIN_CONFIRMED"
    const val EXTRA_ITEM = "home_item"

    private const val JANDAL_PROVIDER = "com.kernel.ai.feature.widget.KernelWidgetReceiver"
    private const val LISTS_PROVIDER = "com.kernel.ai.feature.widget.ListsWidgetReceiver"

    /** Shared outcome for every home-screen item; [Requested] means the launcher accepted the request. */
    enum class PinResult { Requested, Rejected, Unsupported }

    fun request(context: Context, item: HomeItem): PinResult = when (item) {
        HomeItem.JANDAL_WIDGET -> requestWidgetPin(
            context,
            item,
            ComponentName(context, JANDAL_PROVIDER),
            confirmationPendingIntent(context, item),
        )
        HomeItem.LISTS_WIDGET -> requestWidgetPin(
            context,
            item,
            ComponentName(context, LISTS_PROVIDER),
            confirmationPendingIntent(context, item),
        )
        HomeItem.LISTS_SHORTCUT -> when (ListsShortcut.requestPin(context)) {
            ListsShortcut.PinResult.Requested -> PinResult.Requested
            ListsShortcut.PinResult.Rejected -> PinResult.Rejected
            ListsShortcut.PinResult.Unsupported -> PinResult.Unsupported
        }
    }

    /**
     * Returns [PinResult.Requested] only when the launcher accepted the request. A launcher that
     * throws or declines is [PinResult.Rejected], never a silent success. The widget count before the
     * request is recorded so [claimConfirmation] can tell a real placement from a stale callback.
     */
    internal fun requestWidgetPin(
        context: Context,
        item: HomeItem,
        provider: ComponentName,
        successCallback: PendingIntent,
    ): PinResult {
        val manager = context.getSystemService(AppWidgetManager::class.java) ?: return PinResult.Unsupported
        if (!manager.isRequestPinAppWidgetSupported) return PinResult.Unsupported
        // The launcher shows one pin dialog at a time, so a new request supersedes earlier pending ones.
        pendingWidgetPins.clear()
        pendingWidgetPins[item] = manager.getAppWidgetIds(provider).size
        val accepted = runCatching { manager.requestPinAppWidget(provider, null, successCallback) }
            .getOrDefault(false)
        if (!accepted) pendingWidgetPins.remove(item)
        return if (accepted) PinResult.Requested else PinResult.Rejected
    }

    fun resultMessage(item: HomeItem, result: PinResult): String = when (result) {
        PinResult.Requested -> "${item.title} request sent"
        PinResult.Rejected -> "Couldn't add the ${item.title} — check your launcher's home-screen settings"
        PinResult.Unsupported -> "Your launcher can't add this from an app. ${item.fallbackHowTo}"
    }

    fun addedMessage(item: HomeItem): String = "${item.title} added to your home screen"

    /**
     * Success callback for [AppWidgetManager.requestPinAppWidget]. Extras are fixed per item, so the
     * PendingIntent is immutable: the launcher must not rewrite them.
     */
    internal const val CONFIRMATION_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    internal fun confirmationPendingIntent(context: Context, item: HomeItem): PendingIntent {
        val intent = Intent(ACTION_PIN_CONFIRMED)
        intent.setClass(context, HomeWidgetPinConfirmedReceiver::class.java)
        intent.putExtra(EXTRA_ITEM, item.name)
        return PendingIntent.getBroadcast(context, item.ordinal, intent, CONFIRMATION_FLAGS)
    }

    /** Item named by a confirmation broadcast; null for another action, a missing extra, or an unknown name. */
    internal fun confirmedItem(action: String?, itemName: String?): HomeItem? {
        if (action != ACTION_PIN_CONFIRMED || itemName == null) return null
        return HomeItem.entries.firstOrNull { it.name == itemName }
    }

    /** Widget requests the launcher accepted and has not yet confirmed, with each widget count before the request. */
    private val pendingWidgetPins = ConcurrentHashMap<HomeItem, Int>()

    /** Null for the shortcut, which has no AppWidget provider. */
    internal fun providerFor(context: Context, item: HomeItem): ComponentName? = when (item) {
        HomeItem.JANDAL_WIDGET -> ComponentName(context, JANDAL_PROVIDER)
        HomeItem.LISTS_WIDGET -> ComponentName(context, LISTS_PROVIDER)
        HomeItem.LISTS_SHORTCUT -> null
    }

    /**
     * Claims a success callback for [item] only if it was requested and its widget count has grown.
     * Launchers can flush success callbacks for other, earlier requests; those are ignored here.
     */
    internal fun claimConfirmation(context: Context, item: HomeItem): Boolean {
        val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
        val provider = providerFor(context, item) ?: return false
        return claimConfirmation(item, manager.getAppWidgetIds(provider).size)
    }

    internal fun claimConfirmation(item: HomeItem, widgetCount: Int): Boolean {
        val before = pendingWidgetPins[item] ?: return false
        if (widgetCount <= before) return false
        pendingWidgetPins.remove(item)
        return true
    }

    internal fun clearPendingWidgetPins() = pendingWidgetPins.clear()
}
