package com.laksh.coffee.ui.screens.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.formatDuration
import com.laksh.coffee.core.grams
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.ui.components.BrewerIcon
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.PillButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.RecipeCard
import com.laksh.coffee.ui.components.ScreenHeader
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.focusRing
import com.laksh.coffee.ui.components.hoverTint
import com.laksh.coffee.ui.nav.LocalPaneSelection
import com.laksh.coffee.ui.nav.PaneDetail
import com.laksh.coffee.ui.nav.SharedKeys
import com.laksh.coffee.ui.nav.TabKeys
import com.laksh.coffee.ui.screens.history.LogFilterUi
import com.laksh.coffee.ui.nav.listSearchTarget
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalWindowClass
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.listBottomPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How the library lays itself out (v4 §3.2). */
internal enum class RecipeListLayout { Cards, Grid, PaneRows }

internal object RecipeListLayouts {
    /** The list pane beside a detail gets compact rows; a medium window a card grid; phones cards. */
    fun of(inPane: Boolean, contentWidthDp: Float): RecipeListLayout = when {
        inPane -> RecipeListLayout.PaneRows
        contentWidthDp >= 600f -> RecipeListLayout.Grid
        else -> RecipeListLayout.Cards
    }
}

/** Lazy index of the first recipe row in [PaneList]: header, search, chips and sort come first. */
private const val PANE_FIRST_ROW = 4

/**
 * [onNewRecipe] opens the recipe builder; [onBrew], [onEdit] and [onDuplicate] serve the list's
 * keys (B, E, D). All wired by the navigation package.
 */
