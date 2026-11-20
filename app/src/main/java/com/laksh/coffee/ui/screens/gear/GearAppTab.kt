package com.laksh.coffee.ui.screens.gear

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.laksh.coffee.ui.components.RadioRow
import com.laksh.coffee.ui.components.SegmentedControl
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.onboarding.Onboarding
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.LocalWindowClass
import com.laksh.coffee.ui.theme.WidthClass
import com.laksh.coffee.ui.theme.listBottomPadding
import kotlinx.coroutines.launch

/** Gear › Settings sections (v4 §3.10), in reading order. The web twin is settingsSections.ts. */
enum class SettingsSection(val label: String) {
    You("You"),
    Units("Units and measuring"),
    Brewing("Brewing"),
    Coffee("Coffee and water"),
    Ai("AI analysis"),
    Data("Your data"),
    About("About"),
}

/**
 * Gear › Settings on phones and medium windows: one scroll with a sticky jump row of the section
 * names (a row that scrolls sideways on phones, wraps on medium).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GearAppTab(
    vm: GearViewModel,
    state: GearState,
    topPadding: Dp,
    onRunSetup: () -> Unit,
) {
    val colors = CoffeeTheme.colors
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val gutter = Dimens.gutter
    val wraps = LocalWindowClass.current.width != WidthClass.Compact
    // The section at the top of the scroll lights its chip; items: [jump row, sections…, end].
    val current by remember { derivedStateOf { (list.firstVisibleItemIndex - 1).coerceIn(0, SettingsSection.entries.lastIndex) } }

    // Below the pinned section control, so the jump row can stick at the top of this list.
    LazyColumn(
        Modifier.fillMaxSize().padding(top = topPadding - 16.dp),
        state = list,
        contentPadding = PaddingValues(bottom = listBottomPadding()),
    ) {
        stickyHeader(key = "jump", contentType = "jump") {
            val chips: @Composable () -> Unit = {
                SettingsSection.entries.forEach { s ->
                    SelectableChip(
                        s.label,
                        s.ordinal == current,
                        { scope.launch { list.animateScrollToItem(s.ordinal + 1) } },
                        onCanvas = true,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().background(colors.bg).padding(vertical = 8.dp)) {
                if (wraps) {
                    FlowRow(
                        Modifier.padding(horizontal = gutter),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { chips() }
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = gutter),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) { chips() }
                }
            }
        }
        SettingsSection.entries.forEach { s ->
            item(key = s.name, contentType = "section") {
                Column(Modifier.padding(horizontal = gutter)) {
                    SettingsSectionBody(s, vm, state, onRunSetup, showTitle = true)
                }
            }
        }
    }
}

/** The section names as a selectable list beside the open section (expanded and up). */
@Composable
internal fun SettingsList(selected: Int, onSelect: (Int) -> Unit, topPadding: Dp) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.gutter)
            .padding(top = topPadding, bottom = 32.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SettingsSection.entries.forEach { s ->
            RadioRow(title = s.label, selected = s.ordinal == selected, onClick = { onSelect(s.ordinal) })
        }
    }
}

/** One section in the detail pane, max 720 (§3.10). */
@Composable
internal fun SettingsPane(section: SettingsSection, vm: GearViewModel, state: GearState, onRunSetup: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = Dimens.gutter)
            .widthIn(max = 720.dp)
            .padding(top = 16.dp, bottom = 32.dp)
            .navigationBarsPadding(),
    ) {
        Text(
            section.label,
            style = MaterialTheme.typography.headlineMedium,
            color = CoffeeTheme.colors.inkHigh,
            modifier = Modifier.asHeading(),
        )
        SettingsSectionBody(section, vm, state, onRunSetup, showTitle = false)
    }
}

@Composable
private fun SettingsSectionBody(
    section: SettingsSection,
    vm: GearViewModel,
    state: GearState,
    onRunSetup: () -> Unit,
    showTitle: Boolean,
) {
    // AI and Your data open with their own eyebrows; saying it twice would be noise.
    if (showTitle && section != SettingsSection.Ai && section != SettingsSection.Data) {
        Spacer(Modifier.height(Dimens.SectionGap))
        EyebrowLabel(section.label, Modifier.asHeading())
        Spacer(Modifier.height(Dimens.EyebrowGap))
    } else {
        Spacer(Modifier.height(if (showTitle) Dimens.SectionGap else 16.dp))
    }
    when (section) {
        SettingsSection.You -> YouSection(vm, state, onRunSetup)
        SettingsSection.Units -> UnitsSection(vm, state)
        SettingsSection.Brewing -> BrewingSection(vm, state)
        SettingsSection.Coffee -> CoffeeWaterSection(vm, state)
        SettingsSection.Ai -> AiSettingsSection(Modifier.fillMaxWidth())
        SettingsSection.Data -> BackupSection(Modifier.fillMaxWidth())
        SettingsSection.About -> AboutSection()
    }
}

