package com.laksh.coffee.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.grams
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.ui.components.Cell
import com.laksh.coffee.ui.components.CoffeeMug
import com.laksh.coffee.ui.components.DataGrid
import com.laksh.coffee.ui.components.Dripper
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.GroupPosition
import com.laksh.coffee.ui.components.LogRow
import com.laksh.coffee.ui.components.MugSteam
import com.laksh.coffee.ui.components.PillButton
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.RecipeCard
import com.laksh.coffee.ui.components.RouteMode
import com.laksh.coffee.ui.components.RouteRow
import com.laksh.coffee.ui.components.ScreenHeader
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.pourNodes
import com.laksh.coffee.ui.components.rememberGreeting
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.components.timeStatus
import com.laksh.coffee.ui.components.toCaps
import com.laksh.coffee.ui.nav.SharedKeys
import com.laksh.coffee.ui.screens.gear.BeanWords
import com.laksh.coffee.ui.nav.sharedBoundsModifier
import com.laksh.coffee.ui.theme.CardShape
import com.laksh.coffee.ui.theme.CoffeeColors
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.listBottomPadding
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val EYEBROW_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
fun HomeScreen(
    onOpenRecipe: (id: String, from: String) -> Unit,
    onBrew: (String) -> Unit,
    onOpenLog: (Long) -> Unit,
    onSeeAllRecipes: () -> Unit,
    onSeeHistory: () -> Unit,
    /** Opens setup from the "Make it yours" card; wired by the navigation package. */
    onPersonalise: () -> Unit = {},
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // The initial state has no recipes; Room always returns the 14 built-ins, so an empty list
    // means "not loaded yet" and the body waits rather than flashing the empty state.
    val loaded = state.recipes.isNotEmpty()
    val hasLogs = state.recentLogs.isNotEmpty()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("home-list"),
            contentPadding = PaddingValues(bottom = listBottomPadding()),
        ) {
            // ------------------------------------------------------------ header
            item(key = "header", contentType = "header") {
                val h = state.headline
                // The page stays composed between visits, so the day comes from the state and the
                // greeting is re-read on resume; neither is asked of the clock while composing.
                val hello = rememberGreeting()
                ScreenHeader(
                    eyebrow = "$hello · ${EYEBROW_DATE.format(state.today)}".uppercase(),
                    title = playfulTitle(state.brewsToday),
                    modifier = Modifier.animateItem(
                        fadeInSpec = Motion.settle(),
                        placementSpec = Motion.offset(),
                        fadeOutSpec = Motion.quick(),
                    ),
                    trailing = { HomeMug(state.brewsToday) },
                    status = if (loaded) {
                        { StatusLine(h.status, h.lead, detail = h.detail) }
                    } else {
                        null
                    },
                )
            }

            if (loaded) {
                // ----------------------------------------------------- make it yours
                if (state.showPersonalise) {
                    item(key = "personalise", contentType = "personalise") {
                        PersonaliseCard(
                            onPersonalise = onPersonalise,
                            onNotNow = vm::dismissPersonalise,
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = Motion.settle(),
                                    placementSpec = Motion.offset(),
                                    fadeOutSpec = Motion.quick(),
                                )
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }

                // ------------------------------------------------------- next brew
                state.nextRecipe?.let { r ->
                    item(key = "pass", contentType = "pass") {
                        NextBrewPass(
                            recipe = r,
                            firstBrew = !hasLogs,
                            lastGrind = state.lastGrindFor(r.id),
                            lastLog = state.lastLogFor(r.id),
                            resting = state.resting,
                            onOpen = { onOpenRecipe(r.id, SharedKeys.FROM_NEXT) },
                            onBrew = { onBrew(r.id) },
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = Motion.settle(),
                                    placementSpec = Motion.offset(),
                                    fadeOutSpec = Motion.quick(),
                                )
                                .padding(horizontal = 16.dp),
                        )
                    }
                }

                // ----------------------------------------------------------- stats
                item(key = "stats", contentType = "stats") {
                    StatsStrip(
                        state = state,
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            )
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                }

                // ---------------------------------------------------------- recent
                item(key = "recent-label", contentType = "recent-label") {
                    SectionLabel(
                        text = "RECENT",
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            )
                            .padding(horizontal = 16.dp),
                        trailing = if (hasLogs) "See log" else null,
                        onTrailing = onSeeHistory,
                    )
                }

                val recent = state.recentLogs.take(3)
                if (recent.isEmpty()) {
                    item(key = "recent-empty", contentType = "recent-empty") {
                        EmptyRecent(
                            Modifier
                                .animateItem(
                                    fadeInSpec = Motion.settle(),
                                    placementSpec = Motion.offset(),
                                    fadeOutSpec = Motion.quick(),
                                )
                                .padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    // One item per row so a newly saved brew slides the others down.
                    itemsIndexed(recent, key = { _, log -> "recent-${log.id}" }, contentType = { _, _ -> "log-row" }) { index, log ->
                        LogRow(
                            log = log,
                            position = GroupPosition.of(index, recent.size),
                            onClick = { onOpenLog(log.id) },
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = Motion.settle(),
                                    placementSpec = Motion.offset(),
                                    fadeOutSpec = Motion.quick(),
                                )
                                .padding(horizontal = 16.dp),
                        )
                    }
                }

                // --------------------------------------------------------- experts
                item(key = "experts-label", contentType = "experts-label") {
                    SectionLabel(
                        text = "FROM THE EXPERTS",
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            )
                            .padding(horizontal = 16.dp),
                        trailing = "All ${state.recipes.size}",
                        onTrailing = onSeeAllRecipes,
                    )
                }

                item(key = "experts", contentType = "experts") {
                    LazyRow(
                        modifier = Modifier.animateItem(
                            fadeInSpec = Motion.settle(),
                            placementSpec = Motion.offset(),
                            fadeOutSpec = Motion.quick(),
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.recipes, key = { it.id }, contentType = { "recipe-card" }) { recipe ->
                            RecipeCard(
                                recipe = recipe,
                                onClick = { onOpenRecipe(recipe.id, SharedKeys.FROM_HOME) },
                                modifier = Modifier.width(232.dp),
                                compact = true,
                                sharedKeyPrefix = SharedKeys.FROM_HOME,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * For people who had data before setup existed: one quiet card offering it, never a screen in
 * their way. "Not now" puts it away for good; Gear keeps "Run setup again".
 */
@Composable
private fun PersonaliseCard(
    onPersonalise: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CoffeeTheme.colors
    SoftCard(modifier.fillMaxWidth()) {
        EyebrowLabel("MAKE IT YOURS")
        Spacer(Modifier.height(6.dp))
        Text(
            "Pick your grinder from the list and the brewers you own.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.inkHigh,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(text = "Personalise", onClick = onPersonalise)
            Spacer(Modifier.width(4.dp))
            QuietButton(text = "Not now", onClick = onNotNow)
        }
    }
}

private fun playfulTitle(brewsToday: Int): String = when (brewsToday) {
    0 -> "No coffee yet."
    1 -> "One down."
    2 -> "Two down."
    else -> "$brewsToday today. Steady on."
}

/**
 * The app's signature: a small steaming mug that fills with today's brews (a sliver at zero,
 * full at three). Steam is drawn separately so light mode can use a darker wisp than the shell.
 */
@Composable
private fun HomeMug(brewsToday: Int, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    val fill = (brewsToday.coerceAtMost(3) / 3f).coerceAtLeast(0.12f)
    // A fixed parent is what sizes the art: its own size modifier yields to parent constraints.
    // The steam rises from the rim only; filling the box ran the right wisp down the cup wall.
    Box(modifier.size(72.dp)) {
        MugSteam(
            color = if (c.isDark) c.artShell else c.inkLow,
            strength = { fill },
        )
        CoffeeMug(
            fill = fill,
            modifier = Modifier.fillMaxSize(),
            body = artShellOn(c),
            liquid = c.artBed,
            crema = c.artWater,
            showSteam = false,
        )
    }
}

/** Light ceramic vanishes on paper, so light mode draws the shell one step deeper. */
private fun artShellOn(c: CoffeeColors): Color = if (c.isDark) c.artShell else c.outlineStrong

@Composable
private fun NextBrewPass(
    recipe: Recipe,
    firstBrew: Boolean,
    lastGrind: Int?,
    lastLog: BrewLogEntity?,
    resting: Pair<String, Long>?,
    onOpen: () -> Unit,
    onBrew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CoffeeTheme.colors
    val units = LocalUnits.current
    val nodes = remember(recipe) { recipe.pourNodes() }

    SoftCard(modifier = modifier, onClick = onOpen, contentPadding = PaddingValues(0.dp)) {
        // The top block flies into Recipe detail's header, so it carries its own surface:
        // mid-flight it must never be text on transparent.
        Column(
            Modifier
                .then(
                    sharedBoundsModifier(
                        key = SharedKeys.recipeHeader(SharedKeys.FROM_NEXT, recipe.id),
                        clip = CardShape,
                    ),
                )
                .fillMaxWidth()
                .background(c.surface1, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .padding(start = 18.dp, end = 18.dp, top = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyebrowLabel(if (firstBrew) "FIRST BREW" else "NEXT BREW")
                Spacer(Modifier.weight(1f))
                // The one place a recipe's own colour survives: an identity dot.
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(recipe.accent)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    // The short name the recipe cards and log rows use ("V60"), not the catalog's "V60 02".
                    recipe.brewer.shortName.toCaps(),
                    style = Micro,
                    color = c.inkLow,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                recipe.name,
                style = MaterialTheme.typography.headlineMedium,
                color = c.inkHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                recipe.author,
                style = MaterialTheme.typography.bodySmall,
                color = c.inkMid,
                maxLines = 1,
            )
        }

        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp)) {
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
            Spacer(Modifier.height(16.dp))
            DataGrid(
                cells = listOf(
                    // Says where the number came from: your last brew of this recipe, not the recipe.
                    lastGrind?.let {
                        Cell(label = "GRIND", value = "$it", unit = unitWord(it, "click"), sub = "last used")
                    } ?: Cell(label = "GRIND", value = recipe.grind, textValue = true),
                    Cell("TEMP", units.temp(recipe.tempC), units.tempUnit),
                    Cell("TARGET", clock(recipe.totalSec)),
                ),
                columns = 3,
            )
            lastLog?.let { l ->
                Spacer(Modifier.height(12.dp))
                StatusLine(
                    status = scoreStatus(l.overall),
                    lead = if (l.overall > 0) "Last time ${l.overall}/10" else "Last time unrated",
                    detail = timeStatus(l.totalTimeSec, recipe.totalSec).second.lowercase(),
                )
            }
            resting?.let { (bean, daysLeft) ->
                Spacer(Modifier.height(if (lastLog == null) 12.dp else 4.dp))
                // Only for a coffee whose rest you recorded, and always a word with the colour.
                StatusLine(
                    status = Status.Attention,
                    lead = "Resting",
                    detail = "${BeanWords.restDetail(daysLeft)} · $bean",
                )
            }
            Spacer(Modifier.height(16.dp))
            // Inverse, not crema: crema is kept for things that are live, and nothing is yet.
            PrimaryButton(
                text = "Start brew",
                onClick = onBrew,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Rounded.PlayArrow,
                height = Dimens.PrimaryInCard,
                containerColor = c.inverse,
                contentColor = c.onInverse,
            )
        }
    }
}

@Composable
private fun StatsStrip(state: HomeUiState, modifier: Modifier = Modifier) {
    val hasLogs = state.recentLogs.isNotEmpty()
    val last5 = state.lastFiveAvg
    val prev5 = state.prevFiveAvg
    val delta = if (last5 != null && prev5 != null) last5 - prev5 else null

    // The grid's own cell padding supplies the vertical rhythm, so the card adds little.
    SoftCard(modifier, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
        DataGrid(
            cells = listOf(
                // Every cell carries a quiet sub line, so no column has an empty lower half,
                // and the delta says what it is compared with instead of a bare arrow.
                Cell(
                    label = "STREAK",
                    value = state.streakDays.takeIf { hasLogs }?.toString(),
                    // "1 day", "1 brew": the unit follows the count.
                    unit = unitWord(state.streakDays, "day"),
                    sub = if (hasLogs) "in a row" else null,
                ),
                Cell(
                    label = "THIS WEEK",
                    value = state.brewsThisWeek.takeIf { hasLogs }?.toString(),
                    unit = unitWord(state.brewsThisWeek, "brew"),
                    sub = if (hasLogs) "last 7 days" else null,
                ),
                Cell(
                    label = "LAST 5",
                    value = last5?.let(::oneDp),
                    status = last5?.let { scoreStatus(it.roundToInt()) },
                    sub = delta?.let { d -> (if (d >= 0) "▲ " else "▼ ") + oneDp(abs(d)) + " vs prev 5" },
                    subStatus = delta?.let { d -> if (d >= 0) Status.Good else Status.Attention },
                    subChip = false,
                ),
            ),
            columns = 3,
        )
    }
}

/** No brews yet: the dripper, small, instead of a bare sentence. */
@Composable
private fun EmptyRecent(modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    SoftCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp)) {
                Dripper(
                    waterLevel = 0.5f,
                    modifier = Modifier.fillMaxSize(),
                    shell = artShellOn(c),
                    water = c.artWater,
                    bed = c.artBed,
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                "Brews you finish show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMid,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
