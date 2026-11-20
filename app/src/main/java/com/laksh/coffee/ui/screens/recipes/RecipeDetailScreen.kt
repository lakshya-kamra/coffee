package com.laksh.coffee.ui.screens.recipes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.counted
import com.laksh.coffee.core.formatDuration
import com.laksh.coffee.core.grams
import com.laksh.coffee.core.ratioPhrase
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.FirstBrew
import com.laksh.coffee.domain.FirstBrewSettings
import com.laksh.coffee.domain.Glossary
import com.laksh.coffee.domain.MethodLabels
import com.laksh.coffee.domain.NoScale
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.RecipeNeeds
import com.laksh.coffee.domain.Temperatures
import com.laksh.coffee.domain.grind.GrindScale
import com.laksh.coffee.ui.components.BrewerIcon
import com.laksh.coffee.ui.components.Cell
import com.laksh.coffee.ui.components.DataGrid
import com.laksh.coffee.ui.components.DetailTopBar
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.GrindRangeLine
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.RouteMode
import com.laksh.coffee.ui.components.RouteRow
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.StickyCtaBar
import com.laksh.coffee.ui.components.Term
import com.laksh.coffee.ui.components.TimelineCard
import com.laksh.coffee.ui.components.TumblingBeans
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.components.plannedTimelineRows
import com.laksh.coffee.ui.components.pourNodes
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.nav.LocalPane
import com.laksh.coffee.ui.nav.SharedKeys
import com.laksh.coffee.ui.nav.sharedBoundsModifier
import com.laksh.coffee.ui.theme.CardShape
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Eyebrow
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num

@Composable
fun RecipeDetailScreen(
    recipeId: String,
    sharedKeyPrefix: String = SharedKeys.FROM_HOME,
    onBack: () -> Unit,
    onBrew: (String) -> Unit,
    /** Open the recipe builder on this recipe, or on a copy of it. */
    onEdit: (String) -> Unit = {},
    onDuplicate: (String) -> Unit = {},
    vm: RecipeDetailViewModel = viewModel(factory = RecipeDetailViewModel.factory(recipeId)),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Keyed on id so a saved override updates the page in place; only null -> loaded animates.
    AnimatedContent(
        targetState = state.recipe,
        modifier = Modifier.fillMaxSize(),
        contentKey = { it?.id },
        transitionSpec = { fadeIn(Motion.decel(220)) togetherWith fadeOut(Motion.decel(160)) },
        label = "recipeLoad",
    ) { r ->
        if (r == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { TumblingBeans(color = CoffeeTheme.colors.inkLow) }
        } else {
            RecipeDetailContent(
                recipe = r,
                state = state,
                sharedKeyPrefix = sharedKeyPrefix,
                onBack = onBack,
                onBrew = onBrew,
                onEdit = onEdit,
                onDuplicate = onDuplicate,
                onReset = vm::resetToOriginal,
            )
        }
    }
}

/** The recipe as this person reads it: temperatures in their unit, spoons without a scale. */
private class Shown(val recipe: Recipe, val prep: List<String>, val tempUnit: String)

private fun shown(recipe: Recipe, hasScale: Boolean, tempUnit: String): Shown {
    val r = recipe.copy(
        blurb = Temperatures.render(recipe.blurb, tempUnit),
        tips = recipe.tips.map { Temperatures.render(it, tempUnit) },
        steps = recipe.steps.map { it.copy(detail = Temperatures.render(it.detail, tempUnit)) },
    )
    val prep = if (hasScale) recipe.prep.map { Temperatures.render(it, tempUnit) } else NoScale.prep(recipe, tempUnit)
    return Shown(r, prep, tempUnit)
}

/** Two columns once the page (or its pane) is at least this wide (§3.3). */
private val TWO_COLUMNS = 880.dp

