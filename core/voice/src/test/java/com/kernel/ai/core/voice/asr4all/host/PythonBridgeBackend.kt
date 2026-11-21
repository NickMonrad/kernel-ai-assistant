package com.kernel.ai.core.voice.asr4all.host

import com.kernel.ai.core.executorch.ExecuTorchException
import com.kernel.ai.core.executorch.ExecuTorchTensor
import com.kernel.ai.core.executorch.MethodSignature
import com.kernel.ai.core.voice.asr4all.Asr4AllBackend
import com.kernel.ai.core.voice.asr4all.Asr4AllBackendLoader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Host-side [Asr4AllBackend] that runs methods through `tools/asr4all-cleanroom/et_bridge.py`, a
 * black-box wrapper over the official `executorch` Python runtime. Used only by the host
 * harness so the real Kotlin engine can run against the real `.pte` on a desktop JVM; the engine
 * applies the same `:core:executorch` input and output checks as on the device.
 */
class PythonBridgeBackend private constructor(private val process: Process) : Asr4AllBackend {
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

    override fun run(signature: MethodSignature, inputs: List<ExecuTorchTensor>): List<ExecuTorchTensor> {
        ensureOpen()
        output.write(2)
        writeString(signature.method)
        writeInt(inputs.size)
        inputs.forEach(::writeTensor)
        output.flush()
        readStatus()
        return List(readInt()) { readTensor() }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            output.write(4)
            output.flush()
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun ensureOpen() = check(!closed) { "bridge program is closed" }

    private fun readStatus() {
        when (val status = input.read()) {
            0 -> Unit
            1 -> throw ExecuTorchException(readString())
            else -> throw ExecuTorchException("bridge exited or protocol error (status $status)")
        }
    }

    private fun writeTensor(t: ExecuTorchTensor) {
        val bytes = when (t) {
            is ExecuTorchTensor.Float32 -> ByteBuffer.allocate(t.data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                .also { it.asFloatBuffer().put(t.data) }
            is ExecuTorchTensor.Int64 -> ByteBuffer.allocate(t.data.size * 8).order(ByteOrder.LITTLE_ENDIAN)
                .also { it.asLongBuffer().put(t.data) }
            else -> throw ExecuTorchException("bridge does not carry ${t.dtype}")
        }
        output.write(if (t is ExecuTorchTensor.Float32) 0 else 1)
        writeInt(t.shape.size)
        t.shape.forEach(::writeLong)
        output.write(bytes.array())
    }

    private fun readTensor(): ExecuTorchTensor {
        val dtype = input.read()
        val shape = LongArray(readInt()) { readLong() }
        val numel = shape.fold(1L) { a, d -> a * d }.toInt()
        return when (dtype) {
            0 -> ExecuTorchTensor.Float32(FloatArray(numel).also { readBytes(numel * 4).asFloatBuffer().get(it) }, shape)
            1 -> ExecuTorchTensor.Int64(LongArray(numel).also { readBytes(numel * 8).asLongBuffer().get(it) }, shape)
            else -> throw ExecuTorchException("bridge protocol error: dtype $dtype")
        }
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

    companion object {
        /**
         * Loader that starts `python bridgeScript model.pte`, appending the bridge's stderr to
         * [stderrLog]. The engine's thread count is not used by the bridge.
         */
        fun loader(python: String, bridgeScript: File, stderrLog: File): Asr4AllBackendLoader = Asr4AllBackendLoader { file, _ ->
            val process = ProcessBuilder(python, bridgeScript.path, file.path)
                .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
                .start()
            PythonBridgeBackend(process)
        }
    }
}
