package com.laksh.coffee.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.BrewerArt
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// ------------------------------------------------------------------------ steam

/**
 * Rising steam. Each wisp is a sine curve scrolling upward, drawn as short segments so
 * the alpha can fade out toward the top without needing a gradient stroke. The wisps start
 * at the bottom edge of the box, so size the box to the space above the rim.
 *
 * [strength] is read in draw, so a mug whose fill springs does not recompose every frame.
 */
@Composable
fun Steam(
    modifier: Modifier = Modifier,
    color: Color = steamInk(),
    wisps: Int = 3,
    strength: () -> Float = { 1f },
    periodMillis: Int = 3600,
) {
    // Pauses with its tab (LocalAnimationsActive), so a hidden Home never keeps the app awake.
    val phase by rememberLoop(periodMillis)
    Canvas(modifier) {
        val k = strength()
        if (k <= 0.01f) return@Canvas
        val w = size.width
        val h = size.height
        val segments = 22
        repeat(wisps) { i ->
            val wispX = w * (0.5f + (i - (wisps - 1) / 2f) * 0.24f)
            val offset = i * 0.33f
            val amp = w * 0.075f * (0.7f + 0.3f * sin(i.toFloat()))
            // Plain floats: a nullable Offset is boxed, which allocated about 69 objects a frame.
            var prevX = 0f
            var prevY = 0f
            var hasPrev = false
            for (s in 0..segments) {
                val p = s / segments.toFloat()
                // Scroll the wave upward over time.
                val y = h * (1f - p) + (phase * h * 0.35f) % (h * 0.35f) - h * 0.1f
                val wobble = sin((p * 3.2f + phase * 2f * PI.toFloat() + offset * 6f)) * amp * p
                val x = wispX + wobble
                if (hasPrev && y in 0f..h) {
                    // Fade in from the cup, out at the top.
                    val alpha = (1f - p) * p * 4f * k * 0.55f
                    drawLine(
                        color = color,
                        start = Offset(prevX, prevY),
                        end = Offset(x, y),
                        strokeWidth = (w * 0.028f) * (1f - p * 0.4f),
                        cap = StrokeCap.Round,
                        alpha = alpha.coerceIn(0f, 0.6f),
                    )
                }
                prevX = x
                prevY = y
                hasPrev = true
            }
        }
    }
}

/**
 * Steam that reads in both modes: the pale shell colour on dark, warm grey ink on light,
 * where a pale wisp would vanish into the paper.
 */
@Composable
private fun steamInk(): Color {
    val c = CoffeeTheme.colors
    return if (c.isDark) c.artShell else c.inkLow
}

// -------------------------------------------------------------------------- cup

/**
 * A mug that fills up. [fill] is 0..1 and animates, so you can drive it straight from
 * brew progress and let it settle on its own.
 */
