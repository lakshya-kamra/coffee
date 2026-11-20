package com.laksh.coffee.ui.screens.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.clipWords
import com.laksh.coffee.core.formatDuration
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.core.ratio
import com.laksh.coffee.core.screenDate
import com.laksh.coffee.core.screenTime
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.LogFilter
import com.laksh.coffee.domain.LogSort
import com.laksh.coffee.domain.ShotTarget
import com.laksh.coffee.ui.components.BedShapeIcon
import com.laksh.coffee.ui.components.CoffeeConfirm
import com.laksh.coffee.ui.components.Dripper
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.GroupPosition
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.LocalToday
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.ScreenHeader
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.components.beanShortName
import com.laksh.coffee.ui.components.brewerFromLabel
import com.laksh.coffee.ui.components.color
import com.laksh.coffee.ui.components.cupLabel
import com.laksh.coffee.ui.components.focusRing
import com.laksh.coffee.ui.components.hoverTint
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.components.signedClock
import com.laksh.coffee.ui.components.shotTimeStatus
import com.laksh.coffee.ui.components.timeStatus
import com.laksh.coffee.ui.nav.LocalPaneSelection
import com.laksh.coffee.ui.nav.PaneDetail
import com.laksh.coffee.ui.nav.TabKeys
import com.laksh.coffee.ui.nav.listSearchTarget
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.LocalWindowClass
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.WidthClass
import com.laksh.coffee.ui.theme.listBottomPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Log: search, filter chips and day-grouped brews (v4 §3.7, §4.11). On a medium window the
 * rows widen to carry the grind, ratio and score word; at expanded and up the list sits beside the
 * brew's detail. Keys: J/K or Up/Down move, Enter opens, B brews again, Delete asks then undoes.
 *
 * [onBrewAgain] gets the recipe id and the brew to repeat; [onBrewFirst] starts the first brew
 * from the empty state.
 */
