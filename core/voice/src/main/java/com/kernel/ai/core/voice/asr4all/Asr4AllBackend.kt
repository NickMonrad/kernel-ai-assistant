package com.kernel.ai.core.voice.asr4all

import com.kernel.ai.core.executorch.ExecuTorchModel
import com.kernel.ai.core.executorch.ExecuTorchTensor
import com.kernel.ai.core.executorch.MethodSignature
import java.io.File

/**
 * The calls the engine makes on a loaded program. In the app this is [ExecuTorchModel] from
 * `:core:executorch` ([ExecuTorchBackend]); the JVM tests and the host parity harness supply
 * their own, because `ExecuTorchModel` cannot be constructed without the native runtime.
 * Every implementation receives the same [MethodSignature]s, and the engine checks inputs and
 * outputs against them itself, so a non-production backend cannot skip the shape checks.
 */
interface Asr4AllBackend : AutoCloseable {
    val methodNames: Set<String>

    fun run(signature: MethodSignature, inputs: List<ExecuTorchTensor>): List<ExecuTorchTensor>
}

/** Opens a program for the engine with an XNNPACK thread count. */
fun interface Asr4AllBackendLoader {
    fun load(file: File, threads: Int): Asr4AllBackend
}

/** Production backend: ExecuTorch through `:core:executorch`, the app's only ExecuTorch dependency. */
object ExecuTorchBackend : Asr4AllBackendLoader {
    override fun load(file: File, threads: Int): Asr4AllBackend {
        val model = ExecuTorchModel.load(file, threads)
        return object : Asr4AllBackend {
            override val methodNames: Set<String> get() = model.methodNames
            override fun run(signature: MethodSignature, inputs: List<ExecuTorchTensor>) = model.run(signature, inputs)
            override fun close() = model.close()
        }
    }
}
