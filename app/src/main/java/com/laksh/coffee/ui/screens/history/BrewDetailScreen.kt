package com.laksh.coffee.ui.screens.history

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Replay
import com.laksh.coffee.core.formatDuration
import com.laksh.coffee.core.screenDate
import com.laksh.coffee.core.screenTime
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.MethodLabels
import com.laksh.coffee.ui.components.CoffeeConfirm
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.brewerFromLabel
import com.laksh.coffee.ui.theme.Dimens
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.grams
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.core.ratio
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Channeling
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.Sludge
import com.laksh.coffee.domain.ai.DataQuality
import com.laksh.coffee.domain.ai.Fields
import com.laksh.coffee.ui.ai.BrewAnalysisSection
import com.laksh.coffee.ui.ai.rememberBrewVerdict
import com.laksh.coffee.ui.components.BedShapeIcon
import com.laksh.coffee.ui.components.Cell
import com.laksh.coffee.ui.components.DataGrid
import com.laksh.coffee.ui.components.DetailTopBar
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.JourneyLine
import com.laksh.coffee.ui.components.JourneyNode
import com.laksh.coffee.ui.components.JourneyStyle
import com.laksh.coffee.ui.components.NodeKind
import com.laksh.coffee.ui.components.NumberWithUnit
import com.laksh.coffee.ui.components.PerforatedDivider
import com.laksh.coffee.ui.components.QuietPanel
import com.laksh.coffee.ui.components.RouteMode
import com.laksh.coffee.ui.components.RouteRow
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.TimelineCard
import com.laksh.coffee.ui.components.TimelineRow
import com.laksh.coffee.ui.components.TumblingBeans
import com.laksh.coffee.ui.components.beanShortName
import com.laksh.coffee.ui.components.bedStatus
import com.laksh.coffee.ui.components.channelingStatus
import com.laksh.coffee.ui.components.color
import com.laksh.coffee.ui.components.copyText
import com.laksh.coffee.ui.components.cupLabel
import com.laksh.coffee.ui.components.journeyNodes
import com.laksh.coffee.ui.components.lastPourEndSec
import com.laksh.coffee.ui.components.plannedTimelineRows
import com.laksh.coffee.ui.components.pourNodes
import com.laksh.coffee.ui.components.roastStatus
import com.laksh.coffee.ui.components.roastWord
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.components.shareText
import com.laksh.coffee.ui.components.signedClock
import com.laksh.coffee.ui.components.sludgeStatus
import com.laksh.coffee.ui.components.timeStatus
import com.laksh.coffee.ui.photos.BrewDetailPhotos
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.Units
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One brew. [onBrewAgain] gets the recipe id and this brew (v4 §4.10); [onCompare] gets the brew
 * picked in "Compare with…" (§3.8). In a pane at least 880 wide it reads in two columns.
 */
