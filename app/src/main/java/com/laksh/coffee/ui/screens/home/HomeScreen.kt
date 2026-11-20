package com.laksh.coffee.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.formatDuration
import com.laksh.coffee.core.grams
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.core.screenDayLabel
import com.laksh.coffee.core.screenTime
import com.laksh.coffee.core.todayEyebrow
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.NoScale
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.ShotTarget
import com.laksh.coffee.ui.components.BrewerIcon
import com.laksh.coffee.ui.components.Cell
import com.laksh.coffee.ui.components.CoffeeMug
import com.laksh.coffee.ui.components.DataGrid
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.HairLine
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
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.components.focusRing
import com.laksh.coffee.ui.components.hoverTint
import com.laksh.coffee.ui.components.pourNodes
import com.laksh.coffee.ui.components.rememberGreeting
import com.laksh.coffee.ui.components.roastStatus
import com.laksh.coffee.ui.components.roastWord
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.components.shotTimeStatus
import com.laksh.coffee.ui.components.timeStatus
import com.laksh.coffee.ui.components.toCaps
import com.laksh.coffee.ui.nav.SharedKeys
import com.laksh.coffee.ui.nav.sharedBoundsModifier
import com.laksh.coffee.ui.screens.gear.BeanWords
import com.laksh.coffee.ui.theme.CardShape
import com.laksh.coffee.ui.theme.CoffeeColors
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.listBottomPadding
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** How Today lays itself out, from the width it actually has beside the navigation (§3.1). */
internal enum class TodayLayout { Phone, Column, Dashboard, WideDashboard }

internal object TodayLayouts {
    fun of(contentWidthDp: Float): TodayLayout = when {
        contentWidthDp >= 1100f -> TodayLayout.WideDashboard
        contentWidthDp >= 840f -> TodayLayout.Dashboard
        contentWidthDp >= 600f -> TodayLayout.Column
        else -> TodayLayout.Phone
    }

    /** "Start from" cards per row: a carousel on phones, then 2, 3 and 4 up. */
    fun startFromColumns(layout: TodayLayout): Int = when (layout) {
        TodayLayout.Phone -> 1
        TodayLayout.Column -> 2
        TodayLayout.Dashboard -> 3
        TodayLayout.WideDashboard -> 4
    }
}

@Composable
fun HomeScreen(
    onOpenRecipe: (id: String, from: String) -> Unit,
    onBrew: (String) -> Unit,
    onOpenLog: (Long) -> Unit,
    onSeeAllRecipes: () -> Unit,
    onSeeHistory: () -> Unit,
    /** Opens setup from the "Make it yours" card; wired by the navigation package. */
    onPersonalise: () -> Unit = {},
    /** "Brew again" on a Recent row: setup prefilled from that brew (v4 §4.10). */
    onBrewAgain: (recipeId: String, logId: Long) -> Unit = { id, _ -> onBrew(id) },
    /** The empty "Your coffee" card's way to Gear › Beans. */
    onAddCoffee: () -> Unit = {},
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = remember(onOpenRecipe, onBrew, onOpenLog, onSeeAllRecipes, onSeeHistory, onPersonalise, onBrewAgain, onAddCoffee) {
        TodayActions(onOpenRecipe, onBrew, onOpenLog, onSeeAllRecipes, onSeeHistory, onPersonalise, onBrewAgain, vm::dismissPersonalise, onAddCoffee)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = TodayLayouts.of(maxWidth.value)
        TodayList(state, layout, actions)
    }
}

/** Every way out of Today, held once so rows never rebuild their lambdas. */
internal class TodayActions(
    val openRecipe: (String, String) -> Unit,
    val brew: (String) -> Unit,
    val openLog: (Long) -> Unit,
    val seeAllRecipes: () -> Unit,
    val seeHistory: () -> Unit,
    val personalise: () -> Unit,
    val brewAgain: (String, Long) -> Unit,
    val dismissPersonalise: () -> Unit,
    val addCoffee: () -> Unit = {},
)

