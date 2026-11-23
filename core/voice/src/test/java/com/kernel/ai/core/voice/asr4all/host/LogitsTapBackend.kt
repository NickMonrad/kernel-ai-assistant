package com.kernel.ai.core.voice.asr4all.host

import com.kernel.ai.core.executorch.ExecuTorchTensor
import com.kernel.ai.core.executorch.MethodSignature
import com.kernel.ai.core.voice.asr4all.Asr4AllBackend

/**
 * Test-only [Asr4AllBackend] wrapper for the bias-oracle dump (#2093). While [start] is active it
 * copies output 0 of every encoder (`stream_*`) call, the `[1, C, vocab]` CTC logits, and returns
 * them in call order from [stop]. Outputs pass through unchanged, so engine behaviour is identical.
 */
class LogitsTapBackend(private val inner: Asr4AllBackend) : Asr4AllBackend by inner {
    private var captured: MutableList<FloatArray>? = null

    fun start() {
        captured = mutableListOf()
    }

    fun stop(): List<FloatArray> = captured.orEmpty().also { captured = null }

    override fun run(signature: MethodSignature, inputs: List<ExecuTorchTensor>): List<ExecuTorchTensor> {
        val outputs = inner.run(signature, inputs)
        val sink = captured
        if (sink != null && signature.method.startsWith("stream_")) {
            sink += (outputs[0] as ExecuTorchTensor.Float32).data.copyOf()
        }
        return outputs
    }
}
