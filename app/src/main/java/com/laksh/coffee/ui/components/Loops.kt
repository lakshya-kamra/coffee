package com.laksh.coffee.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import kotlin.math.roundToInt

/**
 * False wherever decorative motion should stop: a tab that is hidden or covered by a detail.
 * Tabs stay composed between visits, and a composed infinite transition asks for a frame every
 * vsync even when nothing is drawn, so the app could never idle. Not static: a switch then
 * recomposes only the loops that read it, not the whole tab.
 */
val LocalAnimationsActive = compositionLocalOf { true }

/**
 * A looping 0..1 phase (or [from]..[to]) that runs only while [LocalAnimationsActive] is true
 * and resumes where it stopped. Read the State in draw or layer lambdas only.
 *
 * [reverse] ping-pongs (the old RepeatMode.Reverse); otherwise it restarts from [from].
 */
@Composable
fun rememberLoop(
    periodMillis: Int,
    from: Float = 0f,
    to: Float = 1f,
    reverse: Boolean = false,
    easing: Easing = LinearEasing,
): State<Float> {
    val active = LocalAnimationsActive.current
    val anim = remember { Animatable(from) }
    LaunchedEffect(active, periodMillis, reverse) {
        if (!active) return@LaunchedEffect
        val span = to - from
        // Resume mid-cycle: finish the remaining share of this leg first, then loop whole legs.
        var forward = !reverse || anim.targetValue != from
        while (true) {
            val target = if (forward) to else from
            val left = if (span == 0f) 0f else kotlin.math.abs(target - anim.value) / kotlin.math.abs(span)
            val ms = (periodMillis * left).roundToInt()
            if (ms > 0) anim.animateTo(target, tween(ms, easing = easing))
            if (reverse) forward = !forward else anim.snapTo(from)
        }
    }
    return anim.asState()
}
