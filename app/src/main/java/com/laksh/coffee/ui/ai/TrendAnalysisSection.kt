package com.laksh.coffee.ui.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.data.ai.StoredAnalysis
import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.BrewRefs
import com.laksh.coffee.domain.ai.Experiment
import com.laksh.coffee.domain.ai.Pattern
import com.laksh.coffee.domain.ai.TrendAnalysis
import com.laksh.coffee.ui.components.Cell
import com.laksh.coffee.ui.components.DataGrid
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.components.StatusDot
import com.laksh.coffee.ui.components.TimelineCard
import com.laksh.coffee.ui.components.TimelineRow
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.PillShape
import com.laksh.coffee.ui.theme.VerdictHeadline

private enum class TrendCard { LoadingLocal, Running, Error, Result, Raw, NoKey, Blocked, Ready }

// Same priority as the brew card: a run in flight, then a failure, then what is stored.
private fun TrendAnalysisUi.card(): TrendCard = when {
    loading -> TrendCard.LoadingLocal
    running -> TrendCard.Running
    error != null -> TrendCard.Error
    stored != null && stored.parsed && stored.trend != null -> TrendCard.Result
    stored != null -> TrendCard.Raw
    !configured -> TrendCard.NoKey
    blocker != null -> TrendCard.Blocked
    else -> TrendCard.Ready
}

// Beyond this many dates a pattern row turns into a wall of grey pills; the rest are a "+N".
private const val MAX_DATE_CHIPS = 3

/**
 * "What goes wrong repeatedly" across the whole log, for Insights. Same states and visuals as
 * the brew card; owns its AI settings sheet.
 */
@Composable
fun TrendAnalysisSection(
    onOpenLog: (Long) -> Unit,
    modifier: Modifier = Modifier,
    vm: TrendAnalysisViewModel = viewModel(factory = TrendAnalysisViewModel.Factory),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(false) }
    val openSettings = { sheet = true }

    AnalysisPanel(eyebrow = "PATTERNS", chip = { TrendChip(s) }, modifier = modifier) {
        AnimatedContent(
            targetState = s,
            transitionSpec = stateFade<TrendAnalysisUi>(),
            contentKey = { it.card() },
            label = "trendAnalysis",
        ) { st ->
            when (st.card()) {
                TrendCard.LoadingLocal -> LocalSkeleton()
                TrendCard.Running -> LoadingBody(
                    lead = "Reading ${plural(st.brewCount, "brew")}",
                    detail = st.modelLabel,
                    sinceMs = st.runningSinceMs ?: 0L,
                    onCancel = vm::cancel,
                )
                TrendCard.Error -> Column(Modifier.fillMaxWidth()) {
                    st.error?.let { error ->
                        ErrorBody(
                            error = error,
                            onRetry = vm::analyse,
                            onOpenSettings = openSettings,
                            onDismiss = vm::dismissError,
                        )
                    }
                    // A failed re-run never hides the last good read.
                    val stored = st.stored
                    if (stored != null) {
                        Spacer(Modifier.height(14.dp))
                        HairLine()
                        Spacer(Modifier.height(14.dp))
                        TrendStoredBody(st, stored, onOpenLog, onRerun = vm::analyse)
                    }
                }
                TrendCard.Result, TrendCard.Raw -> {
                    val stored = st.stored
                    if (stored != null) TrendStoredBody(st, stored, onOpenLog, onRerun = vm::analyse)
                }
                TrendCard.NoKey -> TrendNoKeyBody(st, onAddKey = openSettings)
                TrendCard.Blocked -> TrendBlockedBody(st)
                TrendCard.Ready -> TrendReadyBody(st, onAnalyse = vm::analyse)
            }
        }
    }

    if (sheet) {
        AiSettingsSheet(onDismiss = { sheet = false })
    }
}

// ----------------------------------------------------------------------- chip

@Composable
private fun TrendChip(s: TrendAnalysisUi) {
    when (s.card()) {
        TrendCard.LoadingLocal -> Unit
        TrendCard.Running -> StatusChip(Status.Live, "ANALYSING")
        TrendCard.Error -> StatusChip(Status.Fault, "FAILED")
        TrendCard.Result -> {
            val t = s.stored?.trend?.trajectory.orEmpty()
            StatusChip(trajectoryStatus(t), trajectoryChip(t))
        }
        TrendCard.Raw -> StatusChip(Status.Attention, "UNREAD")
        TrendCard.NoKey -> StatusChip(Status.Neutral, "NOT SET UP")
        TrendCard.Blocked -> {
            val b = s.blocker
            StatusChip(
                Status.Neutral,
                if (b is AiError.NotEnoughData) "NEEDS ${b.needed} BREWS" else "UNAVAILABLE",
            )
        }
        TrendCard.Ready -> StatusChip(Status.Neutral, "READY")
    }
}

