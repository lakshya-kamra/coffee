package com.laksh.coffee.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laksh.coffee.ui.theme.BrewMood
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Three soft colour blobs drifting on Lissajous paths over a flat base. Only the Brew screen
 * uses it: there the colour of the room is the phase of the brew (idle, bloom, pour, drawdown,
 * done), so a phase change feels like the room changed rather than a value changed.
 *
 * Every mood has its own dark and light palette. Light mode gets a pale wash of its own rather
 * than the dark palette brightened, which is what made the old light Brew screen look grey.
 */
@Composable
fun MeshGradientBackground(
    mood: BrewMood,
    modifier: Modifier = Modifier,
    /** 0 = flat base only, 1 = full colour. Dial down behind dense content. */
    intensity: Float = 1f,
    speedMillis: Int = 24_000,
    dark: Boolean = CoffeeTheme.colors.isDark,
    /** Multiplier on the top and bottom vignette. Light mode barely needs one. */
    vignetteStrength: Float = if (dark) 1f else 0.15f,
    /**
     * Height at the bottom where the mesh settles into its flat base, with a soft fade of the
     * same height above it. A sticky bar painted in the canvas colour then meets the mesh
     * without a visible seam. 0 turns it off.
     */
    floorHeight: Dp = 0.dp,
) {
    val mc = mood.colors(dark)
    // Kept as State (no `by`) so every read happens in the draw cache, never in composition.
    val a = animateColorAsState(mc.a, Motion.heavy(), label = "meshA")
    val b = animateColorAsState(mc.b, Motion.heavy(), label = "meshB")
    val base = animateColorAsState(mc.base, Motion.heavy(), label = "meshBase")
    val k = animateFloatAsState(intensity, Motion.heavy(), label = "meshK")
    val vk = animateFloatAsState(vignetteStrength, Motion.heavy(), label = "meshVignette")
    val floor = animateDpAsState(floorHeight, Motion.heavy(), label = "meshFloor")

    val t = rememberLoop(speedMillis, to = (2 * PI).toFloat())

    Box(modifier) {
        Spacer(
            Modifier
                .fillMaxSize()
                .quarterResolution()
                .drawWithCache {
                    // Brushes live here so they rebuild only while a mood, intensity, vignette or
                    // floor spring is in flight and then sit still; the drift only moves them, so
                    // steady state allocates nothing.
                    val w = size.width
                    val h = size.height
                    val m = maxOf(w, h)
                    val rA = m * 0.85f
                    val rB = m * 0.70f
                    val rC = m * 0.55f
                    val ca = a.value
                    val cb = b.value
                    val kk = k.value
                    val vv = vk.value
                    val baseColor = base.value

                    val blobA = Brush.radialGradient(
                        listOf(ca.copy(alpha = 0.85f * kk), Color.Transparent),
                        center = Offset.Zero,
                        radius = rA,
                    )
                    val blobB = Brush.radialGradient(
                        listOf(cb.copy(alpha = 0.75f * kk), Color.Transparent),
                        center = Offset.Zero,
                        radius = rB,
                    )
                    val blobC = Brush.radialGradient(
                        listOf(ca.copy(alpha = 0.45f * kk), Color.Transparent),
                        center = Offset.Zero,
                        radius = rC,
                    )
                    // A vignette keeps text legible over the bright middle of the mesh.
                    val vignette = Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.18f * vv),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.30f * vv),
                    )

                    // The floor fades from clear to the base colour in the same hue, so no grey band
                    // appears where it starts; clamping keeps it solid below the fade.
                    val floorPx = (floor.value.toPx() / MESH_SCALE).coerceIn(0f, h / 2f)
                    val hasFloor = floorPx > 0.5f
                    val floorTop = (h - floorPx * 2f).coerceAtLeast(0f)
                    val floorOrigin = Offset(0f, floorTop)
                    val floorSize = Size(w, h - floorTop)
                    val floorBrush = Brush.verticalGradient(
                        0f to baseColor.copy(alpha = 0f),
                        1f to baseColor,
                        startY = floorTop,
                        endY = h - floorPx,
                    )

                    onDrawBehind {
                        val tt = t.value
                        drawRect(baseColor)

                        blob(blobA, rA, w, h, tt, phase = 0f, fx = 1.0f, fy = 0.7f)
                        blob(blobB, rB, w, h, tt, phase = 2.1f, fx = 0.6f, fy = 1.1f)
                        blob(blobC, rC, w, h, tt, phase = 4.2f, fx = 1.4f, fy = 0.4f)

                        drawRect(vignette, blendMode = BlendMode.Multiply)

                        if (hasFloor) drawRect(floorBrush, topLeft = floorOrigin, size = floorSize)
                    }
                },
        )
    }
}

/** The mesh is drawn at a quarter of the width and height, then scaled up. */
private const val MESH_SCALE = 4

/**
 * Lays the content out at 1/[MESH_SCALE] of the space in each direction, rasterises it into its
 * own small offscreen layer and scales that layer up to fill. About six full-screen gradient
 * fills a frame become six fills of 1/16 the pixels plus one scaled copy; a blur-soft mesh loses
 * nothing visible.
 */
private fun Modifier.quarterResolution(): Modifier = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val sw = ((w + MESH_SCALE - 1) / MESH_SCALE).coerceAtLeast(1)
    val sh = ((h + MESH_SCALE - 1) / MESH_SCALE).coerceAtLeast(1)
    val p = measurable.measure(Constraints.fixed(sw, sh))
    layout(w, h) {
        p.placeWithLayer(0, 0) {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = w / sw.toFloat()
            scaleY = h / sh.toFloat()
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }
}

/**
 * One drifting blob. The gradient is centred on the origin, so translating the canvas moves
 * the blob without touching the cached shader. A file-level extension rather than a local
 * fun inside onDrawBehind, because a capturing local function is a closure object allocated
 * on every frame.
 */
private fun DrawScope.blob(
    brush: Brush,
    r: Float,
    w: Float,
    h: Float,
    tt: Float,
    phase: Float,
    fx: Float,
    fy: Float,
) {
    val cx = w * (0.5f + 0.34f * sin(tt * fx + phase))
    val cy = h * (0.42f + 0.30f * cos(tt * fy + phase * 1.3f))
    translate(cx, cy) { drawCircle(brush, r, Offset.Zero) }
}