private fun Modifier.appear(scope: androidx.compose.foundation.lazy.LazyItemScope): Modifier = with(scope) {
    this@appear.animateItem(fadeInSpec = Motion.settle(), placementSpec = Motion.offset(), fadeOutSpec = Motion.quick())
}

@Composable
private fun TodayList(state: HomeUiState, layout: TodayLayout, a: TodayActions) {
    // The initial state has no recipes; Room always returns the built-ins, so an empty list means
    // "not loaded yet" and the body waits rather than flashing the empty state.
    val loaded = state.recipes.isNotEmpty()
    val max: Dp = when (layout) {
        TodayLayout.Phone -> Dp.Unspecified
        TodayLayout.Column -> 720.dp
        TodayLayout.Dashboard -> 1200.dp
        TodayLayout.WideDashboard -> 1360.dp
    }
    val gutter = when (layout) {
        TodayLayout.Phone -> 16.dp
        TodayLayout.WideDashboard -> 32.dp
        else -> 24.dp
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("home-list"),
        contentPadding = PaddingValues(bottom = listBottomPadding()),
    ) {
        item(key = "header", contentType = "header") {
            Centered(max, 0.dp, Modifier.appear(this)) {
                Header(state, loaded, gutter)
            }
        }
        if (!loaded) return@LazyColumn

        if (state.showPersonalise) {
            item(key = "personalise", contentType = "personalise") {
                Centered(max, gutter, Modifier.appear(this)) {
                    PersonaliseCard(onPersonalise = a.personalise, onNotNow = a.dismissPersonalise, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
        }

        when (layout) {
            TodayLayout.Phone -> phoneBody(state, a)
            TodayLayout.Column -> columnBody(state, a, max, gutter)
            TodayLayout.Dashboard, TodayLayout.WideDashboard -> dashboardBody(state, a, max, gutter, wide = layout == TodayLayout.WideDashboard)
        }

        startFrom(state, a, layout, max, gutter)
    }
}

/** Content capped at [max] and centred, with the gutter inside the cap. */
@Composable
private fun Centered(max: Dp, gutter: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.then(if (max != Dp.Unspecified) Modifier.widthIn(max = max) else Modifier).fillMaxWidth().padding(horizontal = gutter)) {
            content()
        }
    }
}

@Composable
private fun Header(state: HomeUiState, loaded: Boolean, gutter: Dp) {
    val h = state.headline
    // The greeting is re-read on resume, and the day comes from the state; the eyebrow follows
    // the phone's language for the date ("EVENING · THU, NOV 19" in en-US).
    val hello = rememberGreeting()
    val eyebrow = remember(hello, state.today) { todayEyebrow() }
    // ScreenHeader brings its own 16dp; the rest of the gutter is added around it.
    ScreenHeader(
        eyebrow = eyebrow,
        title = playfulTitle(state.brewsToday, hasHistory = state.recentLogs.isNotEmpty()),
        modifier = Modifier.padding(horizontal = (gutter - 16.dp).coerceAtLeast(0.dp)),
        trailing = { HomeMug(state.brewsToday) },
        status = if (loaded) {
            { StatusLine(h.status, h.lead, detail = h.detail) }
        } else {
            null
        },
    )
}

// ---------------------------------------------------------------------- bodies

/** Phones: one column, as it always was, plus the first-brew card and Brew again. */
private fun LazyListScope.phoneBody(state: HomeUiState, a: TodayActions) {
    item(key = "next", contentType = "next") {
        NextOrFirst(state, a, Modifier.appear(this).padding(horizontal = 16.dp))
    }
    item(key = "stats", contentType = "stats") {
        StatsStrip(state, Modifier.appear(this).padding(start = 16.dp, end = 16.dp, top = 12.dp))
    }
    item(key = "recent", contentType = "recent") {
        Column(Modifier.appear(this).padding(horizontal = 16.dp)) { RecentBlock(state, a, rows = 3) }
    }
}

/** Medium: a 720 column; Recent and the coffee on deck side by side. */
private fun LazyListScope.columnBody(state: HomeUiState, a: TodayActions, max: Dp, gutter: Dp) {
    item(key = "next", contentType = "next") {
        Centered(max, gutter, Modifier.appear(this)) { NextOrFirst(state, a) }
    }
    item(key = "stats", contentType = "stats") {
        Centered(max, gutter, Modifier.appear(this).padding(top = 12.dp)) { StatsStrip(state) }
    }
    item(key = "pair", contentType = "pair") {
        Centered(max, gutter, Modifier.appear(this)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { RecentBlock(state, a, rows = 4) }
                Column(Modifier.weight(1f)) {
                    SectionLabel("YOUR COFFEE")
                    CoffeeOnDeck(state, onAddCoffee = a.addCoffee)
                }
            }
        }
    }
}

