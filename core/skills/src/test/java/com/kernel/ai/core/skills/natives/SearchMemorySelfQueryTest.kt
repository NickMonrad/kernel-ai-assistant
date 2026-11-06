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
        assertTrue(isPersonalMemoryRecallQuery("Do you remember my favourite cake?", "Nick"))
        assertTrue(isPersonalMemoryRecallQuery("What did we discuss about our holiday?", "Nick"))
        assertFalse(isPersonalMemoryRecallQuery("Do you remember what a pavlova is?", "Nick"))
        assertFalse(isPersonalMemoryRecallQuery("Do you remember what a pavlova is? Tell me.", "Nick"))
        assertFalse(
            isPersonalMemoryRecallQuery("Do you remember what a pavlova is? My sister asked me to check.", "Nick"),
        )
        assertFalse(isPersonalMemoryRecallQuery("What do you remember about Waitangi Day?", "Nick"))
        assertFalse(isPersonalMemoryRecallQuery("Can you recall what a pavlova is?", "Nick"))
        assertFalse(isPersonalMemoryRecallQuery("Can you recall the capital of France for me?", "Nick"))
        assertFalse(isPersonalMemoryRecallQuery("What did I say earlier?", "Nick"))
        assertFalse(isPersonalMemoryRecallQuery("Do not call any tools. What did I say earlier?", "Nick"))
        assertFalse(isPersonalMemoryRecallQuery("What did we discuss just now?", "Nick"))
    }
}
