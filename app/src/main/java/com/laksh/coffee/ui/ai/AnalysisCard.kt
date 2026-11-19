package com.laksh.coffee.ui.ai

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.data.ai.StaleReason
import com.laksh.coffee.data.ai.StoredAnalysis
import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.BrewAnalysis
import com.laksh.coffee.domain.ai.DataFlag
import com.laksh.coffee.domain.ai.Fields
import com.laksh.coffee.domain.ai.FlagSeverity
import com.laksh.coffee.domain.ai.GrindMove
import com.laksh.coffee.domain.ai.ModelCatalog
import com.laksh.coffee.domain.ai.ValueMove
import com.laksh.coffee.ui.components.CoffeeMug
import com.laksh.coffee.ui.components.Dripper
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.NumberWithUnit
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SkeletonBar
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.components.StatusDot
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.VerticalHairline
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.WellShape
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// One visual language for the brew card and the patterns card. The state machines live in the
// sections; everything here only draws what it is given.

// ------------------------------------------------------------------ verdict words

internal fun verdictStatus(v: String): Status = when (v) {
    "dialled_in" -> Status.Good
    "close" -> Status.Neutral
    "needs_change" -> Status.Attention
    "fault" -> Status.Fault
    else -> Status.Neutral
}

internal fun verdictChip(v: String): String = when (v) {
    "dialled_in" -> "DIALLED IN"
    "close" -> "CLOSE"
    "needs_change" -> "NEEDS A TWEAK"
    "fault" -> "SOMETHING'S WRONG"
    else -> "READ"
}

// A verdict word opening the headline as its own clause ("Dialled in: …", "Close. …"). A comma
// is left alone, since "Close, but…" is one sentence, and so is a hyphen with no space before
// it ("Fault-free…").
private val VERDICT_OPENER = Regex(
    """^(dial{1,2}ed in|close|needs a tweak|needs (a )?change|something['’]?s wrong|fault)(?:\s*[:;.!—–]+|\s+-+)(?:\s+|$)""",
    RegexOption.IGNORE_CASE,
)

/**
 * The headline without a verdict word at its start ("Dialled in: sweet, clean…" becomes "Sweet,
 * clean…"). The chip already says the verdict; the headline's job is what the cup was.
 */
internal fun headlineWithoutVerdict(headline: String): String {
    val h = headline.trim()
    val opener = VERDICT_OPENER.find(h) ?: return h
    return h.substring(opener.range.last + 1).trim().replaceFirstChar { it.uppercaseChar() }
}

/** Some replies skip the verdict. The change itself still says whether anything needs to move. */
internal fun effectiveVerdict(a: BrewAnalysis): String =
    a.verdict.ifBlank { if (a.change.variable == "none") "dialled_in" else "needs_change" }

internal fun trajectoryStatus(t: String): Status = when (t) {
    "improving" -> Status.Good
    "steady" -> Status.Neutral
    "stuck" -> Status.Attention
    "erratic" -> Status.Fault
    else -> Status.Neutral
}

internal fun trajectoryChip(t: String): String = when (t) {
    "improving" -> "IMPROVING"
    "steady" -> "STEADY"
    "stuck" -> "STUCK"
    "erratic" -> "ERRATIC"
    else -> "READ"
}

internal fun toneStatus(tone: String): Status = when (tone) {
    "good" -> Status.Good
    "attention" -> Status.Attention
    "fault" -> Status.Fault
    else -> Status.Neutral
}

internal fun strengthLabel(strength: String): String = when (strength) {
    "strong" -> "STRONG"
    "too_little_data" -> "TOO LITTLE DATA"
    else -> "WEAK"
}

internal fun plural(n: Int, one: String, many: String = one + "s"): String = if (n == 1) "1 $one" else "$n $many"

/** "this brew and the 8 before it", worded properly for none and one. */
internal fun brewScope(before: Int): String = when {
    before <= 0 -> "this brew"
    before == 1 -> "this brew and the one before it"
    else -> "this brew and the $before before it"
}

/**
 * Model text as the app writes it: replies (and older samples) use an ASCII "->", while every
 * other number move in the app uses a real arrow. The spaces either side are no-break, so
 * "66 → 64" wraps as one piece and never opens a line with the arrow. Display only; the stored
 * reply is untouched.
 */
internal fun String.arrows(): String = replace(" -> ", " → ").replace(" → ", " → ")

/**
 * The opening of the no-key copy. It names the host a key is needed for, or says where the
 * saved key belongs, instead of always asking for a CommandCode key.
 */
