package com.laksh.coffee.ui.screens.insights

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.unit.Dp
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.LocalWindowClass
import com.laksh.coffee.ui.theme.WidthClass
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.counted
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.ui.ai.TrendAnalysisSection
import com.laksh.coffee.ui.backup.BackupCopy
import com.laksh.coffee.ui.components.ActionRow
import com.laksh.coffee.ui.components.BrewerIcon
import com.laksh.coffee.ui.components.Cell
import com.laksh.coffee.ui.components.DataGrid
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.GroupPosition
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.NumberWithUnit
import com.laksh.coffee.ui.components.ScreenHeader
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.color
import com.laksh.coffee.ui.components.copyText
import com.laksh.coffee.ui.components.pressSquash
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.components.shareText
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.WellShape
import com.laksh.coffee.ui.theme.listBottomPadding
import kotlinx.coroutines.launch
import com.laksh.coffee.core.screenDate
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

// The phone's own day-month order (v4 §4.15): "NOV 19" in en-US, "19 NOV" in en-GB.
private fun dayMonth(epochMs: Long): String = screenDate(epochMs).uppercase()

private fun dayMonth(day: LocalDate): String = dayMonth(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())

/** Insights fill in after this many brews (v4 §4.12). */
internal const val INSIGHTS_MIN_BREWS = 3

/** "Insights start after 3 brews. 2 to go." Null once there are enough. Pure. */
internal fun insightsEmptyLine(brews: Int): String? {
    val left = INSIGHTS_MIN_BREWS - brews
    return if (left <= 0) null else "Insights start after $INSIGHTS_MIN_BREWS brews. $left to go."
}

/**
 * Insights. Phones read one column; a medium window is a 2-column grid under the passport; at
 * expanded and up it is a 12-column dashboard (v4 §3.9, §2.5), with the AI trend analysis in a
 * supporting column at large and up. [onBrewNow] starts a brew from the "not enough yet" card.
 */
@Composable
fun InsightsScreen(
    onOpenLog: (Long) -> Unit,
    onBrewNow: () -> Unit = {},
    vm: InsightsViewModel = viewModel(factory = InsightsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val aiTarget by vm.aiTarget.collectAsStateWithLifecycle()
    val backupSave by vm.backupSave.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wc = LocalWindowClass.current

    // Saving goes through the system file picker, so the app never needs storage permissions
    // and you choose exactly where the file lands.
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.saveBackup(uri)
    }

    val passport = state.passport
    val cards = InsightCards(
        passport = { m -> PassportCard(passport, totalRecipes = state.recipes.size, modifier = m) },
        bests = if (state.bests.hasRecords()) {
            { m -> PersonalBestsCard(state.bests, onOpenLog = onOpenLog, modifier = m) }
        } else {
            null
        },
        score = if (state.scores.isNotEmpty()) {
            { m -> ScoreCard(state, wide = wc.width != WidthClass.Compact, onOpenLog = onOpenLog, modifier = m) }
        } else {
            null
        },
        grind = state.grind?.let { g -> { m -> GrindCard(g, wide = wc.width != WidthClass.Compact, onOpenLog = onOpenLog, modifier = m) } },
        ranked = if (state.rankedRecipes.isNotEmpty()) {
            { m -> RankedCard("MOST BREWED", state.rankedRecipes, m) }
        } else {
            null
        },
        coffees = if (state.rankedBeans.isNotEmpty()) {
            { m -> RankedCard("COFFEES", state.rankedBeans, m) }
        } else {
            null
        },
        // No section label: the card's own eyebrow says PATTERNS.
        patterns = { m -> TrendAnalysisSection(onOpenLog = onOpenLog, modifier = m) },
        export = { m ->
            ExportRows(
                enabled = state.hasData,
                backupSave = backupSave,
                onShareLast = { scope.launch { shareText(context, vm.lastBrewReport(), "Brew log") } },
                onCopyLast = { scope.launch { copyText(context, vm.lastBrewReport()) } },
                onShareTable = { shareText(context, vm.trendReport(25), "Brew history") },
                onCopyTable = { copyText(context, vm.trendReport(25)) },
                onSave = { saveBackup.launch(BackupCopy.fileName(LocalDate.now())) },
                modifier = m,
            )
        },
        privacy = { m -> PrivacyCard(PrivacyCopy.text(aiTarget), m) },
    )
    val emptyLine = insightsEmptyLine(passport.brews)
    val header: @Composable () -> Unit = {
        ScreenHeader(
            eyebrow = passport.firstAt?.let { "SINCE " + dayMonth(it) } ?: "YOUR LOG",
            title = "Insights",
        )
    }

    if (wc.width == WidthClass.Compact) {
        PhoneInsights(cards, header, emptyLine, onBrewNow)
    } else {
        WideInsights(cards, header, emptyLine, onBrewNow, wc.width)
    }
}

