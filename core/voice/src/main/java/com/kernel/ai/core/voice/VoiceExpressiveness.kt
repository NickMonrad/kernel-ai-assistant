package com.kernel.ai.core.voice

enum class VoiceExpressiveness(val noiseScale: Float, val noiseScaleW: Float) {
    LOW(noiseScale = 0.3f, noiseScaleW = 0.3f),
    MEDIUM(noiseScale = 0.667f, noiseScaleW = 0.8f),   // Sherpa default
    HIGH(noiseScale = 1.0f, noiseScaleW = 1.0f),
}