// ---------------------------------------------------------------------------- you

@Composable
private fun YouSection(vm: GearViewModel, state: GearState, onRunSetup: () -> Unit) {
    val s = state.settings
    // Text of its own: DataStore answers a frame late, and a field must never wait for it.
    var name by remember { mutableStateOf(s.name) }
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
                EyebrowLabel("HOW YOU MAKE COFFEE")
                Spacer(Modifier.height(6.dp))
                Column(Modifier.selectableGroup()) {
                    Onboarding.EXPERIENCE_CHOICES.forEach { (id, title, body) ->
                        RadioRow(title = title, subtitle = body, selected = s.experience == id, onClick = { vm.setExperience(id) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                SheetNote("Only sets how much the app explains. Each setting below stays your own.")
            }
            Column {
                EyebrowLabel("APPEARANCE")
                Spacer(Modifier.height(6.dp))
                val modes = listOf("system", "light", "dark")
                SegmentedControl(
                    options = listOf("Auto", "Light", "Dark"),
                    selectedIndex = modes.indexOf(s.darkMode).coerceAtLeast(0),
                    onSelect = { vm.setDarkMode(modes[it]) },
                    modifier = Modifier.fillMaxWidth(),
                    onCard = true,
                )
            }
            QuietButton(
                text = "Run setup again",
                onClick = onRunSetup,
                modifier = Modifier.offset(x = (-8).dp),
            )
        }
    }
}

// ------------------------------------------------------------- units and measuring

@Composable
private fun UnitsSection(vm: GearViewModel, state: GearState) {
    val s = state.settings
    SoftCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ToggleRow(
                label = "I have a kitchen scale",
                checked = s.hasScale,
                onChange = vm::setHasScale,
                subtitle = "Off: amounts show in tablespoons and ml.",
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
        }
    }
}

// ------------------------------------------------------------------------ brewing

@Composable
private fun BrewingSection(vm: GearViewModel, state: GearState) {
    val s = state.settings
    SoftCard(Modifier.fillMaxWidth()) {
        Column {
            EyebrowLabel("REVIEW AFTER BREWING")
            Spacer(Modifier.height(6.dp))
            SegmentedControl(
                options = listOf("Quick", "Detailed"),
                selectedIndex = if (s.reviewMode == "detailed") 1 else 0,
                onSelect = { vm.setReviewMode(if (it == 1) "detailed" else "quick") },
                modifier = Modifier.fillMaxWidth(),
                onCard = true,
            )
            Spacer(Modifier.height(6.dp))
            SheetNote(
                if (s.reviewMode == "detailed") "Every section open: taste, flow, the coffee bed."
                else "A score and four quick toggles. The rest is one tap away under More detail.",
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    SoftCard(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
    ) {
        ToggleRow(
            label = "Sound on each step",
            checked = s.soundCues,
            onChange = vm::setSound,
            subtitle = "A short tone when it's time for the next step. M toggles it while brewing.",
        )
        HairLine()
        ToggleRow(
            label = "Buzz on each step",
            checked = s.haptics,
            onChange = vm::setHaptics,
        )
        HairLine()
        ToggleRow(
            label = "Keep the screen awake",
            checked = s.keepScreenOn,
            onChange = vm::setKeepScreenOn,
        )
    }
}

// ----------------------------------------------------------------- coffee and water

@Composable
private fun CoffeeWaterSection(vm: GearViewModel, state: GearState) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    val s = state.settings
    var pickingBrewers by remember { mutableStateOf(false) }
    var waterOpen by rememberSaveable { mutableStateOf(false) }
    val owned = remember(s.ownedBrewerIds, lists.brewerTitles) {
        s.ownedBrewerIds.mapNotNull { lists.brewerTitles[it] }.sorted()
    }
    val water = lists.water.firstOrNull { it.id == s.defaultWaterId }

    SoftCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ToggleRow(
                label = "I buy ground coffee",
                checked = s.buysGround,
                onChange = vm::setBuysGround,
                subtitle = "Brew setup starts from the coffee as bought, with no grind setting.",
            )
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

// --------------------------------------------------------------------------- about

@Composable
private fun AboutSection() {
    SoftCard(Modifier.fillMaxWidth()) {
        Text(
            "Every recipe says where its numbers came from. Some are transcribed exactly " +
                "from a published method; others are marked ADAPTED, meaning the technique " +
                "is the author's but the exact figures are a sensible starting point rather " +
                "than a quote. Recipes you write or copy are marked CUSTOM: those numbers " +
                "are yours, and editing a built-in recipe keeps your version until you " +
                "reset it to the original.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = CoffeeTheme.colors.inkMid,
        )
    }
}
