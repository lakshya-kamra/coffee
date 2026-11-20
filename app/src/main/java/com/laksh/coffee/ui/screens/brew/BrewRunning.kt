@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.laksh.coffee.ui.screens.brew

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.formatDuration
import com.laksh.coffee.domain.BrewStyle
import com.laksh.coffee.domain.Glossary
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.RecipeStep
import com.laksh.coffee.domain.StepKind
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.ui.components.BrewRing
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.JourneyLine
import com.laksh.coffee.ui.components.JourneyNode
import com.laksh.coffee.ui.components.JourneyStyle
import com.laksh.coffee.ui.components.NodeKind
import com.laksh.coffee.ui.components.PillButton
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.TermChip
import com.laksh.coffee.ui.components.journeyNodes
import com.laksh.coffee.ui.components.pressSquash
import com.laksh.coffee.ui.nav.SharedKeys
import com.laksh.coffee.ui.nav.sharedBoundsModifier
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.PillShape
import com.laksh.coffee.ui.theme.StepTitle
import kotlin.math.roundToInt

/** What every running layout needs; built once per pane, read by the three layouts. */
internal class RunningModel(
    val state: BrewUiState,
    val recipe: Recipe,
    val timer: BrewClock,
    val anchor: TimerAnchor,
    val tripProgress: () -> Float,
    val waterProgress: () -> Float,
    val boundaries: List<Float>,
    val nodes: List<JourneyNode>,
    val onLeave: () -> Unit,
    val onToggle: () -> Unit,
    val onSeek: (Int) -> Unit,
    val onNext: () -> Unit,
    val onBed: () -> Unit,
    val onFinish: () -> Unit,
)

/**
 * The timer (v4 §3.5): portrait, stage or short, chosen from the space it has and recomputed live.
 * The state is in the view model, so swapping layout mid-brew loses nothing.
 */
@Composable
internal fun RunningPane(
    state: BrewUiState,
    timer: BrewClock,
    anchor: TimerAnchor,
    heroScope: AnimatedVisibilityScope?,
    onLeave: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    onNext: () -> Unit,
    onBed: () -> Unit,
    onFinish: () -> Unit,
) {
    val recipe = state.recipe ?: return
    val totalMs = recipe.totalSec.coerceAtLeast(1) * 1000f
    val waterG = recipe.waterG.toFloat().coerceAtLeast(1f)
    // One whole-brew timebase for the ring and the journey line, so the two drawings of progress
    // can never disagree with each other or with the clock. Read only while drawing.
    val tripProgress: () -> Float = remember(recipe) { { (timer.elapsedMs.longValue + timer.seekMs.value) / totalMs } }
    val waterProgress: () -> Float = remember(recipe) {
        { recipe.waterAtF((timer.elapsedMs.longValue + timer.seekMs.value).toLong()) / waterG }
    }
    val boundaries = remember(recipe) { recipe.stepBoundaries() }
    val nodes = remember(recipe, state.bedExposedSec) {
        val total = recipe.totalSec.coerceAtLeast(1).toFloat()
        recipe.journeyNodes() + listOfNotNull(
            state.bedExposedSec?.let { JourneyNode(fraction = (it / total).coerceIn(0f, 1f), kind = NodeKind.Actual, status = Status.Good) },
        )
    }
    val m = RunningModel(state, recipe, timer, anchor, tripProgress, waterProgress, boundaries, nodes, onLeave, onToggle, onSeek, onNext, onBed, onFinish)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (BrewLayouts.of(maxWidth.value, maxHeight.value)) {
            RunLayout.Portrait -> PortraitRunning(m, heroScope, maxWidth, maxHeight)
            RunLayout.Stage -> StageRunning(m)
            RunLayout.Short -> ShortRunning(m)
        }
    }
}

/** A size in dp as sp at any font scale: the clock is sized by its ring, not by the text setting. */
@Composable
private fun Dp.fixedSp() = with(LocalDensity.current) { this@fixedSp.toSp() }

// ------------------------------------------------------------------------ portrait

/** Room the rows other than the dial take: top row, status, journey, a step card, transport, finish. */
private const val PORTRAIT_FIXED_ROWS = 430f

