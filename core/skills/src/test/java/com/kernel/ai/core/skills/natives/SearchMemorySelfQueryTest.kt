package com.kernel.ai.core.skills.natives

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchMemorySelfQueryTest {

    @Test
    fun `treats bare self-referential phrases as generic`() {
        assertTrue(isGenericSelfQuery("about me", "Nick"))
        assertTrue(isGenericSelfQuery("me", "Nick"))
        assertTrue(isGenericSelfQuery("myself", null))
        assertTrue(isGenericSelfQuery("everything about me", "Nick"))
        assertTrue(isGenericSelfQuery("what do you know about me", "Nick"))
        assertTrue(isGenericSelfQuery("about Nick", "Nick"))
    }

    @Test
    fun `treats topical queries with content words as non-generic`() {
        assertFalse(isGenericSelfQuery("about my trip to Japan", "Nick"))
        assertFalse(isGenericSelfQuery("my dog", "Nick"))
        assertFalse(isGenericSelfQuery("project", "Nick"))
        assertFalse(isGenericSelfQuery("my job", "Nick"))
    }

    @Test
    fun `does not treat assistant-referential queries as generic`() {
        assertFalse(isGenericSelfQuery("about you", "Nick"))
        assertFalse(isGenericSelfQuery("", "Nick"))
    }

    @Test
    fun `detects personal fact recall requests without capturing unrelated questions`() {
        assertTrue(isPersonalMemoryRecallQuery("What is my favourite tea?", "Nick"))
        assertTrue(isPersonalMemoryRecallQuery("What do you remember about my trip to Japan?", "Nick"))
        assertTrue(isPersonalMemoryRecallQuery("What did I tell you about my job?", "Nick"))
        assertFalse(isPersonalMemoryRecallQuery("What is the weather today?", "Nick"))
        assertFalse(isPersonalMemoryRecallQuery("Remember to buy tea", "Nick"))
    }
}
