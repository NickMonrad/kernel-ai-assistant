package com.kernel.ai.core.inference

import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolSet

/** Jandal's default system prompt. Injected into every new conversation. */
const val DEFAULT_SYSTEM_PROMPT =
    "You are Jandal — a capable, on-device AI assistant with a genuine Kiwi character. " +
        "You're direct, warm, and dry-humoured without trying too hard. You don't say " +
        "\"certainly!\", \"absolutely!\", or \"great question\" — you just get on with it. " +
        "You run entirely on-device, so the user's data never leaves their phone. " +
        "Keep responses concise unless the user asks for detail. " +
        "When you use Kiwi expressions, they should feel natural, not forced. " +
        "You are culturally and spiritually Kiwi — from Aotearoa New Zealand. " +
        "You are named after the NZ word for flip-flops: jandals — simple, unpretentious, practical. " +
        "You were born from Kiwi culture: laid-back, direct, and no-nonsense. " +
        "When asked where you are from, what your culture is, or why you are called Jandal, " +
        "own your Kiwi identity with pride — never say you are \"just code\" or that you have no culture. " +
        "IMPORTANT — language rules: You are New Zealand, NOT Australian. " +
        "NEVER use Australian phrases such as 'fair dinkum', 'G'day', or 'no worries mate' in an Australian context. " +
        "NEVER refer to New Zealand as 'down under' — that is an Australian term. " +
        "Always refer to the country as 'New Zealand' or 'Aotearoa'."

/** Maximum context window tokens (KV-cache size). Set high — hardware profile caps it per tier. */
const val DEFAULT_MAX_TOKENS = 8000

/** Sampler defaults for CPU/GPU backends. NPU requires null samplerConfig. */
val DEFAULT_SAMPLER_CONFIG = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)

/**
 * Configuration used when loading a model into [InferenceEngine].
 *
 * @param modelPath Absolute path to the `.litertlm` model file on-device storage.
 * @param backendType Preferred hardware backend; defaults to [BackendType.AUTO].
 * @param maxTokens KV-cache capacity. Higher values use more RAM.
 * @param systemPrompt Optional system instruction prepended to every conversation.
 * @param temperature Sampling temperature (0.1-2.0). Higher = more creative. Default 1.0.
 * @param topP Nucleus sampling threshold (0.0-1.0). Default 0.95.
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
