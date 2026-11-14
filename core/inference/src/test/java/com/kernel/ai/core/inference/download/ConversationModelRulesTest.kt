package com.kernel.ai.core.inference.download

import com.kernel.ai.core.inference.hardware.HardwareTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationModelRulesTest {

    private val e4b = KernelModel.GEMMA_4_E4B
    private val e2b = KernelModel.GEMMA_4_E2B

    /** Predicate for a set of downloaded models. */
    private fun have(vararg models: KernelModel): (KernelModel) -> Boolean = { it in models }

    @Test
    fun `non-flagship chat needs E-2B even when E-4B is installed`() {
        assertFalse(ConversationModelRules.conversationReady(HardwareTier.MID_RANGE, have(e4b)))
        assertFalse(ConversationModelRules.conversationReady(HardwareTier.LOW_POWER, have(e4b)))
        assertTrue(ConversationModelRules.conversationReady(HardwareTier.MID_RANGE, have(e2b)))
        assertTrue(ConversationModelRules.conversationReady(HardwareTier.LOW_POWER, have(e2b)))
    }

    @Test
    fun `flagship chat accepts either conversation model`() {
        assertTrue(ConversationModelRules.conversationReady(HardwareTier.FLAGSHIP, have(e4b)))
        assertTrue(ConversationModelRules.conversationReady(HardwareTier.FLAGSHIP, have(e2b)))
        assertFalse(ConversationModelRules.conversationReady(HardwareTier.FLAGSHIP, have()))
    }

    @Test
    fun `app readiness also needs every other required model`() {
        val allRequiredButE2b = KernelModel.entries.filter { it.isRequired && it != e2b }.toTypedArray()
        assertFalse(ConversationModelRules.appRequiredReady(HardwareTier.MID_RANGE, have(*allRequiredButE2b)))
        val allRequired = KernelModel.entries.filter { it.isRequired }.toTypedArray()
        assertTrue(ConversationModelRules.appRequiredReady(HardwareTier.MID_RANGE, have(*allRequired)))
    }

    @Test
    fun `auto model follows the tier`() {
        assertEquals(e4b, ConversationModelRules.autoConversationModel(HardwareTier.FLAGSHIP))
        assertEquals(e2b, ConversationModelRules.autoConversationModel(HardwareTier.MID_RANGE))
        assertEquals(e2b, ConversationModelRules.autoConversationModel(HardwareTier.LOW_POWER))
    }

    @Test
    fun `effective model honours an installed pick then the tier default then E-2B`() {
        assertEquals(e2b, ConversationModelRules.effectiveConversationModel(e2b, HardwareTier.FLAGSHIP, have(e2b, e4b)))
        // Pick not installed: tier default wins when it is installed.
        assertEquals(e4b, ConversationModelRules.effectiveConversationModel(e2b, HardwareTier.FLAGSHIP, have(e4b)))
        // Nothing preferred is installed: E-2B is the fallback.
        assertEquals(e2b, ConversationModelRules.effectiveConversationModel(e4b, HardwareTier.MID_RANGE, have(e2b)))
    }

    @Test
    fun `selectable models exclude E-4B below flagship`() {
        assertEquals(listOf(e2b), ConversationModelRules.selectableConversationModels(HardwareTier.MID_RANGE))
        assertEquals(listOf(e2b), ConversationModelRules.selectableConversationModels(HardwareTier.LOW_POWER))
        assertEquals(listOf(e4b, e2b), ConversationModelRules.selectableConversationModels(HardwareTier.FLAGSHIP))
    }

    @Test
    fun `non-flagship ignores a saved E-4B preference even when E-4B is installed`() {
        assertEquals(e2b, ConversationModelRules.effectiveConversationModel(e4b, HardwareTier.MID_RANGE, have(e2b, e4b)))
        assertEquals(e2b, ConversationModelRules.effectiveConversationModel(e4b, HardwareTier.LOW_POWER, have(e2b, e4b)))
    }
}
