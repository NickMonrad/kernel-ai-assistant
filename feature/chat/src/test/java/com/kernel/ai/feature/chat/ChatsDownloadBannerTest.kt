package com.kernel.ai.feature.chat

import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.model.availability.ConversationModelReadiness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatsDownloadBannerTest {

    private fun banner(
        readiness: ConversationModelReadiness,
        state: ChatsEmptyState = ChatsEmptyState.Plain,
        selecting: Boolean = false,
        archived: Boolean = false,
    ) = chatsDownloadBanner(readiness, state, selecting, archived)

    @Test
    fun `shows running percentage while the preferred model downloads`() {
        val b = banner(ConversationModelReadiness.Preparing(KernelModel.GEMMA_4_E4B, 0.42f))
        assertEquals("Getting Jandal ready: 42%", b?.text)
    }

    @Test
    fun `shows upgrade percentage while the fallback model already chats`() {
        val b = banner(
            ConversationModelReadiness.FallbackPreparing(
                recommendedModel = KernelModel.GEMMA_4_E4B,
                downloadingModel = KernelModel.GEMMA_4_E4B,
                progress = 0.3f,
            ),
        )
        assertEquals("Getting a better Jandal: 30%", b?.text)
    }

    @Test
    fun `unknown, zero, negative, and NaN progress show an indeterminate title, never 0 percent`() {
        listOf(null, 0f, -1f, Float.NaN).forEach { raw ->
            val b = banner(ConversationModelReadiness.Preparing(KernelModel.GEMMA_4_E4B, raw))
            assertEquals("Getting Jandal ready…", b?.text, "raw=$raw")
            assertNull(b?.progress, "raw=$raw")
        }
    }

    @Test
    fun `never reports 100 percent while still downloading`() {
        listOf(1f, 1.5f, 0.995f).forEach { raw ->
            val b = banner(ConversationModelReadiness.Preparing(KernelModel.GEMMA_4_E4B, raw))
            assertEquals("Getting Jandal ready: 99%", b?.text, "raw=$raw")
        }
    }

    @Test
    fun `tiny progress shows 1 percent rather than 0`() {
        val b = banner(ConversationModelReadiness.Preparing(KernelModel.GEMMA_4_E4B, 0.001f))
        assertEquals("Getting Jandal ready: 1%", b?.text)
    }

    @Test
    fun `hidden while the welcome is up, in selection mode, or in the archive`() {
        val downloading = ConversationModelReadiness.Preparing(KernelModel.GEMMA_4_E4B, 0.5f)
        assertNull(banner(downloading, state = ChatsEmptyState.Welcome(WelcomeDownload.Ready)))
        assertNull(banner(downloading, state = ChatsEmptyState.Loading))
        assertNull(banner(downloading, selecting = true))
        assertNull(banner(downloading, archived = true))
    }

    @Test
    fun `no banner when no conversation model is downloading`() {
        assertNull(banner(ConversationModelReadiness.Ready))
        assertNull(banner(ConversationModelReadiness.FallbackActive(KernelModel.GEMMA_4_E4B)))
        assertNull(banner(ConversationModelReadiness.ActionRequired(recommendedModel = KernelModel.GEMMA_4_E4B)))
    }

    @Test
    fun `try asking chips show only for a near-empty plain list with no search`() {
        fun chips(count: Int, state: ChatsEmptyState = ChatsEmptyState.Plain, query: String = "") =
            showTryAskingChips(count, state, showArchived = false, isInSelectionMode = false, searchQuery = query)

        assertTrue(chips(0))
        assertTrue(chips(2))
        assertFalse(chips(TRY_ASKING_CHIPS_MAX_CONVERSATIONS))
        assertFalse(chips(1, query = "milk"))
        assertFalse(chips(1, state = ChatsEmptyState.Welcome(WelcomeDownload.Ready)))
        assertFalse(
            showTryAskingChips(1, ChatsEmptyState.Plain, showArchived = true, isInSelectionMode = false, searchQuery = ""),
        )
    }
}
