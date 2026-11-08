package com.kernel.ai.core.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.ui.theme.PauaDeep
import com.kernel.ai.core.ui.theme.PauaPurple
import com.kernel.ai.core.ui.theme.PauaTeal

/**
 * A lightweight infinite shimmer indicator using Paua-brand colours.
 *
 * Animates a small box through the Paua palette (PauaDeep → PauaTeal → PauaPurple → PauaDeep)
 * using a smooth cross-fade. Designed to replace [CircularProgressIndicator] in loading,
 * thinking, and generation states without adding jank during local inference.
 *
 * @param modifier Modifier for the indicator.
 * @param size Diameter of the circular indicator.
 */
@Composable
fun PauaLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val infiniteTransition = rememberInfiniteTransition()
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    val color = when {
        phase < 0.5f -> lerp(PauaDeep, PauaTeal, phase / 0.5f)
        else -> lerp(PauaTeal, PauaPurple, (phase - 0.5f) / 0.5f)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * A shimmer-animated Paua gradient bar with a moving highlight sweep.
 *
 * Renders a thin rounded bar with a dim Paua base track and a bright animated
 * gradient that sweeps left-to-right repeatedly. The sweep uses PauaTeal and
 * PauaPurple for the bright highlight, creating a clearly visible shimmer effect.
 *
 * @param modifier Modifier for the bar.
 * @param width Width of the bar.
 * @param height Height / thickness of the bar.
 */
@Composable
fun PauaShimmerBar(
    modifier: Modifier = Modifier,
    width: Dp = 220.dp,
    height: Dp = 5.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "paua_shimmer")
    val phase by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "paua_shimmer_phase",
    )

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(PauaDeep.copy(alpha = 0.28f), RoundedCornerShape(height / 2))
            .drawWithContent {
                drawContent()

                val barWidth = size.width
                val sweepWidth = barWidth * 0.45f
                val startX = (barWidth * phase) - sweepWidth
                val endX = startX + sweepWidth

                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            PauaDeep.copy(alpha = 0.0f),
                            PauaTeal.copy(alpha = 0.95f),
                            PauaPurple.copy(alpha = 0.95f),
                            PauaDeep.copy(alpha = 0.0f),
                        ),
                        start = Offset(startX, 0f),
                        end = Offset(endX, 0f),
                    ),
                )
            },
    )
}

/**
 * A circular animated loading ring with visible rotation.
 *
 * Draws a rotating arc with a sweep gradient through PauaDeep → PauaTeal → PauaPurple.
 * The canvas rotates continuously so motion is clearly visible even in screenshots.
 * Serves as a prominent Paua-branded replacement for [CircularProgressIndicator]
 * in full-screen loading states.
 *
 * @param modifier Modifier for the ring.
 * @param size Diameter of the ring in dp.
 * @param strokeWidth Thickness of the arc stroke in dp.
 */
@Composable
fun PauaLoadingRing(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    strokeWidth: Dp = 5.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "paua_ring")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "paua_ring_rotation",
    )

    Canvas(modifier = modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val diameter = size.toPx() - stroke
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        val arcSize = Size(diameter, diameter)

        // Track ring (faint full circle)
        drawArc(
            color = PauaDeep.copy(alpha = 0.15f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        // Rotating arc with sweep gradient — canvas rotates so the gradient moves visibly
        rotate(rotation, pivot = center) {
            drawArc(
                brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                    colors = listOf(
                        PauaDeep,
                        PauaTeal,
                        PauaPurple,
                        PauaDeep,
                    ),
                ),
                startAngle = 0f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}


/**
 * A row of three Paua dots that pulse sequentially, styled like a typing indicator.
 *
 * Lightweight: only three [PauaLoadingIndicator] instances with staggered delays.
 * Suitable for inline thinking / "working on your reply" indicators.
 *
 * @param modifier Modifier for the row.
 * @param dotSize Diameter of each Paua dot.
 */
@Composable
fun PauaDotsIndicator(
    modifier: Modifier = Modifier,
    dotSize: Dp = 9.dp,
) {
    val infiniteTransition = rememberInfiniteTransition()
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    // Dots never drop below 0.35 alpha so they never look broken/static
    fun dotAlpha(offset: Float): Float {
        val shifted = (phase - offset).coerceIn(0f, 1f)
        val pulse = if (shifted < 0.5f) shifted * 2f else (1f - shifted) * 2f
        return 0.35f + (pulse * 0.65f)
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0..2) {
            val alpha = dotAlpha(i * 0.25f)
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(
                        when (i) {
                            0 -> PauaDeep.copy(alpha = alpha)
                            1 -> PauaTeal.copy(alpha = alpha)
                            else -> PauaPurple.copy(alpha = alpha)
                        },
                    ),
            )
        }
    }
}
