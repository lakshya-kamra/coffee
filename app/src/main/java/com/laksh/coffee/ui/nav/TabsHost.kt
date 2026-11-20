package com.laksh.coffee.ui.nav

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.laksh.coffee.ui.components.LocalAnimationsActive
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sign

/** How a [TabsHost] moves between its pages. */
enum class TabMotion {
    /** Material fade-through with a small drift: nested hosts (Gear's sections). */
    FadeThrough,

    /** Forge 2.0: pages slide by tab order across half the width (the bottom bar). */
    SlideHorizontal,

    /** The same beside a rail or the sidebar: up or down by an eighth of the pane. */
    SlideVertical,
}

/**
 * The tab roots, kept composed after their first visit and switched with layer properties only,
 * so a switch does no composition and no measurement of anything already built.
 *
 * [motion] picks the choreography (see [TabMotion]). Either way only Float layer properties move
 * (sub-pixel, so a 120 Hz tail never steps a whole pixel), every page has an opaque canvas, and a
 * page that is not on screen is neither placed nor drawn.
 *
 * [visible] is false while a settled detail covers the tabs: nothing is placed or drawn and
 * decorative loops pause until it pops. [reduceMotion] turns any slide into a short cross-fade.
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
    motion: TabMotion = TabMotion.FadeThrough,
    reduceMotion: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    // Nested hosts (Gear's sub-tabs) stay paused while their parent page is.
    val parentActive = LocalAnimationsActive.current
    val parentSettled = LocalTabSettled.current
    val selectedIndex = tabs.indexOf(selected)
    val alive = rememberSaveable(
        saver = listSaver<SnapshotStateList<Int>, Int>(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf(selectedIndex) }
    if (selectedIndex !in alive) alive += selectedIndex

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

    val scope = rememberCoroutineScope()
    val reduced by rememberUpdatedState(reduceMotion)
    val driver: PageDriver = remember(motion) {
        when (motion) {
            TabMotion.FadeThrough -> FadeThroughDriver(selectedIndex)
            TabMotion.SlideHorizontal -> SlideDriver(tabs.size, selectedIndex, vertical = false, scope) { reduced }
            TabMotion.SlideVertical -> SlideDriver(tabs.size, selectedIndex, vertical = true, scope) { reduced }
        }
    }
    val shown by rememberUpdatedState(visible)
    LaunchedEffect(driver, selectedIndex) { driver.switchTo(selectedIndex, shown) }

    Box(modifier.fillMaxSize()) {
        alive.forEach { index ->
            key(index) {
                val isIn = index == driver.target
                val onScreen = visible && driver.shown(index)
                TabLifecycle(maxState = if (isIn) Lifecycle.State.RESUMED else Lifecycle.State.CREATED) {
                    CompositionLocalProvider(
                        LocalAnimationsActive provides (parentActive && onScreen && isIn),
                        // Entrances (chart bars growing) wait for the page to be the settled one.
                        LocalTabSettled provides (parentSettled && onScreen && isIn && !driver.switching),
                    ) {
                        holder.SaveableStateProvider(index) {
                            TabPage(
                                onScreen = { visible && driver.shown(index) },
                                z = { driver.z(index) },
                                style = { with(driver) { style(index) } },
                            ) { content(tabs[index]) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Moves the pages of one host. [target], [switching] and [shown] are snapshot state: the host
 * reads the first two in composition (they change twice a switch), the page reads [shown] and [z]
 * in placement and [style] in its layer, so a running switch never recomposes or measures a page.
 */
private interface PageDriver {
    /** The page switched to (or settled on). */
    val target: Int

    /** True from a switch's start until every page has come to rest. */
    val switching: Boolean

    /** Placed and drawn. */
    fun shown(index: Int): Boolean

    /** Drawing order among the pages: the page coming in is on top. */
    fun z(index: Int): Float

    fun GraphicsLayerScope.style(index: Int)

    /** Starts a switch to [index]; [visible] false (a detail covers the tabs) snaps instead. */
    suspend fun switchTo(index: Int, visible: Boolean)
}

/** Outgoing page is gone by this share of a fade-through; the incoming page fades in over the rest. */
private const val OUT_SHARE = 0.35f

