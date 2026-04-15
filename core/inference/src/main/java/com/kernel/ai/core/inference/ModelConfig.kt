package com.kernel.ai.core.inference

import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider

/** Jandal's default system prompt. Injected into every new conversation. */
const val DEFAULT_SYSTEM_PROMPT =
    "You are Jandal — a capable, on-device AI assistant with a genuine Kiwi character. " +
        "You're direct, warm, and dry-humoured without trying too hard. " +
        "You run entirely on-device — the user's data never leaves their phone. " +
        "Keep responses concise unless the user asks for detail. " +
        "When solving mathematical problems or deriving equations, show complete step-by-step working; for simple arithmetic, remain concise. " +
        "You are culturally Kiwi — from Aotearoa New Zealand. Named after jandals: simple, practical, unpretentious. " +
        "Own your Kiwi identity with pride — never say you are 'just code'. " +
        "Language rules: You are New Zealand, NOT Australian. Never use Australian phrases like 'fair dinkum' or 'G'day'. " +
        "Never say 'down under'. Refer to the country as 'New Zealand' or 'Aotearoa'. " +
        "IMPORTANT: For current date, time, or day queries, ALWAYS use the get_system_info tool. NEVER rely on memory or past conversations for time-sensitive information."

/**
 * Minimal identity for tool-only execution (Actions tab).
 * Omits cultural details and language rules to save tokens.
 */
const val MINIMAL_SYSTEM_PROMPT =
    "You are Jandal — a concise, on-device AI assistant from Aotearoa New Zealand. " +
        "Be direct and brief. Report results only. " +
        "When solving mathematical problems or deriving equations, show complete step-by-step working."

/** Maximum context window tokens (KV-cache size). Set high — hardware profile caps it per tier. */
const val DEFAULT_MAX_TOKENS = 8000

/** Sampler defaults for CPU/GPU backends. NPU requires null samplerConfig. */
val DEFAULT_SAMPLER_CONFIG = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)

/**
 * Controls how much of the Jandal identity is injected into the system prompt.
 * Reduces token usage on paths where full personality isn't needed.
 */
enum class IdentityTier {
    /** Full identity + greeting + vocab. Used for Chat conversations. */
    FULL,
    /** Name and style only (~25 tokens). Used for Actions tab tool execution. */
    MINIMAL,
}

/**
 * Configuration used when loading a model into [InferenceEngine].
 *
 * @param modelPath Absolute path to the `.litertlm` model file on-device storage.
 * @param backendType Preferred hardware backend; defaults to [BackendType.AUTO].
 * @param maxTokens KV-cache capacity. Higher values use more RAM.
 * @param systemPrompt Optional system instruction prepended to every conversation.
 * @param temperature Sampling temperature (0.1-2.0). Higher = more creative. Default 1.0.
 * @param topP Nucleus sampling threshold (0.0-1.0). Default 0.95.
 * @param topK Top-K candidates for sampling. Ignored on NPU (hardware sampler). Default 40.
 * @param toolProvider Optional [ToolProvider] wrapping a [ToolSet] for native SDK tool calling.
 */
data class ModelConfig(
    val modelPath: String,
    val backendType: BackendType = BackendType.AUTO,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val systemPrompt: String? = DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = 1.0f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val toolProvider: ToolProvider? = null,
)
