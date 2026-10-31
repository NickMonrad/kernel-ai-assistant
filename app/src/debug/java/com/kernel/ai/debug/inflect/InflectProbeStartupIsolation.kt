package com.kernel.ai.debug.inflect

/**
 * Restricts skipping the debug app's normal startup to the explicitly selected Inflect runtime
 * instrumentation test. Other tests and non-debuggable targets retain normal Application startup.
 */
object InflectProbeStartupIsolation {
    const val DEBUG_PACKAGE = "com.kernel.ai.debug"
    const val RUNTIME_TEST_CLASS =
        "com.kernel.ai.debug.inflect.InflectSpikeReceiverRuntimeTest"

    fun shouldSuppressApplicationOnCreate(
        targetPackage: String,
        targetIsDebuggable: Boolean,
        selectedTestClass: String?,
    ): Boolean = targetPackage == DEBUG_PACKAGE &&
        targetIsDebuggable &&
        selectedTestClass == RUNTIME_TEST_CLASS
}
