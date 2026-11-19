package com.laksh.coffee.ui.screens.insights

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

private fun dayMonth(epochMs: Long): String =
    DAY_MONTH.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())).uppercase()

@Composable
fun InsightsScreen(
    onOpenLog: (Long) -> Unit,
    vm: InsightsViewModel = viewModel(factory = InsightsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val aiTarget by vm.aiTarget.collectAsStateWithLifecycle()
    val backupSave by vm.backupSave.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = CoffeeTheme.colors

    // Saving goes through the system file picker, so the app never needs storage permissions
    // and you choose exactly where the file lands.
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.saveBackup(uri)
    }

    val passport = state.passport
    val gutter = Modifier.padding(horizontal = 16.dp)

    // Lazy, one item per card: the switch frame composes and measures only what is on screen,
    // and the cards below the fold (charts, patterns, export) arrive as you scroll.
    LazyColumn(
        Modifier.fillMaxSize().testTag("insights-list"),
        contentPadding = PaddingValues(bottom = listBottomPadding()),
    ) {
        item(key = "header", contentType = "header") {
            ScreenHeader(
                eyebrow = passport.firstAt?.let { "SINCE " + dayMonth(it) } ?: "YOUR LOG",
                title = "Insights",
            )
        }

        item(key = "passport", contentType = "passport") {
            PassportCard(passport, totalRecipes = state.recipes.size, modifier = gutter.fillMaxWidth())
        }

        if (state.bests.hasRecords()) {
            item(key = "bests", contentType = "bests") {
                PersonalBestsCard(state.bests, onOpenLog = onOpenLog, modifier = gutter.padding(top = 12.dp).fillMaxWidth())
            }
        }

        // ------------------------------------------------------------ score
        if (state.scores.isNotEmpty()) {
            item(key = "score", contentType = "score") {
                SoftCard(gutter.padding(top = 12.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EyebrowLabel("SCORE · LAST ${state.scores.size}")
                        Spacer(Modifier.weight(1f))
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
                    ScoreBars(state.scores)
                }
            }
        }

        // ------------------------------------------------------------ grind
        state.grind?.let { g ->
            item(key = "grind", contentType = "grind") {
                GrindCard(g, gutter.padding(top = 12.dp).fillMaxWidth())
            }
        }

        // ------------------------------------------------------ most brewed
        if (state.rankedRecipes.isNotEmpty()) {
            item(key = "ranked", contentType = "ranked") {
                SoftCard(gutter.padding(top = 12.dp).fillMaxWidth()) {
                    EyebrowLabel("MOST BREWED")
                    Spacer(Modifier.height(4.dp))
                    RankedList(state.rankedRecipes)
                    if (state.rankedBeans.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HairLine()
                        Spacer(Modifier.height(12.dp))
                        EyebrowLabel("COFFEES")
                        Spacer(Modifier.height(4.dp))
                        RankedList(state.rankedBeans)
                    }
                }
            }
        }

        // --------------------------------------------------------- patterns
        // No section label: the card's own eyebrow says PATTERNS.
        item(key = "patterns", contentType = "patterns") {
            TrendAnalysisSection(onOpenLog = onOpenLog, modifier = gutter.padding(top = 12.dp))
        }

        // --------------------------------------------------- share & export
        item(key = "export", contentType = "export") {
            Column {
                SectionLabel("SHARE & EXPORT", gutter)
                Column(gutter) {
                    val enabled = state.hasData
                    ActionRow(
                        icon = Icons.Rounded.Share,
                        title = "Last brew report",
                        subtitle = "Markdown with the 8 brews before it",
                        onClick = { scope.launch { shareText(context, vm.lastBrewReport(), "Brew log") } },
                        position = GroupPosition.First,
                        trailingIcon = Icons.Rounded.ContentCopy,
                        onTrailing = { if (enabled) scope.launch { copyText(context, vm.lastBrewReport()) } },
                        trailingDescription = "Copy",
                        enabled = enabled,
                    )
                    ActionRow(
                        icon = Icons.Rounded.Timeline,
                        title = "History table",
                        subtitle = "Last 25 brews, for pattern questions",
                        onClick = { shareText(context, vm.trendReport(25), "Brew history") },
                        position = GroupPosition.Middle,
                        trailingIcon = Icons.Rounded.ContentCopy,
                        onTrailing = { if (enabled) copyText(context, vm.trendReport(25)) },
                        trailingDescription = "Copy",
                        enabled = enabled,
                    )
                    ActionRow(
                        icon = Icons.Rounded.Download,
                        title = if (backupSave == BackupSave.Saving) "Saving a backup…" else "Save backup (JSON)",
                        subtitle = when (backupSave) {
                            BackupSave.Saved -> "Saved. Restore it in Gear › App on any phone or the web"
                            BackupSave.Failed -> BackupCopy.SAVE_FAILED
                            else -> "Everything but photos. No API key, ever"
                        },
                        onClick = { saveBackup.launch(BackupCopy.fileName(LocalDate.now())) },
                        position = GroupPosition.Last,
                        enabled = enabled && backupSave != BackupSave.Saving,
                    )
                }
            }
        }

        // ------------------------------------------------------------ privacy
        item(key = "privacy", contentType = "privacy") {
            Column {
                SectionLabel("WHAT LEAVES THE PHONE", gutter)
                SoftCard(gutter.fillMaxWidth()) {
                    Text(
                        PrivacyCopy.text(aiTarget),
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                        color = c.inkMid,
                    )
                }
            }
        }
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
                    val start = DAY_MONTH.format(run.end.minusDays(run.days - 1L)).uppercase()
                    val end = DAY_MONTH.format(run.end).uppercase()
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
private fun GrindCard(g: GrindSeries, modifier: Modifier = Modifier) {
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
        GrindJourneyChart(g)

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
