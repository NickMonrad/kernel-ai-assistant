package com.kernel.ai.core.inference

import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolSet

/** Jandal's default system prompt. Injected into every new conversation. */
const val DEFAULT_SYSTEM_PROMPT =
    "You are Jandal, a friendly and capable AI assistant. You're concise, helpful, and have a " +
        "slightly playful NZ character — like a smart mate who happens to know a lot. You run " +
        "entirely on-device, so the user's data never leaves their phone. " +
        "Keep responses short unless asked for detail."

/** Maximum context window tokens (KV-cache size). Set high — hardware profile caps it per tier. */
const val DEFAULT_MAX_TOKENS = 8192

/** Sampler defaults for CPU/GPU backends. NPU requires null samplerConfig. */
val DEFAULT_SAMPLER_CONFIG = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)

/**
 * Configuration used when loading a model into [InferenceEngine].
 *
 * @param modelPath Absolute path to the `.litertlm` model file on-device storage.
 * @param backendType Preferred hardware backend; defaults to [BackendType.AUTO].
 * @param maxTokens KV-cache capacity. Higher values use more RAM.
 * @param systemPrompt Optional system instruction prepended to every conversation.
 * @param temperature Sampling temperature (0.1–2.0). Higher = more creative. Default 1.0.
 * @param topP Nucleus sampling threshold (0.0–1.0). Default 0.95.
 */
data class ModelConfig(
    val modelPath: String,
    val backendType: BackendType = BackendType.AUTO,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val systemPrompt: String? = DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = 1.0f,
    val topP: Float = 0.95f,
    val toolSet: ToolSet? = null,
)
