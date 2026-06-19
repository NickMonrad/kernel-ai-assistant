package com.kernel.ai.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val JandalDarkColorScheme = darkColorScheme(
    // Primary — Jandal green
    primary = FernGreen,
    onPrimary = Color.White,
    primaryContainer = FernGreenDark,
    onPrimaryContainer = Color(0xFFE7F5DE),

    // Secondary — Paua teal
    secondary = PauaTeal,
    onSecondary = Color.White,
    secondaryContainer = FernGreenDark,
    onSecondaryContainer = Color(0xFFD0E8C8),

    // Tertiary — Paua purple
    tertiary = PauaPurple,

    // Error
    error = Color(0xFFCF6679),
    onError = Color.Black,
    errorContainer = Color(0xFF442026),
    onErrorContainer = Color(0xFFFFDAD6),

    // Surface hierarchy — unified staircase
    // All large page surfaces share the same base (#1A1A1A) to eliminate
    // the accidental black/grey patchwork that occurred when background
    // and surface used visibly different values.
    background = Color(0xFF1A1A1A),            // = surface — page background
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF1A1A1A),                // scaffold content, bottom nav
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF2A2A2A),         // variant containers (inputs, chips)
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainerLowest = Color(0xFF0D0D0D), // deepest: drawer backdrop, modals
    surfaceContainerLow = Color(0xFF151515),     // top app bar
    surfaceContainer = Color(0xFF1A1A1A),        // = surface
    surfaceContainerHigh = Color(0xFF202020),    // cards, elevated surfaces
    surfaceContainerHighest = Color(0xFF262626), // highest elevation (action cards)

    // Outline
    outline = Color(0xFF444444),
    outlineVariant = Color(0xFF383838),
)

private val JandalLightColorScheme = lightColorScheme(
    // Primary — Jandal green
    primary = FernGreen,
    onPrimary = Color.White,
    primaryContainer = FernGreenLight,
    onPrimaryContainer = Color(0xFF1B3A14),

    // Secondary — Paua teal
    secondary = PauaTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB3E5E5),
    onSecondaryContainer = Color(0xFF002020),

    // Tertiary — Paua purple
    tertiary = PauaPurple,

    // Error
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    // Surface hierarchy
    background = SandLight,
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE8E4DC),
    onSurfaceVariant = Color(0xFF4A4A4A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F6F0),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF0EDE6),

    // Outline
    outline = Color(0xFF7A7670),
    outlineVariant = Color(0xFFD0CCC4),
)

@Composable
fun KernelAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> JandalDarkColorScheme
        else -> JandalLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