@Composable
fun CoffeeMug(
    fill: Float,
    modifier: Modifier = Modifier,
    body: Color = CoffeeTheme.colors.artShell,
    liquid: Color = CoffeeTheme.colors.artBed,
    crema: Color = CoffeeTheme.colors.artWater,
    showSteam: Boolean = true,
) {
    // Kept as State (no `by`) so the draw lambda reads them, not the composition.
    val level = animateFloatAsState(fill.coerceIn(0f, 1f), Motion.heavy(), label = "mugFill")
    val ripple = rememberLoop(4200, to = (2 * PI).toFloat())
    val steam = if (CoffeeTheme.colors.isDark) body else steamInk()

    Box(Modifier.size(120.dp).then(modifier)) {
        if (showSteam) {
            // A sliver of steam even when the mug is nearly empty, so it never looks switched off.
            MugSteam(color = steam, strength = { maxOf(level.value, 0.35f) })
        }
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val w = size.width
                val h = size.height
                // Mug occupies the lower 62% so steam has room above it.
                val top = h * 0.38f
                val bottom = h * 0.94f
                val halfTop = w * 0.30f
                val halfBottom = w * 0.24f
                val cx = w * 0.46f

                val cup = Path().apply {
                    moveTo(cx - halfTop, top)
                    lineTo(cx + halfTop, top)
                    lineTo(cx + halfBottom, bottom)
                    cubicTo(
                        cx + halfBottom, bottom + h * 0.03f,
                        cx - halfBottom, bottom + h * 0.03f,
                        cx - halfBottom, bottom,
                    )
                    close()
                }
                // Rewritten in place each frame rather than rebuilt.
                val wave = Path()
                val handleStroke = Stroke(width = w * 0.045f, cap = StrokeCap.Round)

                onDrawBehind {
                    val lv = level.value
                    val rp = ripple.value

                    // Handle. Drawn before the cup and swept past vertical, so both ends tuck
                    // under the tapering wall instead of floating beside it as a ')'.
                    drawArc(
                        color = body,
                        startAngle = -105f,
                        sweepAngle = 210f,
                        useCenter = false,
                        topLeft = Offset(cx + halfTop - w * 0.12f, top + (bottom - top) * 0.16f),
                        size = Size(w * 0.22f, (bottom - top) * 0.52f),
                        style = handleStroke,
                    )

                    drawPath(cup, body)

                    // Liquid, clipped to the mug.
                    clipPath(cup) {
                        if (lv > 0.01f) {
                            val surfaceY = bottom - (bottom - top) * 0.90f * lv
                            drawRect(
                                color = liquid,
                                topLeft = Offset(0f, surfaceY),
                                size = Size(w, h - surfaceY),
                            )
                            // Surface wobble + crema.
                            wave.rewind()
                            wave.moveTo(0f, surfaceY)
                            var x = 0f
                            while (x <= w) {
                                val y = surfaceY + sin(x / w * 6.5f + rp) * (h * 0.008f)
                                wave.lineTo(x, y)
                                x += w / 24f
                            }
                            wave.lineTo(w, surfaceY + h * 0.03f)
                            wave.lineTo(0f, surfaceY + h * 0.03f)
                            wave.close()
                            drawPath(wave, crema.copy(alpha = 0.85f))
                        }
                    }

                    // Rim highlight.
                    drawLine(
                        color = Color.White.copy(alpha = 0.35f),
                        start = Offset(cx - halfTop, top),
                        end = Offset(cx + halfTop, top),
                        strokeWidth = w * 0.018f,
                        cap = StrokeCap.Round,
                    )
                }
            },
        )
    }
}

/**
 * Steam sized to the mug above: the top 40% of the box, where the rim sits at 38%, and 92%
 * wide so the wisps centre on the cup (it sits at 46% to make room for the handle). Filling
 * the whole box let the right wisp run down the cup wall and read as a stray line.
 */
@Composable
fun MugSteam(
    color: Color,
    strength: () -> Float,
    modifier: Modifier = Modifier,
) {
    Steam(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .fillMaxHeight(0.40f),
        color = color,
        strength = strength,
    )
}

// ---------------------------------------------------------------------- dripper

/**
 * A cone with a bed in it, water above the bed, and drips falling out the bottom.
 * [waterLevel] and [bedDepth] are 0..1 of the cone height.
 */
