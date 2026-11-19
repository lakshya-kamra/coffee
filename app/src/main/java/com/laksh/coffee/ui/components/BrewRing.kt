package com.laksh.coffee.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.laksh.coffee.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The brew timer dial.
 *
 * The arc is elapsed time against the whole recipe, with a tick at every step boundary, so the
 * shape of the brew is visible before it happens and the arc always agrees with the clock
 * inside it. The inner disc is a liquid level showing water poured against the total.
 *
 * [progress] and [waterProgress] are lambdas read only while drawing, so a running clock
 * never recomposes this or anything above it; the arc moves at exact clock speed.
 */
@Composable
fun BrewRing(
    progress: () -> Float,
    waterProgress: () -> Float,
    accent: Color,
    modifier: Modifier = Modifier,
    stepBoundaries: List<Float> = emptyList(),
    pouring: Boolean = false,
    running: Boolean = true,
    trackColor: Color = accent.copy(alpha = 0.16f),
    liquidColor: Color = accent.copy(alpha = 0.22f),
    strokeFraction: Float = 0.055f,
    headCore: Color = Color.White,
    glowRange: ClosedFloatingPointRange<Float> = 0.15f..0.40f,
    /** A step tick the head has not reached yet. Passed ticks turn [accent]. */
    tickColor: Color = accent.copy(alpha = 0.32f),
    /**
     * Once any water is on, the disc shows at least this fraction of its height, so the first
     * grams read as a shallow pool rather than a stray crescent at the bottom of the dial.
     */
    minLiquidLevel: Float = 0.12f,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = rememberInfiniteTransition(label = "ring")
    val wave = t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "ringWave",
    )
    // Sine easing has zero velocity at both turnarounds, so the pulse never kinks.
    val pulse = t.animateFloat(
        0.92f, 1.08f,
        infiniteRepeatable(tween(1100, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "ringPulse",
    )
    // How much of the pulse the head shows; springs in and out instead of switching at pour edges.
    val pulseAmt = animateFloatAsState(if (pouring && running) 1f else 0f, Motion.heavy(), label = "pulseAmt")

    Box(modifier, contentAlignment = Alignment.Center) {
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                // Everything with a size lives here so steady state allocates nothing per frame.
                val stroke = size.minDimension * strokeFraction
                // Leaves room for ticks that stand a little proud of the stroke.
                val radius = size.minDimension / 2f - stroke * 1.2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val arcSize = Size(radius * 2, radius * 2)
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val innerR = radius - stroke * 1.9f
                val amp = innerR * 0.035f
                val disc = Path().apply {
                    addOval(Rect(Offset(center.x - innerR, center.y - innerR), Size(innerR * 2, innerR * 2)))
                }
                val wavePath = Path()
                val ringStroke = Stroke(width = stroke, cap = StrokeCap.Round)
                val glowR = stroke * 3.2f
                val glowBrush = Brush.radialGradient(
                    listOf(accent, Color.Transparent),
                    center = Offset.Zero,
                    radius = glowR,
                )
                val coreColor = headCore.copy(alpha = 0.9f)
                val headR = stroke * 0.9f
                val coreR = stroke * 0.35f
                val glowLow = glowRange.start
                val glowSpan = glowRange.endInclusive - glowRange.start
                // A thin ring needs ticks longer than its stroke to be seen at all.
                val tickIn = radius - stroke * 1.1f
                val tickOut = radius + stroke * 1.1f
                val tickWidth = maxOf(stroke * 0.22f, 1.5.dp.toPx())
                val minLevel = minLiquidLevel.coerceIn(0f, 1f)

                onDrawBehind {
                    val p = progress().coerceIn(0f, 1f)
                    val wp = waterProgress().coerceIn(0f, 1f)
                    val wv = wave.value
                    val amt = pulseAmt.value

                    // Inner liquid disc.
                    clipPath(disc) {
                        if (wp > 0.005f) {
                            val level = minLevel + (1f - minLevel) * wp
                            val surfaceY = center.y + innerR - (innerR * 2f) * level
                            wavePath.rewind()
                            wavePath.moveTo(center.x - innerR, surfaceY)
                            var x = center.x - innerR
                            while (x <= center.x + innerR) {
                                val y = surfaceY + sin((x / innerR) * 3.4f + wv) * amp
                                wavePath.lineTo(x, y)
                                x += innerR / 14f
                            }
                            wavePath.lineTo(center.x + innerR, center.y + innerR)
                            wavePath.lineTo(center.x - innerR, center.y + innerR)
                            wavePath.close()
                            // Fades in over the first few grams instead of popping on at the
                            // minimum level.
                            drawPath(wavePath, liquidColor, alpha = (wp / 0.06f).coerceAtMost(1f))
                        }
                    }

                    // Track.
                    drawArc(trackColor, 0f, 360f, false, arcTopLeft, arcSize, style = ringStroke)

                    // Step boundary ticks cross-fade to the accent over about five degrees as the
                    // head passes. Two alpha draws instead of a colour lerp keep this allocation-free.
                    for (i in stepBoundaries.indices) {
                        val frac = stepBoundaries[i]
                        val rad = (-90f + 360f * frac) * PI.toFloat() / 180f
                        val cs = cos(rad)
                        val sn = sin(rad)
                        val start = Offset(center.x + cs * tickIn, center.y + sn * tickIn)
                        val end = Offset(center.x + cs * tickOut, center.y + sn * tickOut)
                        val lit = ((p - frac) / 0.015f).coerceIn(0f, 1f)
                        if (lit < 1f) {
                            drawLine(
                                color = tickColor,
                                start = start,
                                end = end,
                                strokeWidth = tickWidth,
                                cap = StrokeCap.Round,
                                alpha = 1f - lit,
                            )
                        }
                        if (lit > 0f) {
                            drawLine(
                                color = accent,
                                start = start,
                                end = end,
                                strokeWidth = tickWidth,
                                cap = StrokeCap.Round,
                                alpha = lit,
                            )
                        }
                    }

                    // Progress.
                    if (p > 0.001f) {
                        drawArc(accent, -90f, 360f * p, false, arcTopLeft, arcSize, style = ringStroke)

                        // Leading head, with a glow that swells while a pour is live.
                        val headAngle = (-90f + 360f * p) * PI.toFloat() / 180f
                        val head = Offset(
                            center.x + cos(headAngle) * radius,
                            center.y + sin(headAngle) * radius,
                        )
                        val headScale = 1f + (pulse.value - 1f) * amt
                        val glowAlpha = glowLow + glowSpan * amt
                        translate(head.x, head.y) {
                            scale(headScale, headScale, Offset.Zero) {
                                drawCircle(glowBrush, glowR, Offset.Zero, alpha = glowAlpha)
                            }
                        }
                        drawCircle(accent, headR * headScale, head)
                        drawCircle(coreColor, coreR * headScale, head)
                    }
                }
            },
        )
        content()
    }
}
