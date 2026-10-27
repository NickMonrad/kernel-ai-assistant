package com.kernel.ai.debug.inflect

/** The runtime receiver test must target a fresh package identity, never normal debug. */
object InflectProbeStartupIsolation {
    const val PROBE_PACKAGE = "com.kernel.ai.debug.inflectprobe"
    const val RUNTIME_TEST_CLASS =
        "com.kernel.ai.debug.inflect.InflectSpikeReceiverRuntimeTest"

    fun isRuntimeTestSelected(selectedTestClass: String?): Boolean =
        selectedTestClass?.split(',')?.any {
            it.substringBefore('#') == RUNTIME_TEST_CLASS
        } == true

    fun shouldAllowRuntimeTest(
        targetPackage: String,
        selectedTestClass: String?,
    ): Boolean = targetPackage == PROBE_PACKAGE &&
        (selectedTestClass == RUNTIME_TEST_CLASS ||
            selectedTestClass?.startsWith("$RUNTIME_TEST_CLASS#") == true)
}