internal fun noKeyLead(host: String, keyHost: String, providerName: String = ""): String = when {
    // Nothing chosen yet (a fresh install): any provider will do.
    host.isEmpty() && providerName.isEmpty() -> "Choose a provider, add your own key"
    keyHost.isNotEmpty() && keyHost != host -> "Your key is saved for $keyHost. Paste one for $host"
    providerName.isNotEmpty() -> "Add your ${providerName.substringBefore(" (")} key"
    else -> "Add your key for $host"
}

/**
 * The words for an out-of-date answer. [edited] is the caller's own line for "the data moved on"
 * (a brew and the whole log say it differently); the other two reasons read the same everywhere.
 */
internal fun staleText(reason: StaleReason, edited: String): String = when (reason) {
    StaleReason.Edited -> edited
    StaleReason.OlderPrompt -> "Made by an older version of the analysis · re-run to use your grinder's scale"
    StaleReason.OtherDevice -> "Made on another device · re-run here for a fresh read"
}

/** Body text at an exact size, built on the theme so it keeps Roboto and tabular figures. */
@Composable
@ReadOnlyComposable
internal fun aiText(size: Int, line: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = 0.sp,
    )

/**
 * Fades between card states. No size transform here: the panel's animateContentSize carries
 * the height change, so the two never fight over it.
 */
internal fun <S> stateFade(): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    fadeIn(Motion.decel(260)) togetherWith fadeOut(Motion.decel(140)) using null
}

// -------------------------------------------------------------------------- panel

@Composable
internal fun AnalysisPanel(
    eyebrow: String,
    chip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = CoffeeTheme.colors
    SoftCard(modifier.fillMaxWidth().animateContentSize(Motion.size())) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = c.inkLow,
            )
            Spacer(Modifier.width(6.dp))
            EyebrowLabel(eyebrow)
            Spacer(Modifier.weight(1f))
            chip()
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** Holds roughly the height of the ready state while the first local read finishes. */
@Composable
internal fun LocalSkeleton() {
    Column(Modifier.fillMaxWidth()) {
        SkeletonBar(0.6f, 18.dp)
        Spacer(Modifier.height(8.dp))
        SkeletonBar(0.9f, 14.dp)
        Spacer(Modifier.height(6.dp))
        SkeletonBar(0.75f, 14.dp)
        Spacer(Modifier.height(14.dp))
        SkeletonBar(1f, 52.dp, radius = 18.dp)
    }
}

// --------------------------------------------------------------------- empty states

/** Title and body on the left, a small piece of coffee art on the right. */
@Composable
internal fun EmptyStateRow(
    art: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), content = content)
        Spacer(Modifier.width(12.dp))
        // The art draws at 120-140 dp by default; this box is what makes it small.
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { art() }
    }
}

@Composable
internal fun SmallDripper() {
    val c = CoffeeTheme.colors
    Dripper(
        waterLevel = 0.35f,
        dripping = false,
        shell = c.artShell,
        water = c.artWater,
        bed = c.artBed,
    )
}

@Composable
internal fun SmallMug(fill: Float) {
    val c = CoffeeTheme.colors
    CoffeeMug(
        fill = fill,
        body = c.artShell,
        liquid = c.artBed,
        crema = c.artWater,
        showSteam = false,
    )
}

// ---------------------------------------------------------------------- the change

