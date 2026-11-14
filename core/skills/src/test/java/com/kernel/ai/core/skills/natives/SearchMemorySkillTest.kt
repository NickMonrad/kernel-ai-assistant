package com.kernel.ai.core.skills.natives

import com.kernel.ai.core.memory.rag.MessageSearchResult
import com.kernel.ai.core.memory.rag.RagRepository
import com.kernel.ai.core.memory.repository.MemorySearchResult
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchMemorySkillTest {
    private val ragRepository = mockk<RagRepository>()
    private val userProfileRepository = mockk<UserProfileRepository>()
    private val skill = SearchMemorySkill(ragRepository, userProfileRepository)

    @Test
    fun `topical recall gives the model only filtered evidence`() = runTest {
        coEvery { userProfileRepository.getName() } returns null
        coEvery { ragRepository.searchMessages(any(), any(), any()) } returns listOf(
            MessageSearchResult(
                role = "user",
                content = "We discussed a dry cleaner last week",
                conversationId = "old-chat",
                timestamp = 1L,
                score = 0.1f,
            ),
        )
        coEvery {
            ragRepository.searchCoreAndEpisodic(any(), any(), includeSiblingContext = false)
        } returns listOf(
            MemorySearchResult(
                id = "coffee",
                content = "my favourite coffee is a flat white",
                source = "core",
                score = 0.2f,
            ),
            MemorySearchResult(
                id = "cleaner",
                content = "The user's preferred cleaner is Star Dry Cleaning.",
                source = "core",
                score = 0.25f,
            ),
        )

        val result = skill.execute(
            SkillCall("search_memory", mapOf("query" to "What's my favourite coffee?")),
        )

        val evidence = assertInstanceOf(SkillResult.Success::class.java, result).content
        assertTrue(evidence.contains("my favourite coffee is a flat white"))
        assertFalse(evidence.contains("Star Dry Cleaning"))
        assertFalse(evidence.contains("We discussed a dry cleaner"))
        assertFalse(evidence.contains("Here's what I found in memory"))
        coVerify(exactly = 1) {
            ragRepository.searchCoreAndEpisodic("What's my favourite coffee?", 5, includeSiblingContext = false)
        }
    }

    @Test
    fun `generic self recall remains a direct list of saved user facts`() = runTest {
        coEvery { userProfileRepository.getName() } returns "Nick"
        coEvery { ragRepository.getAllUserCoreMemories() } returns listOf(
            MemorySearchResult(
                id = "coffee",
                content = "Nick's favourite coffee is a flat white",
                source = "core",
                score = 1f,
            ),
        )

        val result = skill.execute(
            SkillCall("search_memory", mapOf("query" to "What do you remember about me?")),
        )

        val reply = assertInstanceOf(SkillResult.DirectReply::class.java, result).content
        assertTrue(reply.contains("flat white"))
        assertTrue(reply.contains("• Nick's favourite coffee is a flat white"))
        assertFalse(reply.contains("Core Memory"))
        assertFalse(reply.contains("Nov"))
        coVerify(exactly = 0) { ragRepository.searchCoreAndEpisodic(any(), any(), any()) }
    }

    @Test
    fun `generic self recall with no memories stays a direct reply`() = runTest {
        coEvery { userProfileRepository.getName() } returns "Nick"
        coEvery { ragRepository.getAllUserCoreMemories() } returns emptyList()
        coEvery {
            ragRepository.searchCoreAndEpisodic(any(), any(), includeSiblingContext = false)
        } returns emptyList()

        val result = skill.execute(
            SkillCall("search_memory", mapOf("query" to "What do you remember about me?")),
        )

        val reply = assertInstanceOf(SkillResult.DirectReply::class.java, result).content
        assertEquals("No relevant memories found.", reply)
        coVerify(exactly = 0) { ragRepository.searchMessages(any(), any(), any()) }
        coVerify(exactly = 1) {
            ragRepository.searchCoreAndEpisodic("What do you remember about me?", 5, includeSiblingContext = false)
        }
    }

    @Test
    fun `generic self recall falls back to episodic memories when core is empty`() = runTest {
        coEvery { userProfileRepository.getName() } returns "Nick"
        coEvery { ragRepository.getAllUserCoreMemories() } returns emptyList()
        coEvery {
            ragRepository.searchCoreAndEpisodic(any(), any(), includeSiblingContext = false)
        } returns listOf(
            MemorySearchResult(
                id = "episode",
                content = "Nick said his sister lives in Wellington",
                source = "episodic",
                score = 0.8f,
            ),
        )

        val result = skill.execute(
            SkillCall("search_memory", mapOf("query" to "What do you remember about me?")),
        )

        val reply = assertInstanceOf(SkillResult.DirectReply::class.java, result).content
        assertTrue(reply.contains("• Nick said his sister lives in Wellington"))
        assertFalse(reply.contains("Episodic Memory"))
    }

    @Test
    fun `empty topical recall remains a direct reply`() = runTest {
        coEvery { userProfileRepository.getName() } returns null
        coEvery { ragRepository.searchMessages(any(), any(), any()) } returns emptyList()
        coEvery {
            ragRepository.searchCoreAndEpisodic(any(), any(), includeSiblingContext = false)
        } returns emptyList()

        val result = skill.execute(
            SkillCall("search_memory", mapOf("query" to "What's my favourite tea?")),
        )

        val reply = assertInstanceOf(SkillResult.DirectReply::class.java, result).content
        assertTrue(reply.contains("No relevant memories found"))
    }

    @Test
    fun `preference recall excludes assistant answers and query-only message history`() = runTest {
        coEvery { userProfileRepository.getName() } returns null
        coEvery { ragRepository.searchMessages(any(), any(), any()) } returns listOf(
            MessageSearchResult(
                role = "user",
                content = "What's my favourite tea?",
                conversationId = "old-question",
                timestamp = 1L,
                score = 1f,
            ),
            MessageSearchResult(
                role = "assistant",
                content = "I don't know your favourite tea; only coffee.",
                conversationId = "old-answer",
                timestamp = 2L,
                score = 1f,
            ),
        )
        coEvery {
            ragRepository.searchCoreAndEpisodic(any(), any(), includeSiblingContext = false)
        } returns listOf(
            MemorySearchResult(
                id = "coffee",
                content = "my favourite coffee is a flat white",
                source = "core",
                score = 0.445f,
            ),
        )

        val result = skill.execute(
            SkillCall("search_memory", mapOf("query" to "What's my favourite tea?")),
        )

        val reply = assertInstanceOf(SkillResult.DirectReply::class.java, result).content
        assertTrue(reply.contains("No relevant memories found"))
    }
}
