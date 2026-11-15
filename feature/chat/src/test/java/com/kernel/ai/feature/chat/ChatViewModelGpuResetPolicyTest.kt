package com.kernel.ai.feature.chat

import com.kernel.ai.core.inference.BackendType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatViewModelGpuResetPolicyTest {
    @Test
    fun `debug GPU retains conversation by default`() {
        assertFalse(shouldForceGpuConversationResetEachTurn(BackendType.GPU, isDebugBuild = true, debugForceReset = false))
    }

    @Test
    fun `debug GPU reset switch forces per turn conversation reset`() {
        assertTrue(shouldForceGpuConversationResetEachTurn(BackendType.GPU, isDebugBuild = true, debugForceReset = true))
    }

    @Test
    fun `release GPU always keeps existing per turn reset behavior`() {
        assertTrue(shouldForceGpuConversationResetEachTurn(BackendType.GPU, isDebugBuild = false, debugForceReset = false))
    }

    @Test
    fun `non GPU backends never use the GPU workaround`() {
        assertFalse(shouldForceGpuConversationResetEachTurn(BackendType.CPU, isDebugBuild = false, debugForceReset = false))
        assertFalse(shouldForceGpuConversationResetEachTurn(BackendType.NPU, isDebugBuild = true, debugForceReset = true))
        assertFalse(shouldForceGpuConversationResetEachTurn(null, isDebugBuild = true, debugForceReset = true))
    }
}
