package com.kernel.ai.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Roles the Material scheme does not cover: the Jandal accent used for links, selected outlines and
 * text buttons, plus status-chip and warning colours (#1936 compose notes §1).
 */
@Immutable
data class JandalExtendedColors(
    val accent: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val warningOutline: Color,
    val neutralChip: Color,
    val onNeutralChip: Color,
)

val JandalDarkExtendedColors = JandalExtendedColors(
    accent = FernGreenLight,
    successContainer = Color(0xFF1F3A24),
    onSuccessContainer = Color(0xFFBFE3C4),
    warningContainer = Color(0xFF3D2E12),
    onWarningContainer = Color(0xFFF2D49B),
    warningOutline = Color(0xFFB8862E),
    neutralChip = Color(0xFF2E2E2E),
    onNeutralChip = Color(0xFFD0D0D0),
)

val JandalLightExtendedColors = JandalExtendedColors(
    accent = FernGreen,
    successContainer = Color(0xFFD7EBD2),
    onSuccessContainer = Color(0xFF1B3A1F),
    warningContainer = Color(0xFFFCE9BE),
    onWarningContainer = Color(0xFF4A3300),
    warningOutline = Color(0xFF9A6B12),
    neutralChip = Color(0xFFE6E0D6),
    onNeutralChip = Color(0xFF3F3A33),
)

/** Dark defaults; [KernelAITheme] supplies the light variant or the wallpaper accent. */
val LocalJandalColors = staticCompositionLocalOf { JandalDarkExtendedColors }