/**
 * Expanded and up: a dashboard. Left (7 of 12): the next brew, the one hero. Right (5 of 12):
 * the stats as tiles, Recent, and at large widths the coffee on deck and the last grind.
 */
private fun LazyListScope.dashboardBody(state: HomeUiState, a: TodayActions, max: Dp, gutter: Dp, wide: Boolean) {
    item(key = "dash", contentType = "dash") {
        Centered(max, gutter, Modifier.appear(this)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 16.dp)) {
                Column(Modifier.weight(7f)) {
                    NextOrFirst(state, a, buttonMax = 420.dp)
                }
                Column(Modifier.weight(5f)) {
                    StatsTiles(state)
                    RecentBlock(state, a, rows = if (wide) 8 else 5, topGap = 16.dp)
                    if (wide) {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                EyebrowLabel("YOUR COFFEE", Modifier.padding(bottom = 8.dp).asHeading())
                                CoffeeOnDeck(state, Modifier.fillMaxHeight(), onAddCoffee = a.addCoffee)
                            }
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                EyebrowLabel("LAST GRIND", Modifier.padding(bottom = 8.dp).asHeading())
                                LastGrind(state, Modifier.fillMaxHeight())
                            }
                        }
                    } else {
                        SectionLabel("YOUR COFFEE", topGap = 16.dp)
                        CoffeeOnDeck(state, onAddCoffee = a.addCoffee)
                    }
                }
            }
        }
    }
}