@Composable
private fun RecipeDetailContent(
    recipe: Recipe,
    state: RecipeDetailState,
    sharedKeyPrefix: String,
    onBack: () -> Unit,
    onBrew: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onReset: () -> Unit,
) {
    val s = remember(recipe, state.hasScale, state.tempUnit) { shown(recipe, state.hasScale, state.tempUnit) }
    val pane = LocalPane.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TWO_COLUMNS) {
            TwoColumns(s, state, sharedKeyPrefix, onBack, onBrew, onEdit, onDuplicate, onReset)
        } else {
            OneColumn(s, state, sharedKeyPrefix, onBack, onBrew, onEdit, onDuplicate, onReset, inPane = pane.inPane)
        }
    }
}

@Composable
private fun TopBar(recipe: Recipe, onBack: () -> Unit, onEdit: (String) -> Unit, onDuplicate: (String) -> Unit, onBrew: ((String) -> Unit)? = null) {
    val c = CoffeeTheme.colors
    // Words, not bare icons: these are the two ways a recipe becomes yours.
    DetailTopBar(onBack = onBack) {
        QuietButton(text = "Duplicate", onClick = { onDuplicate(recipe.id) }, icon = Icons.Rounded.ContentCopy)
        QuietButton(text = "Edit", onClick = { onEdit(recipe.id) }, icon = Icons.Rounded.Edit)
        if (onBrew != null) {
            Spacer(Modifier.width(8.dp))
            PrimaryButton(
                text = "Brew this",
                onClick = { onBrew(recipe.id) },
                modifier = Modifier.widthIn(max = 360.dp),
                icon = Icons.Rounded.PlayArrow,
                height = 48.dp,
                containerColor = c.inverse,
                contentColor = c.onInverse,
            )
        }
    }
}

/** Phones, medium windows and the pane at expanded: one column, capped at 720. */
@Composable
private fun OneColumn(
    s: Shown,
    state: RecipeDetailState,
    sharedKeyPrefix: String,
    onBack: () -> Unit,
    onBrew: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onReset: () -> Unit,
    inPane: Boolean,
) {
    val c = CoffeeTheme.colors
    val recipe = s.recipe
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            // In a pane "Brew this" sits in the header, so nothing is pinned to the window.
            TopBar(recipe, onBack, onEdit, onDuplicate, onBrew = if (inPane) onBrew else null)
            Column(Modifier.padding(horizontal = 16.dp)) {
                HeaderCard(recipe, sharedKeyPrefix, scroll)
                BrewedLine(state)
                Spacer(Modifier.height(12.dp))
                NumbersCard(recipe, state)
                NeedsBlock(recipe, state)
                Spacer(Modifier.height(16.dp))
                Text(recipe.blurb, style = MaterialTheme.typography.bodyMedium, color = c.inkMid)
                YourGrinder(recipe, state.grindScale, state.grinderName)
                StepsBlock(recipe)
                PrepBlock(s.prep, state.hasScale)
                TipsBlock(recipe)
                SourceBlock(recipe, state.isOverride, onReset)
            }
            // Room for the sticky CTA and its fade, so the source note scrolls fully clear.
            Spacer(Modifier.navigationBarsPadding().height(if (inPane) 32.dp else 120.dp))
        }

        if (!inPane) {
            // Inverse, not crema: crema is kept for things that are live, and nothing is yet.
            StickyCtaBar(Modifier.align(Alignment.BottomCenter).widthIn(max = 720.dp)) {
                PrimaryButton(
                    text = "Brew this",
                    onClick = { onBrew(recipe.id) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.PlayArrow,
                    containerColor = c.inverse,
                    contentColor = c.onInverse,
                )
            }
        }
    }
}

/**
 * Large panes (880 and up): the numbers and what you need on a 340 left column that stays put,
 * the method on the right (§3.3).
 */
