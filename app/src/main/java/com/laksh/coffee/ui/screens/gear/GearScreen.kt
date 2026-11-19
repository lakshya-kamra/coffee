package com.laksh.coffee.ui.screens.gear

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogGrinder
import com.laksh.coffee.ui.components.Cell
import com.laksh.coffee.ui.components.CoffeeMug
import com.laksh.coffee.ui.components.DataGrid
import com.laksh.coffee.ui.components.Dripper
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.GrindRangeLine
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.LocalToday
import com.laksh.coffee.ui.components.PillButton
import com.laksh.coffee.ui.components.PinnedScrim
import com.laksh.coffee.ui.components.ScreenHeader
import com.laksh.coffee.ui.components.SegmentedControl
import com.laksh.coffee.ui.components.SegmentedControlHeight
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.cupLabel
import com.laksh.coffee.ui.components.roastStatus
import com.laksh.coffee.ui.components.roastWord
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.nav.TabsHost
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.listBottomPadding
import kotlin.math.roundToInt

private val TABS = listOf("Beans", "Grinders", "App")
private const val BEANS = 0
private const val GRINDERS = 1
private const val APP = 2
private val GEAR_TABS = listOf(BEANS, GRINDERS, APP)

/**
 * [onRunSetup] opens onboarding again. It is wired by the navigation package and does nothing
 * until then.
 */
@Composable
fun GearScreen(
    onRunSetup: () -> Unit = {},
    vm: GearViewModel = viewModel(factory = GearViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Saveable so coming back from another tab lands on the list you left.
    var tab by rememberSaveable { mutableIntStateOf(BEANS) }
    var editingBean by remember { mutableStateOf<BeanEntity?>(null) }
    var editingGrinder by remember { mutableStateOf<GrinderEntity?>(null) }

    // The one + opens whichever list is showing; the App tab has nothing to add.
    val onAdd: () -> Unit = {
        if (tab == BEANS) {
            editingBean = BeanEntity(name = "")
        } else if (tab == GRINDERS) {
            // The first grinder you add is the one brew setup should start from.
            editingGrinder = GrinderEntity(brand = "", model = "", isDefault = state.grinders.isEmpty())
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                            onEdit = { editingBean = it },
                        )
                        GRINDERS -> GrindersTab(
                            state = state,
                            onEdit = { editingGrinder = it },
                            onUseCatalogScale = vm::useCatalogScale,
                            onKeepOwnScale = vm::keepOwnScale,
                        )
                        else -> GearAppTab(
                            vm = vm,
                            state = state,
                            topPadding = TabContentTop,
                            onRunSetup = onRunSetup,
                        )
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
                    onSelect = { tab = it },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = Dimens.Gutter)
                        .fillMaxWidth(),
                )
            }
        }
    }

    editingBean?.let { bean ->
        BeanEditorSheet(
            initial = bean,
            onDismiss = { editingBean = null },
            onSave = { vm.saveBean(it); editingBean = null },
            onDelete = if (bean.id != 0L) {
                { vm.deleteBean(bean); editingBean = null }
            } else null,
        )
    }

    editingGrinder?.let { g ->
        GrinderEditorSheet(
            initial = g,
            method = state.grinderMethod(g.id) ?: BrewMethod.PourOver,
            onDismiss = { editingGrinder = null },
            onSave = { vm.saveGrinder(it); editingGrinder = null },
            onDelete = if (g.id != 0L) {
                { vm.deleteGrinder(g); editingGrinder = null }
            } else null,
        )
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

/**
 * Empty lists get the small illustration rather than a bare sentence, because this is the
 * first thing a new user sees here.
 */
@Composable
private fun EmptyGear(
    text: String,
    modifier: Modifier = Modifier,
    art: @Composable () -> Unit,
) {
    SoftCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The art pieces declare a larger default size; this box caps them.
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { art() }
            Spacer(Modifier.width(14.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = CoffeeTheme.colors.inkMid,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// -------------------------------------------------------------------- beans tab

@Composable
private fun BeansTab(
    state: GearState,
    onEdit: (BeanEntity) -> Unit,
) {
    val colors = CoffeeTheme.colors
    val today = LocalToday.current.toEpochDay()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.Gutter,
            end = Dimens.Gutter,
            top = TabContentTop,
            bottom = listBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
    ) {
        if (state.beans.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyGear(
                    text = "Add the bag you are drinking. Roast date is the one field that pays " +
                        "for itself, because most days you will not remember it.",
                    modifier = Modifier.animateItem(),
                ) {
                    CoffeeMug(
                        fill = 0.6f,
                        body = colors.artShell,
                        liquid = colors.artBed,
                        crema = colors.artWater,
                    )
                }
            }
        }

        items(state.beans, key = { it.id }, contentType = { "bean" }) { bean ->
            val (brews, avg) = state.beanStats(bean.id)
            BeanCard(
                bean = bean,
                brews = brews,
                avg = avg,
                todayEpochDay = today,
                onClick = { onEdit(bean) },
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = Motion.settle(),
                        placementSpec = Motion.offset(),
                        fadeOutSpec = Motion.quick(),
                    )
                    // A finished bag stays in the list for its history but steps back.
                    .alpha(if (bean.isArchived) 0.55f else 1f),
            )
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
    onEdit: (GrinderEntity) -> Unit,
    onUseCatalogScale: (GrinderEntity) -> Unit,
    onKeepOwnScale: (GrinderEntity) -> Unit,
) {
    val colors = CoffeeTheme.colors
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.Gutter,
            end = Dimens.Gutter,
            top = TabContentTop,
            bottom = listBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
    ) {
        if (state.grinders.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyGear(
                    text = "Grind setting is the variable you will change most often, so it is " +
                        "worth recording which grinder the number belongs to.",
                    modifier = Modifier.animateItem(),
                ) {
                    Dripper(
                        waterLevel = 0.5f,
                        shell = colors.artShell,
                        water = colors.artWater,
                        bed = colors.artBed,
                    )
                }
            }
        }

        items(state.grinders, key = { it.id }, contentType = { "grinder" }) { g ->
            GrinderCard(
                g = g,
                brews = state.grinderBrews(g.id),
                method = state.grinderMethod(g.id) ?: BrewMethod.PourOver,
                scaleNotice = state.scaleNotice(g),
                onClick = { onEdit(g) },
                onUseCatalogScale = { onUseCatalogScale(g) },
                onKeepOwnScale = { onKeepOwnScale(g) },
                modifier = Modifier.animateItem(
                    fadeInSpec = Motion.settle(),
                    placementSpec = Motion.offset(),
                    fadeOutSpec = Motion.quick(),
                ),
            )
        }
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
    val meta = listOfNotNull(
        // "Clicks" would repeat the FILTER unit and the direction words, so only dials say it.
        g.settingStyle.label.takeIf { !g.usesClicks },
        finerWords(g),
        when (brews) {
            0 -> "no brews yet"
            1 -> "1 brew"
            else -> "$brews brews"
        },
    ).joinToString(" · ")

    val cells: List<Cell> = buildList {
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