@Composable
fun BrewDetailScreen(
    logId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onBrewAgain: (String, Long) -> Unit = { _, _ -> },
    onCompare: (Long) -> Unit = {},
    vm: BrewDetailViewModel = viewModel(factory = BrewDetailViewModel.factory(logId)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        // The safe answer takes focus first, so Enter never deletes by accident.
        CoffeeConfirm(
            title = "Delete this brew?",
            body = "It will not be in the log or in anything you export after this. Any analysis of it goes too.",
            confirmLabel = "Delete",
            dismissLabel = "Keep it",
            onConfirm = {
                confirmDelete = false
                vm.delete(onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    // Keyed on the log id so the loader fades into the page once and later edits update in place.
    AnimatedContent(
        targetState = state.log,
        contentKey = { it?.id },
        transitionSpec = { fadeIn(Motion.decel(220)) togetherWith fadeOut(Motion.decel(160)) },
        label = "detailLoad",
    ) { l ->
        when {
            l != null -> BrewDetailContent(
                log = l,
                state = state,
                vm = vm,
                onBack = onBack,
                onEdit = onEdit,
                onDelete = { confirmDelete = true },
                onBrewAgain = onBrewAgain,
                onCompare = onCompare,
            )
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TumblingBeans() }
            else -> Column(Modifier.fillMaxSize()) {
                DetailTopBar(onBack = onBack)
                Spacer(Modifier.height(8.dp))
                QuietPanel(
                    "This brew is no longer in the log.",
                    Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun BrewDetailContent(
    log: BrewLogEntity,
    state: BrewDetailState,
    vm: BrewDetailViewModel,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: () -> Unit,
    onBrewAgain: (String, Long) -> Unit,
    onCompare: (Long) -> Unit,
) {
    val c = CoffeeTheme.colors
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val recipe = state.recipe
    val grinder = state.grinder
    val bean = state.bean
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // Hoisted so both the strip and the analysis card's thumbnails open the same viewer.
    var openPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    val gutter = Dimens.gutter
    val canBrewAgain = log.recipeId.isNotBlank()
    val brewAgain = { onBrewAgain(log.recipeId, log.id) }

    // Fields the local log check distrusts. Their numbers stay visible but are marked, and nothing
    // derived from them (the bed-exposed node and row) is drawn as if it were true.
    val distrusted = remember(log, recipe, grinder, bean) {
        DataQuality.distrustedFields(
            DataQuality.check(log = log, recipe = recipe, grinder = grinder, bean = bean),
            log.id,
        )
    }
    val ts = remember(log.totalTimeSec, recipe) { timeStatus(log.totalTimeSec, recipe?.totalSec ?: 0) }
    val timeTrusted = Fields.TIME !in distrusted && log.totalTimeSec > 0
    val bedExposedSec = remember(recipe, log.drawdownSec, distrusted) {
        if (Fields.DRAWDOWN in distrusted) null
        else recipe?.lastPourEndSec()?.let { end -> log.drawdownSec?.let { end + it } }
    }

    // Where the analysis card starts, written at layout and read only when the status line is tapped.
    val analysisY = remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Lands the card just below the status-bar scrim rather than under its fade.
    val topInset = WindowInsets.statusBars.getTop(density) + with(density) { 16.dp.roundToPx() }
    val scrollToAnalysis: () -> Unit = {
        scope.launch { scroll.animateScrollTo((analysisY.intValue - topInset).coerceAtLeast(0)) }
    }

    val header: @Composable () -> Unit = {
        DetailTopBar(
            onBack = onBack,
            actions = {
                if (canBrewAgain) {
                    IconWell(Icons.Rounded.Replay, "Brew again", brewAgain, shortcut = "B")
                    Spacer(Modifier.width(8.dp))
                }
                IconWell(Icons.Rounded.Edit, "Edit brew", { onEdit(log.id) })
                Spacer(Modifier.width(8.dp))
                Box {
                    IconWell(Icons.Rounded.MoreHoriz, "More", { menu = true })
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = c.surface1,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        border = BorderStroke(Dp.Hairline, c.hairline),
                    ) {
                        DropdownMenuItem(
                            text = { Text("Compare with…", color = c.inkHigh) },
                            onClick = {
                                menu = false
                                picking = true
                            },
                            leadingIcon = { Icon(Icons.Rounded.CompareArrows, null, tint = c.inkMid) },
                        )
                        DropdownMenuItem(
                            text = { Text("Share report", color = c.inkHigh) },
                            onClick = {
                                menu = false
                                shareText(context, vm.report(), "Brew log — ${log.recipeName}")
                            },
                            leadingIcon = { Icon(Icons.Rounded.Share, null, tint = c.inkMid) },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy report", color = c.inkHigh) },
                            onClick = {
                                menu = false
                                copyText(context, vm.report())
                            },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = c.inkMid) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete brew", color = c.fault) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.fault) },
                        )
                    }
                }
            },
        )
    }

    val pass: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            BoardingPass(
                log = log,
                recipe = recipe,
                grinder = grinder,
                ts = ts,
                distrusted = distrusted,
            )

            // The answer before the details: the verdict when there is one, else how the timing went.
            Spacer(Modifier.height(10.dp))
            val verdict = rememberBrewVerdict(log.id)
            when {
                verdict != null -> StatusLine(
                    status = verdict.status,
                    lead = verdict.lead,
                    detail = verdict.detail,
                    live = verdict.live,
                    onClick = scrollToAnalysis,
                )
                Fields.TIME in distrusted && log.totalTimeSec > 0 -> StatusLine(
                    status = Status.Attention,
                    lead = "Time looks wrong",
                    detail = "left out of the analysis",
                    onClick = scrollToAnalysis,
                )
                recipe == null && log.totalTimeSec > 0 -> StatusLine(
                    status = Status.Neutral,
                    lead = "Finished in ${clock(log.totalTimeSec)}",
                    detail = bedWords(log.bedShape),
                    onClick = scrollToAnalysis,
                )
                else -> StatusLine(
                    status = ts.first,
                    lead = ts.second,
                    detail = listOfNotNull(
                        recipe?.takeIf { log.totalTimeSec > 0 }
                            ?.let { "${clock(log.totalTimeSec)} against ${clock(it.totalSec)}" },
                        bedWords(log.bedShape),
                    ).joinToString(" · ").ifBlank { null },
                    onClick = scrollToAnalysis,
                )
            }
        }
    }

    val analysis: @Composable (Modifier) -> Unit = { m ->
        BrewAnalysisSection(
            logId = log.id,
            onEditLog = { onEdit(log.id) },
            onOpenPhoto = { openPhoto = it.uid },
            modifier = m.onGloballyPositioned { analysisY.intValue = it.positionInParent().y.roundToInt() },
        )
    }

    val coffeeAndGear: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            SectionLabel("COFFEE & GEAR")
            SoftCard(Modifier.fillMaxWidth()) {
                val days = log.daysOffRoast
                DataGrid(
                    cells = listOf(
                        Cell(
                            label = "COFFEE",
                            value = bean?.name?.ifBlank { null } ?: beanShortName(log.beanLabel).ifBlank { null },
                            sub = bean?.roaster?.ifBlank { null },
                            textValue = true,
                        ),
                        Cell(
                            label = "OFF ROAST",
                            value = days?.toString(),
                            unit = "d",
                            sub = roastWord(days),
                            status = roastStatus(days).takeIf { it == Status.Attention },
                        ),
                        Cell(
                            label = "GRINDER",
                            value = grinder?.model?.ifBlank { null } ?: log.grinderLabel.ifBlank { null },
                            sub = grinder?.brand?.ifBlank { null },
                            textValue = true,
                        ),
                        Cell(label = "BREWER", value = log.brewer.ifBlank { null }, textValue = true),
                    ),
                    columns = 2,
                )
            }
        }
    }

    val taste: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            SectionLabel("TASTE")
            SoftCard(Modifier.fillMaxWidth()) {
                if (Fields.TASTE in distrusted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(Status.Attention, "Ignored")
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Sliders never moved from 5",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.inkMid,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                TasteStrip(log.taste)
                if (log.descriptors.isNotEmpty() || log.faults.isNotEmpty()) Spacer(Modifier.height(14.dp))
                if (log.descriptors.isNotEmpty()) {
                    Text(
                        log.descriptors.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.inkHigh,
                    )
                }
                if (log.faults.isNotEmpty()) {
                    Text(
                        log.faults.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.fault,
                    )
                }
                if (log.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    val rule = c.inkFaint
                    Text(
                        log.notes,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = c.inkMid,
                        modifier = Modifier
                            .drawBehind { drawRect(rule, size = Size(2.dp.toPx(), size.height)) }
                            .padding(start = 10.dp),
                    )
                }
                if (log.nextTime.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("NEXT TIME", style = Micro, color = c.inkLow)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        log.nextTime,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = c.inkHigh,
                    )
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two columns once the pane (or page) has room for a 340 supporting column (§3.8 L/XL).
        val twoColumns = maxWidth >= 880.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            header()
            if (twoColumns) {
                Row(
                    Modifier
                        .padding(horizontal = gutter)
                        .widthIn(max = 1080.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        pass(Modifier.fillMaxWidth())
                        TheBrew(log = log, recipe = recipe, ts = ts, timeTrusted = timeTrusted, bedExposedSec = bedExposedSec, gutter = 0.dp)
                        taste(Modifier.fillMaxWidth())
                        coffeeAndGear(Modifier.fillMaxWidth())
                        TheBed(log, gutter = 0.dp)
                    }
                    Column(Modifier.width(340.dp)) {
                        if (canBrewAgain) {
                            PrimaryButton(
                                "Brew again",
                                brewAgain,
                                modifier = Modifier.fillMaxWidth(),
                                icon = Icons.Rounded.Replay,
                                height = Dimens.PrimaryInCard,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Same coffee, grind and dose",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.inkLow,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                        BrewDetailPhotos(brewLogId = log.id, openUid = openPhoto, onOpen = { openPhoto = it }, gutter = 0.dp)
                        Spacer(Modifier.height(20.dp))
                        analysis(Modifier.fillMaxWidth())
                    }
                }
            } else {
                pass(Modifier.padding(horizontal = gutter).widthIn(max = 760.dp))
                // No section label: the card's own eyebrow says ANALYSIS, and saying it twice was noise.
                Spacer(Modifier.height(20.dp))
                analysis(Modifier.padding(horizontal = gutter).widthIn(max = 760.dp))
                Column(Modifier.widthIn(max = 760.dp + gutter * 2)) {
                    TheBrew(log = log, recipe = recipe, ts = ts, timeTrusted = timeTrusted, bedExposedSec = bedExposedSec, gutter = gutter)
                    coffeeAndGear(Modifier.padding(horizontal = gutter))
                    TheBed(log, gutter = gutter)
                    BrewDetailPhotos(brewLogId = log.id, openUid = openPhoto, onOpen = { openPhoto = it }, gutter = gutter)
                    taste(Modifier.padding(horizontal = gutter))
                    if (canBrewAgain) {
                        Spacer(Modifier.height(20.dp))
                        SecondaryButton(
                            "Brew again",
                            brewAgain,
                            modifier = Modifier.padding(horizontal = gutter).fillMaxWidth().widthIn(max = 420.dp),
                            icon = Icons.Rounded.Replay,
                            onCanvas = true,
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (picking) {
        CompareWithPicker(
            base = log,
            logs = rememberAllLogs(),
            current = null,
            onPick = {
                picking = false
                onCompare(it)
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The boarding pass: when, what, how it scored, the dose-to-water route, then a perforation and
 * the numbers that describe the brew. Values the log check distrusts are marked IGNORED.
 */
@Composable
private fun BoardingPass(
    log: BrewLogEntity,
    recipe: Recipe?,
    grinder: GrinderEntity?,
    ts: Pair<Status, String>,
    distrusted: Set<String>,
    modifier: Modifier = Modifier,
) {
    val c = CoffeeTheme.colors
    val nodes = remember(recipe) { recipe?.pourNodes() ?: emptyList() }
    val units = LocalUnits.current
    val labels = remember(log.brewerId, log.brewer, recipe) {
        MethodLabels.of(recipe?.brewer?.method ?: (Brewers.byId(log.brewerId) ?: brewerFromLabel(log.brewer))?.method ?: BrewMethod.PourOver)
    }
    val cells = remember(log, recipe, grinder, ts, distrusted, units, labels) {
        passCells(log, recipe, grinder, ts, distrusted, units, labels)
    }

    SoftCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The phone's own date and clock style (v4 §4.15); exports keep the pinned format.
                EyebrowLabel("${screenDate(log.startedAt)} · ${screenTime(log.startedAt)}", Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                StatusChip(scoreStatus(log.overall), cupLabel(log.overall))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        log.recipeName.ifBlank { "Brew" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = c.inkHigh,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (log.recipeAuthor.isNotBlank()) {
                        Text(
                            log.recipeAuthor,
                            style = MaterialTheme.typography.bodySmall,
                            color = c.inkMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                val rated = log.overall > 0
                NumberWithUnit(
                    value = if (rated) "${log.overall}" else "—",
                    unit = if (rated) "/10" else null,
                    style = Num.Display,
                    // Unrated is text, so inkLow rather than inkFaint.
                    color = if (rated) scoreStatus(log.overall).color() else c.inkLow,
                )
            }
            Spacer(Modifier.height(16.dp))
            val ratioLabel = ratio(log.doseG, log.waterG)
            RouteRow(
                from = grams(log.doseG),
                fromUnit = "g",
                fromLabel = "COFFEE",
                to = grams(log.waterG),
                toUnit = "g",
                // "YIELD" for a shot, "WATER IN THE BASE" for a moka pot (v4 §4.9).
                toLabel = labels.water,
                nodes = nodes,
                midLabel = if (Fields.RATIO in distrusted) "$ratioLabel · ignored" else ratioLabel,
                style = Num.Large,
                mode = RouteMode.Done,
            )
        }
        PerforatedDivider()
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            DataGrid(cells = cells, columns = 3)
        }
    }
}

/** THE BREW: the trip drawn as a line, then the planned steps with what actually happened in time order. */
@Composable
private fun TheBrew(
    log: BrewLogEntity,
    recipe: Recipe?,
    ts: Pair<Status, String>,
    timeTrusted: Boolean,
    bedExposedSec: Int?,
    gutter: Dp = 16.dp,
) {
    val rows = remember(log, recipe, ts, timeTrusted, bedExposedSec) {
        brewTimeline(log, recipe, ts.first, timeTrusted, bedExposedSec)
    }
    if (recipe == null && rows.isEmpty()) return

    SectionLabel("THE BREW", Modifier.padding(horizontal = gutter))
    SoftCard(Modifier.padding(horizontal = gutter).fillMaxWidth()) {
        if (recipe != null) {
            val total = recipe.totalSec.coerceAtLeast(1)
            val actualEnd = if (timeTrusted) log.totalTimeSec / total.toFloat() else null
            val maxX = maxOf(1f, actualEnd ?: 1f)
            val nodes = remember(recipe, bedExposedSec, maxX) {
                val bed = bedExposedSec?.let {
                    // Neutral, not green: the node marks an event, and colour here would claim a judgement.
                    JourneyNode(
                        fraction = it / total.toFloat(),
                        label = "BED ${clock(it)}",
                        kind = NodeKind.Actual,
                        status = Status.Neutral,
                        priority = 1,
                    )
                }
                // Anything outside the drawn range would land past the canvas edge, so it is dropped.
                (recipe.journeyNodes() + listOfNotNull(bed)).filter { it.fraction in 0f..maxX }
            }
            JourneyLine(
                nodes = nodes,
                progress = { 1f },
                style = JourneyStyle.Historical,
                startLabel = "0:00",
                endLabel = clock(recipe.totalSec),
                actualEnd = actualEnd,
                endStatus = if (timeTrusted) ts.first else null,
                // The planned-versus-actual fact, under the cap it belongs to: "2:49 · −0:41".
                actualLabel = if (timeTrusted) {
                    val delta = if (ts.first == Status.Good) "on time" else signedClock(log.totalTimeSec - recipe.totalSec)
                    "${clock(log.totalTimeSec)} · $delta"
                } else {
                    null
                },
            )
            if (rows.isNotEmpty()) Spacer(Modifier.height(8.dp))
        }
        // The whole instruction: a finished brew is where you check what the method said.
        if (rows.isNotEmpty()) TimelineCard(rows, detailMaxLines = Int.MAX_VALUE)
    }
}

/** THE BED, when anything about it was recorded. The shape is drawn, never typed. */
@Composable
private fun TheBed(log: BrewLogEntity, gutter: Dp = 16.dp) {
    val recorded = log.bedShape != BedShape.Unset || log.sludge != Sludge.Unset ||
        log.channeling != Channeling.Unset || log.bedNotes.isNotBlank()
    if (!recorded) return
    val c = CoffeeTheme.colors

    SectionLabel("THE BED", Modifier.padding(horizontal = gutter))
    SoftCard(Modifier.padding(horizontal = gutter).fillMaxWidth()) {
        var needsGap = false
        if (log.bedShape != BedShape.Unset) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BedShapeIcon(
                    log.bedShape,
                    size = 48.dp,
                    tint = bedStatus(log.bedShape)?.color() ?: c.inkHigh,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(log.bedShape.label, style = MaterialTheme.typography.titleMedium, color = c.inkHigh)
                    Text(log.bedShape.reading, style = MaterialTheme.typography.bodySmall, color = c.inkMid)
                }
            }
            needsGap = true
        }
        if (log.sludge != Sludge.Unset || log.channeling != Channeling.Unset) {
            if (needsGap) {
                Spacer(Modifier.height(12.dp))
                HairLine()
            }
            DataGrid(
                cells = listOf(
                    Cell(
                        label = "SURFACE",
                        value = log.sludge.label.takeIf { log.sludge != Sludge.Unset },
                        status = sludgeStatus(log.sludge),
                        textValue = true,
                    ),
                    Cell(
                        label = "CHANNELLING",
                        value = log.channeling.label.takeIf { log.channeling != Channeling.Unset },
                        status = channelingStatus(log.channeling),
                        textValue = true,
                    ),
                ),
                columns = 2,
            )
            needsGap = true
        }
        if (log.bedNotes.isNotBlank()) {
            if (needsGap) Spacer(Modifier.height(12.dp))
            Text(
                log.bedNotes,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = c.inkHigh,
            )
        }
    }
}

private fun bedWords(shape: BedShape): String? =
    shape.takeIf { it != BedShape.Unset }?.label?.lowercase()

/** A distrusted value keeps its number but reads as questionable: attention colour and an IGNORED chip. */
private fun Cell.ignoredIf(ignored: Boolean): Cell =
    if (ignored) copy(status = Status.Attention, sub = "IGNORED", subStatus = Status.Attention) else this

private fun passCells(
    log: BrewLogEntity,
    recipe: Recipe?,
    grinder: GrinderEntity?,
    ts: Pair<Status, String>,
    distrusted: Set<String>,
    units: Units,
    labels: MethodLabels,
): List<Cell> {
    val clicks = log.grindClicks
    // The way the dial reads it: "dial 7.5" on a numbered ring, "6 turns + 6" on a plain one.
    val scale = grinder?.scale()
    val grindSub = if (clicks == null || scale == null) {
        null
    } else {
        scale.reading(clicks.toDouble())?.let { if (scale.dial.showsDialNumber) "dial $it" else it }
    }
    // A blade grinder is timed in seconds; pre-ground coffee has no setting at all (v4 §4.2).
    val blade = log.grindKind == "blade" && log.grindValue != null
    val grind = Cell(
        label = "GRIND",
        value = when {
            log.grindKind == "preground" -> "As bought"
            clicks != null -> clicks.toString()
            blade -> log.grindValue!!.roundToInt().toString()
            else -> log.grindSetting.trim().ifEmpty { null }
        },
        unit = when {
            log.grindKind == "preground" -> null
            clicks != null -> unitWord(clicks, "click")
            blade -> "s"
            else -> null
        },
        sub = grindSub,
        textValue = clicks == null && !blade,
    ).ignoredIf(Fields.GRIND in distrusted)

    val time = Cell(
        label = labels.time,
        // Cold brew reads in hours, not "720:00".
        value = log.totalTimeSec.takeIf { it > 0 }?.let { if (labels.longTimes) formatDuration(it) else clock(it) },
        sub = recipe?.let { "plan ${if (labels.longTimes) formatDuration(it.totalSec) else clock(it.totalSec)}" },
        status = ts.first,
    ).ignoredIf(Fields.TIME in distrusted)

    val temp = Cell(label = "TEMP", value = log.tempC.takeIf { it > 0 }?.let(units::temp), unit = units.tempUnit)
        .ignoredIf(Fields.TEMP in distrusted)

    val drawdown = Cell(label = "DRAWDOWN", value = log.drawdownSec?.let(::clock))
        .ignoredIf(Fields.DRAWDOWN in distrusted)

    val inCup = Cell(label = "IN CUP", value = log.beverageG?.let(::grams), unit = "g")
        .ignoredIf(Fields.BEVERAGE in distrusted)

    val sixth = log.extractionYield
        ?.let { Cell(label = "EXTRACTION", value = oneDp(it), unit = "%").ignoredIf(Fields.EY in distrusted) }
        ?: Cell(
            label = "OFF ROAST",
            value = log.daysOffRoast?.toString(),
            unit = "d",
            status = roastStatus(log.daysOffRoast).takeIf { it == Status.Attention },
        )

    return listOf(grind, time, temp, drawdown, inCup, sixth)
}

/**
 * Planned steps with the real events placed among them in time order. The actual finish sits on
 * the planned 'Done' row, so planned and actual read on one line with the delta chip. A distrusted
 * drawdown or time adds no row: nothing is drawn as if it happened when the log says otherwise.
 */
private fun brewTimeline(
    log: BrewLogEntity,
    recipe: Recipe?,
    finishStatus: Status,
    timeTrusted: Boolean,
    bedExposedSec: Int?,
): List<TimelineRow> {
    if (recipe == null) {
        if (!timeTrusted) return emptyList()
        return listOf(
            TimelineRow(
                time = clock(log.totalTimeSec),
                kind = "ACTUAL",
                title = "Finished",
                actual = true,
                terminal = true,
            ),
        )
    }

    val planned = recipe.plannedTimelineRows()
    val steps = planned.dropLast(1)
    val plannedDone = planned.lastOrNull()
    val bedRow = bedExposedSec?.let { sec ->
        TimelineRow(
            time = clock(sec),
            kind = "ACTUAL",
            title = "Bed exposed",
            detail = log.drawdownSec?.let { "${clock(it)} after the last pour" },
            actual = true,
        )
    }

    val out = mutableListOf<TimelineRow>()
    var bedPlaced = false
    // plannedTimelineRows maps one row per step in order, so row i starts at steps[i].startSec.
    steps.forEachIndexed { i, row ->
        val startSec = recipe.steps.getOrNull(i)?.startSec ?: 0
        if (bedRow != null && !bedPlaced && startSec > bedExposedSec) {
            out += bedRow
            bedPlaced = true
        }
        out += row
    }
    if (bedRow != null && !bedPlaced) out += bedRow

    if (timeTrusted) {
        out += TimelineRow(
            time = clock(log.totalTimeSec),
            kind = "ACTUAL",
            title = "Finished",
            detail = "planned ${clock(recipe.totalSec)}",
            actual = true,
            delta = signedClock(log.totalTimeSec - recipe.totalSec),
            deltaStatus = finishStatus,
            terminal = true,
        )
    } else if (plannedDone != null) {
        out += plannedDone
    }
    return out
}