@Composable
private fun PortraitRunning(m: RunningModel, heroScope: AnimatedVisibilityScope?, w: Dp, h: Dp) {
    val colors = CoffeeTheme.colors
    val state = m.state
    val ring = BrewLayouts.portraitRing(w.value, h.value, 16f, PORTRAIT_FIXED_ROWS).dp
    // The phone clock never grows with the text setting: it is sized to sit inside its ring.
    val bigClock = BrewLayouts.portraitUsesBigClock(ring.value, m.recipe.totalSec)
    val clockStyle = (if (bigClock) Num.Clock else Num.ClockCompact).let { s -> s.copy(fontSize = (if (bigClock) 88 else 72).dp.fixedSp(), lineHeight = (if (bigClock) 88 else 72).dp.fixedSp()) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            TopRow(m, Modifier.heightIn(min = 48.dp))
            BrewStatus(state = state, anchor = m.anchor, elapsed = m.timer.elapsedMs)

            // The dial, the journey and the step card share what is left. When even the phone
            // ring and a short card do not fit (200 % text, a short window), this part scrolls;
            // the transport below never does.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val roomy = maxHeight >= ring + 260.dp
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(if (roomy) Modifier else Modifier.verticalScroll(rememberScrollState())),
                ) {
                    Spacer(Modifier.height(12.dp))
                    Dial(
                        m = m,
                        size = ring,
                        clockStyle = clockStyle,
                        gramsStyle = Num.Small,
                        heroScope = heroScope,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(20.dp))
                    JourneyLine(
                        nodes = m.nodes,
                        progress = m.tripProgress,
                        modifier = Modifier.fillMaxWidth(),
                        style = JourneyStyle.Live,
                        startLabel = "0:00",
                        endLabel = formatDuration(m.recipe.totalSec),
                    )
                    OverrunLeaf(elapsed = m.timer.elapsedMs, totalSec = m.recipe.totalSec, modifier = Modifier.align(Alignment.End))
                    Spacer(Modifier.height(8.dp))
                    StepCard(
                        m = m,
                        titleStyle = StepTitle,
                        showNext = true,
                        scrollInside = roomy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (roomy) Modifier.weight(1f) else Modifier.heightIn(min = 140.dp)),
                    )
                    BedExposedRow(m)
                }
            }

            Spacer(Modifier.height(12.dp))
            Transport(m, playSize = 64.dp, rowHeight = 64.dp, bedInRow = false)
            Spacer(Modifier.height(12.dp))
            FinishCta(done = state.done, onClick = m.onFinish)
            Spacer(Modifier.height(12.dp))
        }
    }
}

// --------------------------------------------------------------------------- stage

/**
 * Tablet landscape, laptops and desktops: the counter seen from a metre away. The ring and the
 * clock on the left stage; the step, every step after it and the transport on the right.
 */
