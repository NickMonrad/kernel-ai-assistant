package com.kernel.ai.debug.inflect

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Allows the Inflect runtime probe only on its isolated package, while retaining the main-branch
 * exact-class guard for Application startup suppression.
 */
class InflectSpikeAndroidJUnitRunner : AndroidJUnitRunner() {
    private var suppressTargetApplicationOnCreate = false

    internal var targetApplicationOnCreateSuppressed: Boolean = false
        private set

    override fun onCreate(arguments: Bundle?) {
        val target = targetContext
        val selectedTestClass = arguments?.getString(ARG_CLASS)
        val targetIsDebuggable =
            target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (InflectProbeStartupIsolation.isRuntimeTestSelected(selectedTestClass)) {
            check(
                InflectProbeStartupIsolation.shouldAllowRuntimeTest(
                    targetPackage = target.packageName,
                    selectedTestClass = selectedTestClass,
                ),
            ) {
                "InflectSpikeReceiverRuntimeTest requires the isolated " +
                    "${InflectProbeStartupIsolation.PROBE_PACKAGE} target"
            }
        }
        suppressTargetApplicationOnCreate =
            InflectProbeStartupIsolation.shouldSuppressApplicationOnCreate(
                targetPackage = target.packageName,
                targetIsDebuggable = targetIsDebuggable,
                selectedTestClass = selectedTestClass,
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
