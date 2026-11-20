@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.laksh.coffee.ui.screens.brew

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.counted
import com.laksh.coffee.core.grams
import com.laksh.coffee.core.ratio
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.BrewerArt
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.StepKind
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.ui.components.BrewRing
import com.laksh.coffee.ui.components.CoffeeMug
import com.laksh.coffee.ui.components.DialReadingInput
import com.laksh.coffee.ui.components.Dripper
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.GrindStartHint
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.JourneyLine
import com.laksh.coffee.ui.components.JourneyNode
import com.laksh.coffee.ui.components.JourneyStyle
import com.laksh.coffee.ui.components.MeshGradientBackground
import com.laksh.coffee.ui.components.NodeKind
import com.laksh.coffee.ui.components.PillButton
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.RadioRow
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.StickyCtaBar
import com.laksh.coffee.ui.components.TickingNumber
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.components.TumblingBeans
import com.laksh.coffee.ui.components.journeyNodes
import com.laksh.coffee.ui.components.pressSquash
import com.laksh.coffee.ui.components.roastStatus
import com.laksh.coffee.ui.components.roastWord
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.nav.SharedKeys
import com.laksh.coffee.ui.nav.sharedBoundsModifier
import com.laksh.coffee.ui.theme.BrewMood
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.PillShape
import com.laksh.coffee.ui.theme.StepTitle
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Brewers that actually look like a cone with a bed in it. */
// Drip and Siphon drew as V60 and Chemex before v4, so they keep the dripper.
private val POUR_OVER = setOf(BrewerArt.V60, BrewerArt.Chemex, BrewerArt.Kalita, BrewerArt.Switch, BrewerArt.Drip, BrewerArt.Siphon)

/** The setup illustration. Small enough that the grind number stays the hero of the screen. */
private val HERO_ART = 104.dp

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

/** Upper-cases a label unless it holds a micro sign, which Kotlin would turn into a Greek capital Mu. */
private fun String.labelCase(): String = if ('µ' in this) this else uppercase()

private enum class Pane { Loading, Missing, Setup, Running }

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

@Composable
fun BrewScreen(
    recipeId: String,
    onExit: () -> Unit,
    onFinished: (Long) -> Unit,
    vm: BrewViewModel = viewModel(factory = BrewViewModel.factory(recipeId)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val anchor by vm.anchor.collectAsStateWithLifecycle()
    val timer = rememberBrewClock(anchor)
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current

    DisposableEffect(state.settings.keepScreenOn) {
        view.keepScreenOn = state.settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // One firm cue when a step changes: enough to feel without looking.
    val hapticsOn by rememberUpdatedState(state.settings.haptics)
    LaunchedEffect(Unit) {
        vm.stepCue.collect {
            if (hapticsOn) {
                haptics.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackType.Confirm else HapticFeedbackType.LongPress,
                )
            }
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
        else -> Pane.Running
    }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
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
                Pane.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    TumblingBeans()
                }

                Pane.Missing -> MissingPane(onExit = onExit)

                Pane.Setup -> SetupPane(
                    state = state,
                    onExit = onExit,
                    vm = vm,
                    heroScope = this@AnimatedContent,
                    tick = tick,
                )

                Pane.Running -> RunningPane(
                    state = state,
                    timer = timer,
                    anchor = anchor,
                    vm = vm,
                    onExit = onExit,
                    onFinished = onFinished,
                    heroScope = this@AnimatedContent,
                    tick = tick,
                )
            }
        }
    }
}

@Composable
private fun MissingPane(onExit: () -> Unit) {
    val colors = CoffeeTheme.colors
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // An empty mug says "nothing to brew" before the words do.
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                CoffeeMug(
                    fill = 0.08f,
                    body = colors.artShell,
                    liquid = colors.artBed,
                    crema = colors.artWater,
                    showSteam = false,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "That recipe is gone.",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.inkHigh,
            )
            Spacer(Modifier.height(8.dp))
            QuietButton(text = "Back", onClick = onExit)
        }
    }
}

// ------------------------------------------------------------------------ setup