@Composable
private fun StageRunning(m: RunningModel) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val gutter = if (w >= 1200f) 32.dp else 24.dp
        val column = BrewLayouts.stageColumn(w).dp
        val leftWidth = w - column.value - gutter.value * 3
        val ring = minOf(BrewLayouts.stageRing(w, h), leftWidth, h - 140f).coerceAtLeast(200f).dp
        val clockStyle = Num.Clock.copy(fontSize = BrewLayouts.stageClock(h).dp.fixedSp(), lineHeight = BrewLayouts.stageClock(h).dp.fixedSp())
        val gramsSize = BrewLayouts.stageGrams(h).dp.fixedSp()
        val gramsStyle = Num.Large.copy(fontSize = gramsSize, lineHeight = gramsSize * 1.1f)
        val titleSize = BrewLayouts.stageTitle(h).dp.fixedSp()

        Row(Modifier.fillMaxSize().padding(horizontal = gutter, vertical = 16.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BrewStatus(state = m.state, anchor = m.anchor, elapsed = m.timer.elapsedMs, modifier = Modifier.widthIn(max = ring + 80.dp))
                Spacer(Modifier.height(16.dp))
                Dial(m = m, size = ring, clockStyle = clockStyle, gramsStyle = gramsStyle, heroScope = null)
                Spacer(Modifier.height(20.dp))
                Box(Modifier.width(ring + 80.dp)) {
                    JourneyLine(
                        nodes = m.nodes,
                        progress = m.tripProgress,
                        modifier = Modifier.fillMaxWidth(),
                        style = JourneyStyle.Live,
                        startLabel = "0:00",
                        endLabel = formatDuration(m.recipe.totalSec),
                    )
                }
                OverrunLeaf(elapsed = m.timer.elapsedMs, totalSec = m.recipe.totalSec)
            }
            Spacer(Modifier.width(gutter))
            Column(Modifier.width(column).fillMaxHeight()) {
                TopRow(m, Modifier.heightIn(min = 48.dp), longStep = true)
                Spacer(Modifier.height(12.dp))
                StepCard(
                    m = m,
                    titleStyle = StepTitle.copy(fontSize = titleSize, lineHeight = titleSize * 1.2f),
                    showNext = false,
                    scrollInside = true,
                    modifier = Modifier.fillMaxWidth().heightIn(max = (h * 0.42f).dp),
                    bodyStyle = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(12.dp))
                StepList(m, Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Transport(m, playSize = 72.dp, rowHeight = 72.dp, bedInRow = true)
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = if (m.state.done) "Rate this brew" else "Finish",
                    onClick = m.onFinish,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Check,
                    height = 48.dp,
                    onCanvas = true,
                )
            }
        }
    }
}

/** Every step as a timeline: done ones dimmed, the current one lifted, the rest with start times. */
@Composable
private fun StepList(m: RunningModel, modifier: Modifier = Modifier) {
    val colors = CoffeeTheme.colors
    val list = rememberLazyListState()
    val current = m.state.stepIndex
    LaunchedEffect(current) { list.animateScrollToItem((current - 1).coerceAtLeast(0)) }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(m.recipe.steps, key = { i, _ -> i }) { i, s ->
            val past = i < current || (m.state.done && i <= current)
            val now = i == current && !m.state.done
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape12)
                    .background(if (now) colors.surface2 else colors.bg.copy(alpha = 0f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    clock(s.startSec),
                    style = Num.Inline,
                    color = if (past) colors.inkLow else colors.inkMid,
                    modifier = Modifier.width(48.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stepLead(s, m.state.settings.hasScale),
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            now -> colors.inkHigh
                            past -> colors.inkLow
                            else -> colors.inkMid
                        },
                    )
                    if (past) Text("Done", style = Micro, color = colors.inkLow)
                }
            }
        }
    }
}

private val RoundedCornerShape12 = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

// --------------------------------------------------------------------------- short

/**
 * Phone landscape and split screen: two columns that never scroll as a page. The dial on the left;
 * the step card (scrolling inside itself), the next step, the transport and, under it, Finish (with
 * Bed exposed beside it during a drawdown) on the right. The transport row holds only fixed,
 * narrow controls, so it fits a 640x360 phone with a side navigation bar. The journey becomes a
 * 4dp strip along the top edge.
 */
@Composable
private fun ShortRunning(m: RunningModel) {
    val colors = CoffeeTheme.colors
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val w = maxWidth.value
            val h = maxHeight.value
            val square = BrewLayouts.shortSquare(w + 32f, h + 16f).coerceAtMost(h).coerceAtLeast(120f).dp
            val clockSize = BrewLayouts.shortClock(h + 16f).dp.fixedSp()
            val clockStyle = Num.Clock.copy(fontSize = clockSize, lineHeight = clockSize)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Dial(m = m, size = square, clockStyle = clockStyle, gramsStyle = Num.Small, heroScope = null)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopRow(m, Modifier.heightIn(min = 40.dp))
                    Spacer(Modifier.height(4.dp))
                    StepCard(
                        m = m,
                        titleStyle = StepTitle.copy(fontSize = 20.sp, lineHeight = 24.sp),
                        showNext = false,
                        scrollInside = true,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        compact = true,
                    )
                    NextLine(m)
                    Transport(m, playSize = 56.dp, rowHeight = 56.dp, bedInRow = false)
                    Spacer(Modifier.height(8.dp))
                    ShortFinishRow(m)
                }
            }
        }
        // The journey line, as a strip on the top edge.
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .drawBehind {
                    drawRect(colors.inkFaint)
                    val p = m.tripProgress().coerceIn(0f, 1f)
                    drawRect(colors.cremaStroke, size = Size(size.width * p, size.height))
                },
        )
    }
}