@Composable
fun Dripper(
    waterLevel: Float,
    modifier: Modifier = Modifier,
    bedDepth: Float = 0.22f,
    dripping: Boolean = true,
    shell: Color = CoffeeTheme.colors.artShell,
    water: Color = CoffeeTheme.colors.artWater,
    bed: Color = CoffeeTheme.colors.artBed,
) {
    // Kept as State (no `by`) so the draw lambda reads them, not the composition.
    val level = animateFloatAsState(waterLevel.coerceIn(0f, 1f), Motion.settle(), label = "dripLevel")
    val dripT = rememberLoop(900)

    Spacer(
        Modifier.size(140.dp).then(modifier).drawWithCache {
            val w = size.width
            val h = size.height
            val coneTop = h * 0.10f
            val coneBottom = h * 0.66f
            val halfTop = w * 0.34f
            val spoutHalf = w * 0.045f
            val cx = w / 2f
            val bedTopY = coneBottom - (coneBottom - coneTop) * bedDepth

            val cone = Path().apply {
                moveTo(cx - halfTop, coneTop)
                lineTo(cx + halfTop, coneTop)
                lineTo(cx + spoutHalf, coneBottom)
                lineTo(cx - spoutHalf, coneBottom)
                close()
            }

            onDrawBehind {
                val lv = level.value
                val dt = dripT.value

                drawPath(cone, shell)

                clipPath(cone) {
                    // Water sits on the bed.
                    if (lv > 0.01f) {
                        val waterTopY = bedTopY - (bedTopY - coneTop) * lv
                        drawRect(
                            color = water.copy(alpha = 0.92f),
                            topLeft = Offset(0f, waterTopY),
                            size = Size(w, bedTopY - waterTopY),
                        )
                    }
                    drawRect(
                        color = bed,
                        topLeft = Offset(0f, bedTopY),
                        size = Size(w, coneBottom - bedTopY),
                    )
                }

                // Cone ribs, the thing that makes it read as a V60.
                val ribs = 7
                for (i in 0 until ribs) {
                    val p = (i + 0.5f) / ribs
                    val xTop = cx - halfTop + halfTop * 2 * p
                    val xBottom = cx - spoutHalf + spoutHalf * 2 * p
                    drawLine(
                        color = Color.Black.copy(alpha = 0.10f),
                        start = Offset(xTop, coneTop),
                        end = Offset(xBottom, coneBottom),
                        strokeWidth = w * 0.012f,
                    )
                }

                // Rim.
                drawLine(
                    color = Color.White.copy(alpha = 0.30f),
                    start = Offset(cx - halfTop, coneTop),
                    end = Offset(cx + halfTop, coneTop),
                    strokeWidth = w * 0.02f,
                    cap = StrokeCap.Round,
                )

                // Drips into the carafe below.
                if (dripping) {
                    repeat(3) { i ->
                        val p = ((dt + i / 3f) % 1f)
                        val y = coneBottom + (h * 0.30f) * p
                        val alpha = (1f - p).coerceIn(0f, 1f)
                        drawCircle(
                            color = water.copy(alpha = alpha * 0.9f),
                            radius = w * 0.018f * (1f - p * 0.3f),
                            center = Offset(cx, y),
                        )
                    }
                }

                // Carafe line.
                drawLine(
                    color = shell.copy(alpha = 0.55f),
                    start = Offset(cx - w * 0.28f, h * 0.97f),
                    end = Offset(cx + w * 0.28f, h * 0.97f),
                    strokeWidth = w * 0.03f,
                    cap = StrokeCap.Round,
                )
            }
        },
    )
}

// ------------------------------------------------------------------------ beans

/** Draws one coffee bean centred at [center]. Used by the loader and by empty states. */
fun DrawScope.coffeeBean(center: Offset, radius: Float, color: Color, angleDeg: Float = 0f) {
    rotate(angleDeg, center) {
        drawOval(
            color = color,
            topLeft = Offset(center.x - radius, center.y - radius * 0.72f),
            size = Size(radius * 2f, radius * 1.44f),
        )
        // The seam.
        val seam = Path().apply {
            moveTo(center.x - radius * 0.75f, center.y)
            cubicTo(
                center.x - radius * 0.25f, center.y - radius * 0.42f,
                center.x + radius * 0.25f, center.y + radius * 0.42f,
                center.x + radius * 0.75f, center.y,
            )
        }
        drawPath(
            seam,
            color = Color.Black.copy(alpha = 0.28f),
            style = Stroke(width = radius * 0.18f, cap = StrokeCap.Round),
        )
    }
}

/** Three beans tumbling. The app's loading state. */
@Composable
fun TumblingBeans(
    modifier: Modifier = Modifier,
    color: Color = CoffeeTheme.colors.inkLow,
) {
    val p by rememberLoop(1500)
    Canvas(Modifier.size(72.dp).then(modifier)) {
        val w = size.width
        val h = size.height
        repeat(3) { i ->
            val local = ((p + i / 3f) % 1f)
            val x = w * local
            // A little arc so they hop rather than slide.
            val y = h * 0.6f - abs(sin(local * PI.toFloat() * 2f)) * h * 0.3f
            coffeeBean(
                center = Offset(x, y),
                radius = w * 0.11f,
                color = color.copy(alpha = 0.35f + 0.65f * (1f - abs(local - 0.5f) * 2f)),
                angleDeg = local * 540f,
            )
        }
    }
}

// ------------------------------------------------------------------------- bed