@Composable
private fun SetupPane(
    state: BrewUiState,
    onExit: () -> Unit,
    vm: BrewViewModel,
    heroScope: AnimatedVisibilityScope?,
    tick: () -> Unit,
) {
    val recipe = state.recipe ?: return
    val colors = CoffeeTheme.colors
    val type = MaterialTheme.typography
    val scroll = rememberScrollState()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        // The status-bar inset sits outside the scroll (as on Review), so the cards clip at the
        // bar's edge instead of sliding under the clock.
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
        ) {
            // A Box, not a weighted Row: the eyebrow centres on the screen, not in whatever
            // room the close well and the wider brewer chip leave between them.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                IconWell(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Close",
                    onClick = onExit,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                EyebrowLabel("SET UP", Modifier.align(Alignment.Center))
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .height(28.dp)
                        .clip(PillShape)
                        .background(colors.surface2)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        recipe.brewer.label.labelCase(),
                        style = Micro,
                        color = colors.inkMid,
                        maxLines = 1,
                    )
                }
            }

            // A little ambient hardware so setup is not just a form. It is also the brew hero:
            // this box morphs into the dial when the brew starts.
            Box(
                Modifier
                    .then(
                        sharedBoundsModifier(
                            key = SharedKeys.BREW_HERO,
                            clip = RectangleShape,
                            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.Center),
                            visibilityScope = heroScope,
                        ),
                    )
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // The art pins its own default size, so a sized parent is what actually shrinks it.
                Box(Modifier.size(HERO_ART), contentAlignment = Alignment.Center) {
                    if (recipe.brewer.art in POUR_OVER) {
                        Dripper(
                            waterLevel = 0.55f,
                            shell = colors.artShell,
                            water = colors.artWater,
                            bed = colors.artBed,
                        )
                    } else {
                        CoffeeMug(
                            fill = 0.65f,
                            body = colors.artShell,
                            liquid = colors.artBed,
                            crema = colors.artWater,
                            showSteam = true,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                recipe.name,
                modifier = Modifier.fillMaxWidth(),
                style = type.headlineMedium,
                color = colors.inkHigh,
                textAlign = TextAlign.Center,
            )
            Text(
                recipe.author,
                modifier = Modifier.fillMaxWidth(),
                style = type.bodySmall,
                color = colors.inkMid,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))
            DoseCard(state = state, recipe = recipe, vm = vm, tick = tick)

            Spacer(Modifier.height(12.dp))
            GrindCard(state = state, recipe = recipe, vm = vm, tick = tick)

            Spacer(Modifier.height(12.dp))
            TempCard(state = state, vm = vm, tick = tick)

            Spacer(Modifier.height(12.dp))
            CoffeeCard(state = state, vm = vm)

            if (recipe.prep.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SoftCard(Modifier.fillMaxWidth()) {
                    EyebrowLabel("BEFORE YOU START")
                    Spacer(Modifier.height(8.dp))
                    recipe.prep.forEachIndexed { i, line ->
                        Row(Modifier.padding(vertical = 5.dp)) {
                            Text(
                                "${i + 1}",
                                modifier = Modifier.width(24.dp),
                                style = Num.Inline,
                                color = colors.inkLow,
                            )
                            Text(
                                line,
                                style = type.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                                color = colors.inkHigh,
                            )
                        }
                    }
                }
            }

            // Lets the last card scroll fully clear of the sticky Start bar and its fade.
            Spacer(Modifier.height(120.dp + navInset))
        }

        StickyCtaBar(Modifier.align(Alignment.BottomCenter)) {
            // Ink, not crema: nothing is live yet. Crema arrives with the running dial, so the
            // accent keeps meaning "the brew in progress".
            PrimaryButton(
                text = "Start brew",
                onClick = vm::start,
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.PlayArrow,
                enabled = state.readyToBrew,
                containerColor = if (state.readyToBrew) colors.inverse else colors.surface2,
                contentColor = if (state.readyToBrew) colors.onInverse else colors.inkLow,
            )
        }
    }
}

@Composable
private fun DoseCard(state: BrewUiState, recipe: Recipe, vm: BrewViewModel, tick: () -> Unit) {
    val colors = CoffeeTheme.colors
    SoftCard(Modifier.fillMaxWidth()) {
        EyebrowLabel("DOSE")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(
                icon = Icons.Rounded.Remove,
                contentDescription = "Less",
                onClick = {
                    tick()
                    vm.setDose((state.doseG - 0.5).coerceAtLeast(1.0))
                },
                size = 44.dp,
                onCanvas = false,
            )
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Large, not Display: the grind below is the one hero on this screen.
                TickingNumber(grams(state.doseG), style = Num.Large, unit = "g")
                Text(
                    "→ ${grams(recipe.waterG)} g water · ${ratio(state.doseG, recipe.waterG)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = colors.inkMid,
                    textAlign = TextAlign.Center,
                )
            }
            IconWell(
                icon = Icons.Rounded.Add,
                contentDescription = "More",
                onClick = {
                    tick()
                    vm.setDose(state.doseG + 0.5)
                },
                size = 44.dp,
                onCanvas = false,
            )
        }
    }
}

