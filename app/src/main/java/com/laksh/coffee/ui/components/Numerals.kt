package com.laksh.coffee.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.core.clipWords
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num

/** A numeral with its unit after it, baseline to baseline: "500 g", "3:30", "93 °C". */
@Composable
fun NumberWithUnit(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    style: TextStyle = Num.Medium,
    color: Color = Color.Unspecified,
    unitColor: Color = Color.Unspecified,
) {
    val colors = CoffeeTheme.colors
    val unitStyle = remember(style) { Num.unit(style) }
    Row(modifier) {
        Text(
            text = value,
            style = style,
            color = color.takeOrElse { colors.inkHigh },
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.alignByBaseline(),
        )
        if (unit != null) {
            Spacer(Modifier.width(3.dp))
            Text(
                text = unit,
                style = unitStyle,
                color = unitColor.takeOrElse { colors.inkMid },
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/**
 * A number whose digits roll when it steps: up when the value grows, down when it shrinks,
 * and only the digits that changed move. For dose, clicks, temperature and the review score.
 * NEVER the running clock, which stays a plain Text leaf.
 */
@Composable
fun TickingNumber(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Num.Display,
    color: Color = Color.Unspecified,
    unit: String? = null,
    unitColor: Color = Color.Unspecified,
) {
    val colors = CoffeeTheme.colors
    val ink = color.takeOrElse { colors.inkHigh }
    // The previous value lives in a plain holder so recording it never triggers a recomposition.
    val previous = remember { arrayOf(value) }
    val up = remember(value) { numericValue(value) >= numericValue(previous[0]) }
    SideEffect { previous[0] = value }

    val unitStyle = remember(style) { Num.unit(style) }
    // The rolling digits all share one style, so they line up by their tops. Only the unit
    // needs nudging onto the numeral's baseline; measured once per style, not per frame.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val unitTop = remember(style, unitStyle, unit, density) {
        if (unit == null) {
            0.dp
        } else {
            val numeralBaseline = measurer.measure("0", style).firstBaseline
            val unitBaseline = measurer.measure(unit, unitStyle).firstBaseline
            with(density) { (numeralBaseline - unitBaseline).coerceAtLeast(0f).toDp() }
        }
    }

    Row(modifier) {
        value.forEachIndexed { i, c ->
            // Keyed from the right, so the ones digit keeps its slot when the length changes.
            key(value.length - i) {
                if (c.isDigit()) {
                    AnimatedContent(
                        targetState = c,
                        transitionSpec = {
                            val roll = if (up) {
                                (slideInVertically(Motion.offset()) { it } + fadeIn(Motion.decel(140)))
                                    .togetherWith(slideOutVertically(Motion.offset()) { -it } + fadeOut(Motion.decel(140)))
                            } else {
                                (slideInVertically(Motion.offset()) { -it } + fadeIn(Motion.decel(140)))
                                    .togetherWith(slideOutVertically(Motion.offset()) { it } + fadeOut(Motion.decel(140)))
                            }
                            roll using SizeTransform(clip = true)
                        },
                        label = "digit",
                    ) { d ->
                        Text(d.toString(), style = style, color = ink, maxLines = 1, softWrap = false)
                    }
                } else {
                    Text(c.toString(), style = style, color = ink, maxLines = 1, softWrap = false)
                }
            }
        }
        if (unit != null) {
            Spacer(Modifier.width(3.dp))
            Text(
                text = unit,
                style = unitStyle,
                color = unitColor.takeOrElse { colors.inkMid },
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(top = unitTop),
            )
        }
    }
}

private fun numericValue(s: String): Double =
    s.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0

// ----------------------------------------------------------------------- words

/**
 * Words that may not all fit. When [text] overflows [maxLines] it is cut back to the last whole
 * word that fitted and ends in "…" ("Coarse, a step…"). The platform's own ellipsis cuts by
 * character ("Coarse, a ste…"), so it only ever shows for the frame before the cut.
 */
@Composable
fun WordClippedText(
    text: String,
    maxLines: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var shown by remember(text) { mutableStateOf(text) }
    Text(
        text = shown,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { layout ->
            // Once per text: the cut version is no longer than what fitted, so it does not overflow again.
            if (shown == text && layout.hasVisualOverflow) {
                // With an ellipsis on the last line, its visible end is where the ellipsis starts.
                shown = clipWords(text, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            }
        },
    )
}

// ------------------------------------------------------------------------ grid

/**
 * One cell of a [DataGrid]: the label sits above the value.
 *
 * @param status colours the value for Good, Attention and Fault; anything else stays inkHigh.
 * @param textValue render [value] as words (e.g. GRIND "Medium-fine") instead of a numeral.
 * @param subStatus turns [sub] into a StatusChip (e.g. "IGNORED" on a distrusted number).
 * @param subChip false keeps a [subStatus] sub as a plain line in the status colour.
 */
@Immutable
data class Cell(
    val label: String,
    val value: String?,
    val unit: String? = null,
    val sub: String? = null,
    val status: Status? = null,
    val textValue: Boolean = false,
    val subStatus: Status? = null,
    val subChip: Boolean = true,
)

/**
 * Label-above-value cells in equal columns, split by 1px rules. A short last row is padded
 * with empty space rather than empty ruled cells.
 */
@Composable
fun DataGrid(
    cells: List<Cell>,
    columns: Int,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = Num.Medium,
    compact: Boolean = false,
) {
    val cols = columns.coerceAtLeast(1)
    val rows = remember(cells, cols) { cells.chunked(cols) }
    val v = if (compact) 8.dp else 12.dp
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { r, row ->
            if (r > 0) HairLine()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (col in 0 until cols) {
                    val cell = row.getOrNull(col)
                    if (cell == null) {
                        Box(Modifier.weight(1f))
                    } else {
                        if (col > 0) VerticalHairline()
                        GridCell(
                            cell = cell,
                            valueStyle = valueStyle,
                            compact = compact,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = if (col == 0) 0.dp else 14.dp, top = v, bottom = v),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(
    cell: Cell,
    valueStyle: TextStyle,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    val style = if (compact) Num.Small.copy(fontSize = 17.sp, lineHeight = 20.sp) else valueStyle
    val st = cell.status
    val valueColor = if (st != null && (st == Status.Good || st == Status.Attention || st == Status.Fault)) {
        st.color()
    } else {
        colors.inkHigh
    }
    Column(modifier) {
        EyebrowLabel(cell.label)
        Spacer(Modifier.height(4.dp))
        when {
            cell.value == null -> Text("—", style = style, color = colors.inkLow, maxLines = 1)
            cell.textValue -> WordClippedText(
                text = cell.value,
                maxLines = if (compact) 1 else 2,
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkHigh,
            )
            else -> NumberWithUnit(
                value = cell.value,
                unit = cell.unit,
                style = style,
                color = valueColor,
            )
        }
        if (cell.sub != null) {
            Spacer(Modifier.height(2.dp))
            if (cell.subStatus != null && cell.subChip) {
                StatusChip(cell.subStatus, cell.sub)
            } else {
                // A plain line, tinted when it carries a status: lighter than a chip, for a
                // grid where the other cells have plain subs too.
                Text(
                    text = cell.sub,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = cell.subStatus?.color() ?: colors.inkLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
