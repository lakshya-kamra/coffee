package com.laksh.coffee.ui.screens.gear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laksh.coffee.domain.catalog.CatalogKind
import com.laksh.coffee.ui.ai.AiSettingsSection
import com.laksh.coffee.ui.backup.BackupSection
import com.laksh.coffee.ui.catalog.CatalogPickerSheet
import com.laksh.coffee.ui.catalog.ChipGroup
import com.laksh.coffee.ui.catalog.Expandable
import com.laksh.coffee.ui.catalog.PickerField
import com.laksh.coffee.ui.catalog.SheetNote
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SegmentedControl
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.listBottomPadding

/** Gear › App: who you are and how you measure, AI analysis, appearance, brewing, and the small print. */
@Composable
internal fun GearAppTab(
    vm: GearViewModel,
    state: GearState,
    topPadding: Dp,
    onRunSetup: () -> Unit,
) {
    val colors = CoffeeTheme.colors
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = Dimens.Gutter),
    ) {
        Spacer(Modifier.height(topPadding))
        YouSection(vm = vm, state = state, onRunSetup = onRunSetup)

        // AiSettingsSection opens with its own "AI ANALYSIS" eyebrow, so no SectionLabel
        // above it; a second one would say the same words twice.
        Spacer(Modifier.height(Dimens.SectionGap))
        AiSettingsSection(Modifier.fillMaxWidth())

        SectionLabel("APPEARANCE")
        SoftCard(Modifier.fillMaxWidth()) {
            val modes = listOf("system", "light", "dark")
            SegmentedControl(
                options = listOf("Auto", "Light", "Dark"),
                selectedIndex = modes.indexOf(state.settings.darkMode).coerceAtLeast(0),
                onSelect = { vm.setDarkMode(modes[it]) },
                modifier = Modifier.fillMaxWidth(),
                onCard = true,
            )
        }

        SectionLabel("WHILE BREWING")
        SoftCard(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        ) {
            ToggleRow(
                label = "Buzz on each step",
                checked = state.settings.haptics,
                onChange = vm::setHaptics,
            )
            HairLine()
            ToggleRow(
                label = "Keep the screen awake",
                checked = state.settings.keepScreenOn,
                onChange = vm::setKeepScreenOn,
            )
        }

        // BackupSection opens with its own "YOUR DATA" label.
        BackupSection(Modifier.fillMaxWidth())

        SectionLabel("ABOUT THE RECIPES")
        SoftCard(Modifier.fillMaxWidth()) {
            Text(
                "Every recipe says where its numbers came from. Some are transcribed exactly " +
                    "from a published method; others are marked ADAPTED, meaning the technique " +
                    "is the author's but the exact figures are a sensible starting point rather " +
                    "than a quote. Recipes you write or copy are marked CUSTOM: those numbers " +
                    "are yours, and editing a built-in recipe keeps your version until you " +
                    "reset it to the original.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.inkMid,
            )
        }

        Spacer(Modifier.height(listBottomPadding()))
    }
}

// ---------------------------------------------------------------------------- you

@Composable
private fun YouSection(vm: GearViewModel, state: GearState, onRunSetup: () -> Unit) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    val s = state.settings
    // Text of its own: DataStore answers a frame late, and a field must never wait for it.
    var name by remember { mutableStateOf(s.name) }
    var pickingBrewers by remember { mutableStateOf(false) }
    var waterOpen by rememberSaveable { mutableStateOf(false) }
    val owned = remember(s.ownedBrewerIds, lists.brewerTitles) {
        s.ownedBrewerIds.mapNotNull { lists.brewerTitles[it] }.sorted()
    }
    val water = lists.water.firstOrNull { it.id == s.defaultWaterId }

    EyebrowLabel("YOU")
    Spacer(Modifier.height(Dimens.EyebrowGap))
    SoftCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FieldWell(
                label = "YOUR NAME",
                value = name,
                onChange = { v ->
                    name = v.take(40)
                    vm.setName(name.trim())
                },
                placeholder = "Goes on recipes you write",
            )
            Column {
                EyebrowLabel("TEMPERATURE")
                Spacer(Modifier.height(6.dp))
                SegmentedControl(
                    options = listOf("°C", "°F"),
                    selectedIndex = if (s.tempUnit == "f") 1 else 0,
                    onSelect = { vm.setTempUnit(if (it == 1) "f" else "c") },
                    modifier = Modifier.fillMaxWidth(),
                    onCard = true,
                )
            }
            Column {
                EyebrowLabel("WEIGHT")
                Spacer(Modifier.height(6.dp))
                SegmentedControl(
                    options = listOf("Grams", "Ounces"),
                    selectedIndex = if (s.weightUnit == "oz") 1 else 0,
                    onSelect = { vm.setWeightUnit(if (it == 1) "oz" else "g") },
                    modifier = Modifier.fillMaxWidth(),
                    onCard = true,
                )
                Spacer(Modifier.height(6.dp))
                SheetNote("Brew targets stay in grams — that's what coffee scales read. Bag sizes and totals follow your choice.")
            }
            if (lists.areas.isNotEmpty()) {
                ChipGroup(
                    label = "WHERE YOU BUY COFFEE",
                    note = "Roasters near you come first when you pick one.",
                ) {
                    lists.areas.forEach { a ->
                        SelectableChip(a, s.homeArea == a, { vm.setHomeArea(if (s.homeArea == a) "" else a) })
                    }
                }
            }
            PickerField(
                label = "BREWERS YOU OWN",
                value = owned.joinToString(", "),
                onClick = { pickingBrewers = true },
                placeholder = "Pick your brewers",
                helper = "Recipes for these come first.",
            )
            if (lists.water.isNotEmpty()) {
                PickerField(
                    label = "YOUR WATER",
                    value = water?.label.orEmpty(),
                    onClick = { waterOpen = !waterOpen },
                    placeholder = "What you brew with",
                    helper = water?.description,
                    onClear = { vm.setWater("") },
                )
                Expandable(visible = waterOpen) {
                    ChipGroup(label = null) {
                        lists.water.forEach { w ->
                            SelectableChip(w.label, s.defaultWaterId == w.id, {
                                vm.setWater(if (s.defaultWaterId == w.id) "" else w.id)
                            })
                        }
                    }
                }
            }
            QuietButton(
                text = "Run setup again",
                onClick = onRunSetup,
                modifier = Modifier.offset(x = (-8).dp),
            )
        }
    }

    if (pickingBrewers) {
        CatalogPickerSheet(
            kind = CatalogKind.Brewer,
            title = "Brewers you own",
            multiSelect = true,
            selected = s.ownedBrewerIds,
            allowCustom = false,
            onPick = {},
            onDone = { vm.setOwnedBrewers(it) },
            onDismiss = { pickingBrewers = false },
        )
    }
}