/** How far a fade-through's incoming page drifts in, toward the side its tab sits on. */
private val DRIFT = 20.dp

/**
 * Material fade-through: the outgoing page fades out over the first 35 % and does not move; the
 * incoming page fades in over the rest while it drifts [DRIFT]. Only one page is ever translucent.
 */
private class FadeThroughDriver(initial: Int) : PageDriver {
    private var incoming by mutableIntStateOf(initial)
    private var outgoing by mutableIntStateOf(-1)
    private val progress = Animatable(1f, visibilityThreshold = 0.002f)

    override val target: Int get() = incoming
    override val switching: Boolean get() = outgoing >= 0
    override fun shown(index: Int) = index == incoming || index == outgoing
    override fun z(index: Int) = 0f

    override fun GraphicsLayerScope.style(index: Int) {
        val p = progress.value
        when (index) {
            outgoing -> {
                alpha = 1f - (p / OUT_SHARE).coerceIn(0f, 1f)
                translationX = 0f
            }
            incoming -> if (outgoing >= 0) {
                val f = ((p - OUT_SHARE) / (1f - OUT_SHARE)).coerceIn(0f, 1f)
                alpha = f
                translationX = (1f - f) * (incoming - outgoing).toFloat().sign * DRIFT.toPx()
            } else {
                alpha = 1f
                translationX = 0f
            }
            else -> {
                alpha = 1f
                translationX = 0f
            }
        }
    }

    override suspend fun switchTo(index: Int, visible: Boolean) {
        if (index == incoming) return
        // An interrupted switch fades out whatever was coming in, from the opacity it had
        // reached, so a quick second tap never makes a half-faded page jump back to solid.
        val reached = if (outgoing >= 0) ((progress.value - OUT_SHARE) / (1f - OUT_SHARE)).coerceIn(0f, 1f) else 1f
        outgoing = incoming
        incoming = index
        if (!visible) {
            // Covered by a detail (e.g. Review finishing back to Today): nothing to see, so snap.
            progress.snapTo(1f)
        } else {
            progress.snapTo(OUT_SHARE * (1f - reached))
            progress.animateTo(1f, Motion.TabSwitch)
        }
        outgoing = -1
    }
}

/** One page's place in a slide: [pos] in travels (+1 = one travel toward later tabs), [alpha]. */
private class SlidePage {
    val pos = Animatable(0f, visibilityThreshold = 0.001f)
    val alpha = Animatable(1f, visibilityThreshold = 1f / 255f)
    var shown by mutableStateOf(false)
    var z by mutableFloatStateOf(0f)
    var job: Job? = null
}

/**
 * Forge 2.0's tab switch (ForgeNavTransitions): the incoming page comes in from one travel on the
 * side of the tapped tab while the outgoing page leaves one travel the other way, both on a
 * [Motion.TabSlideMillis] Emphasized tween and fading over [Motion.TabFadeMillis]. A travel is half
 * the width beside the bar, and an eighth of the height beside a rail or sidebar, so a big screen
 * never throws whole pages around. Reduced motion is a [Motion.TabReducedMillis] cross-fade.
 *
 * Each page animates on its own, in [scope] rather than the switch's effect, so a tap mid-switch
 * only redirects the pages it concerns: a page already leaving keeps going, and a page caught
 * mid-slide (the one that was coming in, or a leaving one tapped back) turns from where it is on
 * [Motion.TabRetarget], keeping its speed, instead of restarting a tween from rest. A leaving page
 * stops being placed (and drawn) the moment it has faded out.
 */
