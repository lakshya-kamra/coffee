package com.laksh.coffee.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.grind.GrindScale
import kotlin.math.roundToLong

/** Where to start on a grinder for a method: the middle of the catalog's range, on a real step. */
data class GrindStart(val value: Double, val label: String, val detail: String) {
    companion object {
        /** Null when the catalog has no range for this grinder and method. */
        fun of(scale: GrindScale, method: BrewMethod?): GrindStart? {
            val range = scale.rangeFor(method) ?: return null
            val step = scale.step.takeIf { it > 0 } ?: 1.0
            val mid = (range.from + range.to) / 2.0
            val value = ((mid / step).roundToLong() * step).coerceIn(range.from, range.to)
            val shown = scale.format(value)
            val span = if (range.from == range.to) scale.format(range.from) else "${scale.format(range.from)}–${scale.format(range.to)}"
            val sources = GrindRanges.sources(scale)
            val detail = when {
                sources.isEmpty() -> "range $span"
                sources.size == 1 -> "${sources[0].who}'s range $span"
                else -> "range $span · sources: ${sources[0].who} +${sources.size - 1}"
            }
            return GrindStart(value, shown, detail)
        }
    }
}

/**
 * One line for brew setup: "Start around 82 · 1Zpresso's range 75–90", with a pill that sets it.
 * Draws nothing when the catalog has no range for this grinder and method.
 */
@Composable
fun GrindStartHint(
    scale: GrindScale,
    method: BrewMethod?,
    onUse: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = remember(scale, method) { GrindStart.of(scale, method) } ?: return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusLine(
            status = Status.Neutral,
            lead = "Start around ${start.label}",
            detail = start.detail,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        PillButton(text = "Use ${start.label}", onClick = { onUse(start.value) })
    }
}
