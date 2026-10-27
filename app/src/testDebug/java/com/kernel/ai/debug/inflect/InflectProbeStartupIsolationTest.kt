package com.kernel.ai.debug.inflect

import com.kernel.ai.shouldRunNormalBackgroundWork
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InflectProbeStartupIsolationTest {
    @Test
    fun `runtime probe is allowed only for isolated package and single test selection`() {
        val testClass = InflectProbeStartupIsolation.RUNTIME_TEST_CLASS
        val probePackage = InflectProbeStartupIsolation.PROBE_PACKAGE

        assertTrue(
            InflectProbeStartupIsolation.shouldAllowRuntimeTest(
                targetPackage = probePackage,
                selectedTestClass = testClass,
            ),
        )
        assertTrue(
            InflectProbeStartupIsolation.shouldAllowRuntimeTest(
                targetPackage = probePackage,
                selectedTestClass = "$testClass#phonemizesAndSynthesizesSyntheticPhrase",
            ),
        )
        assertFalse(
            InflectProbeStartupIsolation.shouldAllowRuntimeTest(
                targetPackage = "com.kernel.ai.debug",
                selectedTestClass = testClass,
            ),
        )
        assertFalse(
            InflectProbeStartupIsolation.shouldAllowRuntimeTest(
                targetPackage = probePackage,
                selectedTestClass = "$testClass,OtherTest",
            ),
        )
        assertTrue(InflectProbeStartupIsolation.isRuntimeTestSelected("$testClass,OtherTest"))
    }

    @Test
    fun `probe mode skips normal startup while default mode keeps it`() {
        assertTrue(shouldRunNormalBackgroundWork(isInflectProbeBuild = false))
        assertFalse(shouldRunNormalBackgroundWork(isInflectProbeBuild = true))
    }
}
