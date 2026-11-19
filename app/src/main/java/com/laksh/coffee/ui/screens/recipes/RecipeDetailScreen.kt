package com.laksh.coffee.ui.screens.recipes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.counted
import com.laksh.coffee.core.grams
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.Recipe
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
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.StickyCtaBar
import com.laksh.coffee.ui.components.TimelineCard
import com.laksh.coffee.ui.components.TumblingBeans
import com.laksh.coffee.ui.components.plannedTimelineRows
import com.laksh.coffee.ui.components.pourNodes
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.nav.SharedKeys
import com.laksh.coffee.ui.nav.sharedBoundsModifier
import com.laksh.coffee.ui.theme.CardShape
import com.laksh.coffee.ui.theme.CoffeeTheme
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
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                TumblingBeans(color = CoffeeTheme.colors.inkLow)
            }
        } else {
            RecipeDetailContent(
                recipe = r,
                brewedCount = state.brewedCount,
                best = state.bestLog,
                isOverride = state.isOverride,
                grinderName = state.grinderName,
                grindScale = state.grindScale,
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

@Composable
private fun RecipeDetailContent(
    recipe: Recipe,
    brewedCount: Int,
    best: BrewLogEntity?,
    isOverride: Boolean,
    grinderName: String,
    grindScale: GrindScale?,
    sharedKeyPrefix: String,
    onBack: () -> Unit,
    onBrew: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onReset: () -> Unit,
) {
    val c = CoffeeTheme.colors
    val units = LocalUnits.current
    val scroll = rememberScrollState()
    val nodes = remember(recipe) { recipe.pourNodes() }
    val timeline = remember(recipe) { recipe.plannedTimelineRows() }
    val rowText = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            // Words, not bare icons: these are the two ways a recipe becomes yours.
            DetailTopBar(onBack = onBack) {
                QuietButton(text = "Duplicate", onClick = { onDuplicate(recipe.id) }, icon = Icons.Rounded.ContentCopy)
                QuietButton(text = "Edit", onClick = { onEdit(recipe.id) }, icon = Icons.Rounded.Edit)
            }

            // Header card. The outer box owns the shared bounds and the clip, so the parallax
            // layer inside it can never bleed under the body below.
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .then(
                        sharedBoundsModifier(
                            key = SharedKeys.recipeHeader(sharedKeyPrefix, recipe.id),
                            clip = CardShape,
                        ),
                    )
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(c.surface1),
            ) {
                Column(
                    Modifier
                        .graphicsLayer {
                            // Read the scroll here so scrolling redraws a layer, never recomposes.
                            val s = scroll.value.toFloat()
                            translationY = s * 0.4f
                            alpha = 1f - (s / size.height).coerceIn(0f, 0.8f)
                        }
                        .padding(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The one place a recipe's own colour survives: an identity dot.
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(recipe.accent)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            recipe.author.uppercase(),
                            style = Micro,
                            color = c.inkLow,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        BrewerIcon(recipe.brewer, size = 14.dp, tint = c.inkLow)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            recipe.brewer.label.uppercase(),
                            style = Micro,
                            color = c.inkLow,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        recipe.name,
                        style = MaterialTheme.typography.headlineLarge,
                        color = c.inkHigh,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(16.dp))
                    RouteRow(
                        from = grams(recipe.doseG),
                        fromUnit = "g",
                        fromLabel = "COFFEE",
                        to = grams(recipe.waterG),
                        toUnit = "g",
                        toLabel = "WATER",
                        nodes = nodes,
                        midLabel = recipe.ratioLabel,
                        style = Num.Large,
                        mode = RouteMode.Planned,
                    )
                }
            }

            if (brewedCount > 0) {
                Spacer(Modifier.height(12.dp))
                val rated = best?.takeIf { it.overall > 0 }
                // The coloured words are the verdict that earns the colour: the best score
                // leads, and the count is detail.
                StatusLine(
                    status = scoreStatus(rated?.overall ?: 0),
                    lead = rated?.let { "Best ${it.overall}/10" } ?: "Brewed $brewedCount×",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    // A 0-click log is a logging slip, never a grind worth repeating.
                    detail = rated?.let { b ->
                        "brewed $brewedCount×" +
                            (b.grindClicks?.takeIf { it > 0 }?.let { n -> " · at ${counted(n, "click")}" } ?: "")
                    } ?: "not rated yet",
                )
            }

            Spacer(Modifier.height(12.dp))
            // The grid's own cell padding supplies the vertical rhythm, so the card adds little.
            SoftCard(
                Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            ) {
                DataGrid(
                    cells = listOf(
                        Cell("TEMP", units.temp(recipe.tempC), units.tempUnit),
                        Cell("TIME", clock(recipe.totalSec)),
                    ),
                    columns = 2,
                )
                // The grind in full, on a line of its own: many are a sentence, and a third of
                // the card cut them off ("Coarse, a ste…").
                HairLine()
                Column(Modifier.padding(vertical = 12.dp)) {
                    EyebrowLabel("GRIND")
                    Spacer(Modifier.height(4.dp))
                    Text(recipe.grind.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = c.inkHigh)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                recipe.blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMid,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // The recipe's own grind is a word ("medium-fine"); this is where that sits on your
            // grinder, from the catalog's sourced range for this recipe's method. One line, not
            // the whole chart: the other methods' ranges live with the grinder under Gear, and a
            // grinder with no range for this method shows nothing rather than another method's.
            val method = recipe.brewer.method
            if (grindScale != null && grindScale.rangeFor(method) != null) {
                SectionLabel(
                    "ON YOUR ${grinderName.uppercase()}",
                    Modifier.padding(horizontal = 16.dp),
                )
                SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    GrindRangeLine(scale = grindScale, method = method)
                }
            }

            SectionLabel(
                "THE BREW · ${recipe.steps.size} ${if (recipe.steps.size == 1) "STEP" else "STEPS"}",
                Modifier.padding(horizontal = 16.dp),
            )
            // The whole instruction on every step: this page is where the method is learnt.
            SoftCard(Modifier.padding(horizontal = 16.dp)) {
                TimelineCard(timeline, detailMaxLines = Int.MAX_VALUE)
            }

            if (recipe.prep.isNotEmpty()) {
                SectionLabel("SET UP", Modifier.padding(horizontal = 16.dp))
                SoftCard(
                    Modifier.padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    recipe.prep.forEachIndexed { i, line ->
                        Row(Modifier.padding(vertical = 6.dp)) {
                            Text(
                                "${i + 1}",
                                style = Num.Inline,
                                color = c.inkLow,
                                modifier = Modifier.width(24.dp).alignByBaseline(),
                            )
                            Text(
                                line,
                                style = rowText,
                                color = c.inkHigh,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                    }
                }
            }

            if (recipe.tips.isNotEmpty()) {
                SectionLabel("WORTH KNOWING", Modifier.padding(horizontal = 16.dp))
                SoftCard(
                    Modifier.padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    recipe.tips.forEachIndexed { i, tip ->
                        if (i > 0) HairLine()
                        Text(
                            tip,
                            style = rowText,
                            color = c.inkHigh,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }

            // Attribution honesty, deliberately not buried.
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("SOURCE", style = Micro, color = c.inkLow)
                Spacer(Modifier.height(4.dp))
                Text(
                    recipe.sourceNote,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = c.inkLow,
                )
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

            // Room for the sticky CTA and its fade, so the source note scrolls fully clear.
            Spacer(
                Modifier
                    .navigationBarsPadding()
                    .height(120.dp),
            )
        }

        // Inverse, not crema: crema is kept for things that are live, and nothing is yet.
        StickyCtaBar(Modifier.align(Alignment.BottomCenter)) {
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