/** "Start from": a carousel on phones; a 2, 3 or 4 up grid wider (no sideways scrolling on a pointer). */
private fun LazyListScope.startFrom(state: HomeUiState, a: TodayActions, layout: TodayLayout, max: Dp, gutter: Dp) {
    item(key = "experts-label", contentType = "experts-label") {
        Centered(max, gutter, Modifier.appear(this)) {
            SectionLabel(text = "START FROM", trailing = "All ${state.recipes.size}", onTrailing = a.seeAllRecipes)
        }
    }
    val cols = TodayLayouts.startFromColumns(layout)
    if (cols == 1) {
        item(key = "experts", contentType = "experts") {
            LazyRow(
                modifier = Modifier.appear(this),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.recipes, key = { it.id }, contentType = { "recipe-card" }) { recipe ->
                    RecipeCard(
                        recipe = recipe,
                        onClick = { a.openRecipe(recipe.id, SharedKeys.FROM_HOME) },
                        modifier = Modifier.width(232.dp),
                        compact = true,
                        sharedKeyPrefix = SharedKeys.FROM_HOME,
                    )
                }
            }
        }
        return
    }
    // One lazy item per grid row, so fifty recipes never compose at once.
    val rows = state.recipes.chunked(cols)
    rows.forEachIndexed { i, row ->
        item(key = "experts-$i-${row.first().id}", contentType = "experts-row") {
            Centered(max, gutter, Modifier.appear(this).padding(bottom = 16.dp)) {
                // Top-aligned, not intrinsic height: the card's route row is a BoxWithConstraints,
                // and asking it for intrinsics crashed the medium layout on rotation (V4-AND3 glue).
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { recipe ->
                        RecipeCard(
                            recipe = recipe,
                            onClick = { a.openRecipe(recipe.id, SharedKeys.FROM_HOME) },
                            modifier = Modifier.weight(1f),
                            compact = true,
                            sharedKeyPrefix = SharedKeys.FROM_HOME,
                        )
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------- cards

/** The next brew; before any brew, the guided first brew; with no recipe for your brewer, says so. */
@Composable
private fun NextOrFirst(state: HomeUiState, a: TodayActions, modifier: Modifier = Modifier, buttonMax: Dp = Dp.Unspecified) {
    val hasLogs = state.recentLogs.isNotEmpty()
    val next = state.nextRecipe
    Column(modifier) {
        when {
            !hasLogs && next != null -> FirstBrewCard(state, next, onOpen = { a.openRecipe(next.id, SharedKeys.FROM_NEXT) }, onBrew = { a.brew(next.id) }, buttonMax = buttonMax)
            next != null -> NextBrewPass(
                recipe = next,
                lastGrind = state.lastGrindFor(next.id),
                lastLog = state.lastLogFor(next.id),
                resting = state.resting,
                onOpen = { a.openRecipe(next.id, SharedKeys.FROM_NEXT) },
                onBrew = { a.brew(next.id) },
                buttonMax = buttonMax,
                hasScale = state.settings.hasScale,
            )
        }
        if (!hasLogs) {
            state.firstPick.noRecipeFor.firstOrNull()?.let { id ->
                if (next != null) Spacer(Modifier.height(12.dp))
                NoRecipeCard(Brewers.byId(id)?.label ?: id, onSeeAll = a.seeAllRecipes)
            }
        }
    }
}

/**
 * For people who had data before setup existed: one quiet card offering it, never a screen in
 * their way. "Not now" puts it away for good; Gear keeps "Run setup again".
 */
@Composable
private fun PersonaliseCard(onPersonalise: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    SoftCard(modifier.fillMaxWidth()) {
        EyebrowLabel("MAKE IT YOURS", Modifier.asHeading())
        Spacer(Modifier.height(6.dp))
        Text("Pick your grinder from the list and the brewers you own.", style = MaterialTheme.typography.bodyMedium, color = c.inkHigh)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(text = "Personalise", onClick = onPersonalise, height = 44.dp)
            Spacer(Modifier.width(4.dp))
            QuietButton(text = "Not now", onClick = onNotNow)
        }
    }
}

/**
 * [hasHistory]: brews on earlier days. "No coffee yet." would read as if the log were empty to
 * someone with a hundred brews in it, so a quiet day says so instead (A7, the web's words too).
 */
internal fun playfulTitle(brewsToday: Int, hasHistory: Boolean = false): String = when (brewsToday) {
    0 -> if (hasHistory) "None yet today." else "No coffee yet."
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
    Box(modifier.size(72.dp)) {
        MugSteam(color = if (c.isDark) c.artShell else c.inkLow, strength = { fill })
        CoffeeMug(fill = fill, modifier = Modifier.fillMaxSize(), body = artShellOn(c), liquid = c.artBed, crema = c.artWater, showSteam = false)
    }
}

/** Light ceramic vanishes on paper, so light mode draws the shell one step deeper. */
private fun artShellOn(c: CoffeeColors): Color = if (c.isDark) c.artShell else c.outlineStrong

/**
 * "YOUR FIRST BREW" (v4 §4.6): what to get out, three lines on how it goes, and one button. The
 * amounts follow the scale setting, so someone without one reads spoons and ml.
 */
@Composable
private fun FirstBrewCard(state: HomeUiState, recipe: Recipe, onOpen: () -> Unit, onBrew: () -> Unit, buttonMax: Dp) {
    val c = CoffeeTheme.colors
    val type = MaterialTheme.typography
    val method = recipe.brewer.method
    val needs = remember(recipe, state.settings.hasScale) { firstBrewNeeds(recipe, state.settings.hasScale) }
    val steps = remember(recipe) { firstBrewChecklist(recipe) }
    SoftCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel("YOUR FIRST BREW", Modifier.weight(1f).asHeading())
            BrewerIcon(recipe.brewer, size = 16.dp, tint = c.inkLow)
            Spacer(Modifier.width(6.dp))
            Text(recipe.brewer.shortName.toCaps(), style = Micro, color = c.inkLow, maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        Text(recipe.name, style = type.headlineMedium, color = c.inkHigh)
        Text(recipe.author, style = type.bodySmall, color = c.inkMid)

        Spacer(Modifier.height(16.dp))
        EyebrowLabel("YOU'LL NEED")
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            needs.forEach { NeedChip(it) }
        }

        Spacer(Modifier.height(16.dp))
        steps.forEachIndexed { i, line ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("${i + 1}", style = Num.Inline, color = c.inkLow, modifier = Modifier.width(24.dp))
                Text(line, style = type.bodyMedium, color = c.inkHigh)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // Inverse, not crema: crema is kept for things that are live, and nothing is yet.
            PrimaryButton(
                text = "Start my first brew",
                onClick = onBrew,
                modifier = Modifier.then(if (buttonMax != Dp.Unspecified) Modifier.widthIn(max = buttonMax) else Modifier).fillMaxWidth(),
                icon = Icons.Rounded.PlayArrow,
                height = Dimens.PrimaryInCard,
                containerColor = c.inverse,
                contentColor = c.onInverse,
            )
        }
        if (method == BrewMethod.ColdBrew) {
            Spacer(Modifier.height(8.dp))
            Text("This one takes ${formatDuration(recipe.totalSec)}.", style = type.bodySmall, color = c.inkLow)
        }
    }
}

@Composable
private fun NeedChip(text: String) {
    val c = CoffeeTheme.colors
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface2)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.inkHigh)
    }
}

