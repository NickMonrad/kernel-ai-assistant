package com.kernel.ai.feature.chat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatsFabVisibilityTest {

    @Test
    fun `FAB column is hidden while the first-run welcome shows`() {
        assertFalse(showChatsFab(ChatsEmptyState.Welcome(WelcomeDownload.Ready), isInSelectionMode = false, showArchived = false))
    }

    @Test
    fun `FAB column stays on the plain and loaded lists`() {
        assertTrue(showChatsFab(ChatsEmptyState.Plain, isInSelectionMode = false, showArchived = false))
        assertTrue(showChatsFab(ChatsEmptyState.Loading, isInSelectionMode = false, showArchived = false))
    }

    @Test
    fun `FAB column is hidden in selection and archive views`() {
        assertFalse(showChatsFab(ChatsEmptyState.Plain, isInSelectionMode = true, showArchived = false))
        assertFalse(showChatsFab(ChatsEmptyState.Plain, isInSelectionMode = false, showArchived = true))
    }
}
