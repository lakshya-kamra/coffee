@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.laksh.coffee.ui.screens.brew

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.core.counted
import com.laksh.coffee.core.grams
import com.laksh.coffee.core.ratio
import com.laksh.coffee.core.ratioPhrase
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.BrewerArt
import com.laksh.coffee.domain.Glossary
import com.laksh.coffee.domain.MethodLabels
import com.laksh.coffee.domain.NoScale
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.Temperatures
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.ui.components.BrewerIcon
import com.laksh.coffee.ui.components.CoffeeMug
import com.laksh.coffee.ui.components.DialReadingInput
import com.laksh.coffee.ui.components.Dripper
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.GrindStartHint
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.PillButton
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.RadioRow
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.StickyCtaBar
import com.laksh.coffee.ui.components.Term
import com.laksh.coffee.ui.components.TickingNumber
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.components.roastStatus
import com.laksh.coffee.ui.components.roastWord
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.nav.SharedKeys
import com.laksh.coffee.ui.nav.sharedBoundsModifier
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Eyebrow
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.PillShape
import java.time.LocalDate
import kotlin.math.roundToInt

/** Brewers that actually look like a cone with a bed in it. */
// Drip and Siphon drew as V60 and Chemex before v4, so they keep the dripper.
private val POUR_OVER = setOf(BrewerArt.V60, BrewerArt.Chemex, BrewerArt.Kalita, BrewerArt.Switch, BrewerArt.Drip, BrewerArt.Siphon)

/** The setup illustration. Small enough that the grind number stays the hero of the screen. */
private val HERO_ART = 104.dp

/** Upper-cases a label unless it holds a micro sign, which Kotlin would turn into a Greek capital Mu. */
internal fun String.labelCase(): String = if ('µ' in this) this else uppercase()

/** The most clicks setup's steppers reach (three digits), the same ceiling the view model holds. */
private const val SETUP_MAX_CLICKS = 999.0

/**
 * Brew setup (v4 §3.4). One column on phones; on wide windows, wide landscape and phone landscape
 * two columns: the recipe and the dose on the left, everything you set on the right, with Start
 * at the foot of the right column.
 */
@Composable
internal fun SetupPane(
    state: BrewUiState,
    onExit: () -> Unit,
    vm: BrewViewModel,
    heroScope: AnimatedVisibilityScope?,
    tick: () -> Unit,
) {
    val recipe = state.recipe ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val two = BrewLayouts.setupTwoColumns(maxWidth.value, maxHeight.value)
        val short = maxHeight < 480.dp
        if (two) {
            SetupTwoColumns(state, recipe, onExit, vm, heroScope, tick, short)
        } else {
            SetupOneColumn(state, recipe, onExit, vm, heroScope, tick)
        }
    }
}