/** "We don't have a tested recipe for your Cezve yet." with a way to look anyway. */
@Composable
private fun NoRecipeCard(brewer: String, onSeeAll: () -> Unit) {
    val c = CoffeeTheme.colors
    SoftCard(Modifier.fillMaxWidth()) {
        Text("We don't have a tested recipe for your $brewer yet.", style = MaterialTheme.typography.bodyLarge, color = c.inkHigh)
        Spacer(Modifier.height(8.dp))
        PillButton(text = "See all recipes", onClick = onSeeAll, height = 44.dp)
    }
}

@Composable
private fun NextBrewPass(
    recipe: Recipe,
    lastGrind: Int?,
    lastLog: BrewLogEntity?,
    resting: Pair<String, Long>?,
    onOpen: () -> Unit,
    onBrew: () -> Unit,
    buttonMax: Dp,
    hasScale: Boolean = true,
) {
    val c = CoffeeTheme.colors
    val units = LocalUnits.current
    val nodes = remember(recipe) { recipe.pourNodes() }
    // Spoons and ml without a scale, the way setup and the brew will say them (A1).
    val coffee = remember(recipe, hasScale) { NoScale.coffee(recipe.doseG, recipe.brewer.method, hasScale) }
    val water = remember(recipe, hasScale) { NoScale.water(recipe.waterG, hasScale) }
    val espresso = recipe.brewer.method == BrewMethod.Espresso
    val shot = remember(recipe) { if (espresso) ShotTarget.of(recipe) else null }

    SoftCard(onClick = onOpen, contentPadding = PaddingValues(0.dp)) {
        // The top block flies into Recipe detail's header, so it carries its own surface:
        // mid-flight it must never be text on transparent.
        Column(
            Modifier
                .then(sharedBoundsModifier(key = SharedKeys.recipeHeader(SharedKeys.FROM_NEXT, recipe.id), clip = CardShape))
                .fillMaxWidth()
                .background(c.surface1, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .padding(start = 18.dp, end = 18.dp, top = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyebrowLabel("NEXT BREW", Modifier.asHeading())
                Spacer(Modifier.weight(1f))
                // The one place a recipe's own colour survives: an identity dot.
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(recipe.accent)))
                Spacer(Modifier.width(6.dp))
                Text(recipe.brewer.shortName.toCaps(), style = Micro, color = c.inkLow, maxLines = 1)
            }
            Spacer(Modifier.height(10.dp))
            Text(recipe.name, style = MaterialTheme.typography.headlineMedium, color = c.inkHigh, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(recipe.author, style = MaterialTheme.typography.bodySmall, color = c.inkMid, maxLines = 1)
        }

        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 18.dp)) {
            RouteRow(
                from = coffee.first,
                fromUnit = coffee.second,
                fromLabel = "COFFEE",
                to = water.first,
                toUnit = water.second,
                toLabel = if (espresso) "YIELD" else "WATER",
                nodes = nodes,
                midLabel = recipe.ratioLabel,
                style = Num.Large,
                mode = RouteMode.Planned,
            )
            Spacer(Modifier.height(16.dp))
            DataGrid(
                cells = listOf(
                    // Says where the number came from: your last brew of this recipe, not the recipe.
                    lastGrind?.let { Cell(label = "GRIND", value = "$it", unit = unitWord(it, "click"), sub = "last used") }
                        ?: Cell(label = "GRIND", value = recipe.grind, textValue = true),
                    Cell("TEMP", units.temp(recipe.tempC), units.tempUnit),
                    // A shot aims for its own band, not the recipe's whole time with tasting in it (A4).
                    shot?.let { Cell("TARGET", if (it.stated) "${it.lowSec}-${it.highSec}" else "${it.highSec}", "s") }
                        ?: Cell("TARGET", formatDuration(recipe.totalSec)),
                ),
                columns = 3,
            )
            lastLog?.let { l ->
                Spacer(Modifier.height(12.dp))
                StatusLine(
                    status = scoreStatus(l.overall),
                    lead = if (l.overall > 0) "Last time ${l.overall}/10" else "Last time unrated",
                    detail = (shot?.let { shotTimeStatus(l.totalTimeSec, it) } ?: timeStatus(l.totalTimeSec, recipe.totalSec)).second.lowercase(),
                )
            }
            resting?.let { (bean, daysLeft) ->
                Spacer(Modifier.height(if (lastLog == null) 12.dp else 4.dp))
                // Only for a coffee whose rest you recorded, and always a word with the colour.
                StatusLine(status = Status.Attention, lead = "Resting", detail = "${BeanWords.restDetail(daysLeft)} · $bean")
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Inverse, not crema: crema is kept for things that are live, and nothing is yet.
                PrimaryButton(
                    text = "Start brew",
                    onClick = onBrew,
                    modifier = Modifier.then(if (buttonMax != Dp.Unspecified) Modifier.widthIn(max = buttonMax) else Modifier).fillMaxWidth(),
                    icon = Icons.Rounded.PlayArrow,
                    height = Dimens.PrimaryInCard,
                    containerColor = c.inverse,
                    contentColor = c.onInverse,
                )
            }
        }
    }
}

