package com.kernel.ai.feature.convert

sealed class ConversionResult {
    data class Success(val displayValue: String, val rateInfo: String? = null) : ConversionResult()
    data class Error(val message: String) : ConversionResult()
    object Loading : ConversionResult()
}
