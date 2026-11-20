package com.kernel.ai.core.voice.asr4all.host

import com.kernel.ai.core.voice.asr4all.runtime.EtDType
import com.kernel.ai.core.voice.asr4all.runtime.EtExecutionException
import com.kernel.ai.core.voice.asr4all.runtime.EtProgram
import com.kernel.ai.core.voice.asr4all.runtime.EtProgramClosedException
import com.kernel.ai.core.voice.asr4all.runtime.EtProgramLoader
import com.kernel.ai.core.voice.asr4all.runtime.EtTensor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host-side [EtProgram] that runs methods through `tools/asr4all-cleanroom/et_bridge.py`, a
 * black-box wrapper over the official `executorch` Python runtime. Used only by the host
 * harness so the real Kotlin engine can run against the real `.pte` on a desktop JVM.
 */
class PythonBridgeProgram private constructor(private val process: Process) : EtProgram {
    private val input = DataInputStream(BufferedInputStream(process.inputStream, 1 shl 20))
    private val output = BufferedOutputStream(process.outputStream, 1 shl 20)
    private var closed = false

    override val methodNames: Set<String> by lazy {
        ensureOpen()
        output.write(1)
        output.flush()
        readStatus()
        val n = readInt()
        (0 until n).map { readString() }.toSet()
    }

    override fun execute(method: String, inputs: List<EtTensor>): List<EtTensor> {
        ensureOpen()
        output.write(2)
        writeString(method)
        writeInt(inputs.size)
        for (t in inputs) {
            if (t is RemoteTensor && t.owner === this) {
                output.write(1)
                writeLong(t.handle)
            } else {
                output.write(0)
                writeTensor(t)
            }
        }
        output.flush()
        readStatus()
        val n = readInt()
        return (0 until n).map {
            when (input.read()) {
                0 -> readTensor()
                1 -> {
                    val handle = readLong()
                    val dtype = dtypeOf(input.read())
                    val ndim = readInt()
                    RemoteTensor(this, handle, dtype, LongArray(ndim) { readLong() })
                }
                else -> throw EtExecutionException("bridge protocol error")
            }
        }
    }

    internal fun fetch(handle: Long): EtTensor {
        ensureOpen()
        output.write(3)
        writeLong(handle)
        output.flush()
        readStatus()
        return readTensor()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            output.write(4)
            output.flush()
        }
        process.waitFor()
    }

    private fun ensureOpen() {
        if (closed) throw EtProgramClosedException("program is closed")
    }

    private fun readStatus() {
        when (val status = input.read()) {
            0 -> Unit
            1 -> throw EtExecutionException(readString())
            else -> throw EtExecutionException("bridge exited or protocol error (status $status)")
        }
    }

    private fun writeTensor(t: EtTensor) {
        output.write(if (t.dtype == EtDType.FLOAT32) 0 else 1)
        writeInt(t.shape.size)
        t.shape.forEach(::writeLong)
        val bytes = when (t.dtype) {
            EtDType.FLOAT32 -> ByteBuffer.allocate(t.floats().size * 4).order(ByteOrder.LITTLE_ENDIAN)
                .also { it.asFloatBuffer().put(t.floats()) }
            EtDType.INT64 -> ByteBuffer.allocate(t.longs().size * 8).order(ByteOrder.LITTLE_ENDIAN)
                .also { it.asLongBuffer().put(t.longs()) }
        }
        output.write(bytes.array())
    }

    private fun readTensor(): EtTensor {
        val dtype = dtypeOf(input.read())
        val ndim = readInt()
        val shape = LongArray(ndim) { readLong() }
        val numel = EtTensor.numel(shape).toInt()
        return when (dtype) {
            EtDType.FLOAT32 -> {
                val buf = readBytes(numel * 4).asFloatBuffer()
                EtTensor.of(FloatArray(numel).also { buf.get(it) }, *shape)
            }
            EtDType.INT64 -> {
                val buf = readBytes(numel * 8).asLongBuffer()
                EtTensor.of(LongArray(numel).also { buf.get(it) }, *shape)
            }
        }
    }

    private fun dtypeOf(code: Int) = when (code) {
        0 -> EtDType.FLOAT32
        1 -> EtDType.INT64
        else -> throw EtExecutionException("bridge protocol error: dtype $code")
    }

    private fun readBytes(n: Int): ByteBuffer {
        val b = ByteArray(n)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun readInt(): Int = readBytes(4).int
    private fun readLong(): Long = readBytes(8).long
    private fun readString(): String = String(readBytes(readInt()).array(), Charsets.UTF_8)
    private fun writeInt(v: Int) = output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    private fun writeLong(v: Long) = output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
    private fun writeString(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        output.write(b)
    }

    /** A tensor that stays in the bridge process; its contents are fetched only when read. */
    private class RemoteTensor(
        val owner: PythonBridgeProgram,
        val handle: Long,
        override val dtype: EtDType,
        override val shape: LongArray,
    ) : EtTensor {
        private val local by lazy { owner.fetch(handle) }
        override fun floats(): FloatArray = local.floats()
        override fun longs(): LongArray = local.longs()
    }

    companion object {
        /**
         * Loader that starts `python bridgeScript model.pte`, appending the bridge's stderr to
         * [stderrLog]. [EtProgramLoader]'s thread count is not used by the bridge.
         */
        fun loader(python: String, bridgeScript: File, stderrLog: File): EtProgramLoader = EtProgramLoader { file, _ ->
            val process = ProcessBuilder(python, bridgeScript.path, file.path)
                .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
                .start()
            PythonBridgeProgram(process)
        }
    }
}