/** "NEXT · 0:45 · Up to 300 g" on one line; with auto-advance off, the prompt to tap Next. */
@Composable
private fun NextLine(m: RunningModel) {
    val next = m.recipe.steps.getOrNull(m.state.stepIndex + 1) ?: return
    // Without a scale the dial reads "about 0 ml / 1250", so this line speaks ml too (as the web).
    val target = next.targetWaterG?.let { if (m.state.settings.hasScale) " · Up to $it g" else " · Up to about ${aboutMl(it)} ml" }.orEmpty()
    Text(
        if (m.state.waitingForTap) "TIME FOR THE NEXT STEP · TAP NEXT" else "NEXT · ${clock(next.startSec)}$target · ${next.title}",
        style = Micro,
        color = CoffeeTheme.colors.inkLow,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

// ------------------------------------------------------------------- shared pieces

@Composable
private fun TopRow(m: RunningModel, modifier: Modifier = Modifier, longStep: Boolean = false) {
    val colors = CoffeeTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconWell(
            icon = Icons.Rounded.Close,
            contentDescription = "Leave brew",
            onClick = m.onLeave,
            fill = colors.surface1.copy(alpha = 0.7f),
            size = 48.dp,
            shortcut = "Esc",
        )
        Text(
            m.recipe.name,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleSmall,
            color = colors.inkMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            if (longStep) "Step ${m.state.stepIndex + 1} of ${m.recipe.steps.size}" else "STEP ${m.state.stepIndex + 1}/${m.recipe.steps.size}",
            style = if (longStep) MaterialTheme.typography.titleSmall else Micro,
            color = colors.inkLow,
        )
    }
}

/** The ring, the clock inside it, "OF 3:30" and poured / target. */
@Composable
private fun Dial(
    m: RunningModel,
    size: Dp,
    clockStyle: TextStyle,
    gramsStyle: TextStyle,
    heroScope: AnimatedVisibilityScope?,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    val state = m.state
    // The other end of the brew hero: the setup art lands here.
    Box(
        modifier
            .then(
                sharedBoundsModifier(
                    key = SharedKeys.BREW_HERO,
                    clip = RectangleShape,
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.Center),
                    visibilityScope = heroScope,
                ),
            )
            .size(size),
        contentAlignment = Alignment.Center,
    ) {
        BrewRing(
            progress = m.tripProgress,
            waterProgress = m.waterProgress,
            accent = colors.cremaStroke,
            modifier = Modifier.fillMaxSize(),
            stepBoundaries = m.boundaries,
            pouring = state.isPouring,
            running = state.running,
            trackColor = colors.inkFaint,
            liquidColor = colors.ringLiquid,
            strokeFraction = 0.035f,
            headCore = colors.bg,
            tickColor = colors.inkLow,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimerClock(m.timer.elapsedMs, clockStyle)
                // Names the clock's frame, so "1:12" beside a fifth-full ring never looks wrong:
                // the ring and the clock are both the whole brew.
                Text("OF ${formatDuration(m.recipe.totalSec)}", style = Micro, color = colors.inkLow)
                Spacer(Modifier.height(8.dp))
                WaterReadout(
                    elapsed = m.timer.elapsedMs,
                    recipe = m.recipe,
                    target = state.step?.targetWaterG ?: m.recipe.waterG.roundToInt(),
                    hasScale = state.settings.hasScale,
                    style = gramsStyle,
                    pourAll = !state.settings.hasScale && pourAll(m.recipe, state.stepIndex),
                )
            }
        }
    }
}

/** Grams poured, said as a jug reading: to the nearest 10 ml, like the web's aboutMl. */
private fun aboutMl(g: Int): Int = maxOf(0, Math.round(g / 10f) * 10)