/** Each card as a slot that takes its modifier; null when the card has nothing to show yet. */
private class InsightCards(
    val passport: @Composable (Modifier) -> Unit,
    val bests: (@Composable (Modifier) -> Unit)?,
    val score: (@Composable (Modifier) -> Unit)?,
    val grind: (@Composable (Modifier) -> Unit)?,
    val ranked: (@Composable (Modifier) -> Unit)?,
    val coffees: (@Composable (Modifier) -> Unit)?,
    val patterns: @Composable (Modifier) -> Unit,
    val export: @Composable (Modifier) -> Unit,
    val privacy: @Composable (Modifier) -> Unit,
)

@Composable
private fun PhoneInsights(
    cards: InsightCards,
    header: @Composable () -> Unit,
    emptyLine: String?,
    onBrewNow: () -> Unit,
) {
    val gutter = Modifier.padding(horizontal = 16.dp)
    // Lazy, one item per card: the switch frame composes and measures only what is on screen,
    // and the cards below the fold (charts, patterns, export) arrive as you scroll.
    LazyColumn(
        Modifier.fillMaxSize().testTag("insights-list"),
        contentPadding = PaddingValues(bottom = listBottomPadding()),
    ) {
        item(key = "header", contentType = "header") { header() }
        if (emptyLine != null) {
            item(key = "empty", contentType = "empty") { NotYetCard(emptyLine, onBrewNow, gutter.padding(bottom = 12.dp).fillMaxWidth()) }
        }
        item(key = "passport", contentType = "passport") { cards.passport(gutter.fillMaxWidth()) }
        cards.bests?.let { c -> item(key = "bests", contentType = "bests") { c(gutter.padding(top = 12.dp).fillMaxWidth()) } }
        cards.score?.let { c -> item(key = "score", contentType = "score") { c(gutter.padding(top = 12.dp).fillMaxWidth()) } }
        cards.grind?.let { c -> item(key = "grind", contentType = "grind") { c(gutter.padding(top = 12.dp).fillMaxWidth()) } }
        cards.ranked?.let { c -> item(key = "ranked", contentType = "ranked") { c(gutter.padding(top = 12.dp).fillMaxWidth()) } }
        cards.coffees?.let { c -> item(key = "coffees", contentType = "ranked") { c(gutter.padding(top = 12.dp).fillMaxWidth()) } }
        item(key = "patterns", contentType = "patterns") { cards.patterns(gutter.padding(top = 12.dp)) }
        item(key = "export", contentType = "export") {
            Column {
                SectionLabel("SHARE & EXPORT", gutter)
                cards.export(gutter)
            }
        }
        item(key = "privacy", contentType = "privacy") {
            Column {
                SectionLabel("WHAT LEAVES THE PHONE", gutter)
                cards.privacy(gutter.fillMaxWidth())
            }
        }
    }
}

/**
 * Medium: a 2-column grid under the passport. Expanded and up: the 12-column dashboard, rows
 * aligned to the top (a tile never stretches past its content).
 */
