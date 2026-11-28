package com.kernel.ai.core.voice.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KokoroVoiceTest {

    @Test
    fun `no stored voice or an unknown stored name defaults to Emma`() {
        assertEquals(KokoroVoice.EMMA, KokoroVoice.fromStorage(null))
        assertEquals(KokoroVoice.EMMA, KokoroVoice.fromStorage("not-a-voice"))
    }

    @Test
    fun `every voice round-trips through its stored name`() {
        KokoroVoice.entries.forEach { assertEquals(it, KokoroVoice.fromStorage(it.name)) }
    }
}
