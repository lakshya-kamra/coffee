package com.laksh.coffee.ui.screens.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.ui.components.BrewerIcon
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.RecipeCard
import com.laksh.coffee.ui.components.ScreenHeader
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.nav.SharedKeys
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.listBottomPadding

/** [onNewRecipe] opens the recipe builder; wired by the navigation package. */
@Composable
fun RecipeListScreen(
    onOpenRecipe: (String) -> Unit,
    onNewRecipe: () -> Unit = {},
    vm: RecipesViewModel = viewModel(factory = RecipesViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = CoffeeTheme.colors
    val focus = LocalFocusManager.current
    val chipRow = rememberLazyListState()
    // "Mine" and "My brewers" arrive at the front of the row once there is something to offer.
    // A lazy row keeps its first visible chip in place, which would leave them off-screen.
    LaunchedEffect(state.hasMine, state.hasOwned) { chipRow.requestScrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("recipe-list"),
            contentPadding = PaddingValues(bottom = listBottomPadding()),
        ) {
            item(key = "header", contentType = "header") {
                ScreenHeader(
                    eyebrow = headerEyebrow(state.all.size, state.people),
                    title = "Recipes",
                    trailing = {
                        IconWell(
                            icon = Icons.Rounded.Add,
                            contentDescription = "New recipe",
                            onClick = onNewRecipe,
                        )
                    },
                )
            }

            item(key = "search", contentType = "search") {
                FieldWell(
                    label = null,
                    value = state.query,
                    onChange = vm::setQuery,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    placeholder = "Search recipes or people",
                    leadingIcon = Icons.Rounded.Search,
                    trailing = if (state.query.isNotEmpty()) {
                        {
                            IconWell(
                                icon = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                onClick = { vm.setQuery("") },
                                size = 32.dp,
                                onCanvas = false,
                                iconSize = 16.dp,
                            )
                        }
                    } else {
                        null
                    },
                    onCanvas = true,
                    height = 48.dp,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
            }

            item(key = "brewers", contentType = "brewers") {
                LazyRow(
                    state = chipRow,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // These chips sit on the bare canvas, where a light surface2 pill disappears.
                    if (state.hasMine) {
                        item(key = "mine", contentType = "chip") {
                            SelectableChip(
                                text = "Mine",
                                selected = state.mine,
                                onClick = vm::toggleMine,
                                modifier = Modifier.animateItem(placementSpec = Motion.offset()),
                                onCanvas = true,
                            )
                        }
                    }
                    if (state.hasOwned) {
                        item(key = "my-brewers", contentType = "chip") {
                            SelectableChip(
                                text = "My brewers",
                                selected = state.myBrewers,
                                onClick = { vm.toggleMyBrewers(state.myBrewers) },
                                modifier = Modifier.animateItem(placementSpec = Motion.offset()),
                                onCanvas = true,
                            )
                        }
                    }
                    items(state.brewers, key = { it.id }, contentType = { "chip" }) { b ->
                        SelectableChip(
                            text = b.label,
                            selected = state.activeBrewer == b,
                            onClick = { vm.setBrewer(b) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            ),
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
                                modifier = Modifier.animateItem(
                                    fadeInSpec = Motion.settle(),
                                    placementSpec = Motion.offset(),
                                    fadeOutSpec = Motion.quick(),
                                ),
                                onCanvas = true,
                            )
                        }
                    }
                }
            }

            // Keyed so a filter change moves the survivors instead of teleporting them.
            val list = state.filtered
            if (list.isEmpty() && state.all.isNotEmpty()) {
                item(key = "empty", contentType = "empty") {
                    Column(
                        Modifier
                            .animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            )
                            .padding(16.dp),
                    ) {
                        Text(
                            if (state.query.isNotBlank()) {
                                "Nothing matches “${state.query.trim()}”."
                            } else {
                                "Nothing matches that."
                            },
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
            } else {
                items(list, key = { it.id }, contentType = { "recipe-card" }) { recipe ->
                    RecipeCard(
                        recipe = recipe,
                        onClick = { onOpenRecipe(recipe.id) },
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            )
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        sharedKeyPrefix = SharedKeys.FROM_RECIPES,
                    )
                }
            }
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
