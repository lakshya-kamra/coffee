package com.laksh.coffee.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.laksh.coffee.ui.theme.CoffeeTheme
import kotlinx.coroutines.launch

/*
 * Mouse, trackpad and keyboard affordances (v4 §2.6). All three are draw-phase only: hovering or
 * tabbing onto a row repaints that row and recomposes nothing. Touch never triggers either (a tap
 * is neither a hover nor, in touch mode, a focus), so phones look exactly as before.
 */

/** The hand cursor over anything that opens or does something. */
fun Modifier.handPointer(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/** Marks a title as a heading for TalkBack's heading navigation. */
fun Modifier.asHeading(): Modifier = semantics { heading() }

private val HoverSpec = tween<Float>(120)
private val FocusSpec = tween<Float>(90)

/**
 * 0..1 while [interaction] reports a hover (or focus, with [focus] = true). Only ever read in
 * draw lambdas.
 */
@Composable
private fun rememberInteractionLevel(interaction: InteractionSource, focus: Boolean): Animatable<Float, *> {
    val level = remember(interaction) { Animatable(0f) }
    LaunchedEffect(interaction, focus) {
        val hovers = mutableListOf<HoverInteraction.Enter>()
        val focuses = mutableListOf<FocusInteraction.Focus>()
        interaction.interactions.collect { i ->
            when (i) {
                is HoverInteraction.Enter -> hovers += i
                is HoverInteraction.Exit -> hovers -= i.enter
                is FocusInteraction.Focus -> focuses += i
                is FocusInteraction.Unfocus -> focuses -= i.focus
            }
            val on = if (focus) focuses.isNotEmpty() else hovers.isNotEmpty()
            launch { level.animateTo(if (on) 1f else 0f, if (focus) FocusSpec else HoverSpec) }
        }
    }
    return level
}

/**
 * A one-shade lift under the pointer, painted over whatever this element already draws behind
 * itself: put it after the element's own background in the chain. Pass the same [interaction]
 * as the element's clickable, which reports hovers.
 */
@Composable
fun Modifier.hoverTint(
    interaction: InteractionSource,
    color: Color = Color.Unspecified,
    shape: Shape = RectangleShape,
): Modifier {
    val tint = color.takeOrElse { CoffeeTheme.colors.surface2 }
    val level = rememberInteractionLevel(interaction, focus = false)
    return this
        .handPointer()
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            onDrawWithContent {
                val a = level.value
                if (a > 0f) drawOutline(outline, tint, alpha = a)
                drawContent()
            }
        }
}

/**
 * The keyboard focus ring: 2dp of inkHigh, 2dp outside the element, following its [shape]. Put it
 * before any clip in the chain, or the clip trims it. Never removed, never shown for touch.
 */
@Composable
fun Modifier.focusRing(
    interaction: InteractionSource,
    shape: Shape = RectangleShape,
    color: Color = Color.Unspecified,
): Modifier {
    val ink = color.takeOrElse { CoffeeTheme.colors.inkHigh }
    val level = rememberInteractionLevel(interaction, focus = true)
    return drawWithCache {
        val gap = 2.dp.toPx()
        val width = 2.dp.toPx()
        val grow = gap + width / 2f
        val outline = shape.createOutline(Size(size.width + grow * 2f, size.height + grow * 2f), layoutDirection, this)
        val stroke = Stroke(width)
        onDrawWithContent {
            drawContent()
            val a = level.value
            if (a > 0f) translate(-grow, -grow) { drawOutline(outline, ink, alpha = a, style = stroke) }
        }
    }
}
