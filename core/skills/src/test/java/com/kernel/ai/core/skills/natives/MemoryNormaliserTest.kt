package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MemoryNormaliserTest {

    @Test
    fun `conjugates walk to walks for third person`() {
        assertEquals(
            "Nick walks Nick's dog twice a day",
            normaliseSaveContent("I walk my dog twice a day", "Nick"),
        )
    }

    @Test
    fun `conjugates common daily-activity verbs`() {
        assertEquals("Nick cooks dinner every night", normaliseSaveContent("I cook dinner every night", "Nick"))
        assertEquals("Nick rides Nick's bike to work", normaliseSaveContent("I ride my bike to work", "Nick"))
        assertEquals("Nick feeds the cat", normaliseSaveContent("I feed the cat", "Nick"))
    }

    @Test
    fun `applies -es to sibilant verb endings`() {
        assertEquals("Nick washes the dishes", normaliseSaveContent("I wash the dishes", "Nick"))
        assertEquals("Nick brushes Nick's teeth", normaliseSaveContent("I brush my teeth", "Nick"))
    }

    @Test
    fun `applies -ies to consonant-y verb endings`() {
        assertEquals("Nick studies Spanish", normaliseSaveContent("I study Spanish", "Nick"))
    }

    @Test
    fun `preserves existing irregular conjugations`() {
        assertEquals("Nick is lactose intolerant", normaliseSaveContent("I'm lactose intolerant", "Nick"))
        assertEquals("Nick has two cats", normaliseSaveContent("I have two cats", "Nick"))
    }

    @Test
    fun `returns content unchanged when name is blank`() {
        assertEquals("I walk my dog", normaliseSaveContent("I walk my dog", null))
        assertEquals("I walk my dog", normaliseSaveContent("I walk my dog", ""))
    }
}
