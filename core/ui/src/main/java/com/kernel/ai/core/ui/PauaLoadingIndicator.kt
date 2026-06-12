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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * A shimmer-animated Paua gradient bar.
 *
 * Renders a thin rounded bar whose colour sweeps through the Paua palette.
 * Useful as a horizontal loading indicator beneath content or alongside text labels.
 *
 * @param modifier Modifier for the bar.
 * @param width Width of the bar.
 * @param height Height / thickness of the bar.
 */
@Composable
fun PauaShimmerBar(
    modifier: Modifier = Modifier,
    width: Dp = 60.dp,
    height: Dp = 4.dp,
) {
    val infiniteTransition = rememberInfiniteTransition()
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    val color = when {
        phase < 0.33f -> lerp(PauaDeep, PauaTeal, phase / 0.33f)
        phase < 0.66f -> lerp(PauaTeal, PauaPurple, (phase - 0.33f) / 0.33f)
        else -> lerp(PauaPurple, PauaDeep, (phase - 0.66f) / 0.33f)
    }

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(color),
    )
}

/**
 * A circular animated ring that sweeps through Paua colours.
 *
 * Draws a rotating arc whose colour cycles through PauaDeep → PauaTeal → PauaPurple.
 * Serves as a visually prominent Paua-branded replacement for [CircularProgressIndicator]
 * in full-screen loading states. The ring expands and contracts the visible arc while
 * rotating, similar to Material's indeterminate progress indicator but using Paua colours.
 *
 * @param modifier Modifier for the ring.
 * @param size Diameter of the ring in dp.
 * @param strokeWidth Thickness of the arc stroke in dp.
 */
@Composable
fun PauaAnimatedRing(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    strokeWidth: Dp = 4.dp,
) {
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 40f,
        targetValue = 320f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    val color = when {
        phase < 0.5f -> lerp(PauaDeep, PauaTeal, phase / 0.5f)
        else -> lerp(PauaTeal, PauaPurple, (phase - 0.5f) / 0.5f)
    }

    Canvas(modifier = modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val arcSize = Size(size.toPx() - stroke, size.toPx() - stroke)
        val arcOffset = Offset(stroke / 2f, stroke / 2f)

        // Track (faint full ring)
        drawArc(
            color = color.copy(alpha = 0.15f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            size = arcSize,
            topLeft = arcOffset,
        )
        // Sweeping arc (rotating + expanding/contracting)
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            size = arcSize,
            topLeft = arcOffset,
        )
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
    dotSize: Dp = 8.dp,
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

    fun dotAlpha(offset: Float): Float {
        val shifted = (phase - offset).coerceIn(0f, 1f)
        return if (shifted < 0.5f) shifted * 2f else (1f - shifted) * 2f
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
