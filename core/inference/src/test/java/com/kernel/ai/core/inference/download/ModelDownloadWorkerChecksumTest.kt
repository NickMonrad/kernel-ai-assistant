package com.kernel.ai.core.inference.download

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelDownloadWorkerChecksumTest {
    @Test
    fun `verified checksum matches case-insensitively and rejects a different digest`() {
        val file = File.createTempFile("model-checksum", ".bin")
        try {
            file.writeText("abc")

            assertTrue(matchesSha256(file, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
            assertFalse(matchesSha256(file, "0000000000000000000000000000000000000000000000000000000000000000"))
        } finally {
            file.delete()
        }
    }
}
