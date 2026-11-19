package com.laksh.coffee.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.core.clock
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.StepKind
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Num
import kotlin.math.abs

/**
 * - Live: the brew in progress, crema, with a droplet travelling and labelled nodes below.
 * - Historical: a finished brew in ink, with a planned-end tick and an actual-end cap. Below
 *   the line it labels the actual end (in its status colour), the end of the last pour and
 *   any labelled Actual node such as the bed going dry.
 * - Compact: a 24dp route line for cards, no labels.
 */
enum class JourneyStyle { Live, Historical, Compact }

enum class NodeKind { Step, Pour, Event, Actual }

/**
 * A stop on the line. [fraction] is of the planned total (Historical may run past 1).
 * Higher [priority] labels win when labels would collide.
 */
@Immutable
data class JourneyNode(
    val fraction: Float,
    val label: String? = null,
    val kind: NodeKind = NodeKind.Step,
    val status: Status? = null,
    val priority: Int = 0,
)

/**
 * The whole brew as a route with a droplet travelling along it. Everything is drawn in one
 * Spacer: paths, strokes and every label's text layout are built in the cache block, and
 * the draw pass only reads [progress]. Nothing is allocated per frame, which matters
 * because the Live line redraws beside the running timer.
 *
 * The layer is composited offscreen so the droplet's halo and the hollow nodes can clear
 * the line beneath them, which lets the line sit on a card, the canvas or the brew mesh
 * without knowing that background's colour.
 *
 * Nodes outside the drawn range (below 0, or past the end) are dropped rather than drawn
 * off the edge.
 */
