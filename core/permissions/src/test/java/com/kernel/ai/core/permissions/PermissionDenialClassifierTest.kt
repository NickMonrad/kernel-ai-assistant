package com.kernel.ai.core.permissions

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PermissionDenialClassifierTest {

    private val classifier = PermissionDenialClassifier()

    @Test
    fun `first denial is always retryable regardless of shouldShowRationale`() {
        val result = classifier.classify("perm.alpha", shouldShowRationale = false)
        assertEquals(DenialOutcome.RetryableDenied, result)
    }

    @Test
    fun `first denial with shouldShowRationale true is retryable`() {
        val result = classifier.classify("perm.alpha", shouldShowRationale = true)
        assertEquals(DenialOutcome.RetryableDenied, result)
    }

    @Test
    fun `second denial with shouldShowRationale false is repairOnly`() {
        classifier.classify("perm.alpha", shouldShowRationale = true)  // first
        val result = classifier.classify("perm.alpha", shouldShowRationale = false)
        assertEquals(DenialOutcome.RepairOnlyDenied, result)
    }

    @Test
    fun `second denial with shouldShowRationale true is retryable`() {
        classifier.classify("perm.alpha", shouldShowRationale = false)  // first
        val result = classifier.classify("perm.alpha", shouldShowRationale = true)
        assertEquals(DenialOutcome.RetryableDenied, result)
    }

    @Test
    fun `third denial without rationale is repairOnly`() {
        classifier.classify("perm.alpha", shouldShowRationale = true)  // first
        classifier.classify("perm.alpha", shouldShowRationale = false) // second
        val result = classifier.classify("perm.alpha", shouldShowRationale = false)
        assertEquals(DenialOutcome.RepairOnlyDenied, result)
    }

    @Test
    fun `clear resets counter for that permission`() {
        classifier.classify("perm.alpha", shouldShowRationale = false)   // first → retryable
        classifier.classify("perm.beta", shouldShowRationale = false)    // unrelated
        classifier.clear("perm.alpha")
        val result = classifier.classify("perm.alpha", shouldShowRationale = false)
        assertEquals(DenialOutcome.RetryableDenied, result)
    }

    @Test
    fun `different permissions are tracked independently`() {
        classifier.classify("perm.alpha", shouldShowRationale = true)  // first
        classifier.classify("perm.alpha", shouldShowRationale = true)  // second, rationale = true
        val alphaResult = classifier.classify("perm.alpha", shouldShowRationale = false)
        assertEquals(DenialOutcome.RepairOnlyDenied, alphaResult)

        val betaResult = classifier.classify("perm.beta", shouldShowRationale = false)
        assertEquals(DenialOutcome.RetryableDenied, betaResult) // first denial for beta
    }

    @Test
    fun `clearAll resets all counters`() {
        classifier.classify("perm.alpha", shouldShowRationale = false)
        classifier.classify("perm.beta", shouldShowRationale = false)
        classifier.clearAll()
        assertEquals(
            DenialOutcome.RetryableDenied,
            classifier.classify("perm.alpha", shouldShowRationale = false),
        )
        assertEquals(
            DenialOutcome.RetryableDenied,
            classifier.classify("perm.beta", shouldShowRationale = false),
        )
    }
}