// --------------------------------------------------------------------- bodies

private fun trendRerunEnabled(s: TrendAnalysisUi): Boolean = !s.running && s.configured && s.blocker == null

private fun trendStaleText(s: TrendAnalysisUi): String {
    val edited = if (s.newBrewsSince > 0) "${plural(s.newBrewsSince, "new brew")} since · re-run" else "Log edited since · re-run"
    return s.staleReason?.let { staleText(it, edited) } ?: edited
}

/** A stored read: the structured one when it decoded, else the reply as it came. */
@Composable
private fun TrendStoredBody(
    s: TrendAnalysisUi,
    stored: StoredAnalysis,
    onOpenLog: (Long) -> Unit,
    onRerun: () -> Unit,
) {
    val t = stored.trend
    if (stored.parsed && t != null) {
        TrendResultBody(s, stored, t, onOpenLog, onRerun)
    } else {
        Column(Modifier.fillMaxWidth()) {
            RawReplyBody(stored.rawText)
            UsageFooter(
                stored = stored,
                stale = s.stale || s.newBrewsSince > 0,
                staleText = trendStaleText(s),
                onRerun = onRerun,
                rerunEnabled = trendRerunEnabled(s),
            )
        }
    }
}

@Composable
private fun TrendResultBody(
    s: TrendAnalysisUi,
    stored: StoredAnalysis,
    t: TrendAnalysis,
    onOpenLog: (Long) -> Unit,
    onRerun: () -> Unit,
) {
    val c = CoffeeTheme.colors
    var more by rememberSaveable(stored.id) { mutableStateOf(false) }
    var allFindings by rememberSaveable(stored.id) { mutableStateOf(false) }
    var clipped by remember(stored.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            t.headline.arrows().ifBlank { "What your log says" },
            style = VerdictHeadline,
            color = c.inkHigh,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (t.summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                t.summary.arrows(),
                style = aiText(14, 20),
                color = c.inkMid,
                maxLines = if (more) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!more) clipped = it.hasVisualOverflow },
            )
            if (more || clipped) {
                // Nudged left so the label, not the touch padding, lines up with the text above.
                QuietButton(if (more) "Less" else "More", { more = !more }, Modifier.offset(x = (-8).dp))
            }
        }

        if (t.patterns.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            EyebrowLabel("FINDINGS")
            val shown = if (allFindings) t.patterns else t.patterns.take(3)
            shown.forEachIndexed { i, p ->
                if (i > 0) HairLine(inset = 18.dp)
                PatternRow(p, s.brewDates, s.brewRowIds, onOpenLog)
            }
            if (t.patterns.size > 3) {
                QuietButton(
                    if (allFindings) "Fewer findings" else "All ${t.patterns.size} findings",
                    { allFindings = !allFindings },
                    Modifier.offset(x = (-8).dp),
                )
            }
        }

        if (t.changedOften.isNotEmpty() || t.neverChanged.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            DataGrid(
                cells = listOf(
                    Cell("YOU KEEP MOVING", t.changedOften.joinToString(" · ").ifBlank { null }, textValue = true),
                    Cell("NEVER MOVED", t.neverChanged.joinToString(" · ").ifBlank { null }, textValue = true),
                ),
                columns = 2,
            )
        }

        val ex = t.experiment
        if (ex.goal.isNotBlank() || ex.brews.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            ExperimentWell(ex)
        }

        if (t.perCoffee.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            EyebrowLabel("PER COFFEE")
            t.perCoffee.forEachIndexed { i, note ->
                if (i > 0) HairLine()
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    if (note.bean.isNotBlank()) {
                        Text(note.bean, style = aiText(14, 20, FontWeight.Medium), color = c.inkHigh)
                    }
                    Text(note.note.arrows(), style = aiText(13, 18), color = c.inkMid)
                }
            }
        }

        val warnings = (t.dataWarnings + stored.localWarnings).take(3)
        if (warnings.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            WarningRows(warnings)
        }

        UsageFooter(
            stored = stored,
            stale = s.stale || s.newBrewsSince > 0,
            staleText = trendStaleText(s),
            onRerun = onRerun,
            rerunEnabled = trendRerunEnabled(s),
        )
    }
}

