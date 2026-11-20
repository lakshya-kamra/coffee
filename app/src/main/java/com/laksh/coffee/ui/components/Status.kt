package com.laksh.coffee.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.Channeling
import com.laksh.coffee.domain.FlowFeel
import com.laksh.coffee.domain.ShotTarget
import com.laksh.coffee.domain.Sludge
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.PillShape
import kotlin.math.abs

/**
 * The one status vocabulary. Colour means something or it is absent, and a status colour is
 * always shown next to a word. Crema is [Live] only: it never marks good or bad.
 */
enum class Status { Live, Good, Neutral, Attention, Fault, Unrated }

/**
 * The status colour, safe to use for text. Unrated resolves to inkLow rather than inkFaint,
 * because inkFaint words vanish on light surfaces (about 1.7:1).
 */
@Composable
@ReadOnlyComposable
fun Status.color(): Color {
    val c = CoffeeTheme.colors
    return when (this) {
        // cremaInk, not crema: light crema is under 4.5:1 on the brew washes.
        Status.Live -> c.cremaInk
        Status.Good -> c.good
        Status.Neutral -> c.inkHigh
        Status.Attention -> c.attention
        Status.Fault -> c.fault
        Status.Unrated -> c.inkLow
    }
}

/** The tinted fill behind a status: chips, selected chips, the analysis verdict. */
@Composable
@ReadOnlyComposable
fun Status.container(): Color {
    val c = CoffeeTheme.colors
    return when (this) {
        Status.Live -> c.cremaContainer
        Status.Good -> c.goodContainer
        Status.Neutral -> c.surface2
        Status.Attention -> c.attentionContainer
        Status.Fault -> c.faultContainer
        Status.Unrated -> c.surface2
    }
}

// ------------------------------------------------------------------ pure mappers

/** 8 to 10 good, 6 to 7 neutral, 4 to 5 attention, 1 to 3 fault, 0 not rated. */
fun scoreStatus(score: Int): Status = when (score) {
    in 8..10 -> Status.Good
    in 6..7 -> Status.Neutral
    in 4..5 -> Status.Attention
    in 1..3 -> Status.Fault
    else -> Status.Unrated
}

/** Total time against the recipe target, with the words to show beside it. */
fun timeStatus(actualSec: Int, targetSec: Int): Pair<Status, String> {
    if (targetSec <= 0) return Status.Neutral to "No target"
    if (actualSec <= 0) return Status.Unrated to "No time"
    val d = actualSec - targetSec
    val words = (if (d < 0) "Fast by " else "Slow by ") + mss(abs(d))
    return when {
        abs(d) <= 15 -> Status.Good to "On time"
        abs(d) <= 45 -> Status.Attention to words
        else -> Status.Fault to words
    }
}

/**
 * A shot's time against its band (A4), in shot mode's words: "In the band", else fast or slow by
 * the seconds past the nearer edge. Never a fault: a shot a few seconds out is a grind note.
 */
fun shotTimeStatus(actualSec: Int, target: ShotTarget): Pair<Status, String> {
    if (actualSec <= 0) return Status.Unrated to "No time"
    val d = target.offBy(actualSec)
    return if (d == 0) Status.Good to "In the band" else Status.Attention to ((if (d < 0) "Fast by " else "Slow by ") + mss(abs(d)))
}

fun flowStatus(flow: FlowFeel): Status? = when (flow) {
    FlowFeel.OnTarget -> Status.Good
    FlowFeel.Slow, FlowFeel.Fast -> Status.Attention
    FlowFeel.Stalled, FlowFeel.Gusher -> Status.Fault
    FlowFeel.Unset -> null
}

fun bedStatus(bed: BedShape): Status? = when (bed) {
    BedShape.Flat -> Status.Good
    BedShape.Domed -> Status.Neutral
    BedShape.Crater, BedShape.HighAndDry, BedShape.Sloped -> Status.Attention
    BedShape.Sinkhole, BedShape.Muddy -> Status.Fault
    BedShape.Unset -> null
}

fun sludgeStatus(s: Sludge): Status? = when (s) {
    Sludge.None -> Status.Good
    Sludge.Light -> Status.Neutral
    Sludge.Heavy -> Status.Attention
    Sludge.Unset -> null
}

fun channelingStatus(c: Channeling): Status? = when (c) {
    Channeling.None -> Status.Good
    Channeling.Suspected -> Status.Attention
    Channeling.Obvious -> Status.Fault
    Channeling.Unset -> null
}

