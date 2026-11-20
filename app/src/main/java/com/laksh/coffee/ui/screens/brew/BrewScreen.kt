package com.laksh.coffee.ui.screens.brew

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.clock
import com.laksh.coffee.domain.StepKind
import com.laksh.coffee.domain.Temperatures
import com.laksh.coffee.ui.components.CoffeeConfirm
import com.laksh.coffee.ui.components.CoffeeMug
import com.laksh.coffee.ui.components.MeshGradientBackground
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.TumblingBeans
import com.laksh.coffee.ui.theme.BrewMood
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * How much of the bottom of the setup mesh settles into the flat canvas, before the navigation
 * inset: the sticky bar's 40dp fade, its 56dp button and 12dp margin, plus a little slack.
 */
private val SETUP_FLOOR = 112.dp

private fun StepKind.mood(): BrewMood = when (this) {
    StepKind.Prep -> BrewMood.Idle
    StepKind.Bloom -> BrewMood.Bloom
    StepKind.Pour -> BrewMood.Pouring
    StepKind.Swirl, StepKind.Stir -> BrewMood.Pouring
    StepKind.Steep -> BrewMood.Steeping
    StepKind.Press -> BrewMood.Steeping
    StepKind.Drawdown -> BrewMood.Drawdown
    StepKind.Serve -> BrewMood.Done
}

private enum class Pane { Loading, Missing, Setup, Running, Shot }

/**
 * The frame-synced clock. [elapsedMs] is the truth the digits show; [seekMs] is how far the
 * ring is drawn ahead of or behind it, so a seek snaps the numerals but glides the arc.
 */
class BrewClock(val elapsedMs: LongState, val seekMs: State<Float>)

@Composable
fun rememberBrewClock(anchor: TimerAnchor): BrewClock {
    val elapsed = remember { mutableLongStateOf(anchor.elapsedAt(System.nanoTime())) }
    val seek = remember { Animatable(0f, visibilityThreshold = 1f) }
    LaunchedEffect(anchor) {
        val truth = anchor.elapsedAt(System.nanoTime())
        // Keep the ring where it was drawn last frame (about 0 across pause/resume, a few
        // seconds after a seek) while the clock itself snaps. A mid-glide anchor change
        // re-snaps from the current visual, so the offset can never be left non-zero.
        seek.snapTo((elapsed.longValue + seek.value) - truth)
        elapsed.longValue = truth
        launch { seek.animateTo(0f, Motion.settle()) }
        if (anchor.running) {
            // Choreographer frame time shares System.nanoTime's timebase, so this is exact.
            while (true) withFrameNanos { now -> elapsed.longValue = anchor.elapsedAt(now) }
        }
    }
    return remember { BrewClock(elapsed, seek.asState()) }
}

/**
 * Setup, then the timer (or shot mode for espresso). [againLogId] opens setup prefilled from a
 * logged brew ("Brew again", v4 §4.10); 0 is a plain brew.
 */