@Composable
fun RecipeListScreen(
    onOpenRecipe: (String) -> Unit,
    onNewRecipe: () -> Unit = {},
    onBrew: (String) -> Unit = {},
    onEdit: (String) -> Unit = {},
    onDuplicate: (String) -> Unit = {},
    vm: RecipesViewModel = viewModel(factory = RecipesViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val inPane = LocalWindowClass.current.usesPanes
    val paneSel = (LocalPaneSelection.current as? PaneDetail.Recipe)?.id
    val paneList = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Keys (§3.2): in the pane layout the cursor leads and the pane follows once the keys rest, as
    // in the Log. The card layouts have no row to highlight, so only N works there.
    var cursor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(paneSel) { if (paneSel != null) cursor = paneSel }
    LaunchedEffect(cursor, inPane) {
        val target = cursor
        if (inPane && target != null && target != paneSel) {
            delay(LogFilterUi.PANE_SETTLE_MS)
            onOpenRecipe(target)
        }
    }
    val current = if (inPane) cursor ?: paneSel else null
    TabKeys { e ->
        if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return@TabKeys false
        val ids = state.filtered.map { it.id }
        val recipe = current?.let { id -> state.filtered.firstOrNull { it.id == id } }
        val move: (Int) -> Boolean = { by ->
            val next = LogFilterUi.step(ids, current, by)
            if (next != null) {
                cursor = next
                val idx = PANE_FIRST_ROW + ids.indexOf(next)
                scope.launch {
                    val visible = paneList.layoutInfo.visibleItemsInfo
                    if (visible.none { it.index == idx } || visible.lastOrNull()?.index == idx) {
                        paneList.animateScrollToItem((idx - 2).coerceAtLeast(0))
                    }
                }
            }
            next != null
        }
        when (e.key) {
            Key.N -> true.also { onNewRecipe() }
            Key.J, Key.DirectionDown -> inPane && move(1)
            Key.K, Key.DirectionUp -> inPane && move(-1)
            Key.Enter, Key.NumPadEnter -> (recipe != null).also { if (it) onOpenRecipe(recipe!!.id) }
            Key.B -> (recipe != null).also { if (it) onBrew(recipe!!.id) }
            Key.E -> (recipe != null && RecipeListState.isMine(recipe)).also { if (it) onEdit(recipe!!.id) }
            Key.D -> (recipe != null).also { if (it) onDuplicate(recipe!!.id) }
            else -> false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        when (RecipeListLayouts.of(inPane, maxWidth.value)) {
            RecipeListLayout.Cards -> PhoneList(state, vm, onOpenRecipe, onNewRecipe)
            RecipeListLayout.Grid -> CardGrid(state, vm, onOpenRecipe, onNewRecipe)
            RecipeListLayout.PaneRows -> PaneList(state, vm, onOpenRecipe, onNewRecipe, paneList, current)
        }
    }
}

// ----------------------------------------------------------------------- phone

@Composable
private fun PhoneList(state: RecipeListState, vm: RecipesViewModel, onOpenRecipe: (String) -> Unit, onNewRecipe: () -> Unit) {
    val chipRow = rememberLazyListState()
    // "Mine" and "My brewers" arrive at the front of the row once there is something to offer.
    // A lazy row keeps its first visible chip in place, which would leave them off-screen.
    LaunchedEffect(state.hasOwned) { chipRow.requestScrollToItem(0) }

    LazyColumn(
        Modifier.fillMaxSize().testTag("recipe-list"),
        contentPadding = PaddingValues(bottom = listBottomPadding()),
    ) {
        item(key = "header", contentType = "header") { Header(state, onNewRecipe) }
        item(key = "search", contentType = "search") { Search(state, vm, Modifier.padding(horizontal = 16.dp)) }
        item(key = "brewers", contentType = "brewers") {
            // Touch phones keep the chips on one scrolling row.
            LazyRow(
                state = chipRow,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // These chips sit on the bare canvas, where a light surface2 pill disappears. "Mine"
                // is always offered: with nothing of yours it leads to the guided empty state.
                item(key = "mine", contentType = "chip") {
                    SelectableChip("Mine", state.mine, vm::toggleMine, Modifier.animateItem(placementSpec = Motion.offset()), onCanvas = true)
                }
                if (state.hasOwned) {
                    item(key = "my-brewers", contentType = "chip") {
                        SelectableChip("My brewers", state.myBrewers, { vm.toggleMyBrewers(state.myBrewers) }, Modifier.animateItem(placementSpec = Motion.offset()), onCanvas = true)
                    }
                }
                items(state.brewers, key = { it.id }, contentType = { "chip" }) { b ->
                    SelectableChip(
                        text = b.label,
                        selected = state.activeBrewer == b,
                        onClick = { vm.setBrewer(b) },
                        modifier = Modifier.animateItem(fadeInSpec = Motion.settle(), placementSpec = Motion.offset(), fadeOutSpec = Motion.quick()),
                        leadingIcon = { BrewerIcon(b, size = 16.dp) },
                        onCanvas = true,
                    )
                }
            }
        }
        // Whose recipe: the people left after the brewer filters above.
        if (state.authors.size > 1) {
            item(key = "authors", contentType = "authors") {
                LazyRow(
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.authors, key = { it }, contentType = { "chip" }) { name ->
                        SelectableChip(
                            text = name,
                            selected = state.activeAuthor == name,
                            onClick = { vm.setAuthor(name) },
                            modifier = Modifier.animateItem(fadeInSpec = Motion.settle(), placementSpec = Motion.offset(), fadeOutSpec = Motion.quick()),
                            onCanvas = true,
                        )
                    }
                }
            }
        }

        // Keyed so a filter change moves the survivors instead of teleporting them.
        val list = state.filtered
        if (state.mineEmpty) {
            item(key = "empty", contentType = "empty") {
                MineEmpty(vm, onNewRecipe, Modifier.animateItem(fadeInSpec = Motion.settle(), placementSpec = Motion.offset(), fadeOutSpec = Motion.quick()).padding(16.dp))
            }
        } else if (list.isEmpty() && state.all.isNotEmpty()) {
            item(key = "empty", contentType = "empty") {
                Empty(state, vm, Modifier.animateItem(fadeInSpec = Motion.settle(), placementSpec = Motion.offset(), fadeOutSpec = Motion.quick()).padding(16.dp))
            }
        } else {
            items(list, key = { it.id }, contentType = { "recipe-card" }) { recipe ->
                RecipeCard(
                    recipe = recipe,
                    onClick = { onOpenRecipe(recipe.id) },
                    modifier = Modifier
                        .animateItem(fadeInSpec = Motion.settle(), placementSpec = Motion.offset(), fadeOutSpec = Motion.quick())
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    sharedKeyPrefix = SharedKeys.FROM_RECIPES,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ grid

/** Medium windows: cards in an adaptive grid (320 dp and up), the chips wrapping above. */
@Composable
private fun CardGrid(state: RecipeListState, vm: RecipesViewModel, onOpenRecipe: (String) -> Unit, onNewRecipe: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(320.dp),
        modifier = Modifier.fillMaxSize().testTag("recipe-list"),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = listBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            Box(Modifier.bleed(16.dp)) { Header(state, onNewRecipe) }
        }
        item(key = "search", span = { GridItemSpan(maxLineSpan) }, contentType = "search") { Search(state, vm) }
        item(key = "chips", span = { GridItemSpan(maxLineSpan) }, contentType = "chips") { WrappedChips(state, vm) }
        val list = state.filtered
        if (state.mineEmpty) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") { MineEmpty(vm, onNewRecipe) }
        } else if (list.isEmpty() && state.all.isNotEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") { Empty(state, vm) }
        } else {
            items(list, key = { it.id }, contentType = { "recipe-card" }) { recipe ->
                RecipeCard(
                    recipe = recipe,
                    onClick = { onOpenRecipe(recipe.id) },
                    modifier = Modifier.animateItem(fadeInSpec = Motion.settle(), placementSpec = Motion.offset(), fadeOutSpec = Motion.quick()),
                    sharedKeyPrefix = SharedKeys.FROM_RECIPES,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------ pane

/**
 * The list beside a recipe (expanded and up): search, wrapped filters, a sort control, then
 * compact rows. The row the pane shows is lifted.
 */
@Composable
private fun PaneList(
    state: RecipeListState,
    vm: RecipesViewModel,
    onOpenRecipe: (String) -> Unit,
    onNewRecipe: () -> Unit,
    listState: LazyListState,
    selected: String?,
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("recipe-list"),
        state = listState,
        contentPadding = PaddingValues(bottom = listBottomPadding()),
    ) {
        item(key = "header", contentType = "header") { Header(state, onNewRecipe) }
        item(key = "search", contentType = "search") { Search(state, vm, Modifier.padding(horizontal = 16.dp)) }
        // No author chips in the narrow pane: thirty names would push the rows off screen, and
        // the search already finds people.
        item(key = "chips", contentType = "chips") { WrappedChips(state, vm, Modifier.padding(horizontal = 16.dp), authors = false) }
        item(key = "sort", contentType = "sort") { SortRow(state, vm, Modifier.padding(horizontal = 16.dp)) }
        val list = state.filtered
        if (state.mineEmpty) {
            item(key = "empty", contentType = "empty") { MineEmpty(vm, onNewRecipe, Modifier.padding(16.dp)) }
        } else if (list.isEmpty() && state.all.isNotEmpty()) {
            item(key = "empty", contentType = "empty") { Empty(state, vm, Modifier.padding(16.dp)) }
        } else {
            items(list, key = { it.id }, contentType = { "recipe-row" }) { recipe ->
                RecipeRow(
                    recipe = recipe,
                    selected = recipe.id == selected,
                    onClick = { onOpenRecipe(recipe.id) },
                    modifier = Modifier
                        .animateItem(fadeInSpec = Motion.settle(), placementSpec = Motion.offset(), fadeOutSpec = Motion.quick())
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** ~76dp: identity dot and name, the brewer, dose to water and time. */
@Composable
private fun RecipeRow(recipe: Recipe, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .focusRing(interaction, shape)
            .clip(shape)
            .background(if (selected) c.surface2 else Color.Transparent)
            .hoverTint(interaction, c.inkHigh.copy(alpha = 0.04f), shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrewerIcon(recipe.brewer, size = 22.dp, tint = c.inkMid)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The one place a recipe's own colour survives: an identity dot.
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(recipe.accent)))
                Spacer(Modifier.width(6.dp))
                Text(recipe.author, style = Micro, color = c.inkLow, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(recipe.name, style = MaterialTheme.typography.titleMedium, color = c.inkHigh, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${recipe.brewer.shortName} · ${grams(recipe.doseG)} g → ${grams(recipe.waterG)} g · ${formatDuration(recipe.totalSec)}",
                style = MaterialTheme.typography.bodySmall,
                color = c.inkMid,
            )
        }
    }
}

@Composable
private fun SortRow(state: RecipeListState, vm: RecipesViewModel, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 8.dp)) {
        EyebrowLabel("SORT")
        Spacer(Modifier.height(6.dp))
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RecipeSort.entries.forEach { s ->
                SelectableChip(text = s.label, selected = state.sort == s, onClick = { vm.setSort(s) }, onCanvas = true)
            }
        }
    }
}

// --------------------------------------------------------------------- shared

@Composable
private fun Header(state: RecipeListState, onNewRecipe: () -> Unit) {
    ScreenHeader(
        eyebrow = headerEyebrow(state.all.size, state.people),
        title = "Recipes",
        trailing = {
            IconWell(icon = Icons.Rounded.Add, contentDescription = "New recipe", onClick = onNewRecipe, size = 48.dp, shortcut = "N")
        },
    )
}

@Composable
private fun Search(state: RecipeListState, vm: RecipesViewModel, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    FieldWell(
        label = null,
        value = state.query,
        onChange = vm::setQuery,
        // "/" and Ctrl+F land here (v4 §2.7).
        modifier = modifier.listSearchTarget(),
        placeholder = "Search recipes, people, brewers",
        leadingIcon = Icons.Rounded.Search,
        trailing = if (state.query.isNotEmpty()) {
            { IconWell(icon = Icons.Rounded.Close, contentDescription = "Clear search", onClick = { vm.setQuery("") }, size = 36.dp, onCanvas = false, iconSize = 16.dp) }
        } else {
            null
        },
        onCanvas = true,
        height = 48.dp,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

/** The filter chips, wrapping onto more lines instead of scrolling sideways (pointer windows). */
@Composable
private fun WrappedChips(state: RecipeListState, vm: RecipesViewModel, modifier: Modifier = Modifier, authors: Boolean = true) {
    Column(modifier.padding(top = 12.dp, bottom = 4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip("All", !state.hasFilters, vm::clearFilters, onCanvas = true)
            SelectableChip("Mine", state.mine, vm::toggleMine, onCanvas = true)
            if (state.hasOwned) SelectableChip("My brewers", state.myBrewers, { vm.toggleMyBrewers(state.myBrewers) }, onCanvas = true)
            state.brewers.forEach { b ->
                SelectableChip(
                    text = b.label,
                    selected = state.activeBrewer == b,
                    onClick = { vm.setBrewer(b) },
                    leadingIcon = { BrewerIcon(b, size = 16.dp) },
                    onCanvas = true,
                )
            }
        }
        if (authors && state.authors.size > 1) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.authors.forEach { name ->
                    SelectableChip(text = name, selected = state.activeAuthor == name, onClick = { vm.setAuthor(name) }, onCanvas = true)
                }
            }
        }
    }
}

@Composable
private fun Empty(state: RecipeListState, vm: RecipesViewModel, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    Column(modifier) {
        Text(
            if (state.query.isNotBlank()) "Nothing matches “${state.query.trim()}”." else "Nothing matches that.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.inkMid,
        )
        Spacer(Modifier.height(4.dp))
        // Nudged left so the label, not the touch padding, lines up with the text.
        QuietButton(
            text = if (state.hasFilters) "Show every recipe" else "Clear search",
            onClick = vm::clearFilters,
            modifier = Modifier.offset(x = (-8).dp),
        )
    }
}

/**
 * "Mine" with nothing of yours yet (§4.12): where your own recipes will live, and the two ways to
 * get one. Duplicating starts from the full list, so it turns "Mine" off.
 */
@Composable
private fun MineEmpty(vm: RecipesViewModel, onNewRecipe: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("Your own recipes live here.", style = MaterialTheme.typography.bodyLarge, color = CoffeeTheme.colors.inkHigh)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(text = "Make your own", onClick = onNewRecipe, icon = Icons.Rounded.Add, onCanvas = true)
            QuietButton(text = "Duplicate one you like", onClick = vm::toggleMine)
        }
    }
}

/** "14 METHODS · 9 PEOPLE"; blank until Room answers so it never flashes "0 METHODS". */
private fun headerEyebrow(methods: Int, people: Int): String {
    if (methods == 0) return " "
    val m = if (methods == 1) "1 METHOD" else "$methods METHODS"
    val p = if (people == 1) "1 PERSON" else "$people PEOPLE"
    return "$m · $p"
}

/** Lets a ScreenHeader (which brings its own 16dp) sit in a grid whose gutter is already 24. */
private fun Modifier.bleed(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth + extra).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
        ),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}