@Composable
private fun PatternRow(p: Pattern, brewDates: Map<Long, String>, brewRowIds: Map<Long, Long>?, onOpenLog: (Long) -> Unit) {
    val c = CoffeeTheme.colors
    // Only brews that still exist get a chip; a deleted one has nothing to open.
    val dated = remember(p, brewDates, brewRowIds) {
        BrewRefs.follow(p.brewIds, brewRowIds).mapNotNull { id -> brewDates[id]?.let { id to it } }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        StatusDot(toneStatus(p.tone), Modifier.padding(top = 6.dp), size = 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (p.title.isNotBlank()) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, color = c.inkHigh)
            }
            if (p.detail.isNotBlank()) {
                Text(p.detail.arrows(), style = aiText(13, 18), color = c.inkMid)
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strengthLabel(p.strength), style = Micro, color = c.inkLow)
                dated.take(MAX_DATE_CHIPS).forEach { (id, label) ->
                    DateChip(label, onClick = { onOpenLog(id) })
                }
                if (dated.size > MAX_DATE_CHIPS) {
                    Text("+${dated.size - MAX_DATE_CHIPS}", style = Micro, color = c.inkLow)
                }
            }
        }
    }
}

@Composable
private fun DateChip(label: String, onClick: () -> Unit) {
    val c = CoffeeTheme.colors
    Box(
        Modifier
            .height(22.dp)
            .clip(PillShape)
            .background(c.surface2)
            .clickable(onClickLabel = "Open this brew", role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Micro, color = c.inkMid, maxLines = 1)
    }
}

/** A value that changes from one experiment brew to the next: one per brew, and its unit. */
private data class Varied(val values: List<String>, val unit: String)

private val SETTING_VALUE = Regex("""(\d+(?:\.\d+)?)\s*(°C|clicks|clk|g|s)\b""")
private val PLAIN_BREW_LABEL = Regex("""(?i)brew\s*\d+""")

/**
 * The one thing the experiment varies. Grind first when its clicks differ; otherwise the first
 * unit whose value is written on every brew and is not the same on all of them (°C here). Null
 * when nothing parses, and the well then shows the goal alone.
 */
private fun variedValue(ex: Experiment): Varied? {
    val brews = ex.brews
    if (brews.size < 2) return null
    val clicks = brews.map { it.grindClicks }
    if (clicks.all { it != null } && clicks.distinct().size > 1) {
        return Varied(clicks.map { it.toString() }, "clk")
    }
    val parsed = brews.map { b ->
        SETTING_VALUE.findAll(b.settings).associate { m ->
            val unit = if (m.groupValues[2] == "clicks") "clk" else m.groupValues[2]
            unit to m.groupValues[1]
        }
    }
    val units = parsed.first().keys.filter { u -> parsed.all { u in it } }
    val unit = units.firstOrNull { u -> parsed.map { it.getValue(u) }.distinct().size > 1 } ?: return null
    return Varied(parsed.map { it.getValue(unit) }, unit)
}

// The separators a brew's settings list its values with: "66 clicks, 94 °C · 30 g".
private val SETTING_SEPARATOR = Regex("""\s*[,;·]\s*""")

/**
 * A brew's settings with the value under test taken out, because the row's bold column says it
 * ("66 clicks, 100 °C, 30 g to 500 g" becomes "66 clicks, 30 g to 500 g"). Only a value that is
 * a list item of its own is lifted out; in a phrase ("66 clicks at 94 °C") cutting it would
 * strand words, so this returns null and the row keeps the model's words whole.
 * Internal for its unit test.
 */
internal fun withoutTested(settings: String, value: String, unit: String): String? {
    val units = if (unit == "clk") "(?:clicks|clk)" else Regex.escape(unit)
    val tested = Regex("""${Regex.escape(value)}\s*$units""")
    val parts = settings.trim().split(SETTING_SEPARATOR).filter { it.isNotBlank() }
    if (parts.count { tested.matches(it) } != 1) return null
    return parts.filterNot { tested.matches(it) }.joinToString(", ").ifBlank { null }
}