/**
 * The spent bed inside a V60 cone cross-section, one drawing per [BedShape]: flat, domed,
 * cratered, stranded up the walls, sloped, pitted, or muddy and layered. The one bed icon in
 * the app (Review, the log rows, Brew detail), so a bed looks the same on every screen.
 *
 * [tint] colours the bed; the walls stay quiet in inkLow. Pits and layers are cut in
 * [cutColor], which should match whatever the icon sits on. Below 20dp the rim flare and the
 * spout are dropped and the bed is filled more densely: at row size a flared rim over a thin
 * bed line read as a martini glass or '∀', not a cone with grounds in it.
 */
@Composable
fun BedShapeIcon(
    shape: BedShape,
    size: Dp = 16.dp,
    tint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    cutColor: Color = Color.Unspecified,
) {
    val c = CoffeeTheme.colors
    val ink = tint.takeOrElse { c.inkMid }
    val cut = cutColor.takeOrElse { c.surface1 }
    BedShapeIcon(
        shape = shape,
        size = size,
        tint = { ink },
        cut = { cut },
        modifier = modifier,
    )
}

/**
 * The same drawing with its colours read in draw, for a tile whose colours spring (Review's
 * bed picker), so the animation never recomposes it.
 */
@Composable
fun BedShapeIcon(
    shape: BedShape,
    size: Dp,
    tint: () -> Color,
    cut: () -> Color,
    modifier: Modifier = Modifier,
) {
    val wall = CoffeeTheme.colors.inkLow
    val small = size < 20.dp
    // Inside drawWithCache, `size` means the draw area, so keep the Dp under its own name.
    val side = size
    Spacer(
        modifier
            .size(side)
            .semantics { contentDescription = shape.label }
            .drawWithCache {
                val w = this.size.width
                val h = this.size.height
                val stroke = maxOf(1.dp.toPx(), w * 0.045f)
                val top = h * 0.14f
                val apex = h * 0.86f
                val left = w * 0.12f
                val right = w * 0.88f
                val cx = w / 2f
                val depth = apex - top
                val flatY = top + depth * 0.45f

                // Inner wall x at height y: the cone narrows in a straight line to the apex.
                fun xl(y: Float) = left + (cx - left) * ((y - top) / depth)
                fun xr(y: Float) = right - (right - cx) * ((y - top) / depth)

                // The top of the bed, wall to wall. Traced twice: once as a line, once as a fill.
                fun traceSurface(p: Path) {
                    when (shape) {
                        BedShape.Domed, BedShape.Crater -> {
                            val ctrl = if (shape == BedShape.Domed) flatY - depth * 0.30f else flatY + depth * 0.36f
                            p.moveTo(xl(flatY), flatY)
                            p.quadraticTo(cx, ctrl, xr(flatY), flatY)
                        }
                        BedShape.Sloped -> {
                            val yl = top + depth * 0.30f
                            val yr = top + depth * 0.58f
                            p.moveTo(xl(yl), yl)
                            p.lineTo(xr(yr), yr)
                        }
                        BedShape.HighAndDry -> {
                            val low = top + depth * 0.58f
                            p.moveTo(xl(low), low)
                            p.lineTo(xr(low), low)
                        }
                        else -> {
                            p.moveTo(xl(flatY), flatY)
                            p.lineTo(xr(flatY), flatY)
                        }
                    }
                }

                val surface = Path().also { traceSurface(it) }
                val bed = Path().also {
                    traceSurface(it)
                    it.lineTo(cx, apex)
                    it.close()
                }
                val cone = Path().apply {
                    if (small) {
                        moveTo(left, top)
                        lineTo(cx, apex)
                        lineTo(right, top)
                    } else {
                        moveTo(left - w * 0.07f, top)
                        lineTo(left, top)
                        lineTo(cx, apex)
                        lineTo(right, top)
                        lineTo(right + w * 0.07f, top)
                        moveTo(cx, apex)
                        lineTo(cx, apex + h * 0.07f)
                    }
                }

                // Extra marks: grounds stranded up the paper, pits, compacted layers.
                val stranded = mutableListOf<Pair<Offset, Offset>>()
                val pits = mutableListOf<Offset>()
                val layers = mutableListOf<Pair<Offset, Offset>>()
                // A compacted bed reads as dense. Every other bed is a light wash under its
                // surface line, except at row size, where a wash is too faint to see.
                val fillAlpha = when {
                    shape == BedShape.Muddy -> 0.78f
                    small -> 0.7f
                    else -> 0.32f
                }
                when (shape) {
                    BedShape.HighAndDry -> {
                        val low = top + depth * 0.58f
                        val high = top + depth * 0.16f
                        val inset = stroke * 1.2f
                        stranded += Offset(xl(high) + inset, high) to Offset(xl(low) + inset, low)
                        stranded += Offset(xr(high) - inset, high) to Offset(xr(low) - inset, low)
                    }
                    BedShape.Sinkhole -> {
                        pits += Offset(cx - w * 0.11f, flatY)
                        pits += Offset(cx + w * 0.09f, flatY)
                    }
                    BedShape.Muddy -> {
                        val y1 = flatY + depth * 0.14f
                        val y2 = flatY + depth * 0.28f
                        layers += Offset(xl(y1) + stroke, y1) to Offset(xr(y1) - stroke, y1)
                        layers += Offset(xl(y2) + stroke, y2) to Offset(xr(y2) - stroke, y2)
                    }
                    else -> Unit
                }

                val wallStroke = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val surfaceStroke = Stroke(width = stroke * 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val pitRadius = stroke * 1.5f
                val pitDepth = depth * 0.18f

                onDrawBehind {
                    val ink = tint()
                    val hole = cut()
                    if (shape != BedShape.Unset) {
                        drawPath(bed, ink, alpha = fillAlpha)
                        drawPath(surface, ink, style = surfaceStroke)
                    }
                    stranded.forEach { (a, b) ->
                        drawLine(ink, a, b, strokeWidth = stroke * 2f, cap = StrokeCap.Round)
                    }
                    pits.forEach { o ->
                        drawCircle(hole, radius = pitRadius, center = o)
                        drawLine(hole, o, Offset(o.x, o.y + pitDepth), strokeWidth = stroke, cap = StrokeCap.Round)
                    }
                    layers.forEach { (a, b) ->
                        drawLine(hole, a, b, strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
                    }
                    drawPath(cone, wall, style = wallStroke)
                }
            },
    )
}

// ---------------------------------------------------------------------- brewers

/**
 * A small line drawing of the brewer: a cone (V60, Kalita, Switch), an hourglass (Chemex),
 * a cylinder with a plunger (French Press, AeroPress, each with its own cap), a faceted pot
 * (Moka), a cup (Espresso), a jar (Cold Brew), a drip machine, a cezve, a siphon or a phin on its
 * glass (v4). One ink, so it reads in both themes. Replaces the Unicode glyphs, which rendered
 * at mismatched sizes and one of which came out as a glossy colour-emoji tile.
 * [tint] defaults to the surrounding content colour, so it follows a chip's ink.
 */
@Composable
fun BrewerIcon(
    brewer: Brewer,
    size: Dp = 16.dp,
    tint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val ink = tint.takeOrElse { LocalContentColor.current }
    // Inside drawWithCache, `size` means the draw area, so keep the Dp under its own name.
    val side = size
    Spacer(
        modifier
            .size(side)
            .semantics { contentDescription = brewer.label }
            .drawWithCache {
                val s = this.size.minDimension
                val ox = (this.size.width - s) / 2f
                val oy = (this.size.height - s) / 2f
                val line = Path()
                val solid = Path()
                // Unit coordinates: (0, 0) is the top left of the square, (1, 1) the bottom right.
                fun Path.m(x: Float, y: Float) = moveTo(ox + x * s, oy + y * s)
                fun Path.l(x: Float, y: Float) = lineTo(ox + x * s, oy + y * s)
                fun Path.q(cx: Float, cy: Float, x: Float, y: Float) =
                    quadraticTo(ox + cx * s, oy + cy * s, ox + x * s, oy + y * s)
                fun Path.box(l: Float, t: Float, r: Float, b: Float, corner: Float = 0f) = addRoundRect(
                    RoundRect(
                        Rect(ox + l * s, oy + t * s, ox + r * s, oy + b * s),
                        CornerRadius(corner * s),
                    ),
                )

                fun Path.oval(l: Float, t: Float, r: Float, b: Float) =
                    addOval(Rect(ox + l * s, oy + t * s, ox + r * s, oy + b * s))

                when (brewer.art) {
                    BrewerArt.Drip -> {
                        // An auto-drip machine: the hood over a carafe, the tank tower behind it,
                        // the warming plate under, and the coffee in the carafe.
                        line.box(0.12f, 0.10f, 0.86f, 0.26f, corner = 0.04f)
                        line.m(0.70f, 0.26f); line.l(0.70f, 0.88f)
                        line.m(0.10f, 0.90f); line.l(0.88f, 0.90f)
                        line.m(0.24f, 0.46f); line.l(0.54f, 0.46f); line.l(0.58f, 0.84f); line.l(0.20f, 0.84f); line.close()
                        line.m(0.39f, 0.26f); line.l(0.39f, 0.36f)
                        solid.m(0.225f, 0.68f); solid.l(0.565f, 0.68f); solid.l(0.58f, 0.84f); solid.l(0.20f, 0.84f); solid.close()
                    }
                    BrewerArt.Cezve -> {
                        // A cezve: wide at the base, pinched at the neck, a flared lip and a long
                        // handle reaching up and away; the foam sits in the neck.
                        line.m(0.16f, 0.30f); line.l(0.26f, 0.42f); line.l(0.14f, 0.86f); line.l(0.58f, 0.86f)
                        line.l(0.46f, 0.42f); line.l(0.56f, 0.30f)
                        line.m(0.50f, 0.52f); line.l(0.92f, 0.32f)
                        solid.m(0.26f, 0.44f); solid.l(0.46f, 0.44f); solid.l(0.48f, 0.52f); solid.l(0.24f, 0.52f); solid.close()
                    }
                    BrewerArt.Siphon -> {
                        // A siphon: the upper bowl and its tube into the round lower bulb, held
                        // by a stand, with the burner beneath.
                        line.box(0.36f, 0.06f, 0.64f, 0.38f, corner = 0.08f)
                        line.m(0.50f, 0.38f); line.l(0.50f, 0.50f)
                        line.oval(0.30f, 0.48f, 0.70f, 0.82f)
                        line.m(0.70f, 0.64f); line.l(0.84f, 0.64f); line.l(0.84f, 0.92f)
                        line.m(0.20f, 0.92f); line.l(0.88f, 0.92f)
                        solid.box(0.44f, 0.84f, 0.56f, 0.92f, corner = 0.02f)
                    }
                    BrewerArt.Phin -> {
                        // A phin on its glass: the lid and knob, the small chamber on its wide
                        // plate, and the coffee dripping into the glass below.
                        solid.box(0.44f, 0.06f, 0.56f, 0.13f, corner = 0.02f)
                        line.m(0.30f, 0.16f); line.l(0.70f, 0.16f)
                        line.box(0.33f, 0.20f, 0.67f, 0.44f, corner = 0.03f)
                        line.m(0.14f, 0.47f); line.l(0.86f, 0.47f)
                        line.m(0.28f, 0.52f); line.l(0.33f, 0.92f); line.l(0.67f, 0.92f); line.l(0.72f, 0.52f)
                        solid.m(0.31f, 0.74f); solid.l(0.69f, 0.74f); solid.l(0.67f, 0.92f); solid.l(0.33f, 0.92f); solid.close()
                    }
                    BrewerArt.V60 -> {
                        // A pointed cone on a base plate.
                        line.m(0.14f, 0.22f); line.l(0.86f, 0.22f); line.l(0.54f, 0.70f); line.l(0.46f, 0.70f); line.close()
                        line.m(0.26f, 0.82f); line.l(0.74f, 0.82f)
                        line.m(0.50f, 0.70f); line.l(0.50f, 0.82f)
                    }
                    BrewerArt.Kalita -> {
                        // A flat-bottomed cone, wide at the base.
                        line.m(0.14f, 0.22f); line.l(0.86f, 0.22f); line.l(0.66f, 0.70f); line.l(0.34f, 0.70f); line.close()
                        line.m(0.24f, 0.82f); line.l(0.76f, 0.82f)
                        line.m(0.40f, 0.70f); line.l(0.40f, 0.82f)
                        line.m(0.60f, 0.70f); line.l(0.60f, 0.82f)
                    }
                    BrewerArt.Switch -> {
                        // The V60 cone on a round valve base with its lever.
                        line.m(0.14f, 0.18f); line.l(0.86f, 0.18f); line.l(0.54f, 0.62f); line.l(0.46f, 0.62f); line.close()
                        line.box(0.28f, 0.66f, 0.72f, 0.84f, corner = 0.06f)
                        line.m(0.72f, 0.75f); line.l(0.88f, 0.70f)
                    }
                    BrewerArt.Chemex -> {
                        // An hourglass flask with a solid collar at the waist.
                        line.m(0.22f, 0.10f); line.l(0.78f, 0.10f); line.l(0.56f, 0.46f)
                        line.l(0.80f, 0.90f); line.l(0.20f, 0.90f); line.l(0.44f, 0.46f); line.close()
                        solid.m(0.36f, 0.39f); solid.l(0.64f, 0.39f); solid.l(0.62f, 0.55f); solid.l(0.38f, 0.55f); solid.close()
                    }
                    BrewerArt.FrenchPress -> {
                        // A beaker with a handle, a lid and a knobbed plunger rod.
                        line.box(0.20f, 0.32f, 0.68f, 0.90f, corner = 0.05f)
                        line.m(0.16f, 0.30f); line.l(0.72f, 0.30f)
                        line.m(0.44f, 0.30f); line.l(0.44f, 0.14f)
                        line.m(0.68f, 0.42f); line.l(0.84f, 0.42f); line.l(0.84f, 0.76f); line.l(0.68f, 0.76f)
                        solid.addOval(Rect(ox + 0.37f * s, oy + 0.04f * s, ox + 0.51f * s, oy + 0.16f * s))
                    }
                    BrewerArt.AeroPress -> {
                        // A narrow chamber, the plunger in its top, a wide flat plunger cap and
                        // the flanged filter cap at the bottom.
                        line.box(0.30f, 0.36f, 0.70f, 0.84f)
                        line.box(0.36f, 0.16f, 0.64f, 0.36f)
                        line.m(0.24f, 0.12f); line.l(0.76f, 0.12f)
                        line.m(0.22f, 0.90f); line.l(0.78f, 0.90f)
                    }
                    BrewerArt.Moka -> {
                        // Two faceted chambers pinched at the waist, a spout, a lid knob, a handle.
                        line.m(0.28f, 0.90f); line.l(0.72f, 0.90f); line.l(0.64f, 0.56f); line.l(0.36f, 0.56f); line.close()
                        line.m(0.36f, 0.52f); line.l(0.64f, 0.52f); line.l(0.72f, 0.20f); line.l(0.28f, 0.20f); line.close()
                        line.m(0.28f, 0.20f); line.l(0.16f, 0.15f)
                        line.m(0.44f, 0.12f); line.l(0.56f, 0.12f)
                        line.m(0.71f, 0.24f); line.l(0.86f, 0.30f); line.l(0.84f, 0.48f); line.l(0.67f, 0.46f)
                    }
                    BrewerArt.Espresso -> {
                        // A demitasse on a saucer.
                        line.m(0.20f, 0.34f); line.l(0.72f, 0.34f); line.l(0.66f, 0.70f); line.l(0.26f, 0.70f); line.close()
                        line.m(0.71f, 0.40f); line.q(0.92f, 0.44f, 0.68f, 0.60f)
                        line.m(0.10f, 0.82f); line.l(0.84f, 0.82f)
                    }
                    BrewerArt.ColdBrew -> {
                        // A jar with a lid and a fill line.
                        line.box(0.22f, 0.26f, 0.78f, 0.90f, corner = 0.10f)
                        solid.box(0.28f, 0.10f, 0.72f, 0.22f, corner = 0.03f)
                        line.m(0.30f, 0.54f); line.l(0.70f, 0.54f)
                    }
                }
                val stroke = Stroke(
                    width = maxOf(1.dp.toPx(), s * 0.085f),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )

                onDrawBehind {
                    drawPath(line, ink, style = stroke)
                    drawPath(solid, ink)
                }
            },
    )
}

/** Soft radial glow, used behind the timer. */
fun DrawScope.glow(center: Offset, radius: Float, color: Color, alpha: Float = 0.35f) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