private class SlideDriver(
    count: Int,
    initial: Int,
    private val vertical: Boolean,
    private val scope: CoroutineScope,
    private val reduced: () -> Boolean,
) : PageDriver {
    private val pages = List(count) { SlidePage() }.also { it[initial].shown = true }
    private var top = 0f
    private var watcher: Job? = null

    override var target by mutableIntStateOf(initial)
        private set
    override var switching by mutableStateOf(false)
        private set

    override fun shown(index: Int) = pages[index].shown
    override fun z(index: Int) = pages[index].z

    override fun GraphicsLayerScope.style(index: Int) {
        val page = pages[index]
        val travel = if (vertical) size.height / 8f else size.width / 2f
        val offset = page.pos.value * travel
        translationX = if (vertical) 0f else offset
        translationY = if (vertical) offset else 0f
        alpha = page.alpha.value
    }

    override suspend fun switchTo(index: Int, visible: Boolean) {
        val from = target
        if (index == from) return
        target = index
        if (!visible) {
            // Covered by a detail: nothing to see, so every page lands where it belongs at once.
            pages.forEachIndexed { i, page ->
                page.job?.cancel()
                page.job = scope.launch {
                    page.pos.snapTo(0f)
                    page.alpha.snapTo(1f)
                    page.shown = i == index
                }
            }
            settle()
            return
        }
        val direction = (index - from).toFloat().sign
        val reduce = reduced()
        leave(pages[from], -direction, reduce)
        enter(pages[index], direction, reduce)
        settle()
    }

    private fun enter(page: SlidePage, direction: Float, reduce: Boolean) {
        val wasShown = page.shown
        val moving = page.pos.isRunning
        top += 1f
        page.z = top
        page.job?.cancel()
        page.job = scope.launch {
            if (!wasShown) {
                // Set before the page is placed, so its first frame is already off to the side.
                page.pos.snapTo(if (reduce) 0f else direction)
                page.alpha.snapTo(0f)
                page.shown = true
            }
            coroutineScope {
                launch {
                    when {
                        reduce -> page.pos.animateTo(0f, tween(Motion.TabReducedMillis))
                        moving -> page.pos.animateTo(0f, Motion.TabRetarget)
                        else -> page.pos.animateTo(0f, tween(Motion.TabSlideMillis, easing = Motion.Emphasized))
                    }
                }
                launch { page.alpha.animateTo(1f, tween(fadeMillis(reduce, 1f - page.alpha.value))) }
            }
        }
    }

    private fun leave(page: SlidePage, direction: Float, reduce: Boolean) {
        val moving = page.pos.isRunning
        page.job?.cancel()
        // Tapped away before its entrance even started (two taps a frame apart): it never showed.
        if (!page.shown) return
        page.job = scope.launch {
            coroutineScope {
                val slide = launch {
                    when {
                        // Reduced motion: a page caught mid-slide still glides home while it fades.
                        reduce -> page.pos.animateTo(0f, tween(Motion.TabReducedMillis))
                        moving -> page.pos.animateTo(direction, Motion.TabRetarget)
                        else -> page.pos.animateTo(direction, tween(Motion.TabSlideMillis, easing = Motion.Emphasized))
                    }
                }
                page.alpha.animateTo(0f, tween(fadeMillis(reduce, page.alpha.value)))
                // Faded out: the rest of its slide would be invisible, so stop drawing it now.
                slide.cancel()
            }
            page.shown = false
        }
    }

    /** A fade's length for the share of it still to go, so a page part-way faded does not linger. */
    private fun fadeMillis(reduce: Boolean, share: Float): Int =
        ((if (reduce) Motion.TabReducedMillis else Motion.TabFadeMillis) * share.coerceIn(0f, 1f)).roundToInt()

    /** [switching] stays true until every page started by this switch, or still leaving, rests. */
    private fun settle() {
        switching = true
        watcher?.cancel()
        watcher = scope.launch {
            pages.forEach { it.job?.join() }
            switching = false
        }
    }
}

/**
 * One tab's page. Everything that changes during a switch is read in the layout or layer phase,
 * so a switch never recomposes the page.
 */
@Composable
private fun TabPage(
    onScreen: () -> Boolean,
    z: () -> Float,
    style: GraphicsLayerScope.() -> Unit,
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
            // so a switch re-places the page without measuring anything; zIndex there puts the
            // incoming page over the one leaving.
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, p.height) {
                    if (onScreen()) p.place(0, 0, zIndex = z())
                }
            }
            .graphicsLayer(style)
            // Opaque, so a fading page never shows the one behind it.
            .background(bg),
    ) { content() }
}

/**
 * True while the system's animator scale is off ("Remove animations"). Read again on every resume,
 * so turning it on in Settings and coming back takes effect without a restart.
 */
@Composable
internal fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var reduced by remember(context) { mutableStateOf(animatorsOff(context)) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduced = animatorsOff(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return reduced
}

private fun animatorsOff(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)

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
