package com.laksh.coffee.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.clipWords
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.core.timeLabel
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.ui.components.BedShapeIcon
import com.laksh.coffee.ui.components.Dripper
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.GroupPosition
import com.laksh.coffee.ui.components.GroupRow
import com.laksh.coffee.ui.components.LocalToday
import com.laksh.coffee.ui.components.ScreenHeader
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.beanShortName
import com.laksh.coffee.ui.components.brewerFromLabel
import com.laksh.coffee.ui.components.color
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.components.signedClock
import com.laksh.coffee.ui.components.timeStatus
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.listBottomPadding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
fun HistoryScreen(
    onOpenLog: (Long) -> Unit,
    vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = CoffeeTheme.colors
    val days = remember(state.logs) { state.byDay }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("log-list"),
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

            if (state.logs.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    EmptyLog(Modifier.animateItem().padding(horizontal = 16.dp))
                }
            }

            days.forEachIndexed { dayIndex, (day, dayLogs) ->
                item(key = "header-$day", contentType = "day-header") {
                    Row(
                        Modifier
                            .animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            )
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = if (dayIndex == 0) 4.dp else 20.dp,
                                bottom = 8.dp,
                            ),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        EyebrowLabel(dayHeader(day, LocalToday.current))
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
                        position = GroupPosition.of(i, dayLogs.size),
                        analysed = log.id in state.analysedIds,
                        onClick = { onOpenLog(log.id) },
                        modifier = Modifier
                            .testTag("log-row")
                            .animateItem(
                                fadeInSpec = Motion.settle(),
                                placementSpec = Motion.offset(),
                                fadeOutSpec = Motion.quick(),
                            )
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
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

@Composable
private fun EmptyLog(modifier: Modifier = Modifier) {
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
            Text(
                "Brews you finish and rate show up here. The log is where the patterns live.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkMid,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One brew in a day's inset-grouped card. Built on GroupRow here rather than the shared LogRow
 * because the log is the one list that knows each recipe's target: the time delta sits in the
 * meta line in its status colour (the log's "delayed 25m"), and the bed is drawn, not typed.
 */
@Composable
private fun LogEntryRow(
    log: BrewLogEntity,
    targetSec: Int?,
    position: GroupPosition,
    analysed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CoffeeTheme.colors
    // On time says so in words: a green "0:00" beside "3:20" read as a second clock. Only a
    // miss shows the signed delta, because then the number is the news.
    val delta = remember(log.totalTimeSec, targetSec) {
        if (targetSec != null && targetSec > 0 && log.totalTimeSec > 0) {
            val status = timeStatus(log.totalTimeSec, targetSec).first
            status to if (status == Status.Good) "on time" else signedClock(log.totalTimeSec - targetSec)
        } else {
            null
        }
    }
    val deltaColor = delta?.first?.color() ?: Color.Unspecified
    val meta = remember(log, delta, deltaColor) {
        buildAnnotatedString {
            val grind = log.grindClicks?.let { "$it clk" }
                ?: log.grindSetting.takeIf { it.isNotBlank() }?.let { clipWords(it, 14) }
            val time = log.totalTimeSec.takeIf { it > 0 }?.let(::clock)
            append(listOfNotNull(grind, time).joinToString(" · "))
            if (time != null && delta != null) {
                append(" ")
                withStyle(SpanStyle(color = deltaColor, fontWeight = FontWeight.Medium)) {
                    append(delta.second)
                }
            }
            beanShortName(log.beanLabel).takeIf { it.isNotBlank() }?.let {
                if (length > 0) append(" · ")
                append(it)
            }
        }
    }
    val brewer = ((log.brewerId?.let(Brewers::byId) ?: brewerFromLabel(log.brewer))?.shortName ?: log.brewer).uppercase()

    GroupRow(
        position = position,
        modifier = modifier,
        onClick = onClick,
        hairlineInset = 96.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Dimens.LogRowHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 68dp with tighter tracking so 'AEROPRESS' fits; longer names ellipsize.
            Column(Modifier.width(68.dp)) {
                Text(timeLabel(log.startedAt), style = Num.Inline, color = c.inkHigh, maxLines = 1)
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                // A fixed height, so a row with no marks keeps its score top-aligned like the rest.
                Row(
                    modifier = Modifier.height(16.dp),
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
        }
    }
}

private fun dayHeader(day: LocalDate, today: LocalDate): String {
    return when (day) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> DAY_FMT.format(day).uppercase()
    }
}
