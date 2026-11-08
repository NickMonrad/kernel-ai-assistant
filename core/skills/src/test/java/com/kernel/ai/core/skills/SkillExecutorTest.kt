package com.kernel.ai.core.skills

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SkillExecutorTest {

    private lateinit var registry: SkillRegistry
    private lateinit var executor: SkillExecutor

    private fun makeSkill(
        skillName: String,
        required: List<String> = emptyList(),
        result: SkillResult = SkillResult.Success("ok"),
    ): Skill {
        val skill = mockk<Skill>()
        io.mockk.every { skill.name } returns skillName
        io.mockk.every { skill.schema } returns SkillSchema(
            parameters = required.associateWith {
                SkillParameter(type = "string", description = it)
            },
            required = required,
        )
        coEvery { skill.execute(any()) } returns result
        return skill
    }

    @BeforeEach
    fun setUp() {
        registry = mockk()
        executor = SkillExecutor(registry)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parseSkillCall (tested via execute)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ParseSkillCall {

        @Test
        fun `pure JSON dispatches to skill and returns Success`() = runTest {
            val skill = makeSkill("save_memory", required = listOf("content"))
            io.mockk.every { registry.get("save_memory") } returns skill

            val result = executor.execute("""{"name": "save_memory", "arguments": {"content": "test"}}""")

            assertInstanceOf(SkillResult.Success::class.java, result)
            assertEquals("ok", (result as SkillResult.Success).content)
        }

        @Test
        fun `markdown fenced JSON is stripped and dispatched`() = runTest {
            val skill = makeSkill("get_weather")
            io.mockk.every { registry.get("get_weather") } returns skill

            val result = executor.execute("```json\n{\"name\": \"get_weather\", \"arguments\": {}}\n```")

            assertInstanceOf(SkillResult.Success::class.java, result)
        }

        @Test
        fun `empty arguments object is accepted`() = runTest {
            val skill = makeSkill("get_system_info")
            io.mockk.every { registry.get("get_system_info") } returns skill

            val result = executor.execute("""{"name": "get_system_info", "arguments": {}}""")

            assertInstanceOf(SkillResult.Success::class.java, result)
        }

        @Test
        fun `malformed JSON returns ParseError`() = runTest {
            val result = executor.execute("not json at all")
            assertInstanceOf(SkillResult.ParseError::class.java, result)
        }

        @Test
        fun `JSON without name field returns ParseError`() = runTest {
            val result = executor.execute("""{"foo": "bar"}""")
            assertInstanceOf(SkillResult.ParseError::class.java, result)
        }

        @Test
        fun `empty string returns ParseError`() = runTest {
            val result = executor.execute("")
            assertInstanceOf(SkillResult.ParseError::class.java, result)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Skill dispatch
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class SkillDispatch {

        @Test
        fun `unknown skill name returns UnknownSkill`() = runTest {
            io.mockk.every { registry.get("nonexistent") } returns null

            val result = executor.execute("""{"name": "nonexistent", "arguments": {}}""")

            assertInstanceOf(SkillResult.UnknownSkill::class.java, result)
            assertEquals("nonexistent", (result as SkillResult.UnknownSkill).skillName)
        }

        @Test
        fun `missing required parameter returns ParseError`() = runTest {
            val skill = makeSkill("save_memory", required = listOf("content"))
            io.mockk.every { registry.get("save_memory") } returns skill

            // "content" is required but not provided
            val result = executor.execute("""{"name": "save_memory", "arguments": {}}""")

            assertInstanceOf(SkillResult.ParseError::class.java, result)
            val error = result as SkillResult.ParseError
            assertEquals(true, error.reason.contains("content"))
        }

        @Test
        fun `skill throwing exception returns Failure`() = runTest {
            val skill = makeSkill("get_weather")
            coEvery { skill.execute(any()) } throws RuntimeException("network error")
            io.mockk.every { registry.get("get_weather") } returns skill

            val result = executor.execute("""{"name": "get_weather", "arguments": {}}""")

            assertInstanceOf(SkillResult.Failure::class.java, result)
            assertEquals("network error", (result as SkillResult.Failure).error)
        }

        @Test
        fun `skill returning Failure is passed through`() = runTest {
            val skill = makeSkill("get_weather", result = SkillResult.Failure("get_weather", "timeout"))
            io.mockk.every { registry.get("get_weather") } returns skill

            val result = executor.execute("""{"name": "get_weather", "arguments": {}}""")

            assertInstanceOf(SkillResult.Failure::class.java, result)
            assertEquals("timeout", (result as SkillResult.Failure).error)
        }

        @Test
        fun `normal text response with no JSON returns ParseError - no regression`() = runTest {
            val result = executor.execute("Kia ora, how can I help?")
            assertInstanceOf(SkillResult.ParseError::class.java, result)
        }
    }
}
