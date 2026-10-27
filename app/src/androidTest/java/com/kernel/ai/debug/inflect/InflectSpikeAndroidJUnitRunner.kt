package com.kernel.ai.debug.inflect

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Fails closed if the native runtime test is selected against the normal debug application ID.
 * The isolated package uses a build-only startup flag to skip normal background work.
 */
class InflectSpikeAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        val selectedTestClass = arguments?.getString(ARG_CLASS)
        if (InflectProbeStartupIsolation.isRuntimeTestSelected(selectedTestClass)) {
            check(
                InflectProbeStartupIsolation.shouldAllowRuntimeTest(
                    targetPackage = targetContext.packageName,
                    selectedTestClass = selectedTestClass,
                ),
            ) {
                "InflectSpikeReceiverRuntimeTest requires the isolated " +
                    "${InflectProbeStartupIsolation.PROBE_PACKAGE} target"
            }
        }
        super.onCreate(arguments)
    }

    private companion object {
        const val ARG_CLASS = "class"
    }
}
