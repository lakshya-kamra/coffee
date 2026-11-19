package com.laksh.coffee.ui.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.domain.catalog.CatalogKind
import com.laksh.coffee.domain.catalog.SearchDoc
import com.laksh.coffee.ui.components.CoffeeSheet
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.Motion
import kotlinx.coroutines.launch

/** What a picker hands back: a catalog entry, or the text the person typed as their own. */
sealed interface CatalogPick {
    data class Entry(val id: String) : CatalogPick
    data class Custom(val text: String) : CatalogPick
}

/**
 * The one list picker: grinders, roasters, origins, varietals and brewers all open this sheet.
 * A pinned search field, group headers that stick while you browse, a ranked flat list while you
 * search, and (when [allowCustom]) a last row that takes what you typed as your own entry.
 *
 * Single-select: [onPick] fires and the sheet closes. Multi-select: rows toggle, "Done" hands the
 * set to [onDone]; a custom row still goes through [onPick] and the sheet stays open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogPickerSheet(
    kind: CatalogKind,
    title: String,
    multiSelect: Boolean = false,
    selected: Set<String> = emptySet(),
    allowCustom: Boolean = true,
    customLabel: (String) -> String = { "Use “$it”" },
    onPick: (CatalogPick) -> Unit,
    onDone: (Set<String>) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Slide the sheet away first; removing it from composition would cut it off in one frame.
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    CoffeeSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetTitle(title)
        Spacer(Modifier.height(8.dp))
        CatalogPickerContent(
            kind = kind,
            // A fixed share of the screen, so the sheet does not jump as results come and go.
            modifier = Modifier.fillMaxHeight(0.9f).imePadding(),
            multiSelect = multiSelect,
            selected = selected,
            allowCustom = allowCustom,
            customLabel = customLabel,
            // Long lists open ready to type; short ones are quicker to scan than to search.
            autoFocus = kind == CatalogKind.Grinder || kind == CatalogKind.Roaster,
            onPick = { pick ->
                if (multiSelect) onPick(pick) else dismissThen { onPick(pick); onDismiss() }
            },
            onDone = { ids -> dismissThen { onDone(ids); onDismiss() } },
        )
    }
}

/**
 * The picker without its sheet, so onboarding can show the same list inline. Give it a bounded
 * height: the list takes whatever is left under the search field.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogPickerContent(
    kind: CatalogKind,
    modifier: Modifier = Modifier,
    multiSelect: Boolean = false,
    selected: Set<String> = emptySet(),
    allowCustom: Boolean = true,
    customLabel: (String) -> String = { "Use “$it”" },
    autoFocus: Boolean = false,
    onCanvas: Boolean = false,
    onPick: (CatalogPick) -> Unit,
    onDone: (Set<String>) -> Unit = {},
) {
    val vm: CatalogPickerViewModel = viewModel(
        // Keyed: the store may hold a picker for another list at the same time.
        key = "catalog-picker-${kind.name}",
        factory = CatalogPickerViewModel.factory(kind),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = CoffeeTheme.colors
    val focus = LocalFocusManager.current
    var text by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf(selected) }
    val listState = rememberLazyListState()
    val requester = remember { FocusRequester() }
    val rowFill = if (onCanvas) colors.bg else colors.surface1

    // The ViewModel outlives one opening of the sheet; every opening starts from the full list.
    LaunchedEffect(Unit) {
        vm.setQuery("")
        if (autoFocus) runCatching { requester.requestFocus() }
    }
    // A new answer starts at its best match, not wherever the last list was scrolled to.
    LaunchedEffect(state.query) { listState.scrollToItem(0) }

    val typed = text.trim()
    // Offer "use what I typed" only once the rows answer this text, and never for an exact title.
    val showCustom = allowCustom && typed.isNotEmpty() && state.query == typed &&
        state.rows.none { it is PickerRow.Item && it.doc.title.equals(typed, ignoreCase = true) }

    Column(modifier) {
        FieldWell(
            label = null,
            value = text,
            onChange = {
                text = it
                vm.setQuery(it)
            },
            modifier = Modifier.focusRequester(requester),
            placeholder = searchPlaceholder(kind),
            leadingIcon = Icons.Rounded.Search,
            trailing = if (text.isNotEmpty()) {
                {
                    IconWell(
                        icon = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        onClick = {
                            text = ""
                            vm.setQuery("")
                        },
                        size = 32.dp,
                        onCanvas = onCanvas,
                        iconSize = 16.dp,
                    )
                }
            } else {
                null
            },
            onCanvas = onCanvas,
            height = 48.dp,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (!state.loading && state.matches == 0 && typed.isNotEmpty() && state.query == typed) {
                item(key = "empty", contentType = "empty") {
                    Text(
                        if (allowCustom) "Nothing called “$typed”. Use it as your own entry." else "Nothing called “$typed”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMid,
                        modifier = Modifier.animateItem().padding(vertical = 12.dp),
                    )
                }
            }
            state.rows.forEach { row ->
                when (row) {
                    is PickerRow.Header -> stickyHeader(key = row.key, contentType = "header") {
                        // Opaque, or rows would show through the header as they pass under it.
                        Box(Modifier.fillMaxWidth().background(rowFill).padding(top = 12.dp, bottom = 6.dp)) {
                            EyebrowLabel(row.title)
                        }
                    }
                    is PickerRow.Item -> item(key = row.key, contentType = "row") {
                        PickerRowView(
                            doc = row.doc,
                            selected = row.doc.id in chosen,
                            onClick = {
                                if (multiSelect) {
                                    chosen = if (row.doc.id in chosen) chosen - row.doc.id else chosen + row.doc.id
                                } else {
                                    focus.clearFocus()
                                    onPick(CatalogPick.Entry(row.doc.id))
                                }
                            },
                            modifier = Modifier.animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            ),
                        )
                    }
                }
            }
            if (showCustom) {
                item(key = "custom", contentType = "custom") {
                    CustomRow(
                        text = customLabel(typed),
                        onClick = {
                            focus.clearFocus()
                            onPick(CatalogPick.Custom(typed))
                            if (multiSelect) {
                                text = ""
                                vm.setQuery("")
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        if (multiSelect) {
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = if (chosen.isEmpty()) "Done" else "Done · ${chosen.size} chosen",
                onClick = {
                    focus.clearFocus()
                    onDone(chosen)
                },
                modifier = Modifier.fillMaxWidth(),
                height = Dimens.PrimaryInCard,
            )
        }
    }
}

private fun searchPlaceholder(kind: CatalogKind): String = when (kind) {
    CatalogKind.Grinder -> "Search grinders"
    CatalogKind.Roaster -> "Search roasters or cities"
    CatalogKind.Origin -> "Search countries"
    CatalogKind.Varietal -> "Search varietals"
    CatalogKind.Brewer -> "Search brewers"
}

@Composable
private fun PickerRowView(
    doc: SearchDoc,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                doc.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (doc.subtitle.isNotBlank()) {
                Text(
                    doc.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            // A drawn check and the word, so "chosen" is never colour alone.
            Icon(Icons.Rounded.Check, contentDescription = "Chosen", tint = colors.inkHigh, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CustomRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.inkMid, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = colors.inkHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
