package com.kernel.ai.feature.chat.model

data class ToolCallInfo(
    val skillName: String,
    val requestJson: String,   // the raw JSON Gemma-4 output
    val resultText: String,    // the skill result (success message or error)
    val isSuccess: Boolean,
)