@Composable
private fun ExperimentWell(ex: Experiment) {
    val c = CoffeeTheme.colors
    val varied = remember(ex) { variedValue(ex) }
    val rows = remember(ex, varied) {
        ex.brews.mapIndexed { i, b ->
            // Each row says its value under test once: in the bold column, with the settings
            // held constant as its title. When the value can't be lifted cleanly out of the
            // model's words, the words stay whole and the column is left empty.
            val tested = varied?.let { v -> withoutTested(b.settings, v.values[i], v.unit)?.let { it to v } }
            TimelineRow(
                time = "#${i + 1}",
                // "#1" already numbers the row, so a label that only says "Brew 1" is dropped.
                kind = b.label.takeIf { it.isNotBlank() && !PLAIN_BREW_LABEL.matches(it.trim()) },
                title = (tested?.first ?: b.settings).arrows().ifBlank { "—" },
                detail = b.expect.takeIf { it.isNotBlank() }?.let { "Taste for: ${it.arrows()}" },
                // The bold column carries the value under test, never one held constant.
                trailing = tested?.let { (_, v) -> "${v.values[i]} ${v.unit}" },
                terminal = i == ex.brews.lastIndex,
            )
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface2)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel("THE ONE EXPERIMENT")
            Spacer(Modifier.weight(1f))
            if (ex.brews.isNotEmpty()) {
                Text(
                    if (ex.brews.size == 1) "ON THE NEXT BREW" else "OVER THE NEXT ${ex.brews.size} BREWS",
                    style = Micro,
                    color = c.inkLow,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                )
            }
        }
        // Same hero rules as THE ONE CHANGE: the value under test is the biggest thing here,
        // and the question it answers sits under it.
        if (varied != null) {
            Spacer(Modifier.height(8.dp))
            ValueSeries(varied.values, if (varied.unit == "clk") "clicks" else varied.unit)
        }
        if (ex.goal.isNotBlank()) {
            Spacer(Modifier.height(if (varied != null) 4.dp else 8.dp))
            Text(
                ex.goal.arrows(),
                style = if (varied != null) aiText(15, 20, FontWeight.Medium) else aiText(17, 22, FontWeight.Medium),
                color = c.inkHigh,
            )
        }
        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TimelineCard(rows)
        }
        if (ex.decide.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            EyebrowLabel("HOW TO READ IT")
            Spacer(Modifier.height(4.dp))
            Text(ex.decide.arrows(), style = aiText(14, 20), color = c.inkMid)
        }
    }
}

@Composable
private fun TrendNoKeyBody(s: TrendAnalysisUi, onAddKey: () -> Unit) {
    val c = CoffeeTheme.colors
    Column(Modifier.fillMaxWidth()) {
        EmptyStateRow(art = { SmallMug(fill = 0.7f) }) {
            Text("Find what keeps going wrong", style = aiText(17, 22, FontWeight.Medium), color = c.inkHigh)
            Spacer(Modifier.height(4.dp))
            Text(
                noKeyLead(s.host, s.keyHost, s.providerName) + " and a small model reads your whole log and names the patterns.",
                style = aiText(14, 20),
                color = c.inkMid,
            )
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Add key", onAddKey, icon = Icons.Rounded.Key)
    }
}

@Composable
private fun TrendBlockedBody(s: TrendAnalysisUi) {
    val c = CoffeeTheme.colors
    val b = s.blocker
    Column(Modifier.fillMaxWidth()) {
        if (b is AiError.NotEnoughData) {
            // The mug fills as rated brews come in: a third per brew until patterns unlock.
            val fill = (b.have.toFloat() / b.needed.coerceAtLeast(1)).coerceIn(0f, 1f)
            EmptyStateRow(art = { SmallMug(fill = fill) }) {
                Text("Not enough brews yet", style = aiText(17, 22, FontWeight.Medium), color = c.inkHigh)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Patterns need at least ${b.needed} rated brews. You have ${b.have}.",
                    style = aiText(13, 18),
                    color = c.inkMid,
                )
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                "Find patterns",
                {},
                Modifier.fillMaxWidth(),
                icon = Icons.Rounded.AutoAwesome,
                height = 52.dp,
                enabled = false,
            )
        } else if (b != null) {
            Text(b.title, style = aiText(17, 22, FontWeight.Medium), color = c.inkHigh)
            Spacer(Modifier.height(4.dp))
            Text(b.message, style = aiText(13, 18), color = c.inkMid)
        }
    }
}

@Composable
private fun TrendReadyBody(s: TrendAnalysisUi, onAnalyse: () -> Unit) {
    val c = CoffeeTheme.colors
    val sends = buildString {
        if (s.totalLogged > s.brewCount) {
            append("Sends your latest ").append(s.brewCount).append(" brews")
        } else {
            append("Sends all ").append(s.brewCount).append(" brews")
        }
        append(" (").append(s.ratedCount).append(" rated) to ").append(s.host)
        s.estimateLabel?.let { append(" · ").append(it) }
        append(" · ").append(s.modelLabel)
    }
    Column(Modifier.fillMaxWidth()) {
        EmptyStateRow(art = { SmallMug(fill = 0.7f) }) {
            Text("Read the whole log", style = aiText(17, 22, FontWeight.Medium), color = c.inkHigh)
            Spacer(Modifier.height(4.dp))
            Text(sends, style = aiText(13, 18), color = c.inkMid)
            if (s.flaggedBrews > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${plural(s.flaggedBrews, "brew has", "brews have")} fields the analysis will ignore.",
                    style = aiText(12, 16),
                    color = c.inkLow,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // The one primary action on Insights.
        PrimaryButton(
            "Find patterns",
            onAnalyse,
            Modifier.fillMaxWidth(),
            icon = Icons.Rounded.AutoAwesome,
            height = 52.dp,
        )
    }
}
