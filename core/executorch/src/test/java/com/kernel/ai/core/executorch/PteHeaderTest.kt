package com.kernel.ai.core.executorch

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class PteHeaderTest {
    @TempDir
    lateinit var dir: File

    private fun file(bytes: ByteArray) = File(dir, "p.pte").apply { writeBytes(bytes) }

    @Test
    fun `accepts a complete program with an extended header`() {
        assertDoesNotThrow { PteHeader.check(file(pte(programSize = 1984, segmentBase = 2048, segmentSize = 1036, total = 3084))) }
    }

    @Test
    fun `accepts a program without an extended header`() {
        val bytes = ByteArray(64).also { "ET12".toByteArray().copyInto(it, 4) }
        assertDoesNotThrow { PteHeader.check(file(bytes)) }
    }

    @Test
    fun `rejects a file that is not an ExecuTorch program`() {
        val error = assertThrows<ExecuTorchException> { PteHeader.check(file("<html>not found</html>".toByteArray())) }
        assertTrue(error.message!!.contains("ET12"), error.message)
    }

    @Test
    fun `rejects a program for a different schema version`() {
        val bytes = pte(programSize = 100, segmentBase = 0, segmentSize = 0, total = 200)
        "ET11".toByteArray().copyInto(bytes, 4)
        assertThrows<ExecuTorchException> { PteHeader.check(file(bytes)) }
    }

    @Test
    fun `rejects a truncated download`() {
        val error = assertThrows<ExecuTorchException> {
            PteHeader.check(file(pte(programSize = 1984, segmentBase = 2048, segmentSize = 1036, total = 3000)))
        }
        assertTrue(error.message!!.contains("3084"), error.message)
    }

    @Test
    fun `rejects an empty file`() {
        assertThrows<ExecuTorchException> { PteHeader.check(file(ByteArray(0))) }
    }

    companion object {
        /** A `.pte` head as written by executorch.exir 1.5.1, padded to [total] bytes. */
        fun pte(programSize: Long, segmentBase: Long, segmentSize: Long, total: Int): ByteArray {
            val bytes = ByteArray(total)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, 0x3c)
                put(4, "ET12".toByteArray())
                put(8, "eh00".toByteArray())
                putInt(12, 32)
                putLong(16, programSize)
                putLong(24, segmentBase)
                putLong(32, segmentSize)
            }
            return bytes
        }
    }
}
