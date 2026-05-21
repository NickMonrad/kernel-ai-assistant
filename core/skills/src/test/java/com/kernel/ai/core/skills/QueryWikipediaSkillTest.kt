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
    fun `execute rejects unrelated fuzzy result for identifier like query`() = runTest {
        coEvery { runner.execute("query-wikipedia", mapOf("query" to "SM-918B")) } returns
            "Sodium lignosulfonate\n\nA lignosulfonate compound."

        val result = skill.execute(
            SkillCall(
                skillName = "query_wikipedia",
                arguments = mapOf("query" to "SM-918B"),
            ),
        )

        val reply = assertInstanceOf(SkillResult.DirectReply::class.java, result)
        assertEquals("No confident Wikipedia result found for: SM-918B", reply.content)
    }

    @Test
    fun `execute keeps identifier result when title contains the requested id`() = runTest {
        coEvery { runner.execute("query-wikipedia", mapOf("query" to "Samsung sm-918b")) } returns
            "Samsung SM-S918B\n\nSamsung device summary."

        val result = skill.execute(
            SkillCall(
                skillName = "query_wikipedia",
                arguments = mapOf("query" to "Samsung sm-918b"),
            ),
        )

        val reply = assertInstanceOf(SkillResult.DirectReply::class.java, result)
        assertEquals("Samsung SM-S918B\n\nSamsung device summary.", reply.content)
    }

    @Test
    fun `execute keeps natural language query results when only later tokens contain digits`() = runTest {
        coEvery { runner.execute("query-wikipedia", mapOf("query" to "Battle of 1812")) } returns
            "War of 1812\n\nA conflict between the United States and the United Kingdom."

        val result = skill.execute(
            SkillCall(
                skillName = "query_wikipedia",
                arguments = mapOf("query" to "Battle of 1812"),
            ),
        )

        val reply = assertInstanceOf(SkillResult.DirectReply::class.java, result)
        assertEquals(
            "War of 1812\n\nA conflict between the United States and the United Kingdom.",
            reply.content,
        )
    }

    @Test
    fun `execute rejects title whose plain words only form an identifier subsequence`() = runTest {
        coEvery { runner.execute("query-wikipedia", mapOf("query" to "AB12")) } returns
            "Alberta Highway 12\n\nA provincial highway in Alberta."

        val result = skill.execute(
            SkillCall(
                skillName = "query_wikipedia",
                arguments = mapOf("query" to "AB12"),
            ),
        )

        val reply = assertInstanceOf(SkillResult.DirectReply::class.java, result)
        assertEquals("No confident Wikipedia result found for: AB12", reply.content)
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
