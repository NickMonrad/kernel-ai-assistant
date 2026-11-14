package com.kernel.ai.feature.chat

import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.model.availability.ConversationModelReadiness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChatsDownloadBannerTest {

    @Test
    fun `running download shows its percentage`() {
        val banner = chatsDownloadBanner(
            ConversationModelReadiness.Preparing(downloadingModel = KernelModel.GEMMA_4_E4B, progress = 0.42f),
        )
        assertEquals("Getting Jandal ready: 42%", banner?.text)
    }

    @Test
    fun `upgrade download shows as getting a better Jandal`() {
        val banner = chatsDownloadBanner(
            ConversationModelReadiness.FallbackPreparing(
                recommendedModel = KernelModel.GEMMA_4_E4B,
                downloadingModel = KernelModel.GEMMA_4_E4B,
                progress = 0.3f,
            ),
        )
        assertEquals("Getting a better Jandal: 30%", banner?.text)
    }

    @Test
    fun `unknown progress shows the title without a percentage`() {
        val banner = chatsDownloadBanner(
            ConversationModelReadiness.Preparing(downloadingModel = KernelModel.GEMMA_4_E4B, progress = null),
        )
        assertEquals("Getting Jandal ready…", banner?.text)
        assertNull(banner?.progress)
    }

    @Test
    fun `no banner when nothing is downloading`() {
        assertNull(chatsDownloadBanner(ConversationModelReadiness.Ready))
        assertNull(chatsDownloadBanner(ConversationModelReadiness.FallbackActive(KernelModel.GEMMA_4_E4B)))
        assertNull(
            chatsDownloadBanner(ConversationModelReadiness.ActionRequired(recommendedModel = KernelModel.GEMMA_4_E4B)),
        )
    }
}
