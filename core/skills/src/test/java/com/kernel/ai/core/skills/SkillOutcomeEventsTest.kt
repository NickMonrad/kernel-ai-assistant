package com.kernel.ai.core.skills

import com.kernel.ai.core.inference.diagnostics.IntentRoute
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SkillOutcomeEventsTest {

    @Test
    fun `direct skill call records its route and success outcome`() = runBlocking {
        val skill = mockk<Skill>(relaxed = true)
        every { skill.name } returns "outcome_probe_skill"
        coEvery { skill.execute(any()) } returns SkillResult.Success("ok")
        JandalEvents.install(File.createTempFile("jandal-skill-outcome", ".log"))
        val before = JandalEvents.exportLines().size

        val result = SkillOutcomeEvents.execute(skill, emptyMap(), IntentRoute.CONFIRMATION)
        JandalEvents.awaitWritesForTest()

        assertEquals(SkillResult.Success("ok"), result)
        val line = JandalEvents.exportLines().drop(before).single { " skill outcome_probe_skill " in it }
        assertTrue("route=CONFIRMATION" in line, line)
        assertTrue("success=true" in line && "outcome=SUCCESS" in line, line)
    }

    @Test
    fun `failed skill result is recorded as a failure category`() = runBlocking {
        val skill = mockk<Skill>(relaxed = true)
        every { skill.name } returns "outcome_failure_skill"
        coEvery { skill.execute(any()) } returns SkillResult.Failure("outcome_failure_skill", "boom")
        JandalEvents.install(File.createTempFile("jandal-skill-outcome", ".log"))
        val before = JandalEvents.exportLines().size

        SkillOutcomeEvents.execute(skill, emptyMap(), IntentRoute.RECOVERY)
        JandalEvents.awaitWritesForTest()

        val line = JandalEvents.exportLines().drop(before).single { " skill outcome_failure_skill " in it }
        assertTrue("route=RECOVERY" in line, line)
        assertTrue("success=false" in line && "outcome=FAILURE" in line, line)
    }
}