@Composable
fun BrewScreen(
    recipeId: String,
    onExit: () -> Unit,
    onFinished: (Long) -> Unit,
    againLogId: Long = 0L,
    vm: BrewViewModel = viewModel(
        // Keyed: "Brew again" and a plain brew of the same recipe are different setups.
        key = "brew-$recipeId-$againLogId",
        factory = BrewViewModel.factory(recipeId, againLogId),
    ),
) {
    val raw by vm.state.collectAsStateWithLifecycle()
    // Step titles and details in the user's unit (§4.15) wherever the brew shows or says them.
    val tempUnit = if (LocalUnits.current.fahrenheit) "f" else "c"
    val state = remember(raw, tempUnit) {
        raw.recipe?.let { r -> Temperatures.renderSteps(r, tempUnit).takeIf { it !== r }?.let { raw.copy(recipe = it) } } ?: raw
    }
    val anchor by vm.anchor.collectAsStateWithLifecycle()
    val timer = rememberBrewClock(anchor)
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val reduced = rememberReducedMotion()
    val flash = rememberStepFlash()
    val tone = rememberStepTone()

    DisposableEffect(state.settings.keepScreenOn) {
        view.keepScreenOn = state.settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // One firm cue when a step changes: enough to feel without looking, a tone to hear across
    // the kitchen, and a flash and banner to see from across it.
    val hapticsOn by rememberUpdatedState(state.settings.haptics)
    val soundOn by rememberUpdatedState(state.settings.soundCues)
    val latest by rememberUpdatedState(state)
    LaunchedEffect(Unit) {
        vm.stepCue.collect { index ->
            if (hapticsOn) {
                haptics.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackType.Confirm else HapticFeedbackType.LongPress,
                )
            }
            if (soundOn) tone()
            latest.recipe?.steps?.getOrNull(index)?.let { s -> launch { flash.play(BrewWords.banner(s), reduced) } }
        }
    }

    // A small tick for the steppers and transport, behind the same setting.
    val tick: () -> Unit = {
        if (state.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
    }

    val recipe = state.recipe
    val mood = when {
        state.phase == BrewPhase.Setup -> BrewMood.Idle
        state.done -> BrewMood.Done
        else -> state.step?.kind?.mood() ?: BrewMood.Idle
    }
    val pane = when {
        state.loading -> Pane.Loading
        recipe == null -> Pane.Missing
        state.phase == BrewPhase.Setup -> Pane.Setup
        state.isEspresso -> Pane.Shot
        else -> Pane.Running
    }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Leaving a running brew asks first, with "Keep it going" focused (v4 §2.4).
    var askLeave by rememberSaveable { mutableStateOf(false) }
    var askFinish by rememberSaveable { mutableStateOf(false) }
    val live = pane == Pane.Running || pane == Pane.Shot
    val leave: () -> Unit = { if (live) askLeave = true else onExit() }
    BackHandler(enabled = live) { askLeave = true }

    val finish: () -> Unit = { vm.finish(onFinished) }
    val shotPhase = ShotPhase.of(state.running, anchor.accumulatedMs, state.shotStopped)

    // The brew's keys (v4 §2.7) live on its root, which holds focus while it is on screen. The
    // letters reach it before any button that happens to have focus; Space and the arrows only
    // while the root itself has focus, so Space on a focused Next, Finish or -5 s presses that
    // button (and the arrows still move focus) instead of pausing the timer.
    val focus = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    LaunchedEffect(pane) { runCatching { focus.requestFocus() } }
    val onKey: (KeyEvent) -> Boolean = handler@{ e ->
        if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return@handler false
        if (e.nativeKeyEvent.repeatCount > 0 && e.key != Key.DirectionLeft && e.key != Key.DirectionRight) return@handler false
        if (!BrewKeys.reachesRoot(e.key, rootFocused)) return@handler false
        when (pane) {
            Pane.Running -> when (e.key) {
                Key.Spacebar -> true.also { vm.toggleRunning() }
                Key.DirectionLeft -> true.also { vm.addSeconds(-5) }
                Key.DirectionRight -> true.also { vm.addSeconds(5) }
                Key.N, Key.DirectionDown -> true.also { vm.skipToNextStep() }
                Key.X -> (state.step?.kind == StepKind.Drawdown).also { if (it) vm.markBedExposed() }
                Key.F -> true.also { if (state.done) finish() else askFinish = true }
                Key.M -> true.also { vm.toggleSound() }
                Key.Escape -> true.also { askLeave = true }
                else -> false
            }
            Pane.Shot -> when (e.key) {
                Key.Spacebar -> shotKeyAllowed(shotPhase).also { if (it) vm.shotPrimary() }
                Key.Escape -> true.also { askLeave = true }
                else -> false
            }
            Pane.Setup, Pane.Missing -> (e.key == Key.Escape).also { if (it) onExit() }
            Pane.Loading -> false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .semantics { paneTitle = "Brew" }
            .onPreviewKeyEvent(onKey)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusRequester(focus)
            .focusable(),
    ) {
        MeshGradientBackground(
            mood = mood,
            modifier = Modifier.fillMaxSize(),
            intensity = if (state.phase == BrewPhase.Setup) 0.3f else 0.6f,
            // Setup's sticky Start bar is painted in the canvas colour, so the mesh settles into
            // that same colour behind it and the bar has no seam. The floor lifts on Start.
            floorHeight = if (state.phase == BrewPhase.Setup) SETUP_FLOOR + navInset else 0.dp,
        )

        AnimatedContent(
            targetState = pane,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                // Crossfade only. The brew hero is already travelling via shared bounds, and a
                // pane scale layered on top pulls against it.
                fadeIn(Motion.decel(320))
                    .togetherWith(fadeOut(Motion.decel(180)))
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.TopCenter,
            label = "pane",
        ) { p ->
            when (p) {
                Pane.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { TumblingBeans() }
                Pane.Missing -> MissingPane(onExit = onExit)
                Pane.Setup -> SetupPane(state = state, onExit = onExit, vm = vm, heroScope = this@AnimatedContent, tick = tick)
                Pane.Running -> RunningPane(
                    state = state,
                    timer = timer,
                    anchor = anchor,
                    heroScope = this@AnimatedContent,
                    onLeave = leave,
                    onToggle = vm::toggleRunning,
                    onSeek = { d ->
                        tick()
                        vm.addSeconds(d)
                    },
                    onNext = {
                        tick()
                        vm.skipToNextStep()
                    },
                    onBed = {
                        tick()
                        vm.markBedExposed()
                    },
                    onFinish = finish,
                )
                Pane.Shot -> ShotPane(
                    state = state,
                    timer = timer,
                    anchor = anchor,
                    onLeave = leave,
                    onPrimary = {
                        tick()
                        vm.shotPrimary()
                    },
                    onAgain = vm::restartShot,
                    onRate = { y -> vm.finish(onFinished, y) },
                )
            }
        }

        if (pane == Pane.Running) StepFlashOverlay(flash, reduced)
        if (recipe != null) {
            BrewAnnouncer(recipe, state.stepIndex, state.done, live = pane == Pane.Running, elapsed = timer.elapsedMs, waiting = state.waitingForTap)
        }
    }

    if (askLeave) {
        CoffeeConfirm(
            title = "Leave this brew?",
            confirmLabel = "Leave",
            onConfirm = {
                askLeave = false
                onExit()
            },
            onDismiss = { askLeave = false },
            body = "The timer stops and this brew is not saved.",
            dismissLabel = "Keep it going",
        )
    }
    if (askFinish) {
        CoffeeConfirm(
            title = "Finish now?",
            confirmLabel = "Finish",
            onConfirm = {
                askFinish = false
                finish()
            },
            onDismiss = { askFinish = false },
            body = "The brew is saved at ${clock((anchor.elapsedAt(System.nanoTime()) / 1000L).toInt())}, and you rate it next.",
            dismissLabel = "Keep brewing",
            destructive = false,
        )
    }
}

@Composable
private fun MissingPane(onExit: () -> Unit) {
    val colors = CoffeeTheme.colors
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // An empty mug says "nothing to brew" before the words do.
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                CoffeeMug(fill = 0.08f, body = colors.artShell, liquid = colors.artBed, crema = colors.artWater, showSteam = false)
            }
            Spacer(Modifier.height(12.dp))
            Text("That recipe is gone.", style = MaterialTheme.typography.headlineSmall, color = colors.inkHigh)
            Spacer(Modifier.height(8.dp))
            QuietButton(text = "Back", onClick = onExit)
        }
    }
}
