package com.laksh.coffee.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * One motion vocabulary for the whole app. Everything that moves uses one of these,
 * which is why unrelated screens still feel like the same object.
 *
 * Tuned for 120 Hz. A spring's curve does not depend on the frame rate, but 120 Hz shows twice
 * as many frames of its tail, so three rules hold here: anything spatial is damped at 0.9 or
 * more (overshoot stays under a pixel), positions move as Float in graphicsLayer where they can
 * (sub-pixel, never whole-pixel steps), and every spring has a threshold so it ends and the app
 * can idle. The Spatial/Effects numbers are Material 3's Standard motion-scheme tokens.
 *
 * The web app (web/src/design/motion.ts) mirrors these by name and its test reads this file.
 */
object Motion {

    /** Settling into place. Snappy, no visible wobble (overshoot about 0.15 %). */
    fun <T> settle(): SpringSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = 500f,
    )

    /** Something arriving with a little life in it. Tiny elements only (the tab icon). */
    fun <T> bounce(): SpringSpec<T> = spring(
        dampingRatio = 0.55f,
        stiffness = 320f,
    )

    /** Big surfaces: slow, heavy, expensive-feeling. */
    fun <T> heavy(): SpringSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = 180f,
    )

    /** Micro-interactions: press states, chips, toggles. */
    fun <T> quick(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 900f,
    )

    // ------------------------------------------------- Float tokens (px or 0..1)

    /** Short moves: the outgoing drift, a sheet closing. Thresholds are in px. */
    val SpatialFast: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 1400f, visibilityThreshold = 0.5f)

    /** Anything that crosses the screen: the incoming tab, the bar pill, segment indicators. */
    val SpatialDefault: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = 0.5f)

    /** Rare large moves. */
    val SpatialSlow: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 300f, visibilityThreshold = 0.5f)

    /** Alpha, colour and 0..1 progress. Critically damped, so they never overshoot. */
    val EffectsFast: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 3800f, visibilityThreshold = 1f / 255f)
    val EffectsDefault: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1600f, visibilityThreshold = 1f / 255f)
    val EffectsSlow: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 800f, visibilityThreshold = 1f / 255f)

    /** Tab fade-through progress 0 to 1: about 250 ms, and a re-tap mid-switch carries its velocity. Gear's sections. */
    val TabSwitch: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = 0.002f)

    // ------------------------------------- top-level tab slide (Forge 2.0's ForgeNavTransitions)

    /** The pages' slide between top-level tabs, on [Emphasized]. Tweens, as Forge's: a fixed, known length. */
    const val TabSlideMillis = 380

    /** Both pages' fade, over the first half of the slide (FastOutSlowIn, Compose's tween default). */
    const val TabFadeMillis = 190

    /** Reduced motion: the whole switch is a cross-fade this long. */
    const val TabReducedMillis = 150

    /**
     * A page caught mid-slide by another tap turns toward its new place from where it is, keeping
     * its speed (a tween would restart from rest). In travels (half a width), so it settles to a
     * thousandth of one. Critically damped: a page never swings past where it lands.
     */
    val TabRetarget: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f, visibilityThreshold = 0.001f)

    /**
     * The selected nav icon's pop to 1.1 (Forge's FloatingNavBar): medium-bouncy on purpose, the one
     * place a visible bounce belongs, since it is a 24dp glyph and scale is a layer property.
     */
    val NavIconBounce: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
        visibilityThreshold = 0.001f,
    )

    /** Shared-element flight. */
    val Bounds: SpringSpec<Rect> = spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = Rect.VisibilityThreshold)

    // --------------------------------------------------------------- easing

    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> emphasized(durationMillis: Int = 420): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = Emphasized)

    fun <T> decel(durationMillis: Int = 360): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EmphasizedDecel)

    /**
     * For the few places that must place by IntOffset (lazy item placement). Prefer a Float in
     * graphicsLayer: whole-pixel steps show on a 120 Hz tail.
     */
    fun offset(): SpringSpec<IntOffset> = spring(
        dampingRatio = 0.9f,
        stiffness = 700f,
        visibilityThreshold = IntOffset(1, 1),
    )

    /** Shared-element bounds; the same numbers as [Bounds]. */
    fun bounds(): SpringSpec<Rect> = Bounds

    /** Size changes (animateContentSize / expandVertically). */
    fun size(): SpringSpec<IntSize> = spring(
        dampingRatio = 0.9f,
        stiffness = 700f,
        visibilityThreshold = IntSize.VisibilityThreshold,
    )

    /** Stagger delay for list entrances, capped so long lists do not crawl in. */
    fun staggerDelay(index: Int, perItemMs: Int = 45, maxMs: Int = 320): Int =
        (index * perItemMs).coerceAtMost(maxMs)
}
