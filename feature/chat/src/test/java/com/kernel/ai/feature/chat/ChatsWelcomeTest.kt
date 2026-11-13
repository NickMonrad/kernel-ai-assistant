package com.kernel.ai.feature.chat

import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.model.availability.ConversationModelReadiness
import com.kernel.ai.core.skills.QuickIntentRouter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatsWelcomeTest {

    private val ready = ConversationModelReadiness.Ready
    private val downloading = ConversationModelReadiness.Preparing(
        downloadingModel = KernelModel.GEMMA_4_E4B,
        progress = 0.4f,
    )
    private val notStarted = ConversationModelReadiness.ActionRequired(
        recommendedModel = KernelModel.GEMMA_4_E4B,
    )

    @Test
    fun `first run with the assistant ready shows the welcome as ready`() {
        assertEquals(
            ChatsEmptyState.Welcome(WelcomeDownload.Ready),
            chatsEmptyState(welcomeDismissed = false, readiness = ready),
        )
    }

    @Test
    fun `first run while files download shows download progress`() {
        assertEquals(
            ChatsEmptyState.Welcome(
                WelcomeDownload.InProgress(KernelModel.GEMMA_4_E4B, progress = 0.4f),
            ),
            chatsEmptyState(welcomeDismissed = false, readiness = downloading),
        )
    }

    @Test
    fun `first run before any download starts explains that files are needed`() {
        assertEquals(
            ChatsEmptyState.Welcome(WelcomeDownload.Waiting(KernelModel.GEMMA_4_E4B)),
            chatsEmptyState(welcomeDismissed = false, readiness = notStarted),
        )
    }

    @Test
    fun `fallback model already installed counts as ready for the welcome`() {
        val fallback = ConversationModelReadiness.FallbackActive(recommendedModel = KernelModel.GEMMA_4_E4B)
        assertEquals(
            ChatsEmptyState.Welcome(WelcomeDownload.Ready),
            chatsEmptyState(welcomeDismissed = false, readiness = fallback),
        )
    }

    @Test
    fun `fallback model installed while the preferred model downloads is an upgrade, not a wait`() {
        val fallbackPreparing = ConversationModelReadiness.FallbackPreparing(
            recommendedModel = KernelModel.GEMMA_4_E4B,
            downloadingModel = KernelModel.GEMMA_4_E4B,
            progress = 0.3f,
        )
        assertEquals(
            ChatsEmptyState.Welcome(WelcomeDownload.Upgrading(KernelModel.GEMMA_4_E4B, progress = 0.3f)),
            chatsEmptyState(welcomeDismissed = false, readiness = fallbackPreparing),
        )
    }

    @Test
    fun `every example prompt is offered while the fallback model already chats`() {
        val upgrading = WelcomeDownload.Upgrading(KernelModel.GEMMA_4_E4B, progress = null)
        assertEquals(welcomeExamplePrompts, welcomeExamplePromptsFor(upgrading))
    }

    @Test
    fun `returning user sees the normal empty state whatever the download state`() {
        listOf(ready, downloading, notStarted).forEach { readiness ->
            assertEquals(
                ChatsEmptyState.Plain,
                chatsEmptyState(welcomeDismissed = true, readiness = readiness),
            )
        }
    }

    @Test
    fun `nothing is shown until the welcome preference has loaded`() {
        assertEquals(
            ChatsEmptyState.Loading,
            chatsEmptyState(welcomeDismissed = null, readiness = notStarted),
        )
    }

    @Test
    fun `all example prompts are offered once the assistant is ready`() {
        assertEquals(welcomeExamplePrompts, welcomeExamplePromptsFor(WelcomeDownload.Ready))
    }

    @Test
    fun `only prompts the router handles are offered while files download`() {
        val offered = welcomeExamplePromptsFor(WelcomeDownload.InProgress(KernelModel.GEMMA_4_E4B, 0.4f))
        assertTrue(offered.isNotEmpty())
        assertTrue(offered.none { it.needsAssistantFiles })
        assertEquals(
            welcomeExamplePromptsFor(WelcomeDownload.Waiting(KernelModel.GEMMA_4_E4B)),
            offered,
        )
    }

    @Test
    fun `every prompt offered before the assistant is ready is handled without the assistant`() {
        val router = QuickIntentRouter()
        val offered = welcomeExamplePromptsFor(WelcomeDownload.Waiting(KernelModel.GEMMA_4_E4B))
        offered.forEach { example ->
            assertTrue(
                router.route(example.text) is QuickIntentRouter.RouteResult.RegexMatch,
                "Expected '${example.text}' to route without the assistant",
            )
        }
    }
}
