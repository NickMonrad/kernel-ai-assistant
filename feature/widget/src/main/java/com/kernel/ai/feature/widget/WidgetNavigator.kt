package com.kernel.ai.feature.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import javax.inject.Inject

/**
 * Thin navigation abstraction used by [VoiceCommandHandler] to open activities.
 * Extracted so that unit tests can mock it without needing a real Android runtime.
 */
interface WidgetNavigator {
    fun navigateToChat(context: Context, input: String)
    fun navigateToActions(context: Context, input: String)
}

class DefaultWidgetNavigator @Inject constructor() : WidgetNavigator {
    private val mainActivityComponent = ComponentName("com.kernel.ai", "com.kernel.ai.MainActivity")

    override fun navigateToChat(context: Context, input: String) {
        val intent = Intent().apply {
            component = mainActivityComponent
            putExtra("chat_input", input)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun navigateToActions(context: Context, input: String) {
        val intent = Intent().apply {
            component = mainActivityComponent
            putExtra("quick_action_input", input)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