@Composable
fun HistoryScreen(
    onOpenLog: (Long) -> Unit,
    onBrewAgain: (String, Long) -> Unit = { _, _ -> },
    onBrewFirst: () -> Unit = {},
    vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val c = CoffeeTheme.colors
    val wc = LocalWindowClass.current
    val gutter = Dimens.gutter
    // Medium windows have the whole width for one list: rows carry more columns.
    val wide = wc.width == WidthClass.Medium
    val paneSel = (LocalPaneSelection.current as? PaneDetail.Brew)?.logId
    // Without a pane the keyboard still has a place: a highlighted row, saved across rotation.
    var cursor by rememberSaveable { mutableStateOf<Long?>(null) }
    // With a pane the cursor leads and the pane follows once the keys rest (§2.6): holding J
    // must not build a detail tree and its ViewModel at key-repeat rate.
    val inPanes = paneSel != null || wc.usesPanes
    LaunchedEffect(paneSel) { if (paneSel != null) cursor = paneSel }
    val selected = if (inPanes) cursor ?: paneSel else paneSel ?: cursor
    LaunchedEffect(cursor, inPanes) {
        val target = cursor
        if (inPanes && target != null && target != paneSel) {
            delay(LogFilterUi.PANE_SETTLE_MS)
            onOpenLog(target)
        }
    }
    var confirmDelete by remember { mutableStateOf<BrewLogEntity?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val days = state.shownByDay
    // Lazy index of every row, so a key press can bring the row it moves to into view.
    val rowIndex = remember(days) {
        val m = HashMap<Long, Int>()
        var i = 2
        days.forEach { (_, logs) ->
            i++
            logs.forEach { m[it.id] = i++ }
        }
        m
    }
    val ids = remember(days) { days.flatMap { (_, l) -> l.map { it.id } } }
    val byId = remember(state.shown) { state.shown.associateBy { it.id } }

    val moveTo: (Long?) -> Unit = { id ->
        if (id != null) {
            cursor = id
            rowIndex[id]?.let { idx ->
                scope.launch {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    if (visible.none { it.index == idx } || visible.lastOrNull()?.index == idx) listState.animateScrollToItem((idx - 2).coerceAtLeast(0))
                }
            }
        }
    }
    TabKeys { e ->
        if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return@TabKeys false
        val current = selected ?: ids.firstOrNull()
        when (e.key) {
            Key.J, Key.DirectionDown -> true.also { moveTo(LogFilterUi.step(ids, selected, 1)) }
            Key.K, Key.DirectionUp -> true.also { moveTo(LogFilterUi.step(ids, selected, -1)) }
            Key.Enter, Key.NumPadEnter -> (current != null).also { if (it) onOpenLog(current!!) }
            Key.B -> {
                val log = current?.let(byId::get)
                if (log != null && log.recipeId.isNotBlank()) onBrewAgain(log.recipeId, log.id)
                log != null
            }
            Key.Delete, Key.Backspace -> {
                val log = current?.let(byId::get)
                if (log != null) confirmDelete = log
                log != null
            }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("log-list"),
            state = listState,
            // Clears the floating bar and the gesture area, so the last row scrolls fully into view.
            contentPadding = PaddingValues(bottom = listBottomPadding()),
        ) {
            item(key = "title", contentType = "title") {
                val logs = state.logs
                val eyebrow = if (logs.isEmpty()) {
                    "NO BREWS YET"
                } else {
                    val weeks = state.spanWeeks
                    "${logs.size} ${if (logs.size == 1) "BREW" else "BREWS"} · $weeks ${if (weeks == 1) "WEEK" else "WEEKS"}"
                }
                val summary: (@Composable () -> Unit)? = if (logs.isNotEmpty()) {
                    { LogSummary(state) }
                } else {
                    null
                }
                ScreenHeader(eyebrow = eyebrow, title = "Log", status = summary)
            }

            item(key = "filters", contentType = "filters") {
                if (state.logs.isNotEmpty()) {
                    LogFilters(
                        state = state,
                        onFilter = vm::setFilter,
                        modifier = Modifier.padding(horizontal = gutter).padding(bottom = 12.dp),
                    )
                }
            }

            when {
                state.logs.isEmpty() -> item(key = "empty", contentType = "empty") {
                    EmptyLog(onBrewFirst, Modifier.animateItem().padding(horizontal = gutter))
                }
                LogFilterUi.noResults(state.logs.size, state.shown.size, state.filter) -> item(key = "none", contentType = "empty") {
                    NoMatches(onClear = { vm.setFilter(LogFilterUi.cleared(state.filter)) }, Modifier.animateItem().padding(horizontal = gutter))
                }
            }

            days.forEachIndexed { dayIndex, (day, dayLogs) ->
                item(key = "header-${day ?: "best"}", contentType = "day-header") {
                    Row(
                        Modifier
                            .animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            )
                            .fillMaxWidth()
                            .padding(
                                start = gutter,
                                end = gutter,
                                top = if (dayIndex == 0) 4.dp else 20.dp,
                                bottom = 8.dp,
                            ),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        EyebrowLabel(if (day == null) "BEST RATED" else dayHeader(day, LocalToday.current), Modifier.asHeading())
                        // Only a busy day is counted: a "1" on every header was noise, and the
                        // card under it already shows the one brew.
                        if (dayLogs.size > 1) {
                            Spacer(Modifier.weight(1f))
                            // inkLow, not inkFaint: faint is for marks, and this is text someone reads.
                            Text(
                                "${dayLogs.size} BREWS",
                                style = Micro,
                                color = c.inkLow,
                            )
                        }
                    }
                }

                itemsIndexed(dayLogs, key = { _, l -> l.id }, contentType = { _, _ -> "log-row" }) { i, log ->
                    LogEntryRow(
                        log = log,
                        targetSec = state.targetSecByRecipe[log.recipeId],
                        shot = state.shotByRecipe[log.recipeId],
                        position = GroupPosition.of(i, dayLogs.size),
                        analysed = log.id in state.analysedIds,
                        wide = wide,
                        selected = log.id == selected,
                        showDate = day == null,
                        onClick = {
                            cursor = log.id
                            onOpenLog(log.id)
                        },
                        onBrewAgain = log.recipeId.takeIf { it.isNotBlank() }?.let { { onBrewAgain(it, log.id) } },
                        onDelete = { confirmDelete = log },
                        modifier = Modifier
                            .testTag("log-row")
                            .animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            )
                            .padding(horizontal = gutter),
                    )
                }
            }
        }

        UndoBar(
            log = undo,
            onUndo = vm::undoDelete,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = gutter)
                .padding(bottom = listBottomPadding() - 24.dp),
        )
    }

    confirmDelete?.let { log ->
        CoffeeConfirm(
            title = "Delete this brew?",
            body = "${log.recipeName.ifBlank { "This brew" }} goes from the log, with its photos and any analysis. You can undo it for a few seconds.",
            confirmLabel = "Delete",
            dismissLabel = "Keep it",
            onConfirm = {
                confirmDelete = null
                if (cursor == log.id) cursor = null
                // The pane moves on to the next brew rather than showing one that is going.
                if (paneSel == log.id) {
                    val next = LogFilterUi.step(ids, log.id, 1)?.takeIf { it != log.id } ?: LogFilterUi.step(ids, log.id, -1)
                    if (next != null && next != log.id) onOpenLog(next)
                }
                vm.deleteWithUndo(log)
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

/** Search, the chips that wrap, and how many brews they leave. */
@Composable
private fun LogFilters(state: HistoryState, onFilter: (LogFilter) -> Unit, modifier: Modifier = Modifier) {
    val f = state.filter
    val c = CoffeeTheme.colors
    Column(modifier) {
        FieldWell(
            label = null,
            value = f.query,
            onChange = { onFilter(f.copy(query = it.take(80))) },
            modifier = Modifier.listSearchTarget(),
            placeholder = LogFilterUi.PLACEHOLDER,
            leadingIcon = Icons.Rounded.Search,
            onCanvas = true,
            height = 48.dp,
            trailing = if (f.query.isNotEmpty()) {
                {
                    IconWell(Icons.Rounded.Close, "Clear search", { onFilter(f.copy(query = "")) }, size = 32.dp, onCanvas = false, iconSize = 16.dp)
                }
            } else {
                null
            },
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogChip.entries.forEach { chip ->
                SelectableChip(chip.label, LogFilterUi.isOn(f, chip), { onFilter(LogFilterUi.toggle(f, chip)) }, onCanvas = true)
            }
            LogMenu.entries.forEach { menu -> MenuChip(menu, state, onFilter) }
            SortChip(f, onFilter)
        }
        val line = LogFilterUi.resultLine(state.shown.size, f)
        if (line != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    line,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.inkMid,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                QuietButton(LogFilterUi.CLEAR, { onFilter(LogFilterUi.cleared(f)) })
            }
        }
    }
}

@Composable
private fun MenuChip(menu: LogMenu, state: HistoryState, onFilter: (LogFilter) -> Unit) {
    val f = state.filter
    var open by remember { mutableStateOf(false) }
    // Built only while the menu is open: the log can be long.
    val options = remember(state.logs, open) { if (open) LogFilterUi.options(state.logs, menu) else emptyList() }
    val chosen = LogFilterUi.selected(f, menu)
    val label = remember(chosen, state.logs) { LogFilterUi.menuLabel(menu, chosen, LogFilterUi.options(state.logs, menu).takeIf { chosen != null }.orEmpty()) }
    Box {
        SelectableChip(label, chosen != null, { open = true }, onCanvas = true)
        FilterMenu(open, { open = false }) {
            MenuRow("Any ${menu.label.lowercase()}", chosen == null) {
                open = false
                onFilter(LogFilterUi.pick(f, menu, null))
            }
            options.forEach { o ->
                MenuRow(o.label, o.key == chosen) {
                    open = false
                    onFilter(LogFilterUi.pick(f, menu, o.key))
                }
            }
        }
    }
}

@Composable
private fun SortChip(f: LogFilter, onFilter: (LogFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SelectableChip(LogFilterUi.sortLabel(f.sort) + " ▾", false, { open = true }, onCanvas = true)
        FilterMenu(open, { open = false }) {
            LogSort.entries.forEach { s ->
                MenuRow(LogFilterUi.sortLabel(s), f.sort == s) {
                    open = false
                    onFilter(f.copy(sort = s))
                }
            }
        }
    }
}

@Composable
private fun FilterMenu(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = CoffeeTheme.colors
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = c.surface1,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(Dp.Hairline, c.hairline),
        modifier = Modifier.heightIn(max = 420.dp),
    ) { content() }
}

@Composable
private fun MenuRow(text: String, on: Boolean, onClick: () -> Unit) {
    val c = CoffeeTheme.colors
    DropdownMenuItem(
        text = {
            Text(
                text,
                color = c.inkHigh,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        onClick = onClick,
        modifier = Modifier.semantics { selected = on }.widthIn(min = 200.dp),
    )
}

@Composable
private fun LogSummary(state: HistoryState) {
    val text = remember(state.logs) {
        buildList {
            val r = state.recipeCount
            add("$r ${if (r == 1) "recipe" else "recipes"}")
            val b = state.beanCount
            if (b > 0) add("$b ${if (b == 1) "coffee" else "coffees"}")
            state.avgScore?.let { add("avg ${oneDp(it)}") }
        }.joinToString(" · ")
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = CoffeeTheme.colors.inkMid)
}

/** v4 §4.12: say what goes here, and give the one next step. */
@Composable
private fun EmptyLog(onBrewFirst: () -> Unit, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    SoftCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The Dripper is small on purpose: a friendly mark, not a hero.
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Dripper(
                    waterLevel = 0.35f,
                    dripping = true,
                    shell = c.artShell,
                    water = c.artWater,
                    bed = c.artBed,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("No brews yet", style = MaterialTheme.typography.titleMedium, color = c.inkHigh, modifier = Modifier.asHeading())
                Spacer(Modifier.height(2.dp))
                Text("Brews you finish and rate show up here.", style = MaterialTheme.typography.bodyMedium, color = c.inkMid)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            "Brew your first cup",
            onBrewFirst,
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            height = Dimens.PrimaryInCard,
        )
    }
}

@Composable
private fun NoMatches(onClear: () -> Unit, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    SoftCard(modifier.fillMaxWidth()) {
        Text(LogFilterUi.NO_RESULTS, style = MaterialTheme.typography.titleMedium, color = c.inkHigh)
        Spacer(Modifier.height(10.dp))
        SecondaryButton(LogFilterUi.CLEAR, onClear)
    }
}

/** "Brew deleted · Undo", above the bar for the few seconds the delete can still be taken back. */
@Composable
private fun UndoBar(log: BrewLogEntity?, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    // Holds the last brew while the bar slides away, so it never empties mid-exit.
    val shown = remember { arrayOfNulls<BrewLogEntity>(1) }
    if (log != null) shown[0] = log
    AnimatedVisibility(
        visible = log != null,
        modifier = modifier,
        enter = fadeIn(Motion.decel()) + slideInVertically(Motion.offset()) { it / 2 },
        exit = fadeOut(Motion.decel(140)) + slideOutVertically(Motion.offset()) { it / 2 },
    ) {
        Row(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.inverse)
                .padding(start = 18.dp, end = 6.dp)
                .heightIn(min = 52.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Delete, null, tint = c.onInverse, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Deleted ${shown[0]?.recipeName?.ifBlank { null } ?: "a brew"}",
                style = MaterialTheme.typography.bodyMedium,
                color = c.onInverse,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            QuietButton("Undo", onUndo, color = c.onInverse)
        }
    }
}

private val FirstShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val LastShape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
private val SingleShape = RoundedCornerShape(22.dp)

/**
 * One brew in a day's inset-grouped card. The log is the one list that knows each recipe's
 * target: the time delta sits in the meta line in its status colour (the log's "delayed 25m"),
 * and the bed is drawn, not typed. A long press (or the row's own button on wide rows) offers
 * "Brew again" and Delete; the selected row (pane or keyboard) is one shade up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogEntryRow(
    log: BrewLogEntity,
    targetSec: Int?,
    shot: ShotTarget?,
    position: GroupPosition,
    analysed: Boolean,
    wide: Boolean,
    selected: Boolean,
    showDate: Boolean,
    onClick: () -> Unit,
    onBrewAgain: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CoffeeTheme.colors
    var menu by remember { mutableStateOf(false) }
    // On time says so in words: a green "0:00" beside "3:20" read as a second clock. Only a
    // miss shows the signed delta, because then the number is the news.
    val delta = remember(log.totalTimeSec, targetSec, shot) {
        if (shot != null && log.totalTimeSec > 0) {
            // A shot is judged against its band, as shot mode and Review judge it, and a miss is
            // the seconds past the nearer edge: a 14 s gusher is never "on time" for 25-30 s.
            val off = shot.offBy(log.totalTimeSec)
            shotTimeStatus(log.totalTimeSec, shot).first to if (off == 0) "in band" else signedClock(off)
        } else if (targetSec != null && targetSec > 0 && log.totalTimeSec > 0) {
            val status = timeStatus(log.totalTimeSec, targetSec).first
            status to if (status == Status.Good) "on time" else signedClock(log.totalTimeSec - targetSec)
        } else {
            null
        }
    }
    val deltaColor = delta?.first?.color() ?: Color.Unspecified
    val grind = log.grindClicks?.let { "$it clk" }
        ?: log.grindSetting.takeIf { it.isNotBlank() }?.let { clipWords(it, 14) }
    val meta = remember(log, delta, deltaColor, wide) {
        buildAnnotatedString {
            // "12 h 5 min" for a cold brew, never "725:00" (v4 §4.9).
            val time = log.totalTimeSec.takeIf { it > 0 }?.let(::formatDuration)
            // Wide rows give grind its own column; here it leads the meta line.
            append(listOfNotNull(grind.takeIf { !wide }, time).joinToString(" · "))
            if (time != null && delta != null) {
                append(" ")
                withStyle(SpanStyle(color = deltaColor, fontWeight = FontWeight.Medium)) {
                    append(delta.second)
                }
            }
            beanShortName(log.beanLabel).takeIf { it.isNotBlank() && !wide }?.let {
                if (length > 0) append(" · ")
                append(it)
            }
        }
    }
    val brewer = ((log.brewerId?.let(Brewers::byId) ?: brewerFromLabel(log.brewer))?.shortName ?: log.brewer).uppercase()

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = animateColorAsState(
        if (pressed || selected) c.surface2 else c.surface1,
        Motion.quick(),
        label = "logRowBg",
    )
    val shape: Shape = when (position) {
        GroupPosition.First -> FirstShape
        GroupPosition.Last -> LastShape
        GroupPosition.Single -> SingleShape
        GroupPosition.Middle -> RectangleShape
    }
    val topRule = position == GroupPosition.Middle || position == GroupPosition.Last
    val rule = c.hairline
    val rowActions = buildList {
        if (onBrewAgain != null) add(CustomAccessibilityAction("Brew again") { onBrewAgain(); true })
        add(CustomAccessibilityAction("Delete") { onDelete(); true })
    }

    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .focusRing(interaction, shape)
                .clip(shape)
                .drawBehind {
                    drawRect(bg.value)
                    if (topRule) drawLine(rule, Offset(96.dp.toPx(), 0.5f), Offset(size.width, 0.5f), 1f)
                }
                .hoverTint(interaction, c.surface2)
                .semantics {
                    this.selected = selected
                    customActions = rowActions
                }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onLongClickLabel = "More",
                    onLongClick = { menu = true },
                    onClick = onClick,
                ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // A minimum: at 200 % text the two lines grow the row instead of clipping.
                    .heightIn(min = Dimens.LogRowHeight)
                    .padding(start = 16.dp, end = if (wide && onBrewAgain != null) 8.dp else 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 68dp with tighter tracking so 'AEROPRESS' fits; longer names ellipsize.
                // A minimum, not a width: at 200 % text the clock grows the column instead of
                // losing its "PM" (v4 A7, numbers never clip).
                Column(Modifier.widthIn(min = if (showDate) 76.dp else 68.dp, max = 120.dp)) {
                    Text(
                        if (showDate) screenDate(log.startedAt) else screenTime(log.startedAt),
                        style = Num.Inline,
                        color = c.inkHigh,
                    )
                    Text(
                        brewer,
                        style = Micro.copy(letterSpacing = 0.6.sp),
                        color = c.inkLow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        log.recipeName.ifBlank { "Brew" },
                        style = MaterialTheme.typography.titleMedium,
                        color = c.inkHigh,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Two lines, so the time delta (a number) wraps rather than ellipsizes at large text.
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.inkMid,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (wide) {
                    // Medium: coffee, grind and ratio get columns of their own (v4 §3.7 M).
                    WideCell("COFFEE", beanShortName(log.beanLabel).ifBlank { "—" }, 140.dp)
                    WideCell("GRIND", grind ?: "—", 84.dp)
                    WideCell("RATIO", if (log.doseG > 0 && log.waterG > 0) ratio(log.doseG, log.waterG) else "—", 64.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (log.overall > 0) "${log.overall}" else "—",
                        style = Num.Small.copy(fontSize = 22.sp, lineHeight = 26.sp),
                        // Unrated is text, so inkLow; inkFaint would vanish on paper.
                        color = if (log.overall > 0) scoreStatus(log.overall).color() else c.inkLow,
                        maxLines = 1,
                    )
                    if (wide && log.overall > 0) {
                        // Status colour always with a word.
                        Text(cupLabel(log.overall).uppercase(), style = Micro, color = scoreStatus(log.overall).color(), maxLines = 1)
                    }
                    Row(
                        modifier = Modifier.heightIn(min = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (analysed) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = "Analysed",
                                modifier = Modifier.size(12.dp),
                                tint = c.inkLow,
                            )
                        }
                        if (log.bedShape != BedShape.Unset) {
                            BedShapeIcon(log.bedShape, size = 16.dp, tint = c.inkLow)
                        }
                        if (log.faults.isNotEmpty()) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Status.Fault.color()),
                            )
                        }
                    }
                }
                if (wide && onBrewAgain != null) {
                    Spacer(Modifier.width(8.dp))
                    IconWell(Icons.Rounded.Replay, "Brew again", onBrewAgain, onCanvas = false, shortcut = "B")
                }
            }
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = c.surface1,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(Dp.Hairline, c.hairline),
        ) {
            if (onBrewAgain != null) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Brew again", color = c.inkHigh)
                            Text("Same coffee, grind and dose", style = MaterialTheme.typography.bodySmall, color = c.inkMid)
                        }
                    },
                    onClick = {
                        menu = false
                        onBrewAgain()
                    },
                    leadingIcon = { Icon(Icons.Rounded.Replay, null, tint = c.inkMid) },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete", color = c.fault) },
                onClick = {
                    menu = false
                    onDelete()
                },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.fault) },
            )
        }
    }
}

@Composable
private fun WideCell(label: String, value: String, width: Dp) {
    val c = CoffeeTheme.colors
    Column(Modifier.padding(start = 12.dp).width(width)) {
        Text(label, style = Micro, color = c.inkLow, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.inkHigh, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun dayHeader(day: LocalDate, today: LocalDate): String {
    return when (day) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> screenDate(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()).uppercase()
    }
}