@Composable
private fun SetupOneColumn(
    state: BrewUiState,
    recipe: Recipe,
    onExit: () -> Unit,
    vm: BrewViewModel,
    heroScope: AnimatedVisibilityScope?,
    tick: () -> Unit,
) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        // The status-bar inset sits outside the scroll (as on Review), so the cards clip at the
        // bar's edge instead of sliding under the clock.
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SetupHeader(recipe, onExit)
            SetupArt(recipe, heroScope, HERO_ART)
            SetupTitle(recipe)
            Spacer(Modifier.height(20.dp))
            DoseCard(state, recipe, vm, tick)
            SetupDetails(state, recipe, vm, tick)
            // Lets the last card scroll fully clear of the sticky Start bar and its fade.
            Spacer(Modifier.height(120.dp + navInset))
        }

        StickyCtaBar(Modifier.align(Alignment.BottomCenter)) {
            StartButton(state, vm, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SetupTwoColumns(
    state: BrewUiState,
    recipe: Recipe,
    onExit: () -> Unit,
    vm: BrewViewModel,
    heroScope: AnimatedVisibilityScope?,
    tick: () -> Unit,
    short: Boolean,
) {
    val gutter = if (short) 16.dp else 24.dp
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .widthIn(max = 1200.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(gutter),
        ) {
            Column(
                Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
            ) {
                SetupHeader(recipe, onExit)
                // Short windows keep the art out: the dose is what the left column is for.
                if (!short) SetupArt(recipe, heroScope, 140.dp)
                SetupTitle(recipe)
                Spacer(Modifier.height(16.dp))
                DoseCard(state, recipe, vm, tick)
                Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(if (short) 4.dp else 44.dp))
                    SetupDetails(state, recipe, vm, tick, firstGap = 0.dp)
                    Spacer(Modifier.height(16.dp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                    StartButton(state, vm, Modifier.widthIn(max = 420.dp).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun StartButton(state: BrewUiState, vm: BrewViewModel, modifier: Modifier) {
    val colors = CoffeeTheme.colors
    // Ink, not crema: nothing is live yet. Crema arrives with the running dial, so the accent
    // keeps meaning "the brew in progress".
    PrimaryButton(
        text = if (state.isEspresso) "Ready for the shot" else "Start brew",
        onClick = vm::start,
        modifier = modifier,
        icon = Icons.Rounded.PlayArrow,
        enabled = state.readyToBrew,
        containerColor = if (state.readyToBrew) colors.inverse else colors.surface2,
        contentColor = if (state.readyToBrew) colors.onInverse else colors.inkLow,
    )
}

@Composable
private fun SetupHeader(recipe: Recipe, onExit: () -> Unit) {
    val colors = CoffeeTheme.colors
    // A Box, not a weighted Row: the eyebrow centres on the screen, not in whatever room the
    // close well and the wider brewer chip leave between them.
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        IconWell(
            icon = Icons.Rounded.Close,
            contentDescription = "Close",
            onClick = onExit,
            modifier = Modifier.align(Alignment.CenterStart),
            shortcut = "Esc",
        )
        EyebrowLabel("SET UP", Modifier.align(Alignment.Center).asHeading())
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .heightIn(min = 28.dp)
                .clip(PillShape)
                .background(colors.surface2)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrewerIcon(recipe.brewer, size = 14.dp, tint = colors.inkMid)
            Spacer(Modifier.width(6.dp))
            Text(recipe.brewer.label.labelCase(), style = Micro, color = colors.inkMid, maxLines = 1)
        }
    }
}

@Composable
private fun SetupArt(recipe: Recipe, heroScope: AnimatedVisibilityScope?, size: Dp) {
    val colors = CoffeeTheme.colors
    // A little ambient hardware so setup is not just a form. It is also the brew hero: this box
    // morphs into the dial when the brew starts.
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
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (recipe.brewer.art in POUR_OVER) {
                Dripper(waterLevel = 0.55f, shell = colors.artShell, water = colors.artWater, bed = colors.artBed)
            } else {
                CoffeeMug(fill = 0.65f, body = colors.artShell, liquid = colors.artBed, crema = colors.artWater, showSteam = true)
            }
        }
    }
}

@Composable
private fun SetupTitle(recipe: Recipe) {
    val colors = CoffeeTheme.colors
    val type = MaterialTheme.typography
    Spacer(Modifier.height(8.dp))
    Text(
        recipe.name,
        modifier = Modifier.fillMaxWidth().asHeading(),
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
}

/** Everything set on the right: grind, temperature, coffee, the prep list and the chime question. */
@Composable
private fun ColumnScope.SetupDetails(
    state: BrewUiState,
    recipe: Recipe,
    vm: BrewViewModel,
    tick: () -> Unit,
    firstGap: Dp = 12.dp,
) {
    val colors = CoffeeTheme.colors
    val type = MaterialTheme.typography
    val units = LocalUnits.current
    Spacer(Modifier.height(firstGap))
    GrindCard(state = state, recipe = recipe, vm = vm, tick = tick)
    if (recipe.brewer.method != BrewMethod.ColdBrew && recipe.brewer.method != BrewMethod.Turkish) {
        Spacer(Modifier.height(12.dp))
        TempCard(state = state, vm = vm, tick = tick)
    }
    Spacer(Modifier.height(12.dp))
    CoffeeCard(state = state, vm = vm)

    val tempUnit = if (units.fahrenheit) "f" else "c"
    val prep = remember(recipe, state.settings.hasScale, tempUnit, state.selectedBean?.form, state.grindChoice) {
        if (state.settings.hasScale) {
            recipe.prep.map { Temperatures.render(it, tempUnit) }
        } else {
            // Beans measured by the spoon before a blade grinder read "of beans".
            val form = if (state.grindChoice == GrindChoice.Blade) "whole" else ""
            NoScale.prep(recipe, tempUnit, form)
        }
    }
    if (prep.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            EyebrowLabel("BEFORE YOU START", Modifier.asHeading())
            Spacer(Modifier.height(8.dp))
            prep.forEachIndexed { i, line ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Text("${i + 1}", modifier = Modifier.width(24.dp), style = Num.Inline, color = colors.inkLow)
                    Text(line, style = type.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp), color = colors.inkHigh)
                }
            }
        }
    }

    if (asksChime(recipe, state.settings.soundCuesAsked)) {
        Spacer(Modifier.height(12.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Text(
                "Chime when it's time for the next step?",
                style = type.titleMedium,
                color = colors.inkHigh,
                modifier = Modifier.asHeading(),
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(text = "Yes, chime", onClick = { vm.answerChime(true) }, height = 44.dp)
                PillButton(text = "No sound", onClick = { vm.answerChime(false) }, height = 44.dp)
            }
        }
    }
}

@Composable
private fun DoseCard(state: BrewUiState, recipe: Recipe, vm: BrewViewModel, tick: () -> Unit) {
    val colors = CoffeeTheme.colors
    val type = MaterialTheme.typography
    val method = recipe.brewer.method
    val labels = MethodLabels.of(method)
    val hasScale = state.settings.hasScale
    val amounts = remember(state.doseG, recipe.waterG, method, state.grindChoice) {
        NoScale.amounts(state.doseG, recipe.waterG, method, if (state.grindChoice == GrindChoice.Blade) "whole" else "")
    }
    SoftCard(Modifier.fillMaxWidth()) {
        EyebrowLabel("DOSE", Modifier.asHeading())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(
                icon = Icons.Rounded.Remove,
                contentDescription = "Less",
                onClick = {
                    tick()
                    vm.setDose((state.doseG - 0.5).coerceAtLeast(1.0))
                },
                size = 48.dp,
                onCanvas = false,
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (hasScale || amounts.coffeeTbsp == null) {
                    // Large, not Display: the grind below is the one hero on this screen.
                    TickingNumber(grams(state.doseG), style = Num.Large, unit = "g")
                } else {
                    // Spoons lead; the grams stay, small, because they are what is stored.
                    Text(amounts.coffee, style = type.titleLarge, color = colors.inkHigh, textAlign = TextAlign.Center)
                    Text("(${grams(state.doseG)} g)", style = type.bodySmall, color = colors.inkLow)
                }
                val water = if (hasScale) "${grams(recipe.waterG)} g" else amounts.water
                val waterWord = if (method == BrewMethod.Espresso) "out" else "water"
                Text(
                    "→ $water $waterWord · ${ratio(state.doseG, recipe.waterG)}",
                    style = type.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
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
                size = 48.dp,
                onCanvas = false,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Term(key = "ratio", text = "RATIO", style = Eyebrow, color = colors.inkLow)
            Spacer(Modifier.width(8.dp))
            Text(
                ratioPhrase(state.doseG, recipe.waterG, method.id),
                style = type.bodySmall,
                color = colors.inkMid,
            )
        }
        if (!hasScale && amounts.basket != null) {
            Spacer(Modifier.height(6.dp))
            Text(amounts.basket, style = type.bodySmall, color = colors.inkMid)
        }
        if (labels.waterNote.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("${labels.water.lowercase().replaceFirstChar { it.uppercase() }}: ${labels.waterNote}", style = type.bodySmall, color = colors.inkLow)
        }

        // Mugs make no sense for a shot or a moka pot's fixed basket.
        if (method != BrewMethod.Espresso && method != BrewMethod.Moka) {
            Spacer(Modifier.height(12.dp))
            EyebrowLabel("MAKE MORE OR LESS")
            Spacer(Modifier.height(8.dp))
            FlowRow(
                Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (m in 1..3) {
                    SelectableChip(
                        text = if (m == 1) "1 mug" else "$m mugs",
                        selected = state.mugs == m,
                        onClick = {
                            tick()
                            vm.setServings(m)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GrindCard(state: BrewUiState, recipe: Recipe, vm: BrewViewModel, tick: () -> Unit) {
    val colors = CoffeeTheme.colors
    val type = MaterialTheme.typography
    val grinder = state.selectedGrinder
    val method = recipe.brewer.method
    val choice = state.grindChoice
    // Built once per grinder row: it reads the catalog entry the row is linked to.
    val scale = remember(grinder) { grinder?.scale() }
    // Only a click grinder whose dial has words of its own (turns, a numbered ring) gets the
    // choice. The steppers get the same room as the click dial above them, so a grind already
    // past the range the row was saved with is not pulled back into it by the first tap.
    val dialScale = remember(scale) {
        scale?.takeIf { it.usesClicks && it.dial != DialFormat.Plain }
            ?.let { it.copy(max = maxOf(it.max, SETUP_MAX_CLICKS)) }
    }
    var asDial by rememberSaveable { mutableStateOf(false) }
    SoftCard(Modifier.fillMaxWidth()) {
        Term(key = "grind", text = "GRIND", style = Eyebrow, color = colors.inkLow, modifier = Modifier.asHeading())
        Spacer(Modifier.height(10.dp))
        // Pre-ground first (v4 §4.2), then your grinders. Wraps instead of scrolling sideways.
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectableChip(text = "Pre-ground (as bought)", selected = choice == GrindChoice.Preground, onClick = vm::setPreground)
            state.grinders.forEach { g ->
                key(g.id) {
                    SelectableChip(
                        text = g.display,
                        selected = choice != GrindChoice.Preground && g.id == state.selectedGrinderId,
                        onClick = { vm.setGrinder(g.id) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            choice == GrindChoice.Preground -> {
                StatusLine(Status.Neutral, "As bought", detail = "nothing to set")
                state.selectedBean?.groundFor?.takeIf { it.isNotBlank() }?.let { g ->
                    Spacer(Modifier.height(4.dp))
                    Text("Ground for ${groundForWord(g)}.", style = type.bodySmall, color = colors.inkMid)
                }
            }
            choice == GrindChoice.Blade -> BladeStepper(state.bladeSec, onAdjust = { d ->
                tick()
                vm.adjustBlade(d)
            })
            grinder != null && grinder.usesClicks -> {
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
                    ClickDial(clicks = state.grindClicks, grinder = grinder, onAdjust = { delta ->
                        tick()
                        vm.adjustClicks(delta)
                    })
                }
            }
            else -> {
                if (state.grinders.isEmpty()) {
                    Text("No grinders yet. Type a setting, or add one under Gear.", style = type.bodySmall, color = colors.inkMid)
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
        }

        // Where the catalog's sourced range for this grinder and method sits, one tap to take it.
        if (scale != null && choice == GrindChoice.Dial) {
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

        if (dialScale != null && choice == GrindChoice.Dial) {
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

        state.lastLogForSelection?.takeIf { choice != GrindChoice.Preground }?.let { l ->
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
        val reference = Glossary.grindReference(recipe.grind)
        Text(
            "Recipe says: ${recipe.grind}" + (reference?.let { " · $it" } ?: ""),
            style = type.bodySmall,
            color = colors.inkLow,
        )
    }
}

private fun groundForWord(g: String): String = when (g) {
    "press" -> "a French press"
    "espresso" -> "espresso"
    "moka" -> "a moka pot"
    else -> "filter"
}

/** A blade grinder: seconds, not a dial (v4 §4.2). */
@Composable
private fun BladeStepper(sec: Int, onAdjust: (Int) -> Unit) {
    val colors = CoffeeTheme.colors
    Column(Modifier.fillMaxWidth()) {
        EyebrowLabel("GRIND FOR")
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(icon = Icons.Rounded.Remove, contentDescription = "One second less", onClick = { onAdjust(-1) }, size = 48.dp, onCanvas = false)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TickingNumber("$sec", style = Num.Display, unit = "s")
            }
            IconWell(icon = Icons.Rounded.Add, contentDescription = "One second more", onClick = { onAdjust(1) }, size = 48.dp, onCanvas = false)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Pulse in short bursts and shake the grinder between them.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMid,
        )
    }
}

/**
 * Grind position for click grinders, as a number you can nudge with your thumb mid-setup.
 * Stored as an integer so a report can say "go three clicks finer" and mean something.
 */
@Composable
private fun ClickDial(clicks: Int?, grinder: GrinderEntity, onAdjust: (Int) -> Unit) {
    val colors = CoffeeTheme.colors
    val downWord = if (grinder.lowerIsFiner) "finer" else "coarser"
    val upWord = if (grinder.lowerIsFiner) "coarser" else "finer"

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconWell(icon = Icons.Rounded.Remove, contentDescription = "One click $downWord", onClick = { onAdjust(-1) }, size = 48.dp, onCanvas = false)
                Spacer(Modifier.height(4.dp))
                Text(downWord.uppercase(), style = Micro, color = colors.inkLow)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                // An unset grind is a dash in inkLow, never 0: 0 is burr contact.
                TickingNumber(
                    clicks?.toString() ?: "—",
                    style = Num.Display,
                    color = if (clicks == null) colors.inkLow else colors.inkHigh,
                    unit = clicks?.let { unitWord(it, "click") },
                )
                Text(
                    if (clicks == null) "Set your grind" else grinder.scale().describe(clicks.toDouble()),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMid,
                    textAlign = TextAlign.Center,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconWell(icon = Icons.Rounded.Add, contentDescription = "One click $upWord", onClick = { onAdjust(1) }, size = 48.dp, onCanvas = false)
                Spacer(Modifier.height(4.dp))
                Text(upWord.uppercase(), style = Micro, color = colors.inkLow)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (delta in intArrayOf(-5, -1, 1, 5)) {
                PillButton(
                    text = if (delta < 0) "−${-delta}" else "+$delta",
                    onClick = { onAdjust(delta) },
                    modifier = Modifier.weight(1f),
                    height = 44.dp,
                )
            }
        }
    }
}

@Composable
private fun TempCard(state: BrewUiState, vm: BrewViewModel, tick: () -> Unit) {
    // Stored and stepped in °C; °F is how it is shown.
    val units = LocalUnits.current
    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                EyebrowLabel("WATER TEMP", Modifier.asHeading())
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
                size = 48.dp,
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
                size = 48.dp,
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
        EyebrowLabel("COFFEE", Modifier.asHeading())
        Spacer(Modifier.height(8.dp))
        if (state.beans.isEmpty()) {
            Text(
                "No beans yet. Add one under Gear.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.inkMid,
            )
        } else {
            Column(Modifier.selectableGroup()) {
                state.beans.forEach { bean ->
                    key(bean.id) {
                        val days = bean.roastedOnEpochDay?.let { (today - it).coerceAtLeast(0L) }
                        RadioRow(
                            title = bean.name,
                            selected = bean.id == state.selectedBeanId,
                            onClick = { vm.setBean(bean.id) },
                            subtitle = listOfNotNull(
                                bean.roaster.trim().ifEmpty { null },
                                if (bean.form == "ground") "ground" else null,
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
}