/** [BrewWords.lead] on screen: "Pour to about 300 ml" without a scale, where "300 g" means nothing. */
private fun stepLead(step: RecipeStep, hasScale: Boolean): String {
    val t = step.targetWaterG
    if (hasScale || t == null) return BrewWords.lead(step)
    return when (step.kind) {
        StepKind.Bloom -> "Bloom to about ${aboutMl(t)} ml"
        StepKind.Pour -> "Pour to about ${aboutMl(t)} ml"
        else -> BrewWords.lead(step)
    }
}

/** An immersion step whose target is all the water reads "Pour all 500 ml" without a scale. */
private fun pourAll(recipe: Recipe, index: Int): Boolean {
    val s = recipe.steps.getOrNull(index) ?: return false
    return BrewStyle.of(recipe.brewer) == BrewStyle.Immersion && s.targetWaterG == recipe.waterG.roundToInt()
}

@Composable
private fun StepCard(
    m: RunningModel,
    titleStyle: TextStyle,
    showNext: Boolean,
    scrollInside: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    bodyStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    SoftCard(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(if (compact) 14.dp else 18.dp)) {
        AnimatedContent(
            targetState = m.state.stepIndex,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = {
                (fadeIn(Motion.decel(260)) + slideInVertically { it / 3 })
                    .togetherWith(fadeOut(Motion.decel(160)) + slideOutVertically { -it / 3 })
            },
            label = "step",
        ) { index ->
            StepCardBody(
                recipe = m.recipe,
                index = index,
                titleStyle = titleStyle,
                bodyStyle = bodyStyle,
                showNext = showNext,
                scrollInside = scrollInside,
                hasScale = m.state.settings.hasScale,
                explain = m.state.firstEver,
            )
        }
    }
}

/**
 * The step: its kind (a glossary chip), its title and the whole instruction. It scrolls inside the
 * card instead of cutting words off (A9). Without a scale a pour says how much comes from the jug;
 * in a first brew each kind's glossary line shows once, under the first step of that kind.
 */
@Composable
private fun StepCardBody(
    recipe: Recipe,
    index: Int,
    titleStyle: TextStyle,
    bodyStyle: TextStyle,
    showNext: Boolean,
    scrollInside: Boolean,
    hasScale: Boolean,
    explain: Boolean,
) {
    val colors = CoffeeTheme.colors
    val type = MaterialTheme.typography
    val s = recipe.steps.getOrNull(index) ?: return
    val next = recipe.steps.getOrNull(index + 1)
    val termKey = Glossary.termForStepKind(s.kind)
    val explainLine = remember(recipe, index, explain) {
        if (!explain || termKey == null || recipe.steps.indexOfFirst { it.kind == s.kind } != index) null
        else runCatching { Catalog.term(termKey)?.short }.getOrNull()
    }
    val jugMl = remember(recipe, index, hasScale) {
        if (hasScale) null else s.targetWaterG?.let { t -> t - (recipe.steps.take(index).mapNotNull { it.targetWaterG }.lastOrNull() ?: 0) }?.takeIf { it > 0 }
    }

    Column(if (scrollInside) Modifier.fillMaxWidth().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (termKey != null) {
                TermChip(key = termKey, text = s.kind.label.uppercase())
            } else {
                Box(
                    Modifier
                        .heightIn(min = 22.dp)
                        .clip(PillShape)
                        .background(colors.surface2)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.kind.label.uppercase(), style = Micro, color = colors.inkMid)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${clock(s.startSec)}–${clock(s.endSec)}", style = type.bodySmall, color = colors.inkLow)
        }
        Spacer(Modifier.height(10.dp))
        Text(s.title, style = titleStyle, color = colors.inkHigh)
        if (jugMl != null) {
            Spacer(Modifier.height(2.dp))
            Text("About $jugMl ml from your jug", style = type.bodyMedium, color = colors.inkMid)
        }
        Spacer(Modifier.height(6.dp))
        Text(s.detail, style = bodyStyle, color = colors.inkMid)
        if (explainLine != null) {
            Spacer(Modifier.height(8.dp))
            Text(explainLine, style = type.bodySmall, color = colors.inkLow)
        }
        if (s.kind == StepKind.Drawdown) {
            Spacer(Modifier.height(8.dp))
            Text("Tap when you first see the coffee bed through the water", style = type.bodySmall, color = colors.inkLow)
        }
        if (showNext && next != null) {
            Spacer(Modifier.height(12.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))
            Text("NEXT · ${clock(next.startSec)}", style = Micro, color = colors.inkLow)
            Spacer(Modifier.height(2.dp))
            Text(next.title, style = type.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp), color = colors.inkMid)
        }
    }
}

