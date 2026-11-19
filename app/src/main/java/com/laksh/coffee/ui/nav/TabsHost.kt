package com.laksh.coffee.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.laksh.coffee.ui.components.LocalAnimationsActive
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.sign

/** Outgoing page is gone by this share of the switch; the incoming page fades in over the rest. */
private const val OUT_SHARE = 0.35f

/** How far the incoming page drifts in, toward the side its tab sits on. */
private val DRIFT = 20.dp

/**
 * The five tab roots, kept composed after their first visit and switched with layer properties
 * only, so a tab switch does no composition and no measurement of anything already built.
 *
 * The switch is a Material fade-through: the outgoing page fades out over the first 35 % and does
 * not move; the incoming page fades in over the rest while it drifts [DRIFT] in Float
 * translation (sub-pixel, so a 120 Hz tail never steps a whole pixel). Only one page is ever
 * translucent, and both pages have an opaque canvas.
 *
 * [visible] is false while a settled detail covers the tabs: nothing is placed or drawn and
 * decorative loops pause until it pops.
 *
 * Tabs not yet visited are composed and measured (lazy lists included) one at a time shortly
 * after start-up, hidden, so even a first visit finds its page built and laid out.
 *
 * [tabs] must be a fixed list (an enum's entries, or constants); pages are keyed by position.
 */
@Composable
fun <T> TabsHost(
    tabs: List<T>,
    selected: T,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    // Nested hosts (Gear's sub-tabs) stay paused while their parent page is.
    val parentActive = LocalAnimationsActive.current
    val parentSettled = LocalTabSettled.current
    val alive = rememberSaveable(
        saver = listSaver<SnapshotStateList<Int>, Int>(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf(tabs.indexOf(selected)) }
    if (tabs.indexOf(selected) !in alive) alive += tabs.indexOf(selected)

    // Pre-warm after start-up settles: one hidden tab per couple of frames, so no single frame
    // pays for more than one page.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(300)
        for (i in tabs.indices) {
            if (i in alive) continue
            withFrameNanos { }
            withFrameNanos { }
            alive += i
        }
    }

    var incoming by remember { mutableStateOf(selected) }
    var outgoing by remember { mutableStateOf<T?>(null) }
    val progress = remember { Animatable(1f, visibilityThreshold = 0.002f) }
    val shown by rememberUpdatedState(visible)
    LaunchedEffect(selected) {
        if (selected == incoming) return@LaunchedEffect
        // An interrupted switch fades out whatever was coming in, from the opacity it had
        // reached, so a quick second tap never makes a half-faded page jump back to solid.
        val reached = if (outgoing != null) ((progress.value - OUT_SHARE) / (1f - OUT_SHARE)).coerceIn(0f, 1f) else 1f
        outgoing = incoming
        incoming = selected
        if (!shown) {
            // Covered by a detail (e.g. Review finishing back to Today): nothing to see, so snap.
            progress.snapTo(1f)
        } else {
            progress.snapTo(OUT_SHARE * (1f - reached))
            progress.animateTo(1f, Motion.TabSwitch)
        }
        outgoing = null
    }

    Box(modifier.fillMaxSize()) {
        alive.forEach { index ->
            val tab = tabs[index]
            key(index) {
                val isIn = tab == incoming
                val isOut = tab == outgoing
                val onScreen = visible && (isIn || isOut)
                TabLifecycle(maxState = if (isIn) Lifecycle.State.RESUMED else Lifecycle.State.CREATED) {
                    CompositionLocalProvider(
                        LocalAnimationsActive provides (parentActive && onScreen && isIn),
                        // Entrances (chart bars growing) wait for the page to be the settled one.
                        LocalTabSettled provides (parentSettled && onScreen && isIn && outgoing == null),
                    ) {
                        holder.SaveableStateProvider(index) {
                            TabPage(
                                onScreen = { visible && (tab == incoming || tab == outgoing) },
                                layer = {
                                    when (tab) {
                                        outgoing -> PageLayer.Out
                                        incoming -> if (outgoing != null) {
                                            PageLayer.In((tabs.indexOf(incoming) - tabs.indexOf(outgoing!!)).toFloat().sign)
                                        } else {
                                            PageLayer.Still
                                        }
                                        else -> PageLayer.Still
                                    }
                                },
                                progress = { progress.value },
                            ) { content(tab) }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface PageLayer {
    data object Still : PageLayer
    data object Out : PageLayer
    data class In(val direction: Float) : PageLayer
}

/**
 * One tab's page. Everything that changes during a switch is read in the layout or layer phase,
 * so a switch never recomposes the page.
 */
@Composable
private fun TabPage(
    onScreen: () -> Boolean,
    layer: () -> PageLayer,
    progress: () -> Float,
    content: @Composable () -> Unit,
) {
    val bg = CoffeeTheme.colors.bg
    Box(
        Modifier
            .fillMaxSize()
            // Hidden pages are measured but never placed. Measuring is what makes a lazy list
            // compose and lay out its rows, so a pre-warmed page is ready for its first visit; it
            // happens once, because a paused page (no collection, no loops) invalidates nothing.
            // Unplaced, it is neither drawn nor hit. The state is read in the placement block,
            // so a switch re-places the page without measuring anything.
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, p.height) {
                    if (onScreen()) p.place(0, 0)
                }
            }
            .graphicsLayer {
                val p = progress()
                when (val l = layer()) {
                    PageLayer.Still -> {
                        alpha = 1f
                        translationX = 0f
                    }
                    PageLayer.Out -> {
                        alpha = 1f - (p / OUT_SHARE).coerceIn(0f, 1f)
                        translationX = 0f
                    }
                    is PageLayer.In -> {
                        val f = ((p - OUT_SHARE) / (1f - OUT_SHARE)).coerceIn(0f, 1f)
                        alpha = f
                        translationX = (1f - f) * l.direction * DRIFT.toPx()
                    }
                }
            }
            // Opaque, so a fading page never shows the one behind it.
            .background(bg),
    ) { content() }
}

/**
 * Caps a tab's lifecycle below the activity's: a hidden tab sits at CREATED, so its
 * collectAsStateWithLifecycle stops and it resumes from the cached value on return.
 */
@Composable
private fun TabLifecycle(maxState: Lifecycle.State, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember { CappedLifecycleOwner() }
    SideEffect { owner.setCap(maxState) }
    DisposableEffect(parent, owner) {
        val observer = LifecycleEventObserver { _, _ -> owner.setParent(parent.lifecycle.currentState) }
        parent.lifecycle.addObserver(observer)
        onDispose {
            parent.lifecycle.removeObserver(observer)
            owner.destroy()
        }
    }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

private class CappedLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private var parent = Lifecycle.State.INITIALIZED
    private var cap = Lifecycle.State.RESUMED

    fun setParent(state: Lifecycle.State) {
        parent = state
        apply()
    }

    fun setCap(state: Lifecycle.State) {
        cap = state
        apply()
    }

    private fun apply() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        val target = minOf(parent, cap)
        // A registry can only reach DESTROYED from CREATED or above; the parent handles that via destroy().
        if (target == Lifecycle.State.DESTROYED || target == Lifecycle.State.INITIALIZED) return
        registry.currentState = target
    }

    fun destroy() {
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
