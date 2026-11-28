package com.kernel.ai.feature.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide feed of home-screen items the launcher confirmed as placed. Settings collects it to
 * show a snackbar; events with no live collector are dropped, which is acceptable for a
 * confirmation message.
 */
object HomeWidgetPinEvents {
    private val confirmedFlow = MutableSharedFlow<HomeItem>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val confirmed: SharedFlow<HomeItem> = confirmedFlow.asSharedFlow()

    internal fun emit(item: HomeItem) {
        confirmedFlow.tryEmit(item)
    }
}

/**
 * Success callback target for `AppWidgetManager.requestPinAppWidget` (#2194). Declared in the app
 * manifest as non-exported; only explicit intents built by [HomeWidgetPinning] reach it.
 */
class HomeWidgetPinConfirmedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HomeWidgetPinning.ACTION_PIN_CONFIRMED) return
        val item = intent.getStringExtra(HomeWidgetPinning.EXTRA_ITEM)
            ?.let { name -> HomeItem.entries.firstOrNull { it.name == name } }
            ?: return
        HomeWidgetPinEvents.emit(item)
    }
}
