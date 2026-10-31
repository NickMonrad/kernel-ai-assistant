package com.kernel.ai.debug.inflect

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Suppresses app startup only when the single synthetic Inflect runtime test is selected by class.
 * The app's regular startup path remains intact for every other instrumentation invocation.
 */
class InflectSpikeAndroidJUnitRunner : AndroidJUnitRunner() {
    private var suppressTargetApplicationOnCreate = false

    internal var targetApplicationOnCreateSuppressed: Boolean = false
        private set

    override fun onCreate(arguments: Bundle?) {
        val target = targetContext
        suppressTargetApplicationOnCreate = InflectProbeStartupIsolation
            .shouldSuppressApplicationOnCreate(
                targetPackage = target.packageName,
                targetIsDebuggable =
                    target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                selectedTestClass = arguments?.getString(ARG_CLASS),
            )
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        if (suppressTargetApplicationOnCreate &&
            app.packageName == InflectProbeStartupIsolation.DEBUG_PACKAGE
        ) {
            targetApplicationOnCreateSuppressed = true
            return
        }
        super.callApplicationOnCreate(app)
    }

    private companion object {
        const val ARG_CLASS = "class"
    }
}
