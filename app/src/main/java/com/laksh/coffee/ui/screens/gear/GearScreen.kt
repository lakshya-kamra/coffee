package com.laksh.coffee.ui.screens.gear

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.core.screenDate
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogGrinder
import com.laksh.coffee.ui.components.Cell
import com.laksh.coffee.ui.components.CoffeeConfirm
import com.laksh.coffee.ui.components.CoffeeMug
import com.laksh.coffee.ui.components.DataGrid
import com.laksh.coffee.ui.components.Dripper
import com.laksh.coffee.ui.components.EditorChrome
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.GrindRangeLine
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.LocalAnimationsActive
import com.laksh.coffee.ui.components.LocalToday
import com.laksh.coffee.ui.components.PillButton
import com.laksh.coffee.ui.components.PinnedScrim
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.QuietPanel
import com.laksh.coffee.ui.components.ScreenHeader
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SegmentedControl
import com.laksh.coffee.ui.components.SegmentedControlHeight
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.components.color
import com.laksh.coffee.ui.components.cupLabel
import com.laksh.coffee.ui.components.roastStatus
import com.laksh.coffee.ui.components.roastWord
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.nav.GearSection
import com.laksh.coffee.ui.nav.ListDetailLayout
import com.laksh.coffee.ui.nav.LocalGearPane
import com.laksh.coffee.ui.nav.TabKeys
import com.laksh.coffee.ui.nav.TabsHost
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.LocalWindowClass
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.WidthClass
import com.laksh.coffee.ui.theme.listBottomPadding
import kotlin.math.roundToInt

private val TABS = GearSection.entries.map { it.label }
private const val BEANS = 0
private const val GRINDERS = 1
private const val APP = 2
private val GEAR_TABS = listOf(BEANS, GRINDERS, APP)

private const val NEW = "new"

/** "bean:12" → 12, "bean:new" → 0; null for another kind of key. */
private fun idOf(key: String?, kind: String): Long? {
    if (key == null || !key.startsWith("$kind:")) return null
    val rest = key.removePrefix("$kind:")
    return if (rest == NEW) 0L else rest.toLongOrNull()
}

/**
 * Gear (v4 §3.10). Its section is Gear's own pane state at the app root, so the sidebar's
 * Beans / Grinders / Settings rows and rotation both keep it. The open bean or grinder is that
 * state's [com.laksh.coffee.ui.nav.GearPaneState.item]: an editor sheet on phones and medium
 * windows, the inline editor in the detail pane at expanded and up. Typed edits live in the
 * ViewModel, so they survive a rotation across either breakpoint.
 *
 * [onRunSetup] opens onboarding again.
 */
