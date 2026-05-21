package com.kernel.ai.core.skills

import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KernelAIToolSetTest {

    private lateinit var registry: SkillRegistry
    private lateinit var toolSet: KernelAIToolSet

    @BeforeEach
    fun setUp() {
        registry = mockk()
        toolSet = KernelAIToolSet(
            object : Lazy<SkillRegistry> {
                override fun get(): SkillRegistry = registry
            },
        )
    }

    @Test
    fun `queryWikipedia delegates to query_wikipedia skill`() = runTest {
        val skill = mockk<Skill>()
        every { skill.name } returns "query_wikipedia"
<<<<<<< HEAD
     coEvery { skill.execute(any()) } returns SkillResult.DirectReply("Wiki result")
=======
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("Wiki result")
>>>>>>> cf59109c (fix(#941): bypass model synthesis for direct tool replies)
        every { registry.get("query_wikipedia") } returns skill

        val result = toolSet.queryWikipedia("New Zealand")

        assertEquals("Wiki result", result["result"])
        assertTrue(toolSet.wasToolCalled())
<<<<<<< HEAD
      assertTrue(toolSet.lastToolWasDirectReply())
=======
        assertTrue(toolSet.lastToolWasDirectReply())
>>>>>>> cf59109c (fix(#941): bypass model synthesis for direct tool replies)
        assertEquals("query_wikipedia", toolSet.lastToolName())
        assertEquals("{\"query\":\"New Zealand\"}", toolSet.lastToolRequest())
    }

    @Test
    fun `getSystemInfo delegates to get_system_info skill`() = runTest {
        val skill = mockk<Skill>()
        every { skill.name } returns "get_system_info"
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("Date/time: Wednesday")
        every { registry.get("get_system_info") } returns skill

        val result = toolSet.getSystemInfo()

        assertEquals("Date/time: Wednesday", result["result"])
        assertTrue(toolSet.wasToolCalled())
assertTrue(toolSet.lastToolWasDirectReply())
        assertEquals("get_system_info", toolSet.lastToolName())
        assertEquals("{}", toolSet.lastToolRequest())
    }
}
