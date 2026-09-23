package com.strumbum.app.ui.tuner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.strumbum.app.ui.theme.LocalTunerColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val RANGE_CENTS = 50f
private const val SWEEP_DEG = 62f

/**
 * Cents arc with a spring-animated needle. [cents] is clamped to ±50 for drawing.
 * It is null when nothing has been heard yet; the needle then rests at centre, dimmed.
 * The composable is decorative: callers put the spoken description on the surrounding text.
 */
@Composable
fun TuningMeter(
    cents: Double?,
    accent: Color,
    inTune: Boolean,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTunerColors.current
    val target = (cents ?: 0.0).toFloat().coerceIn(-RANGE_CENTS, RANGE_CENTS)
    val needle by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow),
        label = "needle",
    )
    val alpha by animateFloatAsState(if (dimmed || cents == null) 0.35f else 1f, tween(250), label = "alpha")
    val glow = remember { Animatable(0f) }
    LaunchedEffect(inTune) {
        if (inTune) {
            glow.animateTo(1f, tween(160))
            glow.animateTo(0.4f, tween(700))
        } else {
            glow.animateTo(0f, tween(200))
        }
    }

    Canvas(modifier.fillMaxWidth().aspectRatio(1.9f)) {
        val strokeScale = if (colors.highContrast) 1.5f else 1f
        val pivot = Offset(size.width / 2f, size.height * 0.94f)
        val radius = min(size.width / 2f, size.height) * 0.9f
        fun polar(deg: Float, r: Float): Offset {
            val rad = Math.toRadians(deg.toDouble())
            return Offset(pivot.x + r * sin(rad).toFloat(), pivot.y - r * cos(rad).toFloat())
        }
        fun angleOf(c: Float) = c / RANGE_CENTS * SWEEP_DEG

        // In-tune zone (±3 cents).
        val zone = angleOf(3f)
        drawArc(
            color = colors.green.copy(alpha = 0.35f + 0.45f * glow.value),
            startAngle = -90f - zone,
            sweepAngle = zone * 2,
            useCenter = false,
            topLeft = Offset(pivot.x - radius, pivot.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = radius * 0.09f),
        )

        // Ticks every 5 cents, longer every 25.
        for (c in -50..50 step 5) {
            val major = c % 25 == 0
            val a = angleOf(c.toFloat())
            drawLine(
                color = colors.ticks.copy(alpha = if (major) 1f else 0.6f),
                start = polar(a, radius * if (major) 0.8f else 0.87f),
                end = polar(a, radius),
                strokeWidth = (if (major) 3.dp else 1.5.dp).toPx() * strokeScale,
                cap = StrokeCap.Round,
            )
        }

        val tip = polar(angleOf(needle), radius * 0.97f)
        if (glow.value > 0f) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.55f * glow.value), Color.Transparent),
                    center = tip,
                    radius = radius * 0.35f,
                ),
                radius = radius * 0.35f,
                center = tip,
            )
        }
        val needleColor = accent.copy(alpha = alpha)
        drawLine(needleColor, pivot, tip, strokeWidth = 4.dp.toPx() * strokeScale, cap = StrokeCap.Round)
        drawCircle(needleColor, radius = 8.dp.toPx(), center = pivot)
        // A small marker outside the arc when the pitch is beyond the ±50 display range.
        if (cents != null && abs(cents) > RANGE_CENTS) {
            drawCircle(needleColor, radius = 5.dp.toPx(), center = polar(angleOf(target), radius * 1.06f))
        }
    }
}
