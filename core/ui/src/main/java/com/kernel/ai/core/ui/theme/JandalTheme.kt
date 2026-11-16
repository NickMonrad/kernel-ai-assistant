package com.kernel.ai.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/** Access to the Jandal extended colour roles inside [KernelAITheme]. */
object JandalTheme {
    val colors: JandalExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalJandalColors.current
}
