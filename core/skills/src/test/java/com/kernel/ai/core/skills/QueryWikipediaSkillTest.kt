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

    private val samberg = WikipediaArticleEvidence(
        title = "Andy Samberg",
        url = "https://en.wikipedia.org/wiki/Andy_Samberg",
        summary = "Andy Samberg is an American comedian, actor and musician.",
        extract = """
            Andy Samberg is an American comedian, actor and musician.
            == Early life and education ==
            Samberg was born in Berkeley, California. He was raised in a Jewish family.
            == Career ==
            Samberg became a featured player on Saturday Night Live in 2005.
            == Personal life ==
            Samberg married musician Joanna Newsom in 2013. He has described his Jewish heritage.
            == References ==
            "Jewish comedians", a list article.
        """.trimIndent(),
    )

    private fun evidenceJson(evidence: WikipediaArticleEvidence): String = org.json.JSONObject(
        mapOf(
            "title" to evidence.title,
            "url" to evidence.url,
            "summary" to evidence.summary,
            "extract" to evidence.extract,
        ),
    ).toString()

    private suspend fun ask(query: String, question: String): SkillResult = skill.execute(
        SkillCall(
            skillName = "query_wikipedia",
            arguments = mapOf("query" to query, QueryWikipediaSkill.QUESTION_ARGUMENT to question),
        ),
    )

    @Test
    fun `factual question returns relevant article passages for the model to answer from`() = runTest {
        coEvery {
            runner.execute("query-wikipedia", mapOf("query" to "Andy Samberg", "mode" to "evidence"))
        } returns evidenceJson(samberg)

        val result = ask("Andy Samberg", "Is Andy Samberg Jewish?")

        assertInstanceOf(SkillResult.Success::class.java, result)
        val content = (result as SkillResult.Success).content
        assertTrue(content.contains("\"Is Andy Samberg Jewish?\""))
        assertTrue(content.contains("Source: https://en.wikipedia.org/wiki/Andy_Samberg"))
        assertTrue(content.contains("- [Early life and education] Samberg was born in Berkeley, California. He was raised in a Jewish family."))
        assertTrue(content.contains("- [Personal life] Samberg married musician Joanna Newsom in 2013. He has described his Jewish heritage."))
        assertFalse(content.contains("Saturday Night Live"), "career passage does not answer the question")
        assertFalse(content.contains("Jewish comedians"), "reference sections are not evidence")
    }

    @Test
    fun `speech-like question still matches evidence once the model resolves the subject`() = runTest {
        coEvery {
            runner.execute("query-wikipedia", mapOf("query" to "Andy Samberg", "mode" to "evidence"))
        } returns evidenceJson(samberg)

        val content = (ask("Andy Samberg", "Is Andy Samberger Jew") as SkillResult.Success).content

        assertTrue(content.contains("He was raised in a Jewish family."))
        assertFalse(content.contains("Saturday Night Live"))
    }

    @Test
    fun `question the article does not address yields no passages and an insufficient-evidence rule`() = runTest {
        coEvery {
            runner.execute("query-wikipedia", mapOf("query" to "Andy Samberg", "mode" to "evidence"))
        } returns evidenceJson(samberg)

        val content = (ask("Andy Samberg", "Does Andy Samberg own a sailboat?") as SkillResult.Success).content

        assertTrue(content.contains("No other passage in the article mentions the question's terms."))
        assertTrue(content.contains("say Wikipedia does not establish it"))
        assertFalse(content.contains("Relevant passages:"))
    }

    @Test
    fun `unresolved entity question reports no evidence instead of a direct reply`() = runTest {
        coEvery {
            runner.execute("query-wikipedia", mapOf("query" to "Andyberg", "mode" to "evidence"))
        } returns "No Wikipedia results found for: Andyberg"

        val result = ask("Andyberg", "Is Andy Samberg")

        assertInstanceOf(SkillResult.Success::class.java, result)
        val content = (result as SkillResult.Success).content
        assertTrue(content.startsWith("No confident Wikipedia article found for: Andyberg"))
        assertTrue(content.contains("do not guess"))
    }

    @Test
    fun `non-question request keeps the direct article summary`() = runTest {
        coEvery { runner.execute("query-wikipedia", mapOf("query" to "Andy Samberg")) } returns "Summary"

        val result = ask("Andy Samberg", "Tell me about Andy Samberg on Wikipedia")

        assertInstanceOf(SkillResult.DirectReply::class.java, result)
        assertEquals("Summary", (result as SkillResult.DirectReply).content)
    }

    @Test
    fun `passage selection is bounded in count and length`() {
        val longParagraph = "Opening sentence. " + "The jewish community filler text. ".repeat(60)
        val extract = (1..6).joinToString("\n") { "== Section $it ==\n$longParagraph" }

        val passages = selectWikipediaPassages(extract, listOf("jewish"))

        assertEquals(WIKIPEDIA_MAX_EVIDENCE_PASSAGES, passages.size)
        assertEquals(listOf("Section 1", "Section 2", "Section 3"), passages.map { it.heading })
        passages.forEach { assertTrue(it.text.length <= WIKIPEDIA_MAX_PASSAGE_CHARS + 2) }
        assertTrue(passages.first().text.startsWith("…The jewish community"))
    }

    @Test
    fun `passages matching more question terms rank first`() {
        val extract = "== A ==\nHe was born in Berkeley.\n== B ==\nHe was born in Berkeley, California."

        val passages = selectWikipediaPassages(extract, listOf("born", "california"))

        assertEquals(listOf("B", "A"), passages.map { it.heading })
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

    @Test
    fun `instructions and description steer away from conversions and arithmetic`() {
        val instructions = skill.fullInstructions.lowercase()
        val description = skill.description.lowercase()

        assertTrue(instructions.contains("do not use wikipedia for"))
        assertTrue(instructions.contains("conversion"))
        assertTrue(description.contains("conversion"))
        assertTrue(description.contains("not for"))
    }
}
