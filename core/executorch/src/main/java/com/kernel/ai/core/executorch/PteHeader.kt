package com.kernel.ai.core.executorch

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cheap structural check of a `.pte` file before it reaches the runtime.
 *
 * ExecuTorch 1.5.1 on Android aborts the process instead of throwing when a load fails (see
 * docs/architecture/executorch-runtime.md), so a wrong, truncated or partially downloaded file
 * must be rejected here. This checks the flatbuffer file identifier and, when the extended
 * header is present, that the file holds the program and segment bytes it declares. It is not a
 * content check: callers loading downloaded models must still verify their SHA-256 first.
 */
internal object PteHeader {
    /** Flatbuffer identifier the bundled runtime accepts; changes with the .pte schema version. */
    const val PROGRAM_IDENTIFIER = "ET12"

    private const val EXTENDED_MAGIC = "eh00"
    private const val EXTENDED_MIN_LENGTH = 24 // magic, length, program size, segment base offset
    private const val HEAD_BYTES = 40 // flatbuffer root offset + identifier + extended header

    /** @throws ExecuTorchException if [file] is not a complete program for this runtime. */
    fun check(file: File) {
        val size = file.length()
        val head = ByteArray(minOf(size, HEAD_BYTES.toLong()).toInt())
        RandomAccessFile(file, "r").use { it.readFully(head) }
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)

        fun ascii(offset: Int) =
            if (head.size >= offset + 4) String(head, offset, 4, Charsets.US_ASCII) else ""

        val identifier = ascii(4)
        if (identifier != PROGRAM_IDENTIFIER) {
            throw ExecuTorchException(
                "${file.path} is not an ExecuTorch $PROGRAM_IDENTIFIER program " +
                    "(identifier '${identifier.filter { it in ' '..'~' }}', $size bytes)",
            )
        }
        if (ascii(8) != EXTENDED_MAGIC) return

        val length = if (head.size >= 16) buffer.getInt(12) else 0
        if (length < EXTENDED_MIN_LENGTH || head.size < 8 + length.coerceAtMost(32)) {
            throw ExecuTorchException("${file.path} has a malformed extended header ($size bytes)")
        }
        val programSize = buffer.getLong(16)
        val segmentBase = buffer.getLong(24)
        val segmentSize = if (length >= 32) buffer.getLong(32) else 0L
        val required = maxOf(programSize, if (segmentSize > 0) segmentBase + segmentSize else segmentBase)
        if (programSize < 0 || segmentBase < 0 || segmentSize < 0 || required > size) {
            throw ExecuTorchException(
                "${file.path} is truncated: its header declares $required bytes but the file has $size",
            )
        }
    }
}
