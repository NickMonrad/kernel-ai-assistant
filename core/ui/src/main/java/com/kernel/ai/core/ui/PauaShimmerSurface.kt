package com.kernel.ai.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.PauaDeep
import com.kernel.ai.core.ui.theme.PauaPurple
import com.kernel.ai.core.ui.theme.PauaTeal

/**
 * A lightweight infinite shimmer surface using the Jandal Paua palette
 * (Paua Deep → Paua Teal → Paua Purple → Paua Deep).
 *
 * Apply as a background or overlay where a branded loading/thinking
 * treatment is needed. Animates a horizontal gradient.
 *
 * @param modifier Modifier for sizing/positioning.
 * @param shape RoundedCornerShape for the clip surface (default 12.dp).
 * @param content Optional content overlaid on top of the shimmer.
 */
@Composable
fun PauaShimmerSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pauaShimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )

    Box(
        modifier = modifier
            .clip(shape)
            .drawWithContent {
                val gradient = Brush.linearGradient(
                    colors = listOf(
                        PauaDeep,
                        PauaDeep.copy(alpha = 0.7f),
                        PauaTeal,
                        PauaPurple,
                        PauaTeal,
                        PauaDeep.copy(alpha = 0.7f),
                        PauaDeep,
                    ),
                    start = Offset(shimmerOffset * size.width, 0f),
                    end = Offset((shimmerOffset + 2f) * size.width, 0f),
                )
                drawRect(gradient)
                drawContent()
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