@Composable
fun JourneyLine(
    nodes: List<JourneyNode>,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    style: JourneyStyle = JourneyStyle.Live,
    startLabel: String? = null,
    endLabel: String? = null,
    actualEnd: Float? = null,
    endStatus: Status? = null,
    overrunLabel: String? = null,
    actualLabel: String? = null,
) {
    val c = CoffeeTheme.colors
    val live = style == JourneyStyle.Live
    val historical = style == JourneyStyle.Historical
    val compact = style == JourneyStyle.Compact
    // The facts a finished brew labels under its line. Everything else stays unlabelled there,
    // or the planned pour times would crowd out what actually happened.
    fun labelledWhenHistorical(n: JourneyNode) = n.label != null && (n.priority >= 2 || n.kind == NodeKind.Actual)
    val historicalLabels = historical &&
        (actualLabel != null || overrunLabel != null || nodes.any(::labelledWhenHistorical))
    // Without anything to label below the line, a historical line would leave an empty band.
    val height = when {
        compact -> 24.dp
        historical && !historicalLabels -> 46.dp
        else -> 64.dp
    }

    val trackInk = if (compact) c.inkLow else c.inkFaint
    val elapsedInk = if (live) c.cremaStroke else c.inkHigh
    val unlitInk = if (compact) c.inkLow else c.inkFaint
    val litInk = if (live) c.crema else c.inkHigh
    val dropletInk = if (live) c.crema else c.inkMid
    val dropletDoneInk = if (live) c.crema else c.inkHigh
    val startInk = c.inkLow
    val endInk = c.inkHigh
    val nodeLabelInk = c.inkLow
    val tickInk = c.inkLow
    val dashInk = c.inkFaint
    val overrunInk = c.attention
    val capInk = (endStatus ?: Status.Neutral).color()
    // The actual end's label is in the cap's colour, except that neutral is not a word's colour.
    val actualInk = if (endStatus == null || endStatus == Status.Neutral) c.inkHigh else capInk
    // Status colours by ordinal, so draw never needs a composable lookup.
    val statusInks = Status.entries.map { it.color() }.let { inks -> remember(inks) { inks } }

    val measurer = rememberTextMeasurer()
    val endpointStyle = remember { Num.Small.copy(fontSize = 17.sp, lineHeight = 20.sp) }

    Spacer(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val w = size.width
                val strokeW = (if (compact) 2.dp else 3.dp).toPx()
                val nodeR = (if (compact) 7.dp else 9.dp).toPx() / 2f
                val ringW = 1.5.dp.toPx()
                val ring = Stroke(ringW)
                val dropH = (if (compact) 11.dp else 14.dp).toPx()
                val dropW = dropH * 0.72f
                val droplet = teardrop(dropW, dropH)
                val haloR = dropH / 2f + 2.dp.toPx()
                val capR = 5.dp.toPx()
                val tickHalf = 5.dp.toPx()
                val tickW = 1.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
                val lineY = if (compact) size.height / 2f else 34.dp.toPx()

                // Historical may run past the plan, so its scale stretches to the actual end.
                val maxX = if (historical) maxOf(1f, actualEnd ?: 1f) else 1f
                val inset = maxOf(dropW / 2f, nodeR, capR) + 1.dp.toPx()
                val span = (w - 2f * inset).coerceAtLeast(1f)
                fun xOf(f: Float): Float = inset + span * (f / maxX)

                val shown = nodes.filter { it.fraction >= 0f && it.fraction <= maxX }
                val nodeX = FloatArray(shown.size) { xOf(shown[it].fraction) }
                val x0 = xOf(0f)
                val xPlanEnd = xOf(1f)
                val xMax = xOf(maxX)

                // Endpoint labels sit above the line, clear of the droplet's tip.
                val aboveGap = dropH / 2f + 4.dp.toPx()
                val startLayout = if (!compact && startLabel != null) {
                    measurer.measure(startLabel, endpointStyle, softWrap = false, maxLines = 1)
                } else {
                    null
                }
                val endLayout = if (!compact && endLabel != null) {
                    measurer.measure(endLabel, endpointStyle, softWrap = false, maxLines = 1)
                } else {
                    null
                }
                val startTop = startLayout?.let { (lineY - aboveGap - it.size.height).coerceAtLeast(0f) } ?: 0f
                val endTop = endLayout?.let { (lineY - aboveGap - it.size.height).coerceAtLeast(0f) } ?: 0f
                // The end label names the planned total, so it hangs off the planned end.
                val endLeft = endLayout?.let {
                    (minOf(w, xPlanEnd + inset) - it.size.width).coerceAtLeast(0f)
                } ?: 0f

                // Node labels go below the line. Highest priority first, then left to right; a
                // label is dropped if it starts within 36dp of a kept one or would touch it. A
                // finished brew's actual end outranks every node, so it is always kept.
                val keptLayouts = ArrayList<TextLayoutResult>()
                val keptLeft = ArrayList<Float>()
                val keptInk = ArrayList<Color>()
                if (live || historical) {
                    val minStartGap = 36.dp.toPx()
                    val pad = 6.dp.toPx()
                    val order = shown.indices
                        .filter { if (live) shown[it].label != null else labelledWhenHistorical(shown[it]) }
                        .sortedWith(
                            compareByDescending<Int> { shown[it].priority }.thenBy { shown[it].fraction },
                        )
                    val endLabel = if (historical && actualEnd != null) actualLabel else null
                    val candidates = buildList {
                        if (endLabel != null) add(Triple(endLabel, xOf(actualEnd!!.coerceIn(0f, maxX)), actualInk))
                        for (i in order) add(Triple(shown[i].label!!, nodeX[i], nodeLabelInk))
                    }
                    for ((text, x, ink) in candidates) {
                        val layout = measurer.measure(text, Micro, softWrap = false, maxLines = 1)
                        val lw = layout.size.width.toFloat()
                        val left = (x - lw / 2f).coerceIn(0f, (w - lw).coerceAtLeast(0f))
                        val right = left + lw
                        var clash = false
                        for (k in keptLayouts.indices) {
                            val kl = keptLeft[k]
                            val kr = kl + keptLayouts[k].size.width
                            if (abs(left - kl) < minStartGap || (left < kr + pad && right > kl - pad)) {
                                clash = true
                                break
                            }
                        }
                        if (!clash) {
                            keptLayouts += layout
                            keptLeft += left
                            keptInk += ink
                        }
                    }
                }
                val nodeLabelTop = lineY + maxOf(nodeR, dropH / 2f) + 3.dp.toPx()

                // The actual-end label already carries the delta, so the overrun is not repeated.
                val overrunLayout = if (historical && actualLabel == null && overrunLabel != null && (actualEnd ?: 0f) > 1f) {
                    measurer.measure(overrunLabel, Micro, softWrap = false, maxLines = 1)
                } else {
                    null
                }

                onDrawBehind {
                    val p = if (historical && actualEnd != null) {
                        actualEnd.coerceIn(0f, maxX)
                    } else {
                        progress().coerceIn(0f, 1f)
                    }
                    val xp = xOf(p)

                    // Track: the untravelled part. A fast historical brew shows the unused
                    // plan as a dashed tail instead.
                    if (historical && actualEnd != null) {
                        if (actualEnd < 1f) {
                            drawLine(
                                color = dashInk,
                                start = Offset(xp, lineY),
                                end = Offset(xPlanEnd, lineY),
                                strokeWidth = strokeW,
                                cap = StrokeCap.Round,
                                pathEffect = dash,
                            )
                        }
                    } else {
                        drawLine(trackInk, Offset(x0, lineY), Offset(xMax, lineY), strokeW, StrokeCap.Round)
                    }
                    if (xp > x0) {
                        drawLine(elapsedInk, Offset(x0, lineY), Offset(xp, lineY), strokeW, StrokeCap.Round)
                    }

                    // Nodes light over 1.5% of travel as the head passes them.
                    for (i in shown.indices) {
                        val n = shown[i]
                        val at = Offset(nodeX[i], lineY)
                        if (n.kind == NodeKind.Actual) {
                            drawCircle(trackInk, radius = capR + ringW, center = at, blendMode = BlendMode.Clear)
                            drawCircle(statusInks[(n.status ?: Status.Good).ordinal], radius = capR, center = at)
                        } else {
                            val lit = ((p - n.fraction) / 0.015f).coerceIn(0f, 1f)
                            drawCircle(trackInk, radius = nodeR, center = at, blendMode = BlendMode.Clear)
                            drawCircle(unlitInk, radius = nodeR - ringW / 2f, center = at, style = ring)
                            if (lit > 0f) drawCircle(litInk, radius = nodeR, center = at, alpha = lit)
                        }
                    }

                    if (historical) {
                        drawLine(
                            color = tickInk,
                            start = Offset(xPlanEnd, lineY - tickHalf),
                            end = Offset(xPlanEnd, lineY + tickHalf),
                            strokeWidth = tickW,
                        )
                        if (actualEnd != null) {
                            val cap = Offset(xp, lineY)
                            drawCircle(trackInk, radius = capR + ringW, center = cap, blendMode = BlendMode.Clear)
                            drawCircle(capInk, radius = capR, center = cap)
                        }
                    } else {
                        // The halo clears the line around the droplet on any background.
                        drawCircle(trackInk, radius = haloR, center = Offset(xp, lineY), blendMode = BlendMode.Clear)
                        translate(xp, lineY) {
                            drawPath(droplet, if (p >= 1f) dropletDoneInk else dropletInk)
                        }
                    }

                    if (startLayout != null) {
                        drawText(startLayout, color = startInk, topLeft = Offset(0f, startTop))
                    }
                    if (endLayout != null) {
                        drawText(endLayout, color = endInk, topLeft = Offset(endLeft, endTop))
                    }
                    for (k in keptLayouts.indices) {
                        drawText(keptLayouts[k], color = keptInk[k], topLeft = Offset(keptLeft[k], nodeLabelTop))
                    }
                    if (overrunLayout != null) {
                        // Below the line, so it never collides with the planned-end label above.
                        val ow = overrunLayout.size.width.toFloat()
                        val left = (xp - ow / 2f).coerceIn(0f, (w - ow).coerceAtLeast(0f))
                        drawText(overrunLayout, color = overrunInk, topLeft = Offset(left, lineY + capR + 5.dp.toPx()))
                    }
                }
            },
    )
}

