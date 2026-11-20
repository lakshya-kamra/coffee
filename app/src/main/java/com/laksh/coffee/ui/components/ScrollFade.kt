package com.laksh.coffee.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Soft edges on a box that scrolls inside itself, so words cut at the bottom read as "more below"
 * rather than as the end (a step card at 200 % text). Each edge fades in over the first [length]
 * of scroll room on its side and is gone when there is none, so a card that fits looks unchanged.
 * Put it before verticalScroll(state): it draws over the viewport, in the draw phase only.
 */
fun Modifier.scrollEdgeFade(state: ScrollState, length: Dp = 28.dp): Modifier = this
    // Offscreen, so DstOut erases this box's own pixels and not the card behind it.
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val px = length.toPx()
        if (px <= 0f || size.height < px * 2) return@drawWithContent
        val top = (state.value / px).coerceIn(0f, 1f)
        val bottom = ((state.maxValue - state.value) / px).coerceIn(0f, 1f)
        if (top > 0f) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = top), Color.Transparent), startY = 0f, endY = px),
                blendMode = BlendMode.DstOut,
            )
        }
        if (bottom > 0f) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = bottom)), startY = size.height - px, endY = size.height),
                blendMode = BlendMode.DstOut,
            )
        }
    }