@Composable
private fun WideInsights(
    cards: InsightCards,
    header: @Composable () -> Unit,
    emptyLine: String?,
    onBrewNow: () -> Unit,
    width: WidthClass,
) {
    val gap = if (width.atLeast(WidthClass.Large)) 24.dp else 16.dp
    val max = when (width) {
        WidthClass.ExtraLarge -> 1440.dp
        WidthClass.Large -> 1280.dp
        else -> 1200.dp
    }
    Box(
        Modifier
            .fillMaxSize()
            .testTag("insights-list")
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = max)
                .fillMaxWidth()
                .padding(horizontal = Dimens.gutter - 16.dp)
                .padding(bottom = listBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            header()
            val inner = Modifier.padding(horizontal = 16.dp)
            Column(inner, verticalArrangement = Arrangement.spacedBy(gap)) {
                if (emptyLine != null) NotYetCard(emptyLine, onBrewNow, Modifier.fillMaxWidth())
                if (width == WidthClass.Medium) {
                    // The passport is the KPI row; the rest pair up.
                    cards.passport(Modifier.fillMaxWidth())
                    val rest = listOfNotNull(cards.bests, cards.score, cards.grind, cards.ranked, cards.coffees, cards.patterns)
                    rest.chunked(2).forEach { pair ->
                        Tiles(gap, pair.map { 6 to it })
                    }
                } else {
                    Tiles(gap, listOfNotNull(8 to cards.passport, cards.bests?.let { 4 to it }))
                    if (width.atLeast(WidthClass.Large)) {
                        // The AI trend reads beside the charts it talks about (span 4 beside 8).
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            Column(Modifier.weight(8f), verticalArrangement = Arrangement.spacedBy(gap)) {
                                Tiles(gap, listOfNotNull(cards.score?.let { 6 to it }, cards.grind?.let { 6 to it }))
                                Tiles(gap, listOfNotNull(cards.ranked?.let { 6 to it }, cards.coffees?.let { 6 to it }))
                            }
                            Column(Modifier.weight(4f)) { cards.patterns(Modifier.fillMaxWidth()) }
                        }
                    } else {
                        Tiles(gap, listOfNotNull(cards.score?.let { 6 to it }, cards.grind?.let { 6 to it }))
                        Tiles(gap, listOfNotNull(cards.ranked?.let { 4 to it }, cards.coffees?.let { 4 to it }, 4 to cards.patterns))
                    }
                }
                // Share/export and the privacy copy share the footer row.
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("SHARE & EXPORT", topGap = 8.dp)
                        cards.export(Modifier.fillMaxWidth())
                    }
                    Column(Modifier.weight(1f)) {
                        SectionLabel("WHAT LEAVES THE PHONE", topGap = 8.dp)
                        cards.privacy(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** One dashboard row: tiles weighted by their span out of 12, top-aligned. */
@Composable
private fun Tiles(gap: Dp, tiles: List<Pair<Int, @Composable (Modifier) -> Unit>>) {
    if (tiles.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
        tiles.forEach { (span, tile) -> Box(Modifier.weight(span.toFloat())) { tile(Modifier.fillMaxWidth()) } }
    }
}

/** v4 §4.12: what Insights needs, and the one button that gets there. */
@Composable
private fun NotYetCard(line: String, onBrewNow: () -> Unit, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    SoftCard(modifier) {
        Text(line, style = MaterialTheme.typography.titleMedium, color = c.inkHigh)
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Brew now", onBrewNow, icon = Icons.Rounded.PlayArrow, height = Dimens.PrimaryInCard)
    }
}

@Composable
private fun ScoreCard(state: InsightsState, wide: Boolean, onOpenLog: (Long) -> Unit, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    SoftCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel("SCORE · LAST ${state.scores.size}", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            val last = state.lastFive
            val prev = state.prevFive
            // The chip says what it compares, not just which way it moved.
            if (last != null && prev != null) {
                val d = last - prev
                when {
                    abs(d) < 0.05 -> StatusChip(Status.Neutral, "Level vs prev 5")
                    d > 0 -> StatusChip(Status.Good, "▲ " + oneDp(abs(d)) + " vs prev 5")
                    else -> StatusChip(Status.Attention, "▼ " + oneDp(abs(d)) + " vs prev 5")
                }
            }
        }
        // The eyebrow names the bars' range; the big number is the last five only, and
        // says so, or it reads as the average of all the bars.
        state.lastFive?.let {
            Spacer(Modifier.height(6.dp))
            NumberWithUnit(oneDp(it), unit = "/10", style = Num.Large)
            Text("AVG · LAST 5", style = Micro, color = c.inkLow)
        }
        Spacer(Modifier.height(12.dp))
        ScoreBars(state.scores, maxTicks = if (wide) 6 else 4, onOpen = onOpenLog)
        Text("Tap a bar to open that brew.", style = MaterialTheme.typography.bodySmall, color = c.inkLow, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun RankedCard(title: String, items: List<Ranked>, modifier: Modifier = Modifier) {
    SoftCard(modifier) {
        EyebrowLabel(title, Modifier.asHeading())
        Spacer(Modifier.height(4.dp))
        RankedList(items)
    }
}

/** The plain-words export rows (v4 §4.16). */
@Composable
private fun ExportRows(
    enabled: Boolean,
    backupSave: BackupSave?,
    onShareLast: () -> Unit,
    onCopyLast: () -> Unit,
    onShareTable: () -> Unit,
    onCopyTable: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ActionRow(
            icon = Icons.Rounded.Share,
            title = "Last brew report",
            subtitle = "Copy as text for an AI chat (this brew and the 8 before it)",
            onClick = onShareLast,
            position = GroupPosition.First,
            trailingIcon = Icons.Rounded.ContentCopy,
            onTrailing = { if (enabled) onCopyLast() },
            trailingDescription = "Copy",
            enabled = enabled,
        )
        ActionRow(
            icon = Icons.Rounded.Timeline,
            title = "History table",
            subtitle = "Last 25 brews, for pattern questions",
            onClick = onShareTable,
            position = GroupPosition.Middle,
            trailingIcon = Icons.Rounded.ContentCopy,
            onTrailing = { if (enabled) onCopyTable() },
            trailingDescription = "Copy",
            enabled = enabled,
        )
        ActionRow(
            icon = Icons.Rounded.Download,
            title = if (backupSave == BackupSave.Saving) "Saving a backup…" else "Save a backup file",
            subtitle = when (backupSave) {
                BackupSave.Saved -> "Saved. Restore it in Gear › Settings on any phone or the web"
                BackupSave.Failed -> BackupCopy.SAVE_FAILED
                else -> "Everything but photos. No API key, ever"
            },
            onClick = onSave,
            position = GroupPosition.Last,
            enabled = enabled && backupSave != BackupSave.Saving,
        )
    }
}

@Composable
private fun PrivacyCard(text: String, modifier: Modifier = Modifier) {
    SoftCard(modifier) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
            color = CoffeeTheme.colors.inkMid,
        )
    }
}

@Composable
private fun PassportCard(p: Passport, totalRecipes: Int, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    val empty = p.brews == 0

    // Lifetime totals only. The best score and the streak are records, so they live in the
    // Personal bests tiles below with their story, not here as two more bare numbers (and the
    // streak was already Home's).
    val units = LocalUnits.current
    val cells = remember(p, totalRecipes, units) {
        if (empty) {
            listOf("WATER", "COFFEE", "GREAT CUPS", "AVG", "RECIPES", "COFFEES").map { Cell(it, null) }
        } else {
            // A total, so it follows the weight unit; water stays in litres either way.
            val coffee = units.totalWeight(p.coffeeG)
            listOf(
                Cell("WATER", oneDp(p.waterG / 1000), "L"),
                Cell("COFFEE", coffee.first, coffee.second),
                Cell(
                    label = "GREAT CUPS",
                    value = "${p.greatCups}",
                    status = if (p.greatCups > 0) Status.Good else null,
                ),
                Cell(label = "AVG", value = p.avg?.let(::oneDp), status = p.avg?.let { scoreStatus(it.roundToInt()) }),
                Cell(
                    label = "RECIPES",
                    value = "${p.recipesUsed}",
                    unit = if (totalRecipes > 0) "of $totalRecipes" else null,
                ),
                Cell(label = "COFFEES", value = "${p.coffees}"),
            )
        }
    }

    SoftCard(modifier) {
        EyebrowLabel("BREW PASSPORT")
        Spacer(Modifier.height(12.dp))
        Row {
            // inkLow rather than inkFaint for the empty zero: it is text, and inkFaint is for marks.
            Text(
                "${p.brews}",
                style = Num.Display,
                color = if (empty) c.inkLow else c.inkHigh,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (p.brews == 1) "brew" else "brews",
                style = MaterialTheme.typography.titleLarge,
                color = c.inkMid,
                modifier = Modifier.alignByBaseline(),
            )
            // The span, beside the count: "10 brews … in 22 days". The start date is the page's
            // eyebrow, so it isn't printed a second time here.
            if (!empty) {
                Spacer(Modifier.weight(1f))
                Text(
                    if (p.spanDays == 1) "IN 1 DAY" else "IN ${p.spanDays} DAYS",
                    style = Micro,
                    color = c.inkLow,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        if (empty) {
            Text(
                "Your passport fills in as you brew.",
                style = MaterialTheme.typography.bodySmall,
                color = c.inkMid,
            )
        }
        Spacer(Modifier.height(16.dp))
        HairLine()
        DataGrid(cells = cells, columns = 3)
        if (!empty && p.stamps.isNotEmpty()) {
            HairLine()
            Spacer(Modifier.height(12.dp))
            PassportStamps(p)
        }
    }
}

/** The passport's stamps: a drawn brewer per brewer used, with how often. */
@Composable
private fun PassportStamps(p: Passport) {
    val c = CoffeeTheme.colors
    EyebrowLabel("STAMPS")
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        p.stamps.forEach { stamp ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stamp.brewer != null) {
                    BrewerIcon(stamp.brewer, size = 18.dp, tint = c.inkMid)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "${stamp.brewer?.shortName ?: stamp.label} ×${stamp.count}",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.inkHigh,
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

private fun PersonalBests.hasRecords(): Boolean =
    bestCup != null || (longestStreak?.days ?: 0) >= 2 || bestCoffee != null

/**
 * Records with their context, so the pride has a story: which cup, at what grind, when. They are
 * filled tiles led by their number, set apart from the passport's ruled grid of totals. The best
 * cup opens its brew, because the obvious next move is to repeat it.
 */
@Composable
private fun PersonalBestsCard(
    bests: PersonalBests,
    onOpenLog: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CoffeeTheme.colors
    val streak = bests.longestStreak?.takeIf { it.days >= 2 }
    val coffee = bests.bestCoffee
    SoftCard(modifier) {
        EyebrowLabel("PERSONAL BESTS")

        bests.bestCup?.let { cup ->
            Spacer(Modifier.height(12.dp))
            BestCupTile(cup, onClick = { onOpenLog(cup.id) })
        }

        if (streak != null || coffee != null) {
            Spacer(Modifier.height(if (bests.bestCup != null) 8.dp else 12.dp))
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                streak?.let { run ->
                    val start = dayMonth(run.end.minusDays(run.days - 1L))
                    val end = dayMonth(run.end)
                    RecordTile(
                        label = "LONGEST STREAK",
                        value = "${run.days}",
                        unit = unitWord(run.days, "day"),
                        valueColor = c.inkHigh,
                        title = if (run.current) "Still going" else "Every day",
                        detail = if (run.current) "since $start" else "$start – $end",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                coffee?.let {
                    RecordTile(
                        label = "BEST COFFEE",
                        value = it.avg?.let(::oneDp) ?: "—",
                        unit = "avg",
                        valueColor = it.avg?.let { avg -> scoreStatus(avg.roundToInt()).color() } ?: c.inkLow,
                        title = it.name,
                        detail = listOfNotNull(it.sub, counted(it.count, "brew")).joinToString(" · "),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** The headline record: the recipe and the settings that made the best cup, and its score. */
@Composable
private fun BestCupTile(cup: BestCup, onClick: () -> Unit) {
    val c = CoffeeTheme.colors
    val units = LocalUnits.current
    val interaction = remember { MutableInteractionSource() }
    val settings = listOfNotNull(
        cup.clicks?.let { counted(it, "click") } ?: cup.grindSetting.takeIf { it.isNotBlank() },
        cup.tempC.takeIf { it > 0 }?.let { units.tempWithUnit(it) },
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .pressSquash(interaction, 0.98f)
            .clip(WellShape)
            .background(c.surface2)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Open this brew",
                onClick = onClick,
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            EyebrowLabel("BEST CUP · ${dayMonth(cup.at)}")
            Spacer(Modifier.height(6.dp))
            Text(
                cup.recipeName,
                style = MaterialTheme.typography.titleMedium,
                color = c.inkHigh,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (settings.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    settings,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = c.inkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        NumberWithUnit("${cup.score}", unit = "/10", style = Num.Large, color = scoreStatus(cup.score).color())
    }
}

@Composable
private fun RecordTile(
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val c = CoffeeTheme.colors
    Column(
        modifier
            .clip(WellShape)
            .background(c.surface2)
            .padding(14.dp),
    ) {
        EyebrowLabel(label)
        Spacer(Modifier.height(6.dp))
        NumberWithUnit(value, unit = unit, style = Num.Medium, color = valueColor)
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = c.inkHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = c.inkMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GrindCard(g: GrindSeries, wide: Boolean, onOpenLog: (Long) -> Unit, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    SoftCard(modifier) {
        EyebrowLabel("GRIND · ${g.grinderLabel}")
        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                "${g.first ?: "—"}",
                style = Num.Large,
                color = c.inkLow,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                " → ",
                style = Num.Large.copy(fontSize = 28.sp, lineHeight = 32.sp),
                color = c.inkLow,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                "${g.latest ?: "—"}",
                style = Num.Large,
                color = c.inkHigh,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                "clicks",
                style = Num.unit(Num.Large),
                color = c.inkMid,
                modifier = Modifier.alignByBaseline(),
            )
        }
        // Say it the way the dial reads: a numbered ring shows 75 clicks as 7.5.
        val format = DialFormat.decode(g.dialFormat, g.clicksPerTurn)
            ?: if (g.clicksPerTurn == 10) DialFormat.TurnsDotClicks(10) else DialFormat.Plain
        val dial = if (format.showsDialNumber && g.first != null && g.latest != null) {
            " · DIAL ${format.read(g.first)} → ${format.read(g.latest)}"
        } else {
            ""
        }
        Text("FIRST → NOW$dial", style = Micro, color = c.inkLow)

        Spacer(Modifier.height(12.dp))
        GrindJourneyChart(g, maxTicks = if (wide) 5 else 3, onOpen = onOpenLog)

        if (g.otherBrews > 0) {
            val others = if (g.otherBrews == 1) "1 brew on another recipe is" else "${g.otherBrews} brews on other recipes are"
            Spacer(Modifier.height(6.dp))
            Text(
                "${g.recipeName} only. $others left out, because clicks only compare within one recipe.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = c.inkLow,
            )
        }

        g.bestZone?.let { z ->
            Spacer(Modifier.height(12.dp))
            StatusLine(
                status = Status.Good,
                lead = if (z.first == z.last) "Best at ${counted(z.first, "click")}" else "Best zone ${z.first}–${z.last} clicks",
                detail = g.bestZoneAvg?.let { "avg ${oneDp(it)}" },
            )
        }
    }
}