@Composable
private fun StatsStrip(state: HomeUiState, modifier: Modifier = Modifier) {
    // The grid's own cell padding supplies the vertical rhythm, so the card adds little.
    SoftCard(modifier, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
        DataGrid(cells = statCells(state), columns = 3)
    }
}

/** The same three numbers as tiles, for the dashboard's right column. */
@Composable
private fun StatsTiles(state: HomeUiState) {
    // Three side by side; two columns once big text needs the room (A7).
    val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    SoftCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        DataGrid(cells = statCells(state), columns = if (big) 2 else 3, compact = true)
    }
}

private fun statCells(state: HomeUiState): List<Cell> {
    val hasLogs = state.recentLogs.isNotEmpty()
    val last5 = state.lastFiveAvg
    val prev5 = state.prevFiveAvg
    val delta = if (last5 != null && prev5 != null) last5 - prev5 else null
    // Every cell carries a quiet sub line, so no column has an empty lower half, and the delta
    // says what it is compared with instead of a bare arrow.
    return listOf(
        Cell(label = "STREAK", value = state.streakDays.takeIf { hasLogs }?.toString(), unit = unitWord(state.streakDays, "day"), sub = if (hasLogs) "in a row" else null),
        Cell(label = "THIS WEEK", value = state.brewsThisWeek.takeIf { hasLogs }?.toString(), unit = unitWord(state.brewsThisWeek, "brew"), sub = if (hasLogs) "last 7 days" else null),
        Cell(
            label = "LAST 5",
            value = last5?.let(::oneDp),
            status = last5?.let { scoreStatus(it.roundToInt()) },
            sub = delta?.let { d -> (if (d >= 0) "▲ " else "▼ ") + oneDp(abs(d)) + " vs prev 5" },
            subStatus = delta?.let { d -> if (d >= 0) Status.Good else Status.Attention },
            subChip = false,
        ),
    )
}