/**
 * Drawdown marker for the portrait layout: only useful once the pouring is over. It grows into
 * its height so the transport row below slides rather than jumps.
 */
@Composable
private fun BedExposedRow(m: RunningModel) {
    val state = m.state
    AnimatedVisibility(
        visible = state.step?.kind == StepKind.Drawdown || state.done,
        enter = fadeIn(Motion.decel()) + expandVertically(Motion.size()) + slideInVertically(Motion.offset()) { it / 2 },
        exit = fadeOut(Motion.decel(140)) + shrinkVertically(Motion.size()),
    ) {
        Crossfade(
            targetState = state.bedExposedSec,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
            animationSpec = Motion.decel(200),
            label = "bedLabel",
        ) { bed ->
            if (bed == null) {
                SecondaryButton(text = "Bed exposed", onClick = m.onBed, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.TouchApp, onCanvas = true)
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    StatusLine(status = Status.Good, lead = "Bed exposed", detail = "at ${clock(bed)}")
                }
            }
        }
    }
}

/** −5 s, play/pause, +5 s and Next (or Bed exposed during a drawdown, where [bedInRow]). */
@Composable
private fun Transport(
    m: RunningModel,
    playSize: Dp,
    rowHeight: Dp,
    bedInRow: Boolean,
) {
    val state = m.state
    val bedNow = bedInRow && state.step?.kind == StepKind.Drawdown && state.bedExposedSec == null && !state.done
    Row(
        Modifier.fillMaxWidth().heightIn(min = rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(text = "−5s", onClick = { m.onSeek(-5) }, height = 48.dp, onCanvas = true)
        Spacer(Modifier.width(10.dp))
        PlayPauseButton(running = state.running, onClick = m.onToggle, size = playSize)
        Spacer(Modifier.width(10.dp))
        PillButton(text = "+5s", onClick = { m.onSeek(5) }, height = 48.dp, onCanvas = true)
        Spacer(Modifier.weight(1f))
        if (bedNow) {
            PillButton(text = "Bed exposed", onClick = m.onBed, height = 48.dp, onCanvas = true)
        } else {
            IconWell(icon = Icons.Rounded.SkipNext, contentDescription = "Next step", onClick = m.onNext, size = 48.dp, shortcut = "N")
        }
    }
}

/** Short layout's last row: Finish across the column, with Bed exposed beside it in a drawdown. */
@Composable
private fun ShortFinishRow(m: RunningModel) {
    val state = m.state
    val bedNow = state.step?.kind == StepKind.Drawdown && state.bedExposedSec == null && !state.done
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (bedNow) {
            PillButton(text = "Bed exposed", onClick = m.onBed, height = 48.dp, onCanvas = true)
            Spacer(Modifier.width(8.dp))
        }
        FinishPill(done = state.done, onClick = m.onFinish, modifier = Modifier.weight(1f))
    }
}