@Composable
internal fun ChangeHero(
    eyebrow: String,
    variableLabel: String?,
    move: GrindMove?,
    value: ValueMove?,
    instruction: String,
    summary: String?,
    footerMicro: String? = null,
) {
    val c = CoffeeTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface2)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel(eyebrow)
            Spacer(Modifier.weight(1f))
            // Callers pass it already upper-cased, so a µ never turns into a Greek capital.
            if (variableLabel != null) {
                Text(
                    variableLabel,
                    style = Micro,
                    color = c.inkLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val from = move?.from
        when {
            move != null && from != null -> FromTo(from.toString(), move.to.toString(), "clicks")
            move != null -> NumberWithUnit(move.to.toString(), unit = unitWord(move.to, "click"), style = Num.Large)
            value != null -> FromTo(value.from, value.to, value.unit.ifBlank { null })
            else -> Text(
                instruction.arrows(),
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp, lineHeight = 28.sp),
                color = c.inkHigh,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!summary.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                summary.arrows(),
                style = aiText(14, 20),
                color = c.inkMid,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (footerMicro != null) {
            Spacer(Modifier.height(6.dp))
            Text(footerMicro, style = Micro, color = c.inkLow)
        }
    }
}

/**
 * A hero row of values that differ from one brew to the next ("100 → 94 → 97 °C"), in the
 * same numerals as THE ONE CHANGE, so the one thing being tested is the biggest thing shown.
 */
@Composable
internal fun ValueSeries(values: List<String>, unit: String?) {
    val c = CoffeeTheme.colors
    val numeral = if (values.sumOf { it.length } > 9) Num.Medium else Num.Large
    val arrow = numeral.copy(
        fontSize = (numeral.fontSize.value * 0.7f).sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
    Row {
        values.forEachIndexed { i, v ->
            if (i > 0) {
                Spacer(Modifier.width(8.dp))
                Text("→", style = arrow, color = c.inkLow, maxLines = 1, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(8.dp))
            }
            Text(v, style = numeral, color = c.inkHigh, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        }
        if (unit != null) {
            Spacer(Modifier.width(3.dp))
            Text(unit, style = Num.unit(numeral), color = c.inkMid, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
    }
}

@Composable
private fun FromTo(from: String, to: String, unit: String?) {
    val c = CoffeeTheme.colors
    // Long values (1:16 → 1:15) drop a size so the hero row never wraps.
    val numeral = if (from.length + to.length > 7) Num.Medium else Num.Large
    val arrow = numeral.copy(
        fontSize = (numeral.fontSize.value * 0.7f).sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
    Row {
        Text(from, style = numeral, color = c.inkLow, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(8.dp))
        Text("→", style = arrow, color = c.inkLow, maxLines = 1, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(8.dp))
        Text(to, style = numeral, color = c.inkHigh, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        if (unit != null) {
            Spacer(Modifier.width(3.dp))
            Text(unit, style = Num.unit(numeral), color = c.inkMid, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
    }
}

// ----------------------------------------------------------------------- outcomes

@Composable
internal fun OutcomeGrid(ifRight: String, ifWrong: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        OutcomeCell(Status.Good, "IF IT WORKS", ifRight, Modifier.weight(1f).padding(end = 14.dp))
        VerticalHairline()
        OutcomeCell(Status.Attention, "IF IT DOESN'T", ifWrong, Modifier.weight(1f).padding(start = 14.dp))
    }
}

@Composable
private fun OutcomeCell(status: Status, label: String, text: String, modifier: Modifier) {
    val c = CoffeeTheme.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status, size = 6.dp)
            Spacer(Modifier.width(6.dp))
            EyebrowLabel(label)
        }
        Spacer(Modifier.height(4.dp))
        // Never cut short: these are the two sentences the user acts on next brew.
        Text(
            text.arrows().ifBlank { "—" },
            style = aiText(14, 20),
            color = if (text.isBlank()) c.inkLow else c.inkHigh,
        )
    }
}

@Composable
internal fun WarningRows(lines: List<String>) {
    val c = CoffeeTheme.colors
    Column(Modifier.fillMaxWidth()) {
        lines.take(3).forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 1.dp).size(16.dp),
                    tint = c.attention,
                )
                Spacer(Modifier.width(8.dp))
                Text(line.arrows(), style = aiText(13, 18), color = c.inkMid)
            }
        }
    }
}

// ------------------------------------------------------------------------- footer

private val FOOTER_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

@Composable
internal fun UsageFooter(
    stored: StoredAnalysis,
    stale: Boolean,
    staleText: String,
    onRerun: () -> Unit,
    rerunEnabled: Boolean,
) {
    val c = CoffeeTheme.colors
    // Short enough for one line beside Re-run; a lone "REPLY" wrapping under it looked broken.
    val line1 = if (stored.isSample) {
        "SAMPLE · NOT A MODEL REPLY"
    } else {
        // "CLAUDE HAIKU 5.5 · ANTHROPIC": who answered, without the catalog's bracketed aside.
        listOf(stored.modelLabel, stored.providerName.substringBefore(" ("))
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .uppercase()
    }
    val line2 = remember(stored.id, stored.usage, stored.createdAt, stored.photoCount) {
        listOf(
            stored.usage.label(),
            if (stored.photoCount > 0) plural(stored.photoCount, "photo") else "",
            FOOTER_TIME.format(Instant.ofEpochMilli(stored.createdAt)),
        )
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(14.dp))
        HairLine()
        Spacer(Modifier.height(10.dp))
        if (stale) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Status.Attention, size = 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(staleText, style = aiText(12, 16, FontWeight.Medium), color = c.attention)
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(line1, style = Micro, color = c.inkLow, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(line2, style = aiText(11, 15), color = c.inkLow, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            if (stale) {
                SecondaryButton(
                    "Re-run",
                    onRerun,
                    icon = Icons.Rounded.Refresh,
                    height = 40.dp,
                    enabled = rerunEnabled,
                )
            } else {
                QuietButton("Re-run", onRerun, icon = Icons.Rounded.Refresh, enabled = rerunEnabled)
            }
        }
    }
}

// ------------------------------------------------------------------------ loading

/**
 * While a model reads: a mug slowly filling with steam, the seconds so far and what is normal.
 * Only the mug and the seconds text read the ticking state, so only they recompose.
 */
@Composable
internal fun LoadingBody(lead: String, detail: String, sinceMs: Long, onCancel: () -> Unit) {
    val c = CoffeeTheme.colors
    val elapsed = rememberElapsedSeconds(sinceMs)
    Column(Modifier.fillMaxWidth()) {
        StatusLine(Status.Live, lead, detail = detail.ifBlank { null }, live = true)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrewingMug(elapsed)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                SkeletonBar(0.9f, 18.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonBar(0.6f, 18.dp)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ElapsedLeaf(elapsed, prefix = "", size = 12)
                    Text(" · usually 10–40 s", style = aiText(12, 16), color = c.inkLow)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SkeletonBar(0.55f, 44.dp, radius = 16.dp)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { SkeletonBar(0.8f, 14.dp) }
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f)) { SkeletonBar(0.8f, 14.dp) }
        }
        Spacer(Modifier.height(8.dp))
        QuietButton("Cancel", onCancel)
    }
}