@Composable
fun GearScreen(
    onRunSetup: () -> Unit = {},
    vm: GearViewModel = viewModel(factory = GearViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wc = LocalWindowClass.current
    val wide = wc.usesPanes
    val pane = LocalGearPane.current
    val tab = pane.section.coerceIn(0, GEAR_TABS.lastIndex)
    val today = LocalToday.current.toEpochDay()
    var settingsSection by rememberSaveable { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf<String?>(InlineSelection.NONE) }
    var saveTick by remember { mutableIntStateOf(0) }

    // What each section shows when nothing is chosen (§2.3): the default bean or grinder.
    val defaultBean = state.beans.firstOrNull { it.id == state.settings.defaultBeanId && !it.isArchived }
        ?: state.beans.firstOrNull { !it.isArchived }
    val defaultGrinder = state.grinders.firstOrNull { it.isDefault } ?: state.grinders.firstOrNull()
    val chosenBean = idOf(pane.item, "bean")
    val chosenGrinder = idOf(pane.item, "grinder")
    val shownKey: String? = when (tab) {
        BEANS -> if (chosenBean != null) pane.item else if (wide) defaultBean?.let { "bean:${it.id}" } else null
        GRINDERS -> if (chosenGrinder != null) pane.item else if (wide) defaultGrinder?.let { "grinder:${it.id}" } else null
        else -> null
    }

    fun savedBean(key: String?): BeanEntity? = idOf(key, "bean")?.let { id ->
        if (id == 0L) BeanEntity(name = "") else state.beans.firstOrNull { it.id == id }
    }

    fun savedGrinder(key: String?): GrinderEntity? = idOf(key, "grinder")?.let { id ->
        if (id == 0L) GrinderEntity(brand = "", model = "", isDefault = state.grinders.isEmpty()) else state.grinders.firstOrNull { it.id == id }
    }

    fun dirty(key: String?): Boolean {
        if (key == null) return false
        val draft = vm.drafts[key] ?: return false
        return when (draft) {
            is BeanEntity -> savedBean(key)?.let { InlineEdit.dirty(it, draft) } ?: false
            is GrinderEntity -> savedGrinder(key)?.let { InlineEdit.dirty(it, draft) } ?: false
            else -> false
        }
    }

    // Opening something else with unsaved changes asks first (§2.4 discard confirm).
    val select: (String?) -> Unit = { key ->
        val s = InlineEdit.requestSelect(InlineSelection(shownKey), key, wide && dirty(shownKey))
        if (s.asking) asking = s.pending else pane.item = s.selected
    }
    val setTab: (Int) -> Unit = { t ->
        if (wide && dirty(shownKey)) {
            asking = "section:$t"
        } else {
            pane.section = t
        }
    }
    val onAdd: () -> Unit = {
        if (tab == BEANS) select("bean:$NEW") else if (tab == GRINDERS) select("grinder:$NEW")
    }

    TabKeys { e ->
        if (e.type != KeyEventType.KeyDown) return@TabKeys false
        when {
            (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.S -> (wide && shownKey != null).also { if (it) saveTick++ }
            !e.isCtrlPressed && !e.isMetaPressed && !e.isAltPressed && e.key == Key.N -> (tab != APP).also { if (it) onAdd() }
            else -> false
        }
    }

    val listContent: @Composable () -> Unit = {
        GearList(
            state = state,
            tab = tab,
            onTab = setTab,
            wide = wide,
            selectedKey = shownKey,
            today = today,
            onAdd = onAdd,
            onOpen = select,
            vm = vm,
            onRunSetup = onRunSetup,
            settingsSection = settingsSection,
            onSettingsSection = { settingsSection = it },
        )
    }

    // Back from the app root while a bean or grinder is chosen in the pane (§2.5): the same path as
    // choosing nothing, so unsaved edits still ask first.
    LaunchedEffect(pane.backRequested) {
        if (pane.backRequested) {
            pane.backRequested = false
            if (wide && pane.item != null) select(null)
        }
    }

    // The list always sits in the same place in the tree, so its scroll and state survive
    // crossing the expanded breakpoint (§2.10).
    ListDetailLayout(
        showDetail = wide,
        listWidth = wc.listPaneWidth,
        detailKey = if (tab == APP) "settings:$settingsSection" else (shownKey ?: "empty:$tab"),
        list = listContent,
    ) { key ->
        when {
            key.startsWith("settings:") -> SettingsPane(
                section = SettingsSection.entries[key.removePrefix("settings:").toInt()],
                vm = vm,
                state = state,
                onRunSetup = onRunSetup,
            )
            key.startsWith("bean:") -> savedBean(key)?.let { saved ->
                BeanPane(
                    key = key,
                    saved = saved,
                    vm = vm,
                    state = state,
                    saveTick = saveTick,
                    onSaved = { id -> pane.item = "bean:$id" },
                    onDeleted = { pane.item = null },
                )
            } ?: EmptyBeans(onAdd = { select("bean:$NEW") }, Modifier.statusBarsPadding().padding(Dimens.gutter).padding(top = 16.dp))
            key.startsWith("grinder:") -> savedGrinder(key)?.let { saved ->
                GrinderPane(
                    key = key,
                    saved = saved,
                    vm = vm,
                    state = state,
                    saveTick = saveTick,
                    onSaved = { id -> pane.item = "grinder:$id" },
                    onDeleted = { pane.item = null },
                )
            } ?: EmptyGrinders(state.settings.buysGround, onAdd = { select("grinder:$NEW") }, onGround = { vm.setBuysGround(true) }, Modifier.statusBarsPadding().padding(Dimens.gutter).padding(top = 16.dp))
            key == "empty:$BEANS" -> EmptyBeans(onAdd = { select("bean:$NEW") }, Modifier.statusBarsPadding().padding(Dimens.gutter).padding(top = 16.dp))
            else -> EmptyGrinders(state.settings.buysGround, onAdd = { select("grinder:$NEW") }, onGround = { vm.setBuysGround(true) }, Modifier.statusBarsPadding().padding(Dimens.gutter).padding(top = 16.dp))
        }
    }

    // A sheet is its own window, drawn over whatever is on screen, so it shows only while Gear is
    // the tab in view. Without this, rotating a tablet to portrait with a grinder open inline
    // popped "Edit grinder" over a running brew (or over Today). The draft waits in the VM.
    val inView = LocalAnimationsActive.current
    if (!wide && inView) {
        // Phones and medium windows edit in a sheet; its draft is the same one the pane uses.
        val key = pane.item
        savedBean(key)?.takeIf { tab == BEANS }?.let { saved ->
            key(key) {
                BeanEditorSheet(
                    initial = saved,
                    start = (vm.drafts[key!!] as? BeanEntity) ?: saved,
                    onDraft = { vm.drafts[key] = it },
                    onDismiss = {
                        vm.drafts.remove(key)
                        pane.item = null
                    },
                    onSave = {
                        vm.drafts.remove(key)
                        vm.saveBean(it)
                        pane.item = null
                    },
                    onDelete = if (saved.id != 0L) {
                        {
                            vm.drafts.remove(key)
                            vm.deleteBean(saved)
                            pane.item = null
                        }
                    } else null,
                )
            }
        }
        savedGrinder(key)?.takeIf { tab == GRINDERS }?.let { saved ->
            key(key) {
                GrinderEditorSheet(
                    initial = saved,
                    start = (vm.drafts[key!!] as? GrinderEntity) ?: saved,
                    onDraft = { vm.drafts[key] = it },
                    method = state.grinderMethod(saved.id) ?: BrewMethod.PourOver,
                    onDismiss = {
                        vm.drafts.remove(key)
                        pane.item = null
                    },
                    onSave = {
                        vm.drafts.remove(key)
                        vm.saveGrinder(it)
                        pane.item = null
                    },
                    onDelete = if (saved.id != 0L) {
                        {
                            vm.drafts.remove(key)
                            vm.deleteGrinder(saved)
                            pane.item = null
                        }
                    } else null,
                )
            }
        }
    }

    val target = asking
    if (target != InlineSelection.NONE) {
        val isGrinder = shownKey?.startsWith("grinder:") == true
        CoffeeConfirm(
            title = EditorChrome.DISCARD_TITLE,
            body = if (isGrinder) DISCARD_GRINDER else EditorChrome.DISCARD_COFFEE,
            confirmLabel = EditorChrome.DISCARD,
            dismissLabel = EditorChrome.KEEP_EDITING,
            onConfirm = {
                shownKey?.let { vm.drafts.remove(it) }
                asking = InlineSelection.NONE
                if (target != null && target.startsWith("section:")) pane.section = target.removePrefix("section:").toInt() else pane.item = target
            },
            onDismiss = { asking = InlineSelection.NONE },
        )
    }
}

/** The list side: header, the section control, and each section's list (cards, rows or settings). */
@Composable
private fun GearList(
    state: GearState,
    tab: Int,
    onTab: (Int) -> Unit,
    wide: Boolean,
    selectedKey: String?,
    today: Long,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    vm: GearViewModel,
    onRunSetup: () -> Unit,
    settingsSection: Int,
    onSettingsSection: (Int) -> Unit,
) {
    val wc = LocalWindowClass.current
    // Medium windows have the width for two cards side by side (§3.10 M).
    val columns = if (wc.width == WidthClass.Medium) 2 else 1
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            eyebrow = gearCountLine(state.activeBeanCount, state.grinders.size),
            title = "Gear",
            trailing = {
                AnimatedVisibility(
                    visible = tab != APP,
                    enter = fadeIn(Motion.decel(160)) + scaleIn(Motion.quick()),
                    exit = fadeOut(Motion.decel(120)) + scaleOut(Motion.quick()),
                    label = "gearAdd",
                ) {
                    IconWell(
                        icon = Icons.Rounded.Add,
                        contentDescription = if (tab == GRINDERS) "Add grinder" else "Add coffee",
                        onClick = onAdd,
                        shortcut = "N",
                    )
                }
            },
        )

        // The header and tabs stay pinned. Each tab scrolls from the top of this box, under
        // the tabs, and starts below them by its own padding. The tabs sit on the same scrim
        // as the status bar, so content passes under them and fades out softly instead of
        // being sliced square through its rounded corners at the tabs' edge.
        Box(Modifier.fillMaxSize()) {
            // Same fade-through host as the bottom bar: each list stays composed with its
            // scroll position, and a switch fades one page out before the next drifts in.
            TabsHost(
                tabs = GEAR_TABS,
                selected = tab,
                visible = true,
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                when (current) {
                    BEANS -> BeansTab(
                        state = state,
                        columns = columns,
                        rows = wide,
                        selectedKey = selectedKey,
                        today = today,
                        onOpen = { onOpen("bean:${it.id}") },
                        onAdd = onAdd,
                    )
                    GRINDERS -> GrindersTab(
                        state = state,
                        columns = columns,
                        rows = wide,
                        selectedKey = selectedKey,
                        onOpen = { onOpen("grinder:${it.id}") },
                        onAdd = onAdd,
                        onGround = { vm.setBuysGround(true) },
                        onUseCatalogScale = vm::useCatalogScale,
                        onKeepOwnScale = vm::keepOwnScale,
                    )
                    else -> if (wide) {
                        SettingsList(selected = settingsSection, onSelect = onSettingsSection, topPadding = TabContentTop)
                    } else {
                        GearAppTab(
                            vm = vm,
                            state = state,
                            topPadding = TabContentTop,
                            onRunSetup = onRunSetup,
                        )
                    }
                }
            }
            PinnedScrim(
                band = SegmentedControlHeight,
                fade = TabTopGap,
                modifier = Modifier.align(Alignment.TopCenter),
                // This scrim starts mid-screen, where the list's clipped edge must not show,
                // and text must not be cut at the pill's rounded edge: solid for the first
                // 8dp under it, clear by the 24dp where content rests.
                topAlpha = 1f,
                edgeAlpha = 1f,
                solidFraction = 1f / 3f,
            )
            SegmentedControl(
                options = TABS,
                selectedIndex = tab,
                onSelect = onTab,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = Dimens.gutter)
                    .fillMaxWidth(),
            )
        }
    }
}

