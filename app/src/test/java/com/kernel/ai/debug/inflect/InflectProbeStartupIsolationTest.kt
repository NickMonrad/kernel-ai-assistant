package com.kernel.ai.debug.inflect

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InflectProbeStartupIsolationTest {
    @Test
    fun `suppresses startup only for the exact runtime test on the debuggable debug app`() {
        assertTrue(
            InflectProbeStartupIsolation.shouldSuppressApplicationOnCreate(
                targetPackage = InflectProbeStartupIsolation.DEBUG_PACKAGE,
                targetIsDebuggable = true,
                selectedTestClass = InflectProbeStartupIsolation.RUNTIME_TEST_CLASS,
            ),
        )
        assertFalse(
            InflectProbeStartupIsolation.shouldSuppressApplicationOnCreate(
                targetPackage = InflectProbeStartupIsolation.DEBUG_PACKAGE,
                targetIsDebuggable = true,
                selectedTestClass = InflectProbeStartupIsolation.DEVICE_MEASUREMENT_TEST_CLASS,
            ),
        )
        assertFalse(
            InflectProbeStartupIsolation.shouldSuppressApplicationOnCreate(
                targetPackage = InflectProbeStartupIsolation.PROBE_PACKAGE,
                targetIsDebuggable = true,
                selectedTestClass = InflectProbeStartupIsolation.DEVICE_MEASUREMENT_TEST_CLASS,
            ),
        )
    }

    @Test
    fun `keeps normal startup for non-exact targets and test selections`() {
        val nonExactSelections = listOf(
            null,
            "${InflectProbeStartupIsolation.RUNTIME_TEST_CLASS}#phonemizesAndSynthesizesSyntheticTextWithoutNormalApplicationStartup",
            "${InflectProbeStartupIsolation.RUNTIME_TEST_CLASS},com.kernel.ai.OtherTest",
            "com.kernel.ai.debug.inflect.*",
            InflectProbeStartupIsolation.DEVICE_MEASUREMENT_TEST_CLASS,
            "${InflectProbeStartupIsolation.DEVICE_MEASUREMENT_TEST_CLASS}#measureOwnerAndSyntheticSets",
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
                targetPackage = "ai.jandal.assistant",
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

    @Test
    fun `device measurement requires opt in exact class isolated build and unsuppressed application`() {
        fun authorized(
            targetPackage: String,
            isProbeBuild: Boolean,
            selectedTestClass: String?,
            measurementEnabled: Boolean,
            applicationOnCreateSuppressed: Boolean = false,
        ) = InflectProbeStartupIsolation.shouldAllowDeviceMeasurement(
            targetPackage, isProbeBuild, selectedTestClass, measurementEnabled,
            applicationOnCreateSuppressed,
        )

        val exactMeasurementClass = InflectProbeStartupIsolation.DEVICE_MEASUREMENT_TEST_CLASS
        assertFalse(
            authorized(
                InflectProbeStartupIsolation.PROBE_PACKAGE, true,
                exactMeasurementClass, measurementEnabled = false,
            ),
        )
        assertFalse(
            authorized(
                InflectProbeStartupIsolation.DEBUG_PACKAGE, false,
                exactMeasurementClass, measurementEnabled = true,
            ),
        )
        assertFalse(
            authorized(
                InflectProbeStartupIsolation.PROBE_PACKAGE, false,
                exactMeasurementClass, measurementEnabled = true,
            ),
        )
        assertFalse(
            authorized(
                InflectProbeStartupIsolation.PROBE_PACKAGE, true,
                "$exactMeasurementClass#measureOwnerAndSyntheticSets", measurementEnabled = true,
            ),
        )
        assertFalse(
            authorized(
                InflectProbeStartupIsolation.PROBE_PACKAGE, true,
                exactMeasurementClass, measurementEnabled = true,
                applicationOnCreateSuppressed = true,
            ),
        )
        assertTrue(
            authorized(
                InflectProbeStartupIsolation.PROBE_PACKAGE, true,
                exactMeasurementClass, measurementEnabled = true,
            ),
        )
    }
}
