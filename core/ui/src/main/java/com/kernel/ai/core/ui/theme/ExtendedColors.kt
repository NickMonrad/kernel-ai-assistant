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
    accent = AccentDark,
    successContainer = SuccessContainerDark,
    onSuccessContainer = OnSuccessContainerDark,
    warningContainer = WarningContainerDark,
    onWarningContainer = OnWarningContainerDark,
    warningOutline = WarningOutlineDark,
    neutralChip = NeutralChipDark,
    onNeutralChip = OnNeutralChipDark,
)

val JandalLightExtendedColors = JandalExtendedColors(
    accent = AccentLight,
    successContainer = SuccessContainerLight,
    onSuccessContainer = OnSuccessContainerLight,
    warningContainer = WarningContainerLight,
    onWarningContainer = OnWarningContainerLight,
    warningOutline = WarningOutlineLight,
    neutralChip = NeutralChipLight,
    onNeutralChip = OnNeutralChipLight,
)

/** Dark defaults; [KernelAITheme] supplies the light variant or the wallpaper accent. */
val LocalJandalColors = staticCompositionLocalOf { JandalDarkExtendedColors }
