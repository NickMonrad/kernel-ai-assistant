package com.kernel.ai.core.skills

import com.kernel.ai.core.inference.diagnostics.IntentRoute
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import com.kernel.ai.core.skills.natives.NativeIntentHandler
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RunIntentSkillOutcomeTest {

    @Test
    fun `run_intent rejected without an intent name is exported as a parse error on its route`() = runBlocking {
        JandalEvents.install(File.createTempFile("jandal-run-intent", ".log"))
        val mark = System.nanoTime() % 999_999_999L
        JandalEvents.record("test", "mark", "id" to mark)

        val result = RunIntentSkill(mockk<NativeIntentHandler>(relaxed = true))
            .execute(SkillCall("run_intent", emptyMap(), route = IntentRoute.LLM_TOOL))
        JandalEvents.awaitWritesForTest()

        assertInstanceOf(SkillResult.Failure::class.java, result)
        JandalEvents.awaitWritesForTest()
        val exported = JandalEvents.exportLines()
        val line = exported.drop(exported.indexOfLast { " test mark " in it && "id=$mark" in it } + 1).last { " intent run_intent " in it }
        assertTrue(line.contains("route=LLM_TOOL"), line)
        assertTrue(line.contains("success=false"), line)
        assertTrue(line.contains("outcome=PARSE_ERROR"), line)
    }
}