private fun gearCountLine(coffees: Int, grinders: Int): String {
    val c = if (coffees == 1) "COFFEE" else "COFFEES"
    val g = if (grinders == 1) "GRINDER" else "GRINDERS"
    return "$coffees $c · $grinders $g"
}

/**
 * Room between the pinned tabs and a tab's first card; the scrim's fade lives in it. 24dp, not
 * 16: over 16 the fade was too short to soften a card cut off at the tabs' edge.
 */
private val TabTopGap = 24.dp

/** Where a tab's content starts at rest: below the tabs it scrolls under, plus the gap. */
private val TabContentTop = SegmentedControlHeight + TabTopGap

/** v4 §4.12: the art, the one sentence, the next step. */
@Composable
private fun EmptyBeans(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CoffeeTheme.colors
    SoftCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The art pieces declare a larger default size; this box caps them.
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                CoffeeMug(fill = 0.6f, body = colors.artShell, liquid = colors.artBed, crema = colors.artWater)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                "Roast date is the one field that pays for itself.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.inkHigh,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Add the coffee you're drinking", onAdd, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp), icon = Icons.Rounded.Add, height = Dimens.PrimaryInCard)
    }
}

@Composable
private fun EmptyGrinders(buysGround: Boolean, onAdd: () -> Unit, onGround: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CoffeeTheme.colors
    SoftCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Dripper(waterLevel = 0.5f, shell = colors.artShell, water = colors.artWater, bed = colors.artBed)
            }
            Spacer(Modifier.width(14.dp))
            Text(
                if (buysGround) "You're set: brews will use the coffee as bought." else "Add your grinder so grind settings make sense.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.inkHigh,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Add a grinder", onAdd, icon = Icons.Rounded.Add, height = Dimens.PrimaryInCard)
            if (!buysGround) SecondaryButton("I buy ground coffee", onGround, height = Dimens.PrimaryInCard)
        }
    }
}

