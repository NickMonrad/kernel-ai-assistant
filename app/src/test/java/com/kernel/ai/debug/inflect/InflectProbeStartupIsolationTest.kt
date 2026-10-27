package com.kernel.ai.debug.inflect

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InflectProbeStartupIsolationTest {
    @Test
    fun `suppresses startup only for exact runtime test on debuggable debug app`() {
        assertTrue(
            InflectProbeStartupIsolation.shouldSuppressApplicationOnCreate(
                targetPackage = InflectProbeStartupIsolation.DEBUG_PACKAGE,
                targetIsDebuggable = true,
                selectedTestClass = InflectProbeStartupIsolation.RUNTIME_TEST_CLASS,
            ),
        )
    }

    @Test
    fun `keeps normal startup for every non-exact target or test selection`() {
        val nonExactSelections = listOf(
            null,
            "${InflectProbeStartupIsolation.RUNTIME_TEST_CLASS}#phonemizesAndSynthesizesSyntheticTextWithoutNormalApplicationStartup",
            "${InflectProbeStartupIsolation.RUNTIME_TEST_CLASS},com.kernel.ai.OtherTest",
            "com.kernel.ai.debug.inflect.*",
        )
        nonExactSelections.forEach { selectedClass ->
            assertFalse(
                InflectProbeStartupIsolation.shouldSuppressApplicationOnCreate(
                    targetPackage = InflectProbeStartupIsolation.DEBUG_PACKAGE,
                    targetIsDebuggable = true,
                    selectedTestClass = selectedClass,
                ),
                "Unexpected startup suppression for selection $selectedClass",
            )
        }
        assertFalse(
            InflectProbeStartupIsolation.shouldSuppressApplicationOnCreate(
                targetPackage = "com.kernel.ai",
                targetIsDebuggable = true,
                selectedTestClass = InflectProbeStartupIsolation.RUNTIME_TEST_CLASS,
            ),
        )
        assertFalse(
            InflectProbeStartupIsolation.shouldSuppressApplicationOnCreate(
                targetPackage = InflectProbeStartupIsolation.DEBUG_PACKAGE,
                targetIsDebuggable = false,
                selectedTestClass = InflectProbeStartupIsolation.RUNTIME_TEST_CLASS,
            ),
        )
    }
}