/** Short layout's Finish: a 48dp pill under the transport row. */
@Composable
private fun FinishPill(done: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(if (done) colors.crema else colors.surface1)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (done) "Rate this brew" else "Finish",
            style = MaterialTheme.typography.labelLarge,
            color = if (done) colors.onCrema else colors.inkHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val ctaBg = animateColorAsState(if (done) colors.crema else colors.surface1, Motion.heavy(), label = "ctaBg")
    val ctaFg = animateColorAsState(if (done) colors.onCrema else colors.inkHigh, Motion.heavy(), label = "ctaFg")
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
 * The one plain-language instruction, derived from state and the anchor only. The current step's
 * countdown sits beside it in its own leaf, so StatusLine itself never ticks.
 */
@Composable
private fun BrewStatus(state: BrewUiState, anchor: TimerAnchor, elapsed: LongState, modifier: Modifier = Modifier) {
    val step = state.step
    val pausedAt = clock((anchor.accumulatedMs / 1000L).toInt())
    Box(modifier) {
        when {
            state.phase == BrewPhase.Finishing -> StatusLine(status = Status.Neutral, lead = "Saving", detail = "at $pausedAt")
            state.done -> StatusLine(status = Status.Good, lead = "Done", detail = "rate this brew")
            !state.running -> StatusLine(status = Status.Attention, lead = "Paused", detail = "at $pausedAt")
            // Auto-advance off: the clock has passed the step and the card waits for Next.
            state.waitingForTap -> StatusLine(status = Status.Attention, lead = "Time for the next step", detail = "tap Next")
            step == null -> StatusLine(status = Status.Live, lead = "Brewing", live = true)
            else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusLine(status = Status.Live, lead = stepLead(step, state.settings.hasScale), modifier = Modifier.weight(1f, fill = false), live = true)
                StepCountdown(elapsed = elapsed, endSec = step.endSec)
            }
        }
    }
}

/** An inverse circle whose icon swaps with a little bounce. IconWell has no animated swap. */
@Composable
internal fun PlayPauseButton(running: Boolean, onClick: () -> Unit, size: Dp = 64.dp) {
    val colors = CoffeeTheme.colors
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(size)
            .pressSquash(src, 0.92f)
            .clip(CircleShape)
            .background(colors.inverse)
            .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (running) "Pause" else "Resume" },
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
                contentDescription = null,
                tint = colors.onInverse,
                modifier = Modifier.size(size * 0.47f),
            )
        }
    }
}

// ------------------------------------------------------------------ timer leaves
// Each is a plain Text on derivedStateOf, so it recomposes at most once a second (the water
// readout once a gram) and nothing above it recomposes with the clock.

/** The numerals. A plain Text that changes once a second and snaps on a seek. */
@Composable
internal fun TimerClock(elapsed: LongState, style: TextStyle) {
    val sec by remember { derivedStateOf { (elapsed.longValue / 1000L).toInt() } }
    Text(clock(sec), style = style, color = CoffeeTheme.colors.inkHigh, maxLines = 1, softWrap = false)
}

/**
 * Grams so far against this step's target (the total when the step has none), or "about 60 ml /
 * 500" without a scale. It sits on a quiet plate, because the dial's water rises through this line.
 */
@Composable
private fun WaterReadout(elapsed: LongState, recipe: Recipe, target: Int, hasScale: Boolean, style: TextStyle, pourAll: Boolean) {
    val colors = CoffeeTheme.colors
    val w by remember(recipe) { derivedStateOf { recipe.waterAt(elapsed.longValue) } }
    Row(
        Modifier
            .clip(PillShape)
            .background(colors.surface1.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        when {
            pourAll -> Text("Pour all $target ml", style = MaterialTheme.typography.titleMedium, color = colors.inkHigh, maxLines = 1)
            hasScale -> {
                Text("$w g", modifier = Modifier.alignByBaseline(), style = style, color = colors.inkHigh, maxLines = 1)
                Text(" / $target", modifier = Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium, color = colors.inkLow, maxLines = 1)
            }
            else -> {
                Text("about ", modifier = Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium, color = colors.inkMid, maxLines = 1)
                Text("$w ml", modifier = Modifier.alignByBaseline(), style = style, color = colors.inkHigh, maxLines = 1)
                Text(" / $target", modifier = Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium, color = colors.inkLow, maxLines = 1)
            }
        }
    }
}

/** " · 0:12 left" for the current step, matching the status line's detail style. */
@Composable
private fun StepCountdown(elapsed: LongState, endSec: Int) {
    val left by remember(endSec) { derivedStateOf { (endSec - (elapsed.longValue / 1000L).toInt()).coerceAtLeast(0) } }
    Text(" · ${clock(left)} left", style = MaterialTheme.typography.bodyMedium, color = CoffeeTheme.colors.inkMid, maxLines = 1)
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
