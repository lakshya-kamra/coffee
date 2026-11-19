@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.laksh.coffee.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import com.laksh.coffee.ui.theme.Motion

private val HeaderBounds = BoundsTransform { _, _ -> Motion.Bounds }

/** Set once by CoffeeAppRoot; null in previews and anywhere outside the nav graph. */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * The current route's AnimatedContentScope, so participants can be written without knowing about
 * nav. Inside the tabs it is the NavHost's "tabs" entry scope while that entry is composed, which
 * is what a tab card flies out of and back into; null while a settled detail covers the tabs.
 */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * False inside a tab page that is hidden (pre-warmed, or behind another tab) or still fading in;
 * true once it is the settled tab, and everywhere outside the tabs. Not static: a switch then
 * recomposes only the few entrances that read it.
 */
val LocalTabSettled = compositionLocalOf { true }

/**
 * Every shared-element key is minted here so the two sides of a transition can never drift
 * apart. Recipe headers are prefixed by the list they came from, so the Home carousel and the
 * Recipes tab never share a key during a tab slide.
 */
object SharedKeys {
    const val FROM_HOME = "home"
    const val FROM_RECIPES = "recipes"

    /**
     * Prefix for the Home next-brew pass. The pass and the carousel can show the same recipe
     * at once, and two participants with one key would make the header fly from the wrong
     * card, so the pass never shares a key with the carousel card of that recipe.
     */
    const val FROM_NEXT = "next"
    const val BREW_HERO = "brew-hero"

    fun recipeHeader(prefix: String, id: Any): String = "$prefix/recipe-header/$id"
}

/** Text-bearing headers: fill the width, pinned top-left, as a card grows into a page header. */
val HeaderResize: SharedTransitionScope.ResizeMode =
    SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopStart)

/**
 * Shared bounds for one participant, or a plain [Modifier] when there is no transition scope
 * to join. That no-op is deliberate: previews and any screen composed outside the NavHost
 * keep compiling and never throw. Must be called in composition (`Modifier.then(...)`), not
 * from a lambda evaluated later.
 */
// A composable factory rather than a Modifier extension: it reads two CompositionLocals and
// returns plain Modifier when either scope is absent, so callers chain it with .then().
@Suppress("ModifierFactoryExtensionFunction")
@Composable
fun sharedBoundsModifier(
    key: Any,
    clip: Shape = RectangleShape,
    // Scale, not remeasure: each side lays out once at its own size and is scaled to the flying
    // bounds, so a title never re-wraps mid-flight and nothing inside re-measures per frame.
    resizeMode: SharedTransitionScope.ResizeMode = HeaderResize,
    visibilityScope: AnimatedVisibilityScope? = LocalNavAnimatedVisibilityScope.current,
): Modifier {
    val sts = LocalSharedTransitionScope.current ?: return Modifier
    val avs = visibilityScope ?: return Modifier
    return with(sts) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = avs,
            boundsTransform = HeaderBounds,
            resizeMode = resizeMode,
            // The default ParentClip would trim the flying header to the origin card's bounds.
            clipInOverlayDuringTransition = OverlayClip(clip),
        )
    }
}

/**
 * False while this composable's page is still arriving when it first appears (its route's
 * transition is running, or its tab is hidden or mid-switch), true from the first settled frame
 * on, and for good: it is saved. Entrance animations wait for it, so they never compete with the
 * page coming in, and a tab pre-warmed off screen keeps its entrance for the first real visit.
 */
@Composable
fun rememberTransitionSettled(): Boolean {
    val tabSettled = LocalTabSettled.current
    val scope = LocalNavAnimatedVisibilityScope.current
    val running = !tabSettled || (scope != null && scope.transition.isRunning)
    var settled by rememberSaveable { mutableStateOf(!running) }
    LaunchedEffect(running) { if (!running) settled = true }
    return settled
}
