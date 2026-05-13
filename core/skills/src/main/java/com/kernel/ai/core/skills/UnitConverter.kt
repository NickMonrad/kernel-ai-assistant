package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.natives.UnitConversionEvaluator
import java.math.BigDecimal

/**
 * Public result type for [UnitConverter.convert]. Mirrors the fields that feature modules need
 * without exposing the internal [UnitConversionEvaluator] types across module boundaries.
 */
data class UnitConvertResult(
    val outputValue: BigDecimal,
    val fromUnitName: String,
    val toUnitName: String,
)

/**
 * Public façade over the internal [UnitConversionEvaluator].
 * Use this from feature modules instead of accessing the evaluator directly.
 */
object UnitConverter {
    fun convert(amount: String, from: String, to: String): UnitConvertResult {
        val result = UnitConversionEvaluator.convert(amount, from, to)
        return UnitConvertResult(
            outputValue = result.outputValue,
            fromUnitName = result.fromUnit.canonicalName,
            toUnitName = result.toUnit.canonicalName,
        )
    }

    fun supportedUnits(): List<String> =
        UnitConversionEvaluator.SupportedUnit.entries.map { it.canonicalName }

    fun unitsInSameCategoryAs(canonicalName: String): List<String> {
        val category = UnitConversionEvaluator.SupportedUnit.entries
            .find { it.canonicalName == canonicalName }
            ?.category
            ?: return supportedUnits()
        return UnitConversionEvaluator.SupportedUnit.entries
            .filter { it.category == category }
            .map { it.canonicalName }
    }
}