/** Days off roast: resting (0 to 3) and fading (31 and up) both deserve a look. */
fun roastStatus(days: Long?): Status {
    if (days == null) return Status.Unrated
    return when (days.coerceAtLeast(0L)) {
        in 0L..3L -> Status.Attention
        in 4L..30L -> Status.Neutral
        else -> Status.Attention
    }
}

fun roastWord(days: Long?): String? {
    if (days == null) return null
    return when (days.coerceAtLeast(0L)) {
        in 0L..3L -> "resting"
        in 4L..30L -> null
        else -> "fading"
    }
}

fun cupLabel(score: Int): String = when (score) {
    in 8..10 -> "GREAT CUP"
    in 6..7 -> "GOOD"
    in 4..5 -> "OFF"
    in 1..3 -> "FAULTY"
    else -> "UNRATED"
}

/** "+0:12", "−0:41" (a real minus sign, so the column does not shift), or "0:00". */
fun signedClock(deltaSec: Int): String = when {
    deltaSec == 0 -> "0:00"
    deltaSec < 0 -> "−" + mss(-deltaSec)
    else -> "+" + mss(deltaSec)
}

private fun mss(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

// ------------------------------------------------------------------ composables

/**
 * A status dot. When [live] it breathes: the core fades 0.35 to 1 while a halo grows and
 * fades out. The transition is read only inside drawBehind, so nothing recomposes per frame.
 */
@Composable
fun StatusDot(
    status: Status,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    live: Boolean = false,
) {
    // A live dot is a mark, not a word, so it keeps the brighter crema fill. A neutral dot is
    // the quietest mark, as on the charts, so it never outshouts an amber or red one beside it.
    val color = when (status) {
        Status.Live -> CoffeeTheme.colors.crema
        Status.Neutral -> CoffeeTheme.colors.neutralMark
        else -> status.color()
    }
    // Inside drawBehind, `size` would mean the DrawScope's Size, so keep the Dp under its own name.
    val dot = size
    if (live) {
        val pulse = rememberDotPulse()
        Spacer(
            modifier
                .size(dot * 2)
                .drawBehind {
                    val v = pulse.value
                    val core = dot.toPx() / 2f
                    drawCircle(color, radius = core + core * v, alpha = 0.25f * (1f - v))
                    drawCircle(color, radius = core, alpha = 0.35f + 0.65f * v)
                },
        )
    } else {
        Spacer(modifier.size(size).drawBehind { drawCircle(color) })
    }
}

@Composable
private fun rememberDotPulse() = rememberLoop(1100, reverse = true, easing = EaseInOutSine)

/** A 22dp pill: dot plus an UPPERCASE word, tinted by the status. */
@Composable
fun StatusChip(
    status: Status,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            // A minimum: at 200 % text the word grows the pill instead of being clipped.
            .heightIn(min = 22.dp)
            .clip(PillShape)
            .background(status.container())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status, size = 6.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = text.toCaps(),
            style = Micro,
            // Neutral in inkLow rather than inkHigh: 4.8 to 5.0:1 on surface2, level with the
            // status words on their containers but without their hue, so GOOD sits back beside OFF.
            color = if (status == Status.Neutral) CoffeeTheme.colors.inkLow else status.color(),
            maxLines = 1,
        )
    }
}

/**
 * The answer before the details: a dot, a lead in the status colour, then " · detail" in
 * inkMid. It recomposes only when its arguments change, so never feed it a ticking value
 * (the running clock, grams on the scale); those belong in their own leaf Text.
 */
@Composable
fun StatusLine(
    status: Status,
    lead: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    live: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = CoffeeTheme.colors
    val leadColor = when (status) {
        Status.Neutral -> colors.inkHigh
        Status.Unrated -> colors.inkMid
        else -> status.color()
    }
    val detailColor = colors.inkMid
    val annotated = remember(lead, detail, leadColor, detailColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = leadColor, fontWeight = FontWeight.Medium)) { append(lead) }
            if (detail != null) {
                withStyle(SpanStyle(color = detailColor)) { append(" · $detail") }
            }
        }
    }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 24.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        // Top, not centred: when the text wraps, the dot stays beside the first line instead
        // of floating between the two. The pad centres it on bodyMedium's 21sp first line.
        verticalAlignment = Alignment.Top,
    ) {
        StatusDot(status, live = live, modifier = Modifier.padding(top = if (live) 2.5.dp else 6.5.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