// -------------------------------------------------------------------- beans tab

@Composable
private fun BeansTab(
    state: GearState,
    columns: Int,
    rows: Boolean,
    selectedKey: String?,
    today: Long,
    onOpen: (BeanEntity) -> Unit,
    onAdd: () -> Unit,
) {
    val gutter = Dimens.gutter
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = gutter, end = gutter, top = TabContentTop, bottom = listBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(if (rows) 8.dp else Dimens.CardGap),
        horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap),
    ) {
        if (state.beans.isEmpty()) {
            item(key = "empty", contentType = "empty", span = { GridItemSpan(maxLineSpan) }) {
                // Beside the pane the full empty state is in the detail; the list only says so.
                if (rows) QuietPanel("No coffees yet.", Modifier.animateItem()) else EmptyBeans(onAdd, Modifier.animateItem())
            }
        }

        items(state.beans, key = { it.id }, contentType = { if (rows) "bean-row" else "bean" }) { bean ->
            val m = Modifier
                .animateItem(
                    fadeInSpec = Motion.settle(),
                    placementSpec = Motion.offset(),
                    fadeOutSpec = Motion.quick(),
                )
                // A finished bag stays in the list for its history but steps back.
                .alpha(if (bean.isArchived) 0.55f else 1f)
            if (rows) {
                val days = bean.roastedOnEpochDay?.let { (today - it).coerceAtLeast(0L) }
                GearRow(
                    eyebrow = bean.roaster.ifBlank { "COFFEE" },
                    title = bean.name.ifBlank { "Unnamed" },
                    value = days?.let { "$it d" },
                    word = if (bean.isArchived) "Finished" else BeanWords.freshness(bean, today),
                    wordStatus = when (BeanWords.freshness(bean, today)) {
                        "Peak" -> Status.Good
                        null -> null
                        else -> Status.Attention
                    }.takeIf { !bean.isArchived },
                    selected = selectedKey == "bean:${bean.id}",
                    onClick = { onOpen(bean) },
                    modifier = m,
                )
            } else {
                val (brews, avg) = state.beanStats(bean.id)
                BeanCard(
                    bean = bean,
                    brews = brews,
                    avg = avg,
                    todayEpochDay = today,
                    onClick = { onOpen(bean) },
                    modifier = m,
                )
            }
        }
    }
}

