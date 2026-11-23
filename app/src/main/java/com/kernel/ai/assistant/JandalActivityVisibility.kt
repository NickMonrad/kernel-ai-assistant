package com.kernel.ai.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * #2081: whether any Jandal activity is started, i.e. has a visible window. A wake with no visible
 * window cannot start an activity without a background launch, so the wake route avoids the direct
 * start then. Counts onStart/onStop across the process; [register] must run before any activity starts.
 */
object JandalActivityVisibility {
    private val started = AtomicInteger(0)

    val isVisible: Boolean get() = started.get() > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                started.incrementAndGet()
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                started.updateAndGet { if (it > 0) it - 1 else 0 }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
