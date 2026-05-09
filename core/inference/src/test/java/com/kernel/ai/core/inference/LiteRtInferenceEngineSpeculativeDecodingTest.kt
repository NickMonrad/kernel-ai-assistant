package com.kernel.ai.core.inference

import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalApi::class)
class LiteRtInferenceEngineSpeculativeDecodingTest {
    @AfterEach
    fun tearDown() {
        ExperimentalFlags.enableSpeculativeDecoding = false
    }

    @Test
    fun `resolveSpeculativeDecodingForInit returns false without probing when setting disabled`() {
        var probed = false

        val result = resolveSpeculativeDecodingForInit(
            requested = false,
            modelPath = "/tmp/model.litertlm",
            capabilityProbe = {
                probed = true
                true
            },
        )

        assertFalse(result)
        assertFalse(probed)
    }

    @Test
    fun `resolveSpeculativeDecodingForInit returns true when requested and supported`() {
        val result = resolveSpeculativeDecodingForInit(
            requested = true,
            modelPath = "/tmp/model.litertlm",
            capabilityProbe = { true },
        )

        assertTrue(result)
    }

    @Test
    fun `resolveSpeculativeDecodingForInit returns false when capability probe throws`() {
        val result = resolveSpeculativeDecodingForInit(
            requested = true,
            modelPath = "/tmp/model.litertlm",
            capabilityProbe = { throw IllegalStateException("boom") },
        )

        assertFalse(result)
    }

    @Test
    fun `withSpeculativeDecodingEnabledForInit enables flag only during init block`() {
        assertFalse(ExperimentalFlags.enableSpeculativeDecoding == true)

        withSpeculativeDecodingEnabledForInit(true) {
            assertTrue(ExperimentalFlags.enableSpeculativeDecoding == true)
        }

        assertFalse(ExperimentalFlags.enableSpeculativeDecoding == true)
    }

    @Test
    fun `withSpeculativeDecodingEnabledForInit resets flag after init failure`() {
        assertFalse(ExperimentalFlags.enableSpeculativeDecoding == true)

        assertThrows(IllegalStateException::class.java) {
            withSpeculativeDecodingEnabledForInit(true) {
                assertTrue(ExperimentalFlags.enableSpeculativeDecoding == true)
                throw IllegalStateException("init failed")
            }
        }

        assertFalse(ExperimentalFlags.enableSpeculativeDecoding == true)
    }
}