@Composable
private fun TwoColumns(
    s: Shown,
    state: RecipeDetailState,
    sharedKeyPrefix: String,
    onBack: () -> Unit,
    onBrew: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onReset: () -> Unit,
) {
    val c = CoffeeTheme.colors
    val recipe = s.recipe
    val right = rememberScrollState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1080.dp).fillMaxSize()) {
            TopBar(recipe, onBack, onEdit, onDuplicate)
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(
                    Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    HeaderCard(recipe, sharedKeyPrefix, null)
                    Spacer(Modifier.height(12.dp))
                    NumbersCard(recipe, state)
                    BrewedLine(state)
                    NeedsBlock(recipe, state)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        text = "Brew this",
                        onClick = { onBrew(recipe.id) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.PlayArrow,
                        containerColor = c.inverse,
                        contentColor = c.onInverse,
                    )
                    Spacer(Modifier.navigationBarsPadding().height(24.dp))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(right),
                ) {
                    Text(recipe.blurb, style = MaterialTheme.typography.bodyLarge, color = c.inkMid, modifier = Modifier.widthIn(max = 640.dp))
                    StepsBlock(recipe)
                    PrepBlock(s.prep, state.hasScale)
                    TipsBlock(recipe)
                    YourGrinder(recipe, state.grindScale, state.grinderName)
                    SourceBlock(recipe, state.isOverride, onReset)
                    Spacer(Modifier.navigationBarsPadding().height(32.dp))
                }
            }
        }
    }
}

/**
 * The recipe's name and route. The outer box owns the shared bounds and the clip, so the parallax
 * layer inside it can never bleed under the body below. [scroll] drives that parallax; null keeps
 * the card still (the two-column page does not scroll it away).
 */
@Composable
private fun HeaderCard(recipe: Recipe, sharedKeyPrefix: String, scroll: ScrollState?) {
    val c = CoffeeTheme.colors
    val nodes = remember(recipe) { recipe.pourNodes() }
    val espresso = recipe.brewer.method == BrewMethod.Espresso
    Box(
        Modifier
            .then(sharedBoundsModifier(key = SharedKeys.recipeHeader(sharedKeyPrefix, recipe.id), clip = CardShape))
            .fillMaxWidth()
            .clip(CardShape)
            .background(c.surface1),
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    // Read the scroll here so scrolling redraws a layer, never recomposes.
                    if (scroll != null) {
                        val s = scroll.value.toFloat()
                        translationY = s * 0.4f
                        alpha = 1f - (s / size.height).coerceIn(0f, 0.8f)
                    }
                }
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The one place a recipe's own colour survives: an identity dot.
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(recipe.accent)))
                Spacer(Modifier.width(6.dp))
                Text(recipe.author.uppercase(), style = Micro, color = c.inkLow, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                BrewerIcon(recipe.brewer, size = 14.dp, tint = c.inkLow)
                Spacer(Modifier.width(4.dp))
                Text(recipe.brewer.label.uppercase(), style = Micro, color = c.inkLow, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            Text(recipe.name, style = MaterialTheme.typography.headlineLarge, color = c.inkHigh, modifier = Modifier.asHeading())
            Spacer(Modifier.height(16.dp))
            RouteRow(
                from = grams(recipe.doseG),
                fromUnit = "g",
                fromLabel = "COFFEE",
                to = grams(recipe.waterG),
                toUnit = "g",
                toLabel = if (espresso) "YIELD" else "WATER",
                nodes = nodes,
                midLabel = recipe.ratioLabel,
                style = Num.Large,
                mode = RouteMode.Planned,
            )
        }
    }
}

@Composable
private fun BrewedLine(state: RecipeDetailState) {
    val brewedCount = state.brewedCount
    if (brewedCount <= 0) return
    val rated = state.bestLog?.takeIf { it.overall > 0 }
    Spacer(Modifier.height(12.dp))
    // The coloured words are the verdict that earns the colour: the best score leads, and the
    // count is detail. A 0-click log is a logging slip, never a grind worth repeating.
    StatusLine(
        status = scoreStatus(rated?.overall ?: 0),
        lead = rated?.let { "Best ${it.overall}/10" } ?: "Brewed $brewedCount×",
        detail = rated?.let { b -> "brewed $brewedCount×" + bestGrind(b) } ?: "not rated yet",
    )
}