/** A teardrop, point up, centred on the origin so drawing it is a single translate. */
private fun teardrop(w: Float, h: Float): Path {
    val r = w / 2f
    val top = -h / 2f
    val cy = h / 2f - r
    val shoulder = top + (cy - top) * 0.45f
    return Path().apply {
        moveTo(0f, top)
        cubicTo(r * 0.25f, shoulder, r, cy - r * 0.35f, r, cy)
        arcTo(Rect(-r, cy - r, r, cy + r), 0f, 180f, false)
        cubicTo(-r, cy - r * 0.35f, -r * 0.25f, shoulder, 0f, top)
        close()
    }
}

// --------------------------------------------------------------------------- route

enum class RouteMode { Planned, Done }

private val NoProgress: () -> Float = { 0f }
private val FullProgress: () -> Float = { 1f }

/**
 * The LHR to JFK of coffee: "30 g ─○─○─ 500 g" with the ratio under the line. The nodes
 * are pour starts in TIME (see [pourNodes]), so a journey line always means time; that is
 * why [midLabel] carries a RATIO caption, or "1:16.7" under a time axis reads as 1 min 16.7 s.
 * Pass [midCaption] = null for a mid label that already says what it is.
 *
 * If both numbers will not fit at [style], it steps down once (Large to Medium, Medium to
 * Small). That is decided per input and width, never per frame.
 */
