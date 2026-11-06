package com.kernel.ai.debug.llamaprobe

import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Utf8TokenDecoderTest {
    @Test
    fun `incomplete multibyte sequences are buffered across native chunks`() {
        val decoder = Utf8TokenDecoder()
        val bytes = "A😃é".toByteArray(StandardCharsets.UTF_8)

        assertEquals("A", decoder.append(bytes.copyOfRange(0, 2), 2))
        assertEquals("", decoder.append(bytes.copyOfRange(2, 4), 2))
        assertEquals("😃", decoder.append(bytes.copyOfRange(4, 6), 2))
        assertEquals("é", decoder.append(bytes.copyOfRange(6, 7), 1))
        assertEquals("", decoder.finish())
    }

    @Test
    fun `malformed and truncated sequences fail instead of emitting replacement text`() {
        val malformed = Utf8TokenDecoder()
        assertThrows(CharacterCodingException::class.java) {
            malformed.append(byteArrayOf(0xC0.toByte()), 1)
        }

        val truncated = Utf8TokenDecoder()
        assertEquals("", truncated.append(byteArrayOf(0xE2.toByte(), 0x82.toByte()), 2))
        assertThrows(CharacterCodingException::class.java) { truncated.finish() }
    }
}
