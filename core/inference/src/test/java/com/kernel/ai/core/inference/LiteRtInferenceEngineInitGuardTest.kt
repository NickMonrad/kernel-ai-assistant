package com.kernel.ai.core.inference

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiteRtInferenceEngineInitGuardTest {
    @Test
    fun `waitForInteractiveState returns when screen becomes interactive`() = runTest {
        var interactive = false
        backgroundScope.launch {
            delay(30)
            interactive = true
        }

        assertTrue(
            waitForInteractiveState(
                isInteractive = { interactive },
                pollMs = 10,
                timeoutMs = 100,
            ),
        )
    }

    @Test
    fun `waitForInteractiveState times out when screen stays non interactive`() = runTest {
        assertFalse(
            waitForInteractiveState(
                isInteractive = { false },
                pollMs = 10,
                timeoutMs = 50,
            ),
        )
    }
}
