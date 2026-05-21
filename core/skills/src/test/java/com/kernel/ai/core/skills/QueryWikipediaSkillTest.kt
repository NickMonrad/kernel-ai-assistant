package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.js.JsSkillRunner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryWikipediaSkillTest {

    private val runner: JsSkillRunner = mockk()
    private val skill = QueryWikipediaSkill(runner)

    @Test
    fun `execute delegates to query-wikipedia js skill`() = runTest {
        coEvery { runner.execute("query-wikipedia", mapOf("query" to "Constantinople")) } returns "Result"

        val result = skill.execute(
            SkillCall(
                skillName = "query_wikipedia",
                arguments = mapOf("query" to "Constantinople"),
            ),
        )

        assertInstanceOf(SkillResult.DirectReply::class.java, result)
        assertEquals("Result", (result as SkillResult.DirectReply).content)
        coVerify(exactly = 1) { runner.execute("query-wikipedia", mapOf("query" to "Constantinople")) }
    }

    @Test
    fun `fullInstructions stay focused on wikipedia and omit forecast guidance`() {
        val instructions = skill.fullInstructions

        assertTrue(instructions.contains("queryWikipedia(query="))
        assertTrue(instructions.contains("Call the queryWikipedia tool directly"))
        assertTrue(instructions.contains("query=\"Constantinople\""))
        assertFalse(instructions.contains("forecast_days (1–7)"))
        assertFalse(instructions.contains("ALWAYS call this tool for weather"))
    }
}