@Composable
fun RouteRow(
    from: String,
    fromUnit: String,
    fromLabel: String,
    to: String,
    toUnit: String,
    toLabel: String,
    nodes: List<Float>,
    modifier: Modifier = Modifier,
    midLabel: String? = null,
    style: TextStyle = Num.Large,
    mode: RouteMode = RouteMode.Planned,
    midCaption: String? = "RATIO",
) {
    val colors = CoffeeTheme.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val journey = remember(nodes) { nodes.map { JourneyNode(it) } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxW = constraints.maxWidth
        val fitted = remember(from, fromUnit, to, toUnit, style, maxW, density) {
            if (maxW == Constraints.Infinity) {
                style
            } else {
                val unitStyle = Num.unit(style)
                val numbers = measurer.measure(from, style).size.width +
                    measurer.measure(fromUnit, unitStyle).size.width +
                    measurer.measure(to, style).size.width +
                    measurer.measure(toUnit, unitStyle).size.width
                val room = with(density) { 102.dp.toPx() }
                if (numbers + room <= maxW) {
                    style
                } else {
                    when (style) {
                        Num.Large -> Num.Medium
                        Num.Medium -> Num.Small
                        else -> style
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column {
                NumberWithUnit(from, unit = fromUnit, style = fitted)
                Text(fromLabel.toCaps(), style = Micro, color = colors.inkLow, maxLines = 1)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .then(if (midLabel == null) Modifier.align(Alignment.CenterVertically) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                JourneyLine(
                    nodes = journey,
                    progress = if (mode == RouteMode.Done) FullProgress else NoProgress,
                    style = JourneyStyle.Compact,
                )
                if (midLabel != null) {
                    Spacer(Modifier.height(4.dp))
                    Row {
                        if (midCaption != null) {
                            Text(
                                text = midCaption,
                                style = Micro,
                                color = colors.inkLow,
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline(),
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = midLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.inkMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                NumberWithUnit(to, unit = toUnit, style = fitted)
                Text(toLabel.toCaps(), style = Micro, color = colors.inkLow, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------ recipe helpers

/** When the last pour finishes, in seconds from the start, or null for a recipe with no pours. */
fun Recipe.lastPourEndSec(): Int? = steps.filter { it.targetWaterG != null }.maxOfOrNull { it.endSec }

/**
 * Route nodes for a recipe card: where each pour starts, as a fraction of the total TIME.
 * Kasuya's five pours show more stops than Hoffmann's two, and the line means the same
 * thing here as on the brew screen.
 */
fun Recipe.pourNodes(): List<Float> {
    val total = totalSec
    if (total <= 0) return emptyList()
    return steps
        .filter { it.targetWaterG != null }
        .map { it.startSec / total.toFloat() }
        .filter { it > 0.02f && it < 0.98f }
        .distinct()
}

/**
 * Nodes for the live and historical journey lines: every step start after the first, with
 * clock labels on pours and the drawdown, and the end of the last pour marked most
 * important (that is when the bed starts to drain).
 */
fun Recipe.journeyNodes(): List<JourneyNode> {
    val total = totalSec.coerceAtLeast(1)
    val starts = ArrayList<Int>()
    val out = ArrayList<JourneyNode>()
    steps.forEachIndexed { i, s ->
        if (i == 0 || s.startSec <= 0 || s.startSec >= total) return@forEachIndexed
        val kind = when (s.kind) {
            StepKind.Pour, StepKind.Bloom -> NodeKind.Pour
            StepKind.Drawdown -> NodeKind.Event
            else -> NodeKind.Step
        }
        val labelled = kind == NodeKind.Pour || kind == NodeKind.Event
        starts += s.startSec
        out += JourneyNode(
            fraction = s.startSec / total.toFloat(),
            label = if (labelled) clock(s.startSec) else null,
            kind = kind,
            priority = if (kind == NodeKind.Pour) 1 else 0,
        )
    }
    val end = lastPourEndSec()
    if (end != null && end in 1 until total) {
        val at = starts.indexOf(end)
        if (at >= 0) {
            out[at] = out[at].copy(label = clock(end), priority = 2)
        } else {
            out += JourneyNode(
                fraction = end / total.toFloat(),
                label = clock(end),
                kind = NodeKind.Pour,
                priority = 2,
            )
        }
    }
    return out
}
