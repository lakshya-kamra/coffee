package com.laksh.coffee.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.catalog.GrindRange
import com.laksh.coffee.domain.catalog.SourceRef
import com.laksh.coffee.domain.grind.GrindScale
import com.laksh.coffee.ui.theme.CoffeeTheme

/** Pure wording for grind ranges, shared by the card, the one-line form and the brew-setup hint. */
object GrindRanges {

    /** "75–90 clicks · dial 7.5–9.0", "15–25", or "8" when the range is one setting. */
    fun words(scale: GrindScale, range: GrindRange): String {
        val unit = scale.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val stored = span(scale.format(range.from), scale.format(range.to)) + unit
        val to = scale.reading(range.to)
        // A range that ends on a full turn reads "9.0–10.0"; one that starts on the turn and runs
        // past it keeps both ends in turns ("1 turn + 0.0 to 1 turn + 7.0").
        val from = scale.reading(range.from, inTurns = to != null && ' ' in to)
        if (from == null || to == null) return stored
        val dial = span(from, to)
        return if (scale.dial.showsDialNumber) "$stored · dial $dial" else "$stored · $dial"
    }

    // "9.0–1 turn + 2.0" reads as a sum; a reading that has words in it is joined with "to".
    private fun span(from: String, to: String): String = when {
        from == to -> from
        ' ' in from || ' ' in to -> "$from to $to"
        else -> "$from–$to"
    }

    /** The catalog's ranges in a fixed order, with [first] (the method being brewed) on top. */
    fun ordered(scale: GrindScale, first: BrewMethod?): List<Pair<BrewMethod, GrindRange>> {
        val all = BrewMethod.entries.mapNotNull { m -> scale.ranges[m]?.let { m to it } }
        return all.sortedBy { if (it.first == first) 0 else 1 }
    }

    /** One source per name, keeping the first link given for it. */
    fun sources(scale: GrindScale): List<SourceRef> = scale.rangeSources.filter { it.who.isNotBlank() }.distinctBy { it.who }

    /**
     * Who to credit for a range. The catalog lists sources per grinder, not per range, so only a
     * single source is named as the range's own; several are listed as sources.
     */
    fun credit(scale: GrindScale): String? {
        val s = sources(scale)
        return when {
            s.isEmpty() -> null
            s.size == 1 -> s[0].who
            else -> "${s[0].who} +${s.size - 1}"
        }
    }

    /** The chip that says how far to trust the catalog's numbers for this grinder. */
    fun confidence(confidence: String?): Pair<Status, String>? = when (confidence) {
        "high" -> Status.Good to "From the maker"
        "medium" -> Status.Attention to "Check your manual"
        "low" -> Status.Attention to "Unverified"
        else -> null
    }

    /** One line for a grinder card: "Pour-over 75–90 clicks · dial 7.5–9.0". Null without a range. */
    fun line(scale: GrindScale, method: BrewMethod?): String? {
        val (m, r) = ordered(scale, method).firstOrNull() ?: return null
        return "${m.label} ${words(scale, r)}"
    }
}

/**
 * "STARTING POINTS": one row per range the catalog has for this grinder, the method being brewed
 * first and in full ink, then who the numbers come from (tap to open the source), how sure the
 * catalog is, and the reminder that taste decides. Draws nothing when there are no ranges.
 *
 * [inSheet] gives it the surface2 fill a card needs to show on a sheet.
 */
@Composable
fun GrindRangeCard(
    scale: GrindScale,
    method: BrewMethod?,
    modifier: Modifier = Modifier,
    inSheet: Boolean = false,
) {
    val rows = remember(scale, method) { GrindRanges.ordered(scale, method) }
    if (rows.isEmpty()) return
    val colors = CoffeeTheme.colors
    val sources = remember(scale) { GrindRanges.sources(scale) }
    val chip = GrindRanges.confidence(scale.confidence)
    val uri = LocalUriHandler.current

    SoftCard(
        modifier = modifier.fillMaxWidth(),
        color = if (inSheet) colors.surface2 else colors.surface1,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel("STARTING POINTS", Modifier.weight(1f))
            if (chip != null) StatusChip(status = chip.first, text = chip.second)
        }
        Spacer(Modifier.height(10.dp))
        rows.forEach { (m, range) ->
            val chosen = m == method
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Text(
                    m.label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (chosen) colors.inkHigh else colors.inkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.42f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    GrindRanges.words(scale, range),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (chosen) colors.inkHigh else colors.inkMid,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.58f),
                )
            }
        }
        if (sources.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HairLine()
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "From:",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkLow,
                    modifier = Modifier.heightIn(min = 28.dp).padding(top = 5.dp),
                )
                sources.forEach { s ->
                    val link = s.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    val interaction = remember { MutableInteractionSource() }
                    Text(
                        s.who,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            textDecoration = if (link != null) TextDecoration.Underline else null,
                        ),
                        color = colors.inkMid,
                        modifier = Modifier
                            .heightIn(min = 28.dp)
                            .then(
                                if (link != null) {
                                    Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                                        // No browser installed is not worth a crash.
                                        runCatching { uri.openUri(link) }
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(top = 5.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "A starting point. Taste decides.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = colors.inkLow,
        )
    }
}

/** The collapsed form for a grinder card: the range for [method] (or the first one) on one line. */
@Composable
fun GrindRangeLine(
    scale: GrindScale,
    method: BrewMethod?,
    modifier: Modifier = Modifier,
) {
    val line = remember(scale, method) { GrindRanges.line(scale, method) } ?: return
    val credit = remember(scale) { GrindRanges.credit(scale) }
    Column(modifier) {
        EyebrowLabel("STARTING POINT")
        Spacer(Modifier.height(4.dp))
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = CoffeeTheme.colors.inkHigh,
        )
        if (credit != null) {
            Text(
                "From $credit. Taste decides.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = CoffeeTheme.colors.inkLow,
            )
        }
    }
}