/** Recent brews, each with "Brew again"; with none yet, where they will appear and a way on. */
@Composable
private fun RecentBlock(state: HomeUiState, a: TodayActions, rows: Int, topGap: Dp = 24.dp) {
    val recent = state.recentLogs.take(rows)
    SectionLabel(text = "RECENT", trailing = if (recent.isNotEmpty()) "See log" else null, onTrailing = a.seeHistory, topGap = topGap)
    if (recent.isEmpty()) {
        EmptyRecent(onSeeRecipes = a.seeAllRecipes)
        return
    }
    SoftCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        recent.forEachIndexed { i, log ->
            if (i > 0) HairLine(inset = 16.dp)
            RecentRow(log, onOpen = { a.openLog(log.id) }, onAgain = { a.brewAgain(log.recipeId, log.id) })
        }
    }
}

@Composable
private fun RecentRow(log: BrewLogEntity, onOpen: () -> Unit, onAgain: () -> Unit) {
    val c = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val zone = remember { ZoneId.systemDefault() }
    val whenText = remember(log.startedAt) { "${screenDayLabel(log.startedAt, zone = zone)} · ${screenTime(log.startedAt, zone = zone)}" }
    // At large text the score and "Brew again" move under the name, so the name keeps the row's
    // whole width and wraps instead of ending in "…" beside them (A9).
    val stacked = LocalDensity.current.fontScale > 1.3f
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 64.dp)
        .focusRing(interaction, RoundedCornerShape(12.dp))
        .hoverTint(interaction, c.inkHigh.copy(alpha = 0.04f))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
        .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
    val words: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(log.recipeName.ifBlank { "A brew" }, style = MaterialTheme.typography.titleMedium, color = c.inkHigh, maxLines = if (stacked) 3 else 2, overflow = TextOverflow.Ellipsis)
            Text(whenText, style = MaterialTheme.typography.bodySmall, color = c.inkMid)
        }
    }
    val actions: @Composable () -> Unit = {
        if (log.overall > 0) {
            StatusChip(status = scoreStatus(log.overall), text = "${log.overall}/10")
            Spacer(Modifier.width(8.dp))
        }
        PillButton(text = "Brew again", onClick = onAgain, height = 44.dp)
    }
    if (stacked) {
        Column(rowModifier) {
            words(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    } else {
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            words(Modifier.weight(1f))
            actions()
        }
    }
}

/** No brews yet: the mug, small, where they will appear, and a way to the recipes. */
@Composable
private fun EmptyRecent(onSeeRecipes: () -> Unit) {
    val c = CoffeeTheme.colors
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp)) {
                CoffeeMug(fill = 0.1f, modifier = Modifier.fillMaxSize(), body = artShellOn(c), liquid = c.artBed, crema = c.artWater, showSteam = false)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Brews you finish show up here.", style = MaterialTheme.typography.bodyMedium, color = c.inkMid)
                QuietButton(text = "See recipes", onClick = onSeeRecipes)
            }
        }
    }
}

/** The coffee setup starts with: its name, roaster and days off roast as a word with its colour. */
@Composable
private fun CoffeeOnDeck(state: HomeUiState, modifier: Modifier = Modifier, onAddCoffee: () -> Unit = {}) {
    val c = CoffeeTheme.colors
    val bean: BeanEntity? = state.beans.firstOrNull { it.id == state.settings.defaultBeanId } ?: state.beans.firstOrNull()
    SoftCard(modifier.fillMaxWidth()) {
        if (bean == null) {
            Text("No coffee saved yet. Add the one you're drinking under Gear.", style = MaterialTheme.typography.bodyMedium, color = c.inkMid)
            // Every empty state has its way out (v4 §5).
            QuietButton(text = "Add a coffee", onClick = onAddCoffee)
        } else {
            val days = bean.roastedOnEpochDay?.let { (state.today.toEpochDay() - it).coerceAtLeast(0L) }
            Text(bean.name.trim(), style = MaterialTheme.typography.titleMedium, color = c.inkHigh)
            if (bean.roaster.isNotBlank()) Text(bean.roaster.trim(), style = MaterialTheme.typography.bodySmall, color = c.inkMid)
            Spacer(Modifier.height(8.dp))
            val word = roastWord(days)
            StatusLine(
                status = roastStatus(days),
                // 4 to 30 days has no word of its own: it is the usual window to brew in.
                lead = word?.replaceFirstChar { it.uppercase() } ?: if (days == null) "Roast date unknown" else "In its window",
                detail = days?.let { "$it ${unitWord(it.toInt(), "day")} off roast" },
            )
            if (bean.form == "ground") {
                Spacer(Modifier.height(4.dp))
                Text("Ground", style = MaterialTheme.typography.bodySmall, color = c.inkLow)
            }
        }
    }
}

