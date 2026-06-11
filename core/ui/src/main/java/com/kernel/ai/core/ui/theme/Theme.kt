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
    primary = FernGreen,
    onPrimary = Color.White,
    primaryContainer = FernGreenDark,
    onPrimaryContainer = Color(0xFFE7F5DE),
    secondary = PauaTeal,
    onSecondary = Color.White,
    secondaryContainer = PauaPurple,
    onSecondaryContainer = Color(0xFFE8D5FF),
    tertiary = PauaPurple,
    background = CharcoalDark,
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF444444),
)

private val JandalLightColorScheme = lightColorScheme(
    primary = FernGreen,
    onPrimary = Color.White,
    primaryContainer = FernGreenLight,
    onPrimaryContainer = Color(0xFF1B3A14),
    secondary = PauaTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB3E5E5),
    onSecondaryContainer = Color(0xFF002020),
    tertiary = PauaPurple,
    background = SandLight,
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE8E4DC),
    onSurfaceVariant = Color(0xFF4A4A4A),
    outline = Color(0xFF7A7670),
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