@Composable
private fun GrindCard(state: BrewUiState, recipe: Recipe, vm: BrewViewModel, tick: () -> Unit) {
    val colors = CoffeeTheme.colors
    val type = MaterialTheme.typography
    val grinder = state.selectedGrinder
    val method = recipe.brewer.method
    // Built once per grinder row: it reads the catalog entry the row is linked to.
    val scale = remember(grinder) { grinder?.scale() }
    // Only a click grinder whose dial has words of its own (turns, a numbered ring) gets the
    // choice. The steppers get the same room as the click dial above them, so a grind already
    // past the range the row was saved with (75 on a row that says 40 is the top) is not pulled
    // back into it by the first tap.
    val dialScale = remember(scale) {
        scale?.takeIf { it.usesClicks && it.dial != DialFormat.Plain }
            ?.let { it.copy(max = maxOf(it.max, SETUP_MAX_CLICKS)) }
    }
    var asDial by rememberSaveable { mutableStateOf(false) }
    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel("GRIND")
            Spacer(Modifier.weight(1f))
            if (state.grinders.size == 1 && grinder != null) {
                Text(
                    grinder.display.labelCase(),
                    style = Micro,
                    color = colors.inkLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // With several grinders the chips name the selection, so the corner label steps aside.
        if (state.grinders.size > 1) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.grinders, key = { it.id }) { g ->
                    SelectableChip(
                        text = g.display,
                        selected = g.id == state.selectedGrinderId,
                        onClick = { vm.setGrinder(g.id) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (grinder != null && grinder.usesClicks) {
            if (dialScale != null && asDial) {
                DialReadingInput(
                    scale = dialScale,
                    value = state.grindClicks?.toDouble(),
                    onValue = { v ->
                        tick()
                        vm.setGrindClicks(v?.roundToInt())
                    },
                )
            } else {
                ClickDial(
                    clicks = state.grindClicks,
                    grinder = grinder,
                    onAdjust = { delta ->
                        tick()
                        vm.adjustClicks(delta)
                    },
                )
            }
        } else {
            if (state.grinders.isEmpty()) {
                Text(
                    "No grinders yet. Type a setting, or add one under Gear.",
                    style = type.bodySmall,
                    color = colors.inkMid,
                )
                Spacer(Modifier.height(8.dp))
            }
            FieldWell(
                label = "SETTING",
                value = state.grindSetting,
                onChange = vm::setGrindSetting,
                placeholder = recipe.grind,
                // The catalog's one line on what to type for this grinder's dial.
                helper = scale?.entryHint?.ifBlank { null },
            )
        }

        // Where the catalog's sourced range for this grinder and method sits, one tap to take it.
        // Draws nothing for a grinder the catalog has no range for.
        if (scale != null) {
            GrindStartHint(
                scale = scale,
                method = method,
                onUse = { v ->
                    tick()
                    if (scale.usesClicks) vm.setGrindClicks(v.roundToInt()) else vm.setGrindSetting(scale.format(v))
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (dialScale != null) {
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                label = "Set it as the dial reads",
                checked = asDial,
                onChange = { asDial = it },
                subtitle = if (dialScale.dial is DialFormat.NumberTick) {
                    "The number on the dial and the marks after it, instead of one click count."
                } else {
                    "Full turns and the clicks past them, instead of one click count."
                },
            )
        }

        state.lastLogForSelection?.let { l ->
            Spacer(Modifier.height(12.dp))
            HairLine()
            Spacer(Modifier.height(12.dp))
            // The verdict takes the status colour; the grind that earned it is the detail.
            StatusLine(
                status = scoreStatus(l.overall),
                lead = if (l.overall > 0) "Last time ${l.overall}/10" else "Last time unrated",
                // Typed grind text is trimmed for the sentence, so "7.5 on the dial " never leaves a double space.
                detail = (l.grindClicks?.let { counted(it, "click") } ?: l.grindSetting.trim().ifEmpty { "no grind" }) +
                    " on the ${recipe.brewer.shortName}",
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Recipe says: ${recipe.grind}",
            style = type.bodySmall,
            color = colors.inkLow,
        )
    }
}

/**
 * Grind position for click grinders, as a number you can nudge with your thumb mid-setup.
 * Stored as an integer so a report can say "go three clicks finer" and mean something.
 */
@Composable
private fun ClickDial(
    clicks: Int?,
    grinder: GrinderEntity,
    onAdjust: (Int) -> Unit,
) {
    val colors = CoffeeTheme.colors
    val downWord = if (grinder.lowerIsFiner) "finer" else "coarser"
    val upWord = if (grinder.lowerIsFiner) "coarser" else "finer"

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconWell(
                    icon = Icons.Rounded.Remove,
                    contentDescription = "One click $downWord",
                    onClick = { onAdjust(-1) },
                    size = 44.dp,
                    onCanvas = false,
                )
                Spacer(Modifier.height(4.dp))
                Text(downWord.uppercase(), style = Micro, color = colors.inkLow)
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // An unset grind is a dash in inkLow, never 0: 0 is burr contact.
                TickingNumber(
                    clicks?.toString() ?: "—",
                    style = Num.Display,
                    color = if (clicks == null) colors.inkLow else colors.inkHigh,
                    unit = clicks?.let { unitWord(it, "click") },
                )
                Text(
                    describeClicks(clicks, grinder),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMid,
                    textAlign = TextAlign.Center,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconWell(
                    icon = Icons.Rounded.Add,
                    contentDescription = "One click $upWord",
                    onClick = { onAdjust(1) },
                    size = 44.dp,
                    onCanvas = false,
                )
                Spacer(Modifier.height(4.dp))
                Text(upWord.uppercase(), style = Micro, color = colors.inkLow)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (delta in intArrayOf(-5, -1, 1, 5)) {
                PillButton(
                    text = if (delta < 0) "−${-delta}" else "+$delta",
                    onClick = { onAdjust(delta) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The most clicks setup's steppers reach (three digits), the same ceiling the view model holds. */
private const val SETUP_MAX_CLICKS = 999.0

/** "dial 7.5 · burr travel ≈ 1.5 mm", or "7 turns + 5 · dial 7.5" on a 1.x row: the grinder's scale says it. */
private fun describeClicks(clicks: Int?, g: GrinderEntity): String {
    if (clicks == null) return "Set your grind"
    return g.scale().describe(clicks.toDouble())
}

@Composable
private fun TempCard(state: BrewUiState, vm: BrewViewModel, tick: () -> Unit) {
    // Stored and stepped in °C; °F is how it is shown.
    val units = LocalUnits.current
    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                EyebrowLabel("WATER TEMP")
                Spacer(Modifier.height(4.dp))
                TickingNumber(units.temp(state.tempC), style = Num.Large, unit = units.tempUnit)
            }
            IconWell(
                icon = Icons.Rounded.Remove,
                contentDescription = "Cooler",
                onClick = {
                    tick()
                    vm.setTemp(state.tempC - 1)
                },
                size = 44.dp,
                onCanvas = false,
            )
            Spacer(Modifier.width(8.dp))
            IconWell(
                icon = Icons.Rounded.Add,
                contentDescription = "Hotter",
                onClick = {
                    tick()
                    vm.setTemp(state.tempC + 1)
                },
                size = 44.dp,
                onCanvas = false,
            )
        }
    }
}

@Composable
private fun CoffeeCard(state: BrewUiState, vm: BrewViewModel) {
    val colors = CoffeeTheme.colors
    // Read once: days off roast only needs to be right to the day while this screen is open.
    val today = remember { LocalDate.now().toEpochDay() }
    SoftCard(Modifier.fillMaxWidth()) {
        EyebrowLabel("COFFEE")
        Spacer(Modifier.height(8.dp))
        if (state.beans.isEmpty()) {
            Text(
                "No beans yet. Add one under Gear.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.inkMid,
            )
        } else {
            state.beans.forEach { bean ->
                key(bean.id) {
                    val days = bean.roastedOnEpochDay?.let { (today - it).coerceAtLeast(0L) }
                    RadioRow(
                        title = bean.name,
                        selected = bean.id == state.selectedBeanId,
                        onClick = { vm.setBean(bean.id) },
                        subtitle = listOfNotNull(
                            bean.roaster.trim().ifEmpty { null },
                            days?.let { "$it d off roast" } ?: "roast date unknown",
                        ).joinToString(" · "),
                        note = roastWord(days),
                        noteStatus = roastStatus(days).takeIf { it == Status.Attention },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- running

@Composable
private fun RunningPane(
    state: BrewUiState,
    timer: BrewClock,
    anchor: TimerAnchor,
    vm: BrewViewModel,
    onExit: () -> Unit,
    onFinished: (Long) -> Unit,
    heroScope: AnimatedVisibilityScope?,
    tick: () -> Unit,
) {
    val recipe = state.recipe ?: return
    val colors = CoffeeTheme.colors
    val step = state.step
    val done = state.done
    val totalMs = recipe.totalSec.coerceAtLeast(1) * 1000f
    val waterG = recipe.waterG.toFloat().coerceAtLeast(1f)

    // One whole-brew timebase for the ring and the journey line, so the two drawings of
    // progress can never disagree with each other or with the clock. Read only while drawing.
    val tripProgress: () -> Float = remember(recipe) {
        { (timer.elapsedMs.longValue + timer.seekMs.value) / totalMs }
    }
    val waterProgress: () -> Float = remember(recipe) {
        { recipe.waterAtF((timer.elapsedMs.longValue + timer.seekMs.value).toLong()) / waterG }
    }
    val boundaries = remember(recipe) { recipe.stepBoundaries() }
    val nodes = remember(recipe, state.bedExposedSec) {
        val total = recipe.totalSec.coerceAtLeast(1).toFloat()
        recipe.journeyNodes() + listOfNotNull(
            state.bedExposedSec?.let {
                JourneyNode(
                    fraction = (it / total).coerceIn(0f, 1f),
                    kind = NodeKind.Actual,
                    status = Status.Good,
                )
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The dial gives way on short screens so the step card keeps room for its words.
        val ringSize = (maxHeight - 630.dp).coerceIn(176.dp, 264.dp)
        val clockStyle = if (recipe.totalSec >= 600 || ringSize < 240.dp) Num.ClockCompact else Num.Clock

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconWell(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Abandon brew",
                    onClick = onExit,
                    fill = colors.surface1.copy(alpha = 0.7f),
                )
                Text(
                    recipe.name,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "STEP ${state.stepIndex + 1}/${recipe.steps.size}",
                    style = Micro,
                    color = colors.inkLow,
                )
            }

            BrewStatus(state = state, anchor = anchor, elapsed = timer.elapsedMs)

            Spacer(Modifier.height(12.dp))

            // The dial. The other end of the brew hero: the setup art lands here.
            Box(
                Modifier
                    .then(
                        sharedBoundsModifier(
                            key = SharedKeys.BREW_HERO,
                            clip = RectangleShape,
                            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.Center),
                            visibilityScope = heroScope,
                        ),
                    )
                    .size(ringSize)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center,
            ) {
                BrewRing(
                    progress = tripProgress,
                    waterProgress = waterProgress,
                    accent = colors.cremaStroke,
                    modifier = Modifier.fillMaxSize(),
                    stepBoundaries = boundaries,
                    pouring = state.isPouring,
                    running = state.running,
                    trackColor = colors.inkFaint,
                    liquidColor = colors.ringLiquid,
                    strokeFraction = 0.035f,
                    headCore = colors.bg,
                    tickColor = colors.inkLow,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TimerClock(timer.elapsedMs, clockStyle)
                        // Names the clock's frame, so "1:12" beside a fifth-full ring never
                        // looks wrong: the ring and the clock are both the whole brew.
                        Text(
                            "OF ${clock(recipe.totalSec)}",
                            style = Micro,
                            color = colors.inkLow,
                        )
                        Spacer(Modifier.height(8.dp))
                        WaterReadout(
                            elapsed = timer.elapsedMs,
                            recipe = recipe,
                            target = step?.targetWaterG ?: recipe.waterG.roundToInt(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            JourneyLine(
                nodes = nodes,
                progress = tripProgress,
                modifier = Modifier.fillMaxWidth(),
                style = JourneyStyle.Live,
                startLabel = "0:00",
                endLabel = clock(recipe.totalSec),
            )
            OverrunLeaf(
                elapsed = timer.elapsedMs,
                totalSec = recipe.totalSec,
                modifier = Modifier.align(Alignment.End),
            )

            Spacer(Modifier.height(8.dp))

            SoftCard(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AnimatedContent(
                    targetState = state.stepIndex,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (fadeIn(Motion.decel(260)) + slideInVertically { it / 3 })
                            .togetherWith(fadeOut(Motion.decel(160)) + slideOutVertically { -it / 3 })
                    },
                    label = "step",
                ) { index ->
                    StepCardBody(recipe = recipe, index = index)
                }
            }

            // Drawdown marker: only useful once the pouring is over. It grows into its height so
            // the transport row below slides rather than jumps.
            AnimatedVisibility(
                visible = step?.kind == StepKind.Drawdown || done,
                enter = fadeIn(Motion.decel()) + expandVertically(Motion.size()) + slideInVertically(Motion.offset()) { it / 2 },
                exit = fadeOut(Motion.decel(140)) + shrinkVertically(Motion.size()),
            ) {
                Crossfade(
                    targetState = state.bedExposedSec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(48.dp),
                    animationSpec = Motion.decel(200),
                    label = "bedLabel",
                ) { bed ->
                    if (bed == null) {
                        SecondaryButton(
                            text = "Bed exposed",
                            onClick = {
                                tick()
                                vm.markBedExposed()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Rounded.TouchApp,
                            onCanvas = true,
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            StatusLine(
                                status = Status.Good,
                                lead = "Bed exposed",
                                detail = "at ${clock(bed)}",
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Transport controls.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(
                    text = "−5s",
                    onClick = {
                        tick()
                        vm.addSeconds(-5)
                    },
                    height = 44.dp,
                    onCanvas = true,
                )
                Spacer(Modifier.width(12.dp))
                PlayPauseButton(running = state.running, onClick = vm::toggleRunning)
                Spacer(Modifier.width(12.dp))
                PillButton(
                    text = "+5s",
                    onClick = {
                        tick()
                        vm.addSeconds(5)
                    },
                    height = 44.dp,
                    onCanvas = true,
                )
                Spacer(Modifier.weight(1f))
                IconWell(
                    icon = Icons.Rounded.SkipNext,
                    contentDescription = "Skip to next step",
                    onClick = {
                        tick()
                        vm.skipToNextStep()
                    },
                    size = 48.dp,
                )
            }

            Spacer(Modifier.height(12.dp))

            FinishCta(done = done, onClick = { vm.finish(onFinished) })

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The finish CTA turns crema when the brew completes, in step with the mesh. Its own leaf: the
 * colours spring for about a second, and reading them here recomposes this button per frame
 * instead of the whole running pane.
 */
@Composable
private fun FinishCta(done: Boolean, onClick: () -> Unit) {
    val colors = CoffeeTheme.colors
    val ctaBg = animateColorAsState(
        if (done) colors.crema else colors.surface1,
        Motion.heavy(),
        label = "ctaBg",
    )
    val ctaFg = animateColorAsState(
        if (done) colors.onCrema else colors.inkHigh,
        Motion.heavy(),
        label = "ctaFg",
    )
    PrimaryButton(
        text = if (done) "Rate this brew" else "Finish early",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.Rounded.Check,
        height = 52.dp,
        containerColor = ctaBg.value,
        contentColor = ctaFg.value,
    )
}

/**
 * The one plain-language instruction, derived from state and the anchor only. The current
 * step's countdown sits beside it in its own leaf, so StatusLine itself never ticks.
 */
@Composable
private fun BrewStatus(state: BrewUiState, anchor: TimerAnchor, elapsed: LongState) {
    val step = state.step
    val pausedAt = clock((anchor.accumulatedMs / 1000L).toInt())
    when {
        state.phase == BrewPhase.Finishing ->
            StatusLine(status = Status.Neutral, lead = "Saving", detail = "at $pausedAt")

        state.done ->
            StatusLine(status = Status.Good, lead = "Done", detail = "rate this brew")

        !state.running ->
            StatusLine(status = Status.Attention, lead = "Paused", detail = "at $pausedAt")

        step == null ->
            StatusLine(status = Status.Live, lead = "Brewing", live = true)

        else -> {
            val target = step.targetWaterG
            val lead = when {
                step.kind == StepKind.Bloom && target != null -> "Bloom to $target g"
                step.kind == StepKind.Pour && target != null -> "Pour to $target g"
                step.kind == StepKind.Drawdown -> "Drawdown"
                else -> step.title
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusLine(
                    status = Status.Live,
                    lead = lead,
                    modifier = Modifier.weight(1f, fill = false),
                    live = true,
                )
                StepCountdown(elapsed = elapsed, endSec = step.endSec)
            }
        }
    }
}

@Composable
private fun StepCardBody(recipe: Recipe, index: Int) {
    val colors = CoffeeTheme.colors
    val type = MaterialTheme.typography
    val s = recipe.steps.getOrNull(index) ?: return
    val next = recipe.steps.getOrNull(index + 1)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .height(22.dp)
                    .clip(PillShape)
                    .background(colors.surface2)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.kind.label.uppercase(), style = Micro, color = colors.inkMid, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${clock(s.startSec)}–${clock(s.endSec)}",
                style = type.bodySmall,
                color = colors.inkLow,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            s.title,
            style = StepTitle,
            color = colors.inkHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            s.detail,
            style = type.bodyMedium,
            color = colors.inkMid,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (next != null) {
            HairLine()
            Spacer(Modifier.height(10.dp))
            Text("NEXT · ${clock(next.startSec)}", style = Micro, color = colors.inkLow)
            Spacer(Modifier.height(2.dp))
            Text(
                next.title,
                style = type.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.inkMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** An inverse circle whose icon swaps with a little bounce. IconWell has no animated swap. */
@Composable
private fun PlayPauseButton(running: Boolean, onClick: () -> Unit) {
    val colors = CoffeeTheme.colors
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(64.dp)
            .pressSquash(src, 0.92f)
            .clip(CircleShape)
            .background(colors.inverse)
            .clickable(
                interactionSource = src,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = running,
            transitionSpec = {
                (scaleIn(Motion.bounce(), 0.6f) + fadeIn(Motion.decel(160)))
                    .togetherWith(scaleOut(Motion.settle(), 0.6f) + fadeOut(Motion.decel(120)))
            },
            label = "playPause",
        ) { r ->
            Icon(
                if (r) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (r) "Pause" else "Resume",
                tint = colors.onInverse,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ timer leaves
// Each is a plain Text on derivedStateOf, so it recomposes at most once a second (the water
// readout once a gram) and nothing above it recomposes with the clock.

/** The numerals. A plain Text that changes once a second and snaps on a seek. */
@Composable
private fun TimerClock(elapsed: LongState, style: TextStyle) {
    val sec by remember { derivedStateOf { (elapsed.longValue / 1000L).toInt() } }
    Text(clock(sec), style = style, color = CoffeeTheme.colors.inkHigh, maxLines = 1)
}

/**
 * Grams so far against this step's target (the total when the step has none). It sits on a
 * quiet plate, like the close well above, because the dial's water rises through this line:
 * the waterline now passes behind the plate instead of cutting through the digits.
 */
@Composable
private fun WaterReadout(elapsed: LongState, recipe: Recipe, target: Int) {
    val colors = CoffeeTheme.colors
    val w by remember(recipe) { derivedStateOf { recipe.waterAt(elapsed.longValue) } }
    Row(
        Modifier
            .clip(PillShape)
            .background(colors.surface1.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(
            "$w g",
            modifier = Modifier.alignByBaseline(),
            style = Num.Small,
            color = colors.inkHigh,
            maxLines = 1,
        )
        Text(
            " / $target",
            modifier = Modifier.alignByBaseline(),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkLow,
            maxLines = 1,
        )
    }
}

/** " · 0:12 left" for the current step, matching the status line's detail style. */
@Composable
private fun StepCountdown(elapsed: LongState, endSec: Int) {
    val left by remember(endSec) {
        derivedStateOf { (endSec - (elapsed.longValue / 1000L).toInt()).coerceAtLeast(0) }
    }
    Text(
        " · ${clock(left)} left",
        style = MaterialTheme.typography.bodyMedium,
        color = CoffeeTheme.colors.inkMid,
        maxLines = 1,
    )
}

/** "+0:12" under the journey line's end once the brew runs past the recipe. Empty before. */
@Composable
private fun OverrunLeaf(elapsed: LongState, totalSec: Int, modifier: Modifier = Modifier) {
    val label by remember(totalSec) {
        derivedStateOf {
            val s = (elapsed.longValue / 1000L).toInt()
            if (s > totalSec) "+" + clock(s - totalSec) else ""
        }
    }
    Text(label, modifier = modifier, style = Micro, color = CoffeeTheme.colors.attention, maxLines = 1)
}