/** The grinder and setting last used for the next recipe. */
@Composable
private fun LastGrind(state: HomeUiState, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    val next = state.nextRecipe
    val log = next?.let { state.lastLogFor(it.id) }
    SoftCard(modifier.fillMaxWidth()) {
        if (log == null || next == null) {
            Text("Your grind for the next recipe shows here after you brew it.", style = MaterialTheme.typography.bodyMedium, color = c.inkMid)
        } else {
            Text(log.grindSetting.trim().ifEmpty { "No setting" }, style = MaterialTheme.typography.titleMedium, color = c.inkHigh)
            Text(log.grinderLabel.ifBlank { "Pre-ground" }, style = MaterialTheme.typography.bodySmall, color = c.inkMid)
            Spacer(Modifier.height(8.dp))
            Text("For ${next.name}, ${formatDuration(log.totalTimeSec)}", style = MaterialTheme.typography.bodySmall, color = c.inkLow)
        }
    }
}

// --------------------------------------------------------------- first-brew copy

/**
 * "You'll need" for a first brew: the brewer, a kettle when the method boils water, the coffee
 * and the water in the person's measures, and a mug.
 */
internal fun firstBrewNeeds(recipe: Recipe, hasScale: Boolean): List<String> {
    val method = recipe.brewer.method
    val a = NoScale.amounts(recipe.doseG, recipe.waterG, method)
    val kettle = usesKettle(recipe)
    val coffee = when {
        a.basket != null -> "Ground coffee"
        hasScale -> "${grams(recipe.doseG)} g of coffee"
        else -> a.coffeeLine
    }
    val water = when {
        method == BrewMethod.Espresso -> null
        hasScale -> "${grams(recipe.waterG)} g water"
        else -> "${a.waterMl} ml water"
    }
    return listOfNotNull(
        recipe.brewer.label.replaceFirstChar { it.uppercase() },
        if (kettle) "Kettle" else null,
        if (hasScale && method != BrewMethod.ColdBrew) "Kitchen scale" else null,
        coffee,
        water,
        if (method == BrewMethod.ColdBrew) "A jar" else "A mug",
    )
}

/** Methods that start from water off the boil. */
private fun usesKettle(recipe: Recipe): Boolean = when (recipe.brewer.method) {
    BrewMethod.Espresso, BrewMethod.ColdBrew, BrewMethod.Moka, BrewMethod.Turkish -> false
    else -> recipe.brewer.id != "drip-machine"
}

/** The three lines (v4 §4.6). The kettle line is the spec's, for methods that use a kettle. */
internal fun firstBrewChecklist(recipe: Recipe): List<String> {
    val first = when {
        usesKettle(recipe) -> "Boil the kettle, then let it sit for 30 seconds."
        recipe.brewer.id == "drip-machine" -> "Fill the machine's tank with fresh water."
        recipe.brewer.method == BrewMethod.Espresso -> "Turn the machine on and let it heat up."
        recipe.brewer.method == BrewMethod.ColdBrew -> "Use cold or room-temperature water."
        recipe.brewer.method == BrewMethod.Moka -> "Fill the base with water to just below the valve."
        else -> "Fill the brewer with fresh water."
    }
    return listOf(first, "Measure the coffee and the water.", "Press Start as you pour. We'll tell you each step.")
}