/** A 1 Hz seconds line for anything waiting on the network, e.g. "Waiting for GLM-5.3 Flash · 4s". */
@Composable
internal fun ElapsedLine(sinceMs: Long, prefix: String) {
    val elapsed = rememberElapsedSeconds(sinceMs)
    ElapsedLeaf(elapsed, prefix = prefix, size = 13)
}

@Composable
private fun ElapsedLeaf(elapsed: State<Int>, prefix: String, size: Int) {
    Text(
        "$prefix${elapsed.value}s",
        style = aiText(size, size + 5, FontWeight.Medium),
        color = CoffeeTheme.colors.inkLow,
        maxLines = 1,
    )
}

@Composable
private fun BrewingMug(elapsed: State<Int>) {
    val c = CoffeeTheme.colors
    // Fills over roughly the 10-40 s a read takes: never empty, never brimming.
    val fill = (0.15f + elapsed.value / 45f).coerceAtMost(0.9f)
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        CoffeeMug(
            fill = fill,
            body = c.artShell,
            liquid = c.artBed,
            crema = c.artWater,
            showSteam = true,
        )
    }
}

@Composable
private fun rememberElapsedSeconds(sinceMs: Long): State<Int> =
    produceState(elapsedSeconds(sinceMs), sinceMs) {
        while (true) {
            value = elapsedSeconds(sinceMs)
            // Wake on the next whole second so the count never skips or stutters.
            delay(1_000L - (System.currentTimeMillis() - sinceMs).mod(1_000L))
        }
    }

private fun elapsedSeconds(sinceMs: Long): Int =
    ((System.currentTimeMillis() - sinceMs) / 1_000L).toInt().coerceAtLeast(0)

// ------------------------------------------------------------------ errors and raw

@Composable
internal fun ErrorBody(
    error: AiError,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CoffeeTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(error.title, style = aiText(17, 22, FontWeight.Medium), color = c.inkHigh)
        Spacer(Modifier.height(4.dp))
        // The error's own words only: never exception text or a stack trace.
        Text(error.message, style = aiText(13, 18), color = c.inkMid)
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (error.retryable) {
                SecondaryButton("Try again", onRetry, icon = Icons.Rounded.Refresh, height = 40.dp)
            }
            if (error.opensSettings) {
                QuietButton("AI settings", onOpenSettings, icon = Icons.Rounded.Tune)
            }
            QuietButton("Dismiss", onDismiss)
        }
    }
}

@Composable
internal fun RawReplyBody(raw: String) {
    val c = CoffeeTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val long = remember(raw) { raw.length > 480 || raw.count { it == '\n' } >= 12 }
    Column(Modifier.fillMaxWidth()) {
        Text("Couldn't read the structured answer", style = aiText(17, 22, FontWeight.Medium), color = c.inkHigh)
        Spacer(Modifier.height(2.dp))
        Text("Here's what the model said, as it said it.", style = aiText(13, 18), color = c.inkMid)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(WellShape)
                .background(c.surface2)
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    raw.ifBlank { "(an empty reply)" },
                    style = aiText(12, 17),
                    color = c.inkMid,
                    maxLines = if (expanded) Int.MAX_VALUE else 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (long) {
            QuietButton(if (expanded) "Show less" else "Show all", { expanded = !expanded })
        }
    }
}

