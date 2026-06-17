package com.kernel.ai.feature.convert

enum class ConvertTab { CURRENCY, UNIT, COOKING }

/**
 * Maps a query-parameter tab value to a [ConvertTab].
 *
 * Accepts:
 * - "currency" -> ConvertTab.CURRENCY
 * - "unit" / "units" -> ConvertTab.UNIT
 * - "cooking" -> ConvertTab.COOKING
 */
fun mapQueryParamToConvertTab(param: String): ConvertTab? {
    return when (param.lowercase()) {
        "currency" -> ConvertTab.CURRENCY
        "unit", "units" -> ConvertTab.UNIT
        "cooking" -> ConvertTab.COOKING
        else -> null
    }
}