private fun bestGrind(b: BrewLogEntity): String =
    b.grindClicks?.takeIf { it > 0 }?.let { n -> " · at ${counted(n, "click")}" }
        ?: b.grindSetting.trim().takeIf { it.isNotEmpty() }?.let { " · at $it" }.orEmpty()

/** Temperature, time, the ratio in words, and the grind with something everyone has seen. */
@Composable
private fun NumbersCard(recipe: Recipe, state: RecipeDetailState) {
    val c = CoffeeTheme.colors
    val units = LocalUnits.current
    val labels = MethodLabels.of(recipe.brewer.method)
    val type = MaterialTheme.typography
    SoftCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
        DataGrid(
            cells = listOf(
                Cell("TEMP", units.temp(recipe.tempC), units.tempUnit),
                Cell(labels.time, formatDuration(recipe.totalSec)),
            ),
            columns = 2,
        )
        HairLine()
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Term(key = "ratio", text = "RATIO", style = Eyebrow, color = c.inkLow)
                Spacer(Modifier.width(8.dp))
                Text(recipe.ratioLabel, style = type.titleMedium, color = c.inkHigh)
            }
            Text(ratioPhrase(recipe.doseG, recipe.waterG, recipe.brewer.method.id), style = type.bodySmall, color = c.inkMid)
        }
        if (!state.hasScale) {
            val a = remember(recipe) { NoScale.amounts(recipe.doseG, recipe.waterG, recipe.brewer.method) }
            HairLine()
            Column(Modifier.padding(vertical = 12.dp)) {
                EyebrowLabel("WITHOUT A SCALE")
                Spacer(Modifier.height(4.dp))
                Text(a.coffeeLine, style = type.bodyMedium, color = c.inkHigh)
                if (recipe.brewer.method != BrewMethod.Espresso) Text("${a.water} of water", style = type.bodyMedium, color = c.inkHigh)
                Text("(${grams(recipe.doseG)} g and ${grams(recipe.waterG)} g)", style = type.bodySmall, color = c.inkLow)
            }
        }
        // The grind in full, on lines of its own: many are a sentence, and the reference after it
        // ("like table salt") is what someone new can act on. It wraps; it never cuts off.
        HairLine()
        Column(Modifier.padding(vertical = 12.dp)) {
            Term(key = "grind", text = "GRIND", style = Eyebrow, color = c.inkLow)
            Spacer(Modifier.height(4.dp))
            Text(recipe.grind.ifBlank { "—" }, style = type.bodyMedium, color = c.inkHigh)
            Glossary.grindReference(recipe.grind)?.let { ref ->
                Text(ref.replaceFirstChar { it.uppercase() }, style = type.bodySmall, color = c.inkMid)
            }
        }
    }
}

/** "What you'll need": the kit this recipe depends on, with what you are missing said plainly. */
@Composable
private fun NeedsBlock(recipe: Recipe, state: RecipeDetailState) {
    if (recipe.needs.isEmpty()) return
    val c = CoffeeTheme.colors
    val unmet = remember(recipe, state.hasScale, state.buysGround, state.experience, state.grinderCount) {
        FirstBrew.unmetNeeds(recipe.needs, FirstBrewSettings(emptySet(), state.hasScale, state.buysGround, state.experience), state.grinderCount).toSet()
    }
    SectionLabel("WHAT YOU'LL NEED")
    SoftCard(Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recipe.needs.forEach { n ->
                Box(
                    Modifier
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.surface2)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(needWord(n), style = MaterialTheme.typography.labelLarge, color = c.inkHigh)
                }
            }
        }
        if (unmet.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            unmet.forEach { n ->
                StatusLine(status = Status.Neutral, lead = "No ${needWord(n).lowercase()}?", detail = needWorkaround(n))
            }
        }
    }
}