/**
 * A compact row for the list beside the editor (§3.10 E+): an eyebrow, the name, and on the right
 * a value with its word in status colour. The open row is one shade up.
 */
@Composable
private fun GearRow(
    eyebrow: String,
    title: String,
    value: String?,
    word: String?,
    wordStatus: Status?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CoffeeTheme.colors
    SoftCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.selected = selected },
        onClick = onClick,
        color = if (selected) c.surface2 else c.surface1,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                EyebrowLabel(eyebrow)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.inkHigh,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (value != null || word != null) {
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (value != null) Text(value, style = MaterialTheme.typography.titleSmall, color = c.inkHigh, maxLines = 1)
                    if (word != null) {
                        Text(word.uppercase(), style = MaterialTheme.typography.labelSmall, color = wordStatus?.color() ?: c.inkMid, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun BeanCard(
    bean: BeanEntity,
    brews: Int,
    avg: Double?,
    todayEpochDay: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    val days = bean.roastedOnEpochDay?.let { (todayEpochDay - it).coerceAtLeast(0L) }
    // The roaster is the eyebrow; then where it grew, how it was processed and how dark it is.
    val meta = remember(bean) { BeanWords.meta(bean) }
    val resting = BeanWords.restDaysLeft(bean, todayEpochDay)

    SoftCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        // The grid's cells carry their own bottom padding.
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel(bean.roaster.ifBlank { "COFFEE" }, Modifier.weight(1f))
            if (bean.isArchived) StatusChip(status = Status.Neutral, text = "FINISHED")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            bean.name.ifBlank { "Unnamed" },
            style = MaterialTheme.typography.headlineSmall,
            color = colors.inkHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            meta.ifBlank { "No details yet" },
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkMid,
        )
        if (resting != null && !bean.isArchived) {
            Spacer(Modifier.height(8.dp))
            // Only when a rest was recorded: the app never guesses how long a coffee should rest.
            StatusLine(Status.Attention, "Resting", detail = BeanWords.restDetail(resting))
        }
        Spacer(Modifier.height(14.dp))
        HairLine()
        DataGrid(
            cells = listOf(
                Cell(
                    label = "OFF ROAST",
                    value = days?.toString(),
                    unit = "d",
                    // An unknown roast date is the gap worth nudging about.
                    sub = if (days == null) "add date" else roastWord(days),
                    status = roastStatus(days).takeIf { it == Status.Attention },
                ),
                Cell(label = "BREWS", value = "$brews"),
                // Every rated bag gets the same chip, its word in its status colour (GREAT CUP,
                // GOOD, OFF, FAULTY). A plain word for 6 to 7 beside a chip for 4 to 5 read as
                // two different kinds of field.
                Cell(
                    label = "AVG",
                    value = avg?.let(::oneDp),
                    status = avg?.let { scoreStatus(it.roundToInt()) },
                    sub = avg?.let { cupLabel(it.roundToInt()) },
                    subStatus = avg?.let { scoreStatus(it.roundToInt()) },
                ),
            ),
            columns = 3,
        )
    }
}

// ----------------------------------------------------------------- grinders tab

@Composable
private fun GrindersTab(
    state: GearState,
    columns: Int,
    rows: Boolean,
    selectedKey: String?,
    onOpen: (GrinderEntity) -> Unit,
    onAdd: () -> Unit,
    onGround: () -> Unit,
    onUseCatalogScale: (GrinderEntity) -> Unit,
    onKeepOwnScale: (GrinderEntity) -> Unit,
) {
    val gutter = Dimens.gutter
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = gutter, end = gutter, top = TabContentTop, bottom = listBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(if (rows) 8.dp else Dimens.CardGap),
        horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap),
    ) {
        if (state.grinders.isEmpty()) {
            item(key = "empty", contentType = "empty", span = { GridItemSpan(maxLineSpan) }) {
                if (rows) {
                    QuietPanel(if (state.settings.buysGround) "No grinder: brews use the coffee as bought." else "No grinders yet.", Modifier.animateItem())
                } else {
                    EmptyGrinders(state.settings.buysGround, onAdd, onGround, Modifier.animateItem())
                }
            }
        }

        items(state.grinders, key = { it.id }, contentType = { if (rows) "grinder-row" else "grinder" }) { g ->
            val m = Modifier.animateItem(
                fadeInSpec = Motion.settle(),
                placementSpec = Motion.offset(),
                fadeOutSpec = Motion.quick(),
            )
            if (rows) {
                GearRow(
                    eyebrow = g.brand.ifBlank { "GRINDER" },
                    title = g.model.ifBlank { "Unnamed" },
                    value = null,
                    word = if (g.settingUnit == "s") "Seconds" else g.settingStyle.label,
                    wordStatus = null,
                    selected = selectedKey == "grinder:${g.id}",
                    onClick = { onOpen(g) },
                    modifier = m,
                )
            } else {
                GrinderCard(
                    g = g,
                    brews = state.grinderBrews(g.id),
                    method = state.grinderMethod(g.id) ?: BrewMethod.PourOver,
                    scaleNotice = state.scaleNotice(g),
                    onClick = { onOpen(g) },
                    onUseCatalogScale = { onUseCatalogScale(g) },
                    onKeepOwnScale = { onKeepOwnScale(g) },
                    modifier = m,
                )
            }
        }
    }
}

