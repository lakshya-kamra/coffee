package com.laksh.coffee.ui.screens.insights

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.laksh.coffee.core.screenDate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.LocalToday
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.color
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.nav.rememberTransitionSettled
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** "SEP 6" or "6 SEP" by locale, or "TODAY" when the brew was on [today]; pass null where TODAY must not appear. */
private fun axisDate(epochMs: Long, today: LocalDate?): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return if (date == today) "TODAY" else screenDate(epochMs).uppercase()
}

/**
 * Which points get a date under the x-axis: the first, the last, and evenly spaced ones between,
 * at most [max]. Pure, so the spacing is tested without a canvas.
 */
internal fun tickIndices(count: Int, max: Int): List<Int> {
    if (count <= 0) return emptyList()
    if (count <= max) return (0 until count).toList()
    val steps = (max - 1).coerceAtLeast(1)
    return (0..steps).map { i -> ((count - 1) * i / steps.toFloat()).roundToInt() }.distinct()
}

/** The index of the point nearest [x], given each point's x as a fraction of [width]. */
internal fun nearestIndex(fractions: List<Float>, x: Float, width: Float): Int? {
    if (fractions.isEmpty() || width <= 0f) return null
    var best = 0
    var bestD = Float.MAX_VALUE
    fractions.forEachIndexed { i, f ->
        val d = abs(f * width - x)
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

/**
 * A score's mark on a chart. The status colours carry the story (red, amber, green); a neutral
 * 6 or 7 is the quietest scored mark. inkLow matched the status colours' weight in light mode,
 * so the grey middle outweighed the arc it sits in. Built per status in composition, so the
 * remembered colour lists below stay valid and draw never needs a composable lookup.
 */
@Composable
private fun chartInks(): Map<Status, Color> = Status.entries.associateWith { chartInkFor(it) }

@Composable
@ReadOnlyComposable
private fun chartInkFor(s: Status): Color {
    val c = CoffeeTheme.colors
    return when (s) {
        Status.Unrated -> c.inkFaint
        Status.Neutral -> c.neutralMark
        else -> s.color()
    }
}

/** Round gridline steps (1, 2, 5, 10…) with at most five lines, so the gaps are even. */
private fun gridSteps(lo: Float, hi: Float): List<Int> {
    val step = listOf(1, 2, 5, 10, 20, 50).firstOrNull { (hi - lo) / it <= 5f } ?: 100
    val first = (ceil(lo / step) * step).toInt()
    return generateSequence(first) { it + step }.takeWhile { it <= hi }.toList()
}

/**
 * The last scored brews as columns, oldest on the left. Everything static (gridlines, labels,
 * geometry) is built once in the cache block; the only per-frame work while the bars grow is
 * reading each bar's Animatable in draw.
 */
@Composable
internal fun ScoreBars(points: List<ScorePoint>, modifier: Modifier = Modifier, maxTicks: Int = 4, onOpen: ((Long) -> Unit)? = null) {
    val c = CoffeeTheme.colors
    val measurer = rememberTextMeasurer()
    val ink = chartInks()
    // Remembered: a fresh list per recomposition would defeat drawWithCache below.
    val barColors = remember(points, ink) { points.map { ink.getValue(scoreStatus(it.score)) } }
    val today = LocalToday.current
    // Dates under the bars at even steps (v4 §3.9): first, last and a few between.
    val ticks = remember(points, today, maxTicks) {
        tickIndices(points.size, maxTicks).map { i -> i to axisDate(points[i].at, today.takeIf { i == points.lastIndex }) }
    }
    val fractions = remember(points) { points.indices.map { i -> (i + 0.5f) / points.size } }
    val tapLabel = "Open a brew"

    // Bars grow once. Keyed on the count, so a new brew regrows the set but a re-emission of
    // the same brews leaves them standing. "Played" is saved, so scrolling the card away and
    // back (it is a lazy item) or switching tabs never replays it.
    var played by rememberSaveable(points.size) { mutableStateOf(false) }
    val anims = remember(points.size) { List(points.size) { Animatable(if (played) 1f else 0f) } }
    val settled = rememberTransitionSettled()
    LaunchedEffect(points, settled) {
        if (played || !settled) return@LaunchedEffect
        played = true
        anims.forEachIndexed { i, a ->
            launch {
                delay(Motion.staggerDelay(i, 30, 240).toLong())
                a.animateTo(1f, Motion.heavy())
            }
        }
    }

    Spacer(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .then(
                if (onOpen != null && points.isNotEmpty()) {
                    // Tap a bar to open that brew; the bars sit in the plot left of the score labels.
                    Modifier
                        .pointerInput(points) {
                            detectTapGestures { pos ->
                                val plotW = size.width - 22.dp.toPx()
                                nearestIndex(fractions, pos.x, plotW)?.let { onOpen(points[it].id) }
                            }
                        }
                        .semantics { onClick(label = tapLabel) { onOpen(points.last().id); true } }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val labelStyle = Micro.copy(color = c.inkLow)
                val ten = measurer.measure("10", labelStyle)
                val five = measurer.measure("5", labelStyle)
                val tickLabels = ticks.map { (i, text) -> i to measurer.measure(text, labelStyle) }

                val plotRight = size.width - 22.dp.toPx()
                // Half a label of headroom so the "10" tick is not clipped at the top edge.
                val plotTop = ten.size.height / 2f
                // Room under the plot for the dates at whatever text size is set.
                val dateBand = (tickLabels.maxOfOrNull { it.second.size.height } ?: 0) + 4.dp.toPx()
                val plotBottom = size.height - dateBand
                val plotH = plotBottom - plotTop
                val y10 = plotTop
                val y5 = plotTop + plotH / 2f
                val slot = if (points.isEmpty()) 0f else plotRight / points.size
                val barW = 10.dp.toPx()
                val radius = 3.dp.toPx()
                val corner = CornerRadius(radius, radius)
                val stub = 3.dp.toPx()

                onDrawBehind {
                    drawLine(c.hairline, Offset(0f, y10), Offset(plotRight, y10), strokeWidth = 1f)
                    drawLine(c.hairline, Offset(0f, y5), Offset(plotRight, y5), strokeWidth = 1f)
                    drawText(ten, topLeft = Offset(size.width - ten.size.width, y10 - ten.size.height / 2f))
                    drawText(five, topLeft = Offset(size.width - five.size.width, y5 - five.size.height / 2f))

                    points.forEachIndexed { i, p ->
                        val left = slot * (i + 0.5f) - barW / 2f
                        val colour = barColors[i]
                        if (p.score <= 0) {
                            drawRect(colour, Offset(left, plotBottom - stub), Size(barW, stub))
                        } else {
                            val h = plotH * (p.score / 10f) * anims[i].value
                            if (h > 0.5f) {
                                drawRoundRect(colour, Offset(left, plotBottom - h), Size(barW, h), corner)
                                // Square the base: only the top corners are rounded.
                                val base = minOf(radius, h)
                                drawRect(colour, Offset(left, plotBottom - base), Size(barW, base))
                            }
                        }
                    }

                    drawLine(c.hairline, Offset(0f, plotBottom), Offset(plotRight, plotBottom), strokeWidth = 1f)
                    // Each date centred under its bar, kept inside the plot, and skipped where it
                    // would touch the one before it.
                    var lastRight = Float.NEGATIVE_INFINITY
                    val gap = 6.dp.toPx()
                    tickLabels.forEach { (i, label) ->
                        val cx = slot * (i + 0.5f)
                        val x = (cx - label.size.width / 2f).coerceIn(0f, (plotRight - label.size.width).coerceAtLeast(0f))
                        if (x >= lastRight + gap) {
                            drawText(label, topLeft = Offset(x, size.height - label.size.height))
                            lastRight = x + label.size.width
                        }
                    }
                }
            }
    )
}

/**
 * Clicks over time for one grinder on one recipe, finer at the bottom. The best-scoring band
 * sits behind the points, so "where the good cups were" reads before any number does.
 */
@Composable
internal fun GrindJourneyChart(series: GrindSeries, modifier: Modifier = Modifier, maxTicks: Int = 3, onOpen: ((Long) -> Unit)? = null) {
    val c = CoffeeTheme.colors
    val measurer = rememberTextMeasurer()
    val points = series.points
    val today = LocalToday.current
    // Where each point sits across the plot, by time (0..1); shared by drawing and tapping.
    val fractions = remember(points) {
        val t0 = points.firstOrNull()?.at ?: 0L
        val span = ((points.lastOrNull()?.at ?: 0L) - t0).toFloat()
        points.indices.map { i ->
            when {
                span > 0f -> (points[i].at - t0) / span
                points.size > 1 -> i / (points.size - 1f)
                else -> 0.5f
            }
        }
    }
    val ticks = remember(points, today, maxTicks) {
        tickIndices(points.size, maxTicks).map { i -> i to axisDate(points[i].at, today.takeIf { i == points.lastIndex }) }
    }
    val tapLabel = "Open a brew"
    val ink = chartInks()
    val pointColors = remember(points, ink) { points.map { ink.getValue(scoreStatus(it.score)) } }
    val bandColor = c.good.copy(alpha = if (c.isDark) 0.12f else 0.10f)

    // Points fade and scale in once, one after another; saved, so a return never replays them.
    var played by rememberSaveable(points.size) { mutableStateOf(false) }
    val anims = remember(points.size) { List(points.size) { Animatable(if (played) 1f else 0f) } }
    val settled = rememberTransitionSettled()
    LaunchedEffect(points, settled) {
        if (played || !settled) return@LaunchedEffect
        played = true
        anims.forEachIndexed { i, a ->
            launch {
                delay(Motion.staggerDelay(i, 25, 300).toLong())
                a.animateTo(1f, Motion.settle())
            }
        }
    }

    Spacer(
        modifier
            .fillMaxWidth()
            .height(196.dp)
            .then(
                if (onOpen != null && points.isNotEmpty()) {
                    Modifier
                        .pointerInput(points) {
                            detectTapGestures { pos ->
                                val inset = 8.dp.toPx()
                                val plotW = size.width - 28.dp.toPx() - inset * 2
                                nearestIndex(fractions, pos.x - inset, plotW)?.let { onOpen(points[it].id) }
                            }
                        }
                        .semantics { onClick(label = tapLabel) { onOpen(points.last().id); true } }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val axisStyle = Micro.copy(color = c.inkLow)
                val valueStyle = Num.Inline.copy(fontSize = 11.sp, lineHeight = 14.sp, color = c.inkLow)
                val coarser = measurer.measure("COARSER ↑", axisStyle)
                val finer = measurer.measure("FINER ↓", axisStyle)
                val tickLabels = ticks.map { (i, text) -> i to measurer.measure(text, axisStyle) }
                val dateBand = (tickLabels.maxOfOrNull { it.second.size.height } ?: 0) + 4.dp.toPx()

                val minClicks = points.minOfOrNull { it.clicks } ?: 0
                val maxClicks = points.maxOfOrNull { it.clicks } ?: 0
                val lo = (minClicks - 3).toFloat()
                val hi = (maxClicks + 3).toFloat()

                // The axis words live in their own top and bottom gutters, never over a point;
                // the dates get a row of their own under "FINER".
                val plotTop = coarser.size.height + 4.dp.toPx()
                val plotBottom = size.height - dateBand - finer.size.height - 4.dp.toPx()
                val plotH = plotBottom - plotTop
                val rightGutter = 28.dp.toPx()
                val plotRight = size.width - rightGutter
                val inset = 8.dp.toPx()
                val xLeft = inset
                val xRight = plotRight - inset

                // Coarser is always up: flip the axis for dials numbered the other way round.
                fun yOf(clicks: Float): Float {
                    val f = (clicks - lo) / (hi - lo)
                    return if (series.lowerIsFiner) plotBottom - f * plotH else plotTop + f * plotH
                }

                val xs = FloatArray(points.size) { i -> xLeft + fractions[i] * (xRight - xLeft) }
                val ys = FloatArray(points.size) { i -> yOf(points[i].clicks.toFloat()) }
                val line = Path().apply {
                    xs.indices.forEach { i -> if (i == 0) moveTo(xs[i], ys[i]) else lineTo(xs[i], ys[i]) }
                }
                val lineStroke = Stroke(width = 1.5.dp.toPx())

                val grid = gridSteps(lo, hi)
                val gridYs = grid.map { yOf(it.toFloat()) }
                val gridLabels = grid.map { measurer.measure("$it", valueStyle) }

                val band = series.bestZone?.let { z ->
                    val a = yOf(z.first - 0.5f)
                    val b = yOf(z.last + 0.5f)
                    minOf(a, b) to maxOf(a, b)
                }
                val bandLabel = series.bestZone?.let { z ->
                    measurer.measure("BEST ${z.first}–${z.last}", Micro.copy(color = c.good))
                }
                val latestLabel = points.lastOrNull()?.let {
                    measurer.measure("${it.clicks}", Micro.copy(color = c.inkHigh))
                }

                val r = 4.dp.toPx()
                val rLatest = 6.dp.toPx()
                val ring = 2.dp.toPx()
                val labelGap = 2.dp.toPx()
                val bandLabelX = 4.dp.toPx()
                // An axis number right beside the latest point's own number makes the current
                // value ambiguous, so that axis label steps aside (its gridline stays).
                val latestLabelMid = latestLabel?.let { label ->
                    ys.lastOrNull()?.let { it - rLatest - ring - labelGap - label.size.height / 2f }
                }
                val clear = 12.dp.toPx()
                val showGridLabel = gridYs.map { y -> latestLabelMid == null || abs(y - latestLabelMid) >= clear }

                onDrawBehind {
                    gridYs.forEachIndexed { i, y ->
                        drawLine(c.hairline, Offset(0f, y), Offset(plotRight, y), strokeWidth = 1f)
                        if (showGridLabel[i]) {
                            val label = gridLabels[i]
                            drawText(label, topLeft = Offset(size.width - label.size.width, y - label.size.height / 2f))
                        }
                    }
                    band?.let { (top, bottom) ->
                        drawRect(bandColor, Offset(0f, top), Size(plotRight, bottom - top))
                    }
                    if (band != null && bandLabel != null) {
                        val mid = (band.first + band.second) / 2f
                        drawText(bandLabel, topLeft = Offset(bandLabelX, mid - bandLabel.size.height / 2f))
                    }
                    drawText(coarser, topLeft = Offset.Zero)
                    drawText(finer, topLeft = Offset(0f, plotBottom + 2.dp.toPx()))
                    var lastRight = Float.NEGATIVE_INFINITY
                    val gap = 6.dp.toPx()
                    tickLabels.forEach { (i, label) ->
                        val x = (xs[i] - label.size.width / 2f).coerceIn(0f, (plotRight - label.size.width).coerceAtLeast(0f))
                        if (x >= lastRight + gap) {
                            drawText(label, topLeft = Offset(x, size.height - label.size.height))
                            lastRight = x + label.size.width
                        }
                    }

                    // The line arrives with the first point rather than ahead of every point. It
                    // is no heavier than a neutral point, so the coloured points read first.
                    val lineAlpha = anims.firstOrNull()?.value ?: 1f
                    if (points.size > 1 && lineAlpha > 0f) {
                        drawPath(line, c.neutralMark, alpha = lineAlpha.coerceIn(0f, 1f), style = lineStroke)
                    }

                    points.indices.forEach { i ->
                        val v = anims[i].value.coerceIn(0f, 1f)
                        if (v <= 0f) return@forEach
                        val center = Offset(xs[i], ys[i])
                        if (i == points.lastIndex) {
                            drawCircle(c.bg, radius = (rLatest + ring) * v, center = center, alpha = v)
                            drawCircle(pointColors[i], radius = rLatest * v, center = center, alpha = v)
                            latestLabel?.let { label ->
                                val x = (center.x - label.size.width / 2f)
                                    .coerceIn(0f, (plotRight - label.size.width).coerceAtLeast(0f))
                                val y = center.y - rLatest - ring - labelGap - label.size.height
                                drawText(label, topLeft = Offset(x, y), alpha = v)
                            }
                        } else {
                            drawCircle(pointColors[i], radius = r * v, center = center, alpha = v)
                        }
                    }
                }
            }
    )
}

/**
 * A short leaderboard: rank, name with a proportional bar, brew count, and the average in its
 * score colour. Rank numerals and missing averages use inkLow so they stay legible in light
 * mode; inkFaint is reserved for marks.
 */
@Composable
internal fun RankedList(items: List<Ranked>, modifier: Modifier = Modifier, max: Int = 3) {
    val c = CoffeeTheme.colors
    val shown = items.take(max)
    val maxCount = (shown.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)

    Column(modifier.fillMaxWidth()) {
        shown.forEachIndexed { i, item ->
            if (i > 0) HairLine(inset = 28.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}",
                    style = Num.Small,
                    color = c.inkLow,
                    modifier = Modifier.width(28.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = c.inkHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.sub?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = c.inkMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(item.count.toFloat() / maxCount)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(c.inkHigh.copy(alpha = 0.22f))
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "${item.count}",
                        style = Num.Inline.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        color = c.inkHigh,
                    )
                    Text(
                        if (item.count == 1) "BREW" else "BREWS",
                        style = Micro,
                        color = c.inkLow,
                    )
                }
                Spacer(Modifier.width(12.dp))
                // Labelled like the count beside it: a coloured number never stands without a word.
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.widthIn(min = 28.dp),
                ) {
                    Text(
                        item.avg?.let { oneDp(it) } ?: "—",
                        style = Num.Inline.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        color = item.avg?.let { scoreStatus(it.roundToInt()).color() } ?: c.inkLow,
                        textAlign = TextAlign.End,
                    )
                    Text("AVG", style = Micro, color = c.inkLow)
                }
            }
        }
    }
}
