package com.laksh.coffee.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.core.clock
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.ui.theme.CoffeeColors
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Num

/**
 * One stop on a timeline. Planned rows are grey; actual rows are bold, coloured by
 * [deltaStatus], and carry a [delta] chip. To show planned and actual on the SAME row (the
 * Flighty way), put the actual time in [time] and the plan it replaced in [planned]; the
 * plan is shown struck through under it.
 */
@Immutable
data class TimelineRow(
    val time: String,
    val kind: String?,
    val title: String,
    val detail: String? = null,
    val trailing: String? = null,
    val actual: Boolean = false,
    val delta: String? = null,
    val deltaStatus: Status? = null,
    val terminal: Boolean = false,
    val planned: String? = null,
)

private val TimeColumn = 52.dp
private val RailColumn = 20.dp
private val RowPadV = 10.dp

/**
 * A vertical timeline with a continuous rail. It has no card of its own: put it in a
 * SoftCard. The last actual row gets a surface2 band so "where you ended up" stands out.
 * [detailMaxLines] caps each step's detail; screens that teach the method pass
 * Int.MAX_VALUE so an instruction is never cut mid-sentence.
 */
@Composable
fun TimelineCard(
    rows: List<TimelineRow>,
    modifier: Modifier = Modifier,
    detailMaxLines: Int = 2,
) {
    val colors = CoffeeTheme.colors
    val highlight = rows.indexOfLast { it.actual }
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, row ->
            TimelineRowView(
                row = row,
                first = i == 0,
                last = i == rows.lastIndex,
                highlighted = i == highlight,
                colors = colors,
                detailMaxLines = detailMaxLines,
            )
        }
    }
}

@Composable
private fun TimelineRowView(
    row: TimelineRow,
    first: Boolean,
    last: Boolean,
    highlighted: Boolean,
    colors: CoffeeColors,
    detailMaxLines: Int,
) {
    val status = row.deltaStatus
    val nodeInk = if (row.actual && status != null) status.color() else colors.inkHigh
    val timeInk = when {
        !row.actual -> colors.inkMid
        status == Status.Good || status == Status.Attention || status == Status.Fault -> nodeInk
        else -> colors.inkHigh
    }
    val band = colors.surface2
    val rail = colors.inkFaint
    val plannedInk = colors.inkMid
    val terminalInk = colors.inkMid

    Row(
        Modifier
            .fillMaxWidth()
            // The rail is drawn at row level, through the padding, so it runs unbroken from
            // row to row instead of stopping 10dp short at each edge.
            .drawBehind {
                if (highlighted) {
                    val bleed = 8.dp.toPx()
                    drawRoundRect(
                        color = band,
                        topLeft = Offset(-bleed, 0f),
                        size = Size(size.width + 2f * bleed, size.height),
                        cornerRadius = CornerRadius(14.dp.toPx()),
                    )
                }
                val cx = TimeColumn.toPx() + RailColumn.toPx() / 2f
                val cy = RowPadV.toPx() + 10.dp.toPx()
                val railW = 2.dp.toPx()
                val nodeR = when {
                    row.terminal -> 5.dp.toPx()
                    row.actual -> 5.dp.toPx()
                    else -> 4.dp.toPx()
                }
                // Actual and terminal nodes get a gap in the rail around them; planned dots
                // simply sit on it.
                val gap = when {
                    row.terminal -> nodeR
                    row.actual -> nodeR + 3.dp.toPx()
                    else -> 0f
                }
                val top = if (first) cy else 0f
                val bottom = if (row.terminal || last) cy else size.height
                if (top < cy - gap) {
                    drawLine(rail, Offset(cx, top), Offset(cx, cy - gap), strokeWidth = railW)
                }
                if (bottom > cy + gap) {
                    drawLine(rail, Offset(cx, cy + gap), Offset(cx, bottom), strokeWidth = railW)
                }
                when {
                    row.terminal -> {
                        val sw = 1.5.dp.toPx()
                        drawCircle(terminalInk, radius = nodeR - sw / 2f, center = Offset(cx, cy), style = Stroke(sw))
                    }
                    row.actual -> drawCircle(nodeInk, radius = nodeR, center = Offset(cx, cy))
                    else -> drawCircle(plannedInk, radius = nodeR, center = Offset(cx, cy))
                }
            }
            .padding(vertical = RowPadV),
    ) {
        Column(Modifier.width(TimeColumn)) {
            Text(
                text = row.time,
                style = Num.Inline,
                color = timeInk,
                maxLines = 1,
            )
            if (row.planned != null) {
                Text(
                    text = row.planned,
                    style = Num.Inline.copy(fontSize = 13.sp, textDecoration = TextDecoration.LineThrough),
                    color = colors.inkLow,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(RailColumn))
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            if (row.kind != null) {
                Text(row.kind.toCaps(), style = Micro, color = colors.inkLow, maxLines = 1)
            }
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (row.actual) FontWeight.Bold else FontWeight.Medium,
                ),
                color = colors.inkHigh,
            )
            if (row.detail != null) {
                Text(
                    text = row.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMid,
                    maxLines = detailMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (row.actual && row.delta != null) {
            Spacer(Modifier.width(8.dp))
            StatusChip(status ?: Status.Neutral, row.delta)
        } else if (row.trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.trailing,
                style = Num.Inline,
                color = colors.inkHigh,
                maxLines = 1,
            )
        }
    }
}

/** The recipe as a planned timeline: every step, then a terminal "Done" at the total time. */
fun Recipe.plannedTimelineRows(): List<TimelineRow> =
    steps.map { s ->
        TimelineRow(
            time = clock(s.startSec),
            kind = s.kind.label,
            title = s.title,
            detail = s.detail.ifBlank { null },
            trailing = s.targetWaterG?.let { "$it g" },
        )
    } + TimelineRow(
        time = clock(totalSec),
        kind = null,
        title = "Done",
        terminal = true,
    )
