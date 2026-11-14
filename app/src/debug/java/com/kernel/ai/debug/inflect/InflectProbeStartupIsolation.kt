package com.kernel.ai.debug.inflect
import com.kernel.ai.BuildConfig

/** The runtime receiver test requires a fresh package and preserves the debug startup gate. */
object InflectProbeStartupIsolation {
    const val DEBUG_PACKAGE = BuildConfig.DEBUG_APPLICATION_ID
    const val PROBE_PACKAGE = BuildConfig.PROBE_APPLICATION_ID
    const val RUNTIME_TEST_CLASS =
        "com.kernel.ai.debug.inflect.InflectSpikeReceiverRuntimeTest"

    fun isRuntimeTestSelected(selectedTestClass: String?): Boolean =
        selectedTestClass?.split(',')?.any {
            it.substringBefore('#') == RUNTIME_TEST_CLASS
        } == true

    fun shouldAllowRuntimeTest(
        targetPackage: String,
        selectedTestClass: String?,
    ): Boolean {
        if (targetPackage != PROBE_PACKAGE) return false
        val selection = selectedTestClass ?: return false
        return selection == RUNTIME_TEST_CLASS ||
            (selection.startsWith("$RUNTIME_TEST_CLASS#") && ',' !in selection)
    }

    fun shouldSuppressApplicationOnCreate(
        targetPackage: String,
        targetIsDebuggable: Boolean,
        selectedTestClass: String?,
    ): Boolean = targetPackage == DEBUG_PACKAGE &&
        targetIsDebuggable &&
        selectedTestClass == RUNTIME_TEST_CLASS
}