private fun needWord(n: String): String = when (n) {
    RecipeNeeds.SCALE -> "Kitchen scale"
    RecipeNeeds.GOOSENECK -> "Gooseneck kettle"
    RecipeNeeds.GRINDER -> "Grinder"
    RecipeNeeds.THERMOMETER -> "Thermometer"
    else -> n.replaceFirstChar { it.uppercase() }
}

private fun needWorkaround(n: String): String = when (n) {
    RecipeNeeds.SCALE -> "amounts show in spoons and ml"
    RecipeNeeds.GOOSENECK -> "a normal kettle works if you pour gently"
    RecipeNeeds.GRINDER -> "buy it ground for this brewer"
    RecipeNeeds.THERMOMETER -> "let the kettle sit 30 seconds off the boil"
    else -> ""
}

/**
 * The recipe's own grind is a word ("medium-fine"); this is where that sits on your grinder, from
 * the catalog's sourced range for this recipe's method. A grinder with no range for this method
 * shows nothing rather than another method's.
 */
@Composable
private fun YourGrinder(recipe: Recipe, grindScale: GrindScale?, grinderName: String) {
    val method = recipe.brewer.method
    if (grindScale == null || grindScale.rangeFor(method) == null) return
    SectionLabel("ON YOUR ${grinderName.uppercase()}")
    SoftCard(Modifier.fillMaxWidth()) { GrindRangeLine(scale = grindScale, method = method) }
}

@Composable
private fun StepsBlock(recipe: Recipe) {
    val timeline = remember(recipe) { recipe.plannedTimelineRows() }
    SectionLabel("THE BREW · ${recipe.steps.size} ${if (recipe.steps.size == 1) "STEP" else "STEPS"}")
    // The whole instruction on every step: this page is where the method is learnt.
    SoftCard(Modifier.fillMaxWidth()) { TimelineCard(timeline, detailMaxLines = Int.MAX_VALUE) }
}

@Composable
private fun PrepBlock(prep: List<String>, hasScale: Boolean) {
    if (prep.isEmpty()) return
    val c = CoffeeTheme.colors
    val rowText = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
    SectionLabel(if (hasScale) "SET UP" else "SET UP · NO SCALE")
    SoftCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
        prep.forEachIndexed { i, line ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Text("${i + 1}", style = Num.Inline, color = c.inkLow, modifier = Modifier.width(24.dp).alignByBaseline())
                Text(line, style = rowText, color = c.inkHigh, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

@Composable
private fun TipsBlock(recipe: Recipe) {
    if (recipe.tips.isEmpty()) return
    val rowText = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
    SectionLabel("WORTH KNOWING")
    SoftCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        recipe.tips.forEachIndexed { i, tip ->
            if (i > 0) HairLine()
            Text(tip, style = rowText, color = CoffeeTheme.colors.inkHigh, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

/** Attribution honesty, deliberately not buried. */
@Composable
private fun ColumnScope.SourceBlock(recipe: Recipe, isOverride: Boolean, onReset: () -> Unit) {
    val c = CoffeeTheme.colors
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("SOURCE", style = Micro, color = c.inkLow)
        if (recipe.fidelity in setOf("published", "adapted", "community")) {
            Spacer(Modifier.width(8.dp))
            Term(key = recipe.fidelity, text = recipe.fidelity.replaceFirstChar { it.uppercase() }, style = Micro, color = c.inkMid)
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(recipe.sourceNote, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp), color = c.inkLow)
    if (isOverride) {
        // Two taps, like every other undo-less action in the app.
        var armed by rememberSaveable { mutableStateOf(false) }
        Spacer(Modifier.height(4.dp))
        QuietButton(
            text = if (armed) "Tap again to reset" else "Reset to original",
            onClick = {
                if (armed) {
                    armed = false
                    onReset()
                } else {
                    armed = true
                }
            },
            modifier = Modifier.offset(x = (-8).dp),
        )
    }
}