// ---------------------------------------------------------------------- log check

// Fields the Review screen can edit, so "Fix in log" leads somewhere for them.
private val LOG_EDITABLE = setOf(
    Fields.GRIND,
    Fields.BEVERAGE,
    Fields.EY,
    Fields.DRAWDOWN,
    Fields.TASTE,
    Fields.OVERALL,
    Fields.TIME,
)

/** Where to fix a field that doesn't live on the brew log itself. */
private fun elsewhereHint(field: String): String? = when (field) {
    Fields.ROAST -> "Set the roast date in Gear › Beans; brews after that record it."
    Fields.BEAN -> "Pick the coffee on the brew screen next time."
    else -> null
}

/** One line for above the analysis, so the answer stays in the first screen. */
@Composable
internal fun LogCheckLine(flags: List<DataFlag>, modifier: Modifier = Modifier) {
    val ignored = remember(flags) {
        flags.filter { it.severity == FlagSeverity.Distrust }.map { it.fieldLabel }.distinct()
    }
    if (ignored.isNotEmpty()) {
        StatusLine(
            status = Status.Attention,
            lead = if (ignored.size == 1) "1 field ignored" else "${ignored.size} fields ignored",
            modifier = modifier,
            detail = ignored.joinToString(", "),
        )
    } else {
        val labels = remember(flags) { flags.map { it.fieldLabel }.distinct() }
        StatusLine(
            status = Status.Neutral,
            lead = if (flags.size == 1) "1 note on this log" else "${flags.size} notes on this log",
            modifier = modifier,
            detail = labels.joinToString(", "),
        )
    }
}

@Composable
internal fun LogCheckCard(flags: List<DataFlag>, onFix: (() -> Unit)?, modifier: Modifier = Modifier) {
    val ignored = remember(flags) {
        flags.filter { it.severity == FlagSeverity.Distrust }.map { it.field }.distinct().size
    }
    val fixable = remember(flags) { flags.any { it.field in LOG_EDITABLE } }
    SoftCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel("LOG CHECK")
            Spacer(Modifier.weight(1f))
            if (ignored > 0) {
                StatusChip(Status.Attention, "$ignored IGNORED")
            } else {
                StatusChip(Status.Neutral, if (flags.size == 1) "1 NOTE" else "${flags.size} NOTES")
            }
        }
        Spacer(Modifier.height(10.dp))
        flags.forEachIndexed { i, flag ->
            if (i > 0) HairLine(inset = 26.dp)
            LogCheckRow(flag)
        }
        if (onFix != null && fixable) {
            Spacer(Modifier.height(4.dp))
            QuietButton("Fix in log", onFix, icon = Icons.Rounded.Edit)
        }
    }
}

@Composable
private fun LogCheckRow(flag: DataFlag) {
    val c = CoffeeTheme.colors
    val distrust = flag.severity == FlagSeverity.Distrust
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (distrust) Icons.Rounded.WarningAmber else Icons.Rounded.Info,
            contentDescription = null,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
            tint = if (distrust) c.attention else c.inkLow,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(flag.fieldLabel, style = MaterialTheme.typography.titleMedium, color = c.inkHigh)
            if (distrust) {
                Text("IGNORED BY ANALYSIS", style = Micro, color = c.attention)
            }
            Spacer(Modifier.height(2.dp))
            Text(flag.message, style = aiText(13, 18), color = c.inkMid)
            val hint = elsewhereHint(flag.field)
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(hint, style = aiText(12, 16, FontWeight.Medium), color = c.inkLow)
            }
        }
    }
}

// ------------------------------------------------------------------- offline read

@Composable
internal fun OfflineRead(lines: List<String>, collapsible: Boolean) {
    val c = CoffeeTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    if (collapsible && !open) {
        QuietButton(
            "Offline read · ${plural(lines.size, "note")}",
            { open = true },
            icon = Icons.Rounded.ExpandMore,
        )
    } else {
        Column(Modifier.fillMaxWidth()) {
            EyebrowLabel("OFFLINE FIRST READ")
            Spacer(Modifier.height(4.dp))
            lines.forEachIndexed { i, line ->
                if (i > 0) HairLine()
                Text(
                    line,
                    style = aiText(14, 20),
                    color = c.inkHigh,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text("Pattern matching on your notes, not a verdict.", style = aiText(12, 16), color = c.inkLow)
            if (collapsible) {
                QuietButton("Hide offline read", { open = false })
            }
        }
    }
}