// ------------------------------------------------------------ inline editors (E+)

/**
 * The pane header for an inline editor (§3.10): the name, then Revert and Save. Save also answers
 * Ctrl+S (the tab's key handler bumps [saveTick]).
 */
@Composable
private fun PaneEditorHeader(
    title: String,
    dirty: Boolean,
    canSave: Boolean,
    onRevert: () -> Unit,
    onSave: () -> Unit,
) {
    val c = CoffeeTheme.colors
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = Dimens.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = c.inkHigh,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).asHeading(),
            )
            if (dirty) {
                Text("Not saved", style = MaterialTheme.typography.labelMedium, color = c.attention, modifier = Modifier.padding(horizontal = 8.dp))
            }
            QuietButton("Revert", onRevert, enabled = dirty)
            Spacer(Modifier.width(8.dp))
            PrimaryButton("Save", onSave, height = 44.dp, enabled = dirty && canSave)
        }
        HairLine()
    }
}

@Composable
private fun BeanPane(
    key: String,
    saved: BeanEntity,
    vm: GearViewModel,
    state: GearState,
    saveTick: Int,
    onSaved: (Long) -> Unit,
    onDeleted: () -> Unit,
) {
    val c = CoffeeTheme.colors
    var draft by remember(key) { mutableStateOf((vm.drafts[key] as? BeanEntity) ?: saved) }
    // Bumped by Revert and Save, so the form's own text fields read the bean again.
    var revision by remember(key) { mutableIntStateOf(0) }
    val update: BeanUpdate = remember(key) {
        { change ->
            draft = change(draft)
            vm.drafts[key] = draft
        }
    }
    val dirty = InlineEdit.dirty(saved, draft)
    val canSave = InlineEdit.canSave(draft)
    var confirmDelete by remember { mutableStateOf(false) }
    val save: () -> Unit = {
        if (dirty && canSave) {
            vm.saveBeanThen(cleaned(draft)) { id ->
                vm.drafts.remove(key)
                revision++
                onSaved(id)
            }
        }
    }
    LaunchedEffect(saveTick) { if (saveTick > 0) save() }

    Column(Modifier.fillMaxSize()) {
        PaneEditorHeader(
            title = draft.name.ifBlank { if (saved.id == 0L) "New coffee" else "Unnamed" },
            dirty = dirty,
            canSave = canSave,
            onRevert = {
                draft = saved
                vm.drafts.remove(key)
                revision++
            },
            onSave = save,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.gutter)
                .widthIn(max = 720.dp)
                .padding(top = 16.dp, bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (saved.id != 0L) BrewedWithThisCoffee(saved, state)
            key(revision) {
                BeanEditorForm(bean = draft, savedId = saved.id, update = update)
            }
            if (saved.id != 0L) {
                QuietButton("Delete this coffee", { confirmDelete = true }, color = c.fault)
            }
        }
    }
    if (confirmDelete) {
        CoffeeConfirm(
            title = "Delete this coffee?",
            body = "Its brews stay in the log. To keep it listed but out of brew setup, mark it finished instead.",
            confirmLabel = "Delete",
            dismissLabel = "Keep it",
            onConfirm = {
                confirmDelete = false
                vm.drafts.remove(key)
                vm.deleteBean(saved)
                onDeleted()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * "Brewed with this coffee" (§3.10): how many brews, the average, the grind of the best one and
 * the last five, so the next cup starts from what worked.
 */
@Composable
private fun BrewedWithThisCoffee(bean: BeanEntity, state: GearState) {
    val c = CoffeeTheme.colors
    val logs = remember(state.logs, bean.id) { state.logs.filter { it.beanId == bean.id }.sortedByDescending { it.startedAt } }
    SoftCard(Modifier.fillMaxWidth()) {
        EyebrowLabel("BREWED WITH THIS COFFEE", Modifier.asHeading())
        if (logs.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("No brews with it yet.", style = MaterialTheme.typography.bodyMedium, color = c.inkMid)
            return@SoftCard
        }
        val rated = logs.filter { it.overall > 0 }
        val avg = rated.map { it.overall }.average().takeIf { !it.isNaN() }
        val best = rated.maxWithOrNull(compareBy<BrewLogEntity> { it.overall }.thenBy { it.startedAt })
        val bestGrind = best?.let { b -> b.grindClicks?.let { "${it} clicks" } ?: b.grindSetting.ifBlank { null } }
        DataGrid(
            cells = listOf(
                Cell(label = "BREWS", value = "${logs.size}"),
                Cell(
                    label = "AVG",
                    value = avg?.let(::oneDp),
                    status = avg?.let { scoreStatus(it.roundToInt()) },
                    sub = avg?.let { cupLabel(it.roundToInt()) },
                    subStatus = avg?.let { scoreStatus(it.roundToInt()) },
                ),
                Cell(label = "BEST GRIND", value = bestGrind, sub = best?.let { "scored ${it.overall}" }, textValue = true),
            ),
            columns = 3,
        )
        HairLine()
        Spacer(Modifier.height(8.dp))
        logs.take(5).forEach { l ->
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(screenDate(l.startedAt), style = MaterialTheme.typography.bodySmall, color = c.inkMid, modifier = Modifier.width(72.dp))
                Text(l.recipeName.ifBlank { "Brew" }, style = MaterialTheme.typography.bodyMedium, color = c.inkHigh, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    if (l.overall > 0) "${l.overall}" else "—",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (l.overall > 0) scoreStatus(l.overall).color() else c.inkLow,
                )
            }
        }
    }
}

@Composable
private fun GrinderPane(
    key: String,
    saved: GrinderEntity,
    vm: GearViewModel,
    state: GearState,
    saveTick: Int,
    onSaved: (Long) -> Unit,
    onDeleted: () -> Unit,
) {
    val c = CoffeeTheme.colors
    var draft by remember(key) { mutableStateOf((vm.drafts[key] as? GrinderEntity) ?: saved) }
    var revision by remember(key) { mutableIntStateOf(0) }
    val dirty = InlineEdit.dirty(saved, draft)
    val canSave = InlineEdit.canSave(draft)
    var confirmDelete by remember { mutableStateOf(false) }
    val save: () -> Unit = {
        if (dirty && canSave) {
            vm.saveGrinderThen(InlineEdit.tidy(draft)) { id ->
                vm.drafts.remove(key)
                revision++
                onSaved(id)
            }
        }
    }
    LaunchedEffect(saveTick) { if (saveTick > 0) save() }

    Column(Modifier.fillMaxSize()) {
        PaneEditorHeader(
            title = draft.display.ifBlank { if (saved.id == 0L) "New grinder" else "Unnamed" },
            dirty = dirty,
            canSave = canSave,
            onRevert = {
                draft = saved
                vm.drafts.remove(key)
                revision++
            },
            onSave = save,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.gutter)
                .widthIn(max = 720.dp)
                .padding(top = 16.dp, bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (saved.id != 0L && state.scaleNotice(saved)) {
                Catalog.grinder(saved.catalogId)?.let { entry ->
                    ScaleNotice(saved, entry, { vm.useCatalogScale(saved) }, { vm.keepOwnScale(saved) })
                }
            }
            key(revision) {
                GrinderEditorForm(
                    g = draft,
                    savedId = saved.id,
                    method = state.grinderMethod(saved.id) ?: BrewMethod.PourOver,
                    onChange = {
                        draft = it
                        vm.drafts[key] = it
                    },
                )
            }
            if (saved.id != 0L) {
                QuietButton("Delete this grinder", { confirmDelete = true }, color = c.fault)
            }
        }
    }
    if (confirmDelete) {
        CoffeeConfirm(
            title = "Delete this grinder?",
            body = "Brews made with it keep their grind settings as they were logged.",
            confirmLabel = "Delete",
            dismissLabel = "Keep it",
            onConfirm = {
                confirmDelete = false
                vm.drafts.remove(key)
                vm.deleteGrinder(saved)
                onDeleted()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun GrinderCard(
    g: GrinderEntity,
    brews: Int,
    method: BrewMethod,
    scaleNotice: Boolean,
    onClick: () -> Unit,
    onUseCatalogScale: () -> Unit,
    onKeepOwnScale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    val scale = remember(g) { g.scale() }
    val entry = remember(g.catalogId) { Catalog.grinder(g.catalogId) }
    val unit = scale.unit.ifBlank { null }
    // A blade grinder is timed, not dialled (v4 §4.2): one number, in seconds.
    val blade = g.settingUnit == "s"
    val meta = listOfNotNull(
        // "Clicks" would repeat the FILTER unit and the direction words, so only dials say it.
        if (blade) "timed in seconds" else g.settingStyle.label.takeIf { !g.usesClicks },
        if (blade) "longer is finer" else finerWords(g),
        when (brews) {
            0 -> "no brews yet"
            1 -> "1 brew"
            else -> "$brews brews"
        },
    ).joinToString(" · ")

    val cells: List<Cell> = if (blade) {
        listOf(Cell(label = "GRIND FOR", value = g.defaultFilterSetting?.roundToInt()?.toString(), unit = "s"))
    } else buildList {
        add(
            Cell(
                label = "FILTER",
                value = g.defaultFilterSetting?.let(scale::format),
                unit = unit,
                sub = dialWords(g, g.defaultFilterSetting),
            )
        )
        add(Cell(label = "PER TURN", value = g.clicksPerRotation.takeIf { it > 0 }?.toString()))
        add(
            Cell(
                label = "BURRS",
                value = g.burrSizeMm.trim().removeSuffix("mm").trim().ifBlank { null },
                unit = "mm",
                sub = g.burrType.takeIf { it != BurrType.Unknown }?.label,
            )
        )
        // The second row only earns its space when the manual's numbers were filled in.
        if (g.micronsPerClick != null || g.defaultEspressoSetting != null) {
            // Not "µm / CLICK": the label is upper-cased, and µ upper-cases to Greek Mu,
            // which reads as "MM". The unit stays lower-case, so it keeps the µ.
            add(Cell(label = "PER CLICK", value = g.micronsPerClick?.let(::oneDp), unit = "µm"))
            add(
                Cell(
                    label = "ESPRESSO",
                    value = g.defaultEspressoSetting?.let(scale::format),
                    unit = unit,
                    sub = dialWords(g, g.defaultEspressoSetting),
                )
            )
        }
    }

    SoftCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel(g.brand.ifBlank { "GRINDER" }, Modifier.weight(1f))
            if (g.isDefault) StatusChip(status = Status.Neutral, text = "DEFAULT")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            g.model.ifBlank { "Unnamed" },
            style = MaterialTheme.typography.headlineSmall,
            color = colors.inkHigh,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.inkMid)

        if (scaleNotice && entry != null) {
            Spacer(Modifier.height(12.dp))
            ScaleNotice(g, entry, onUseCatalogScale, onKeepOwnScale)
        }

        // Draws nothing when the list has no range for this grinder.
        GrindRangeLine(scale = scale, method = method, modifier = Modifier.padding(top = 12.dp))

        Spacer(Modifier.height(14.dp))
        HairLine()
        DataGrid(cells = cells, columns = 3)
    }
}

/**
 * The list and this row disagree about the scale (for a 1.x K-Ultra row: 10 clicks a turn against
 * the maker's 100). Nothing changes until one of the two buttons is tapped.
 */
@Composable
private fun ScaleNotice(
    g: GrinderEntity,
    entry: CatalogGrinder,
    onUse: () -> Unit,
    onKeep: () -> Unit,
) {
    StatusLine(Status.Attention, "Check the scale", detail = GrinderMapping.noticeWords(g, entry))
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(text = "Use these numbers", onClick = onUse)
        PillButton(text = "Keep mine", onClick = onKeep)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Your logged settings keep their numbers. 75 stays 75 — only how it's read out changes.",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        color = CoffeeTheme.colors.inkLow,
    )
}

/** Which way is finer, in the grinder's own terms. Always shown: every grind change depends on it. */
private fun finerWords(g: GrinderEntity): String = when {
    g.usesClicks && g.lowerIsFiner -> "fewer clicks is finer"
    g.usesClicks -> "more clicks is finer"
    g.lowerIsFiner -> "lower is finer"
    else -> "higher is finer"
}

/** A click count the way the dial is read: "dial 7.5" on a numbered dial, "6 turns + 6" on a plain one. */
private fun dialWords(g: GrinderEntity, clicks: Double?): String? {
    if (!g.usesClicks || clicks == null || clicks < 0) return null
    val scale = g.scale()
    val reading = scale.reading(clicks) ?: return null
    return if (scale.dial.showsDialNumber) "dial $reading" else reading
}
