package com.laksh.coffee.ui.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.data.ai.StoredAnalysis
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.BrewAnalysis
import com.laksh.coffee.domain.ai.ChangeParse
import com.laksh.coffee.domain.ai.DataFlag
import com.laksh.coffee.domain.ai.Fields
import com.laksh.coffee.domain.ai.GrindMove
import com.laksh.coffee.domain.ai.GrindValueMove
import com.laksh.coffee.domain.ai.ModelCatalog
import com.laksh.coffee.domain.ai.ValueMove
import com.laksh.coffee.ui.components.Cell
import com.laksh.coffee.ui.components.DataGrid
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.photos.SmallPhotoTile
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.VerdictHeadline

/**
 * The analysis in one line, for the status line under the boarding pass: what to do, in the
 * verdict's colour. The verdict word itself is the card's chip, so it is not said here again.
 */
data class VerdictSummary(
    val status: Status,
    val lead: String,
    val detail: String?,
    val live: Boolean = false,
)

private enum class BrewCard { LoadingLocal, Running, Error, Result, Raw, NoKey, Blocked, Ready }

// First match wins: a run in flight beats a failure, which beats what is stored.
private fun BrewAnalysisUi.card(): BrewCard = when {
    loading -> BrewCard.LoadingLocal
    running -> BrewCard.Running
    error != null -> BrewCard.Error
    stored != null && stored.parsed && stored.brew != null -> BrewCard.Result
    stored != null -> BrewCard.Raw
    !configured -> BrewCard.NoKey
    blocker != null -> BrewCard.Blocked
    else -> BrewCard.Ready
}

/**
 * The per-brew analysis Brew detail places under the pass: a one-line log check, the analysis
 * card in whichever state applies, then the full log check under the answer so the change
 * stays in the first screenful. Owns its AI settings sheet, so no route is needed.
 */
@Composable
fun BrewAnalysisSection(
    logId: Long,
    onEditLog: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens one of the photos the card lists (Brew detail's viewer, where it can be unticked). */
    onOpenPhoto: ((PhotoEntity) -> Unit)? = null,
    vm: BrewAnalysisViewModel = viewModel(key = "brew-analysis-$logId", factory = BrewAnalysisViewModel.factory(logId)),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf(false) }
    val openSettings = { sheet = true }

    Column(modifier.fillMaxWidth()) {
        if (s.flags.isNotEmpty()) {
            LogCheckLine(s.flags)
            Spacer(Modifier.height(10.dp))
        }
        AnalysisPanel(eyebrow = "ANALYSIS", chip = { BrewChip(s) }) {
            AnimatedContent(
                targetState = s,
                transitionSpec = stateFade<BrewAnalysisUi>(),
                contentKey = { it.card() },
                label = "brewAnalysis",
            ) { st ->
                // Each state draws from its own snapshot, so a fading-out state never reads
                // the next state's nulls.
                when (st.card()) {
                    BrewCard.LoadingLocal -> LocalSkeleton()
                    BrewCard.Running -> LoadingBody(
                        lead = "Reading this brew",
                        detail = listOfNotNull(
                            st.contextBrews.takeIf { it > 0 }?.let { "+ $it before it" },
                            st.modelLabel.ifBlank { null },
                        ).joinToString(" · "),
                        sinceMs = st.runningSinceMs ?: 0L,
                        onCancel = vm::cancel,
                    )
                    BrewCard.Error -> Column(Modifier.fillMaxWidth()) {
                        st.error?.let { error ->
                            ErrorBody(
                                error = error,
                                onRetry = vm::analyse,
                                onOpenSettings = openSettings,
                                onDismiss = vm::dismissError,
                            )
                        }
                        // A failed re-run never hides the last good answer.
                        val stored = st.stored
                        if (stored != null) {
                            Spacer(Modifier.height(14.dp))
                            HairLine()
                            Spacer(Modifier.height(14.dp))
                            StoredBody(st, stored, onRerun = vm::analyse)
                        }
                    }
                    BrewCard.Result, BrewCard.Raw -> {
                        val stored = st.stored
                        if (stored != null) StoredBody(st, stored, onRerun = vm::analyse)
                    }
                    BrewCard.NoKey -> NoKeyBody(st, onAddKey = openSettings)
                    BrewCard.Blocked -> st.blocker?.let { BlockedBody(it) }
                    BrewCard.Ready -> ReadyBody(st, onAnalyse = vm::analyse, onOpenPhoto = onOpenPhoto)
                }
            }
        }
        if (s.flags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LogCheckCard(s.flags, onFix = onEditLog)
        }
    }

    if (sheet) {
        AiSettingsSheet(onDismiss = { sheet = false })
    }
}

/**
 * The verdict for the pass's status line, from the same ViewModel instance the section uses
 * (same key, same owner), so the two never disagree.
 */
@Composable
fun rememberBrewVerdict(logId: Long): VerdictSummary? {
    val vm: BrewAnalysisViewModel = viewModel(
        key = "brew-analysis-$logId",
        factory = BrewAnalysisViewModel.factory(logId),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val running = s.running
    val stored = s.stored
    return remember(running, stored) { verdictOf(running, stored) }
}

private fun verdictOf(running: Boolean, stored: StoredAnalysis?): VerdictSummary? {
    if (running) return VerdictSummary(Status.Live, "Analysing", "reading this brew", live = true)
    if (stored == null || !stored.parsed) return null
    val a = stored.brew ?: return null
    // A change to make is THE ONE CHANGE card's job, right under the pass. Repeating it here
    // said the same move twice, so the pass keeps saying what happened (fast by 0:32) and the
    // card says what to do about it. "Change nothing" is the one answer the card never states.
    if (a.change.variable != "none" || stored.grindMove != null || stored.valueMove != null) return null
    return VerdictSummary(verdictStatus(effectiveVerdict(a)), "Change nothing", null)
}

// ----------------------------------------------------------------------- chip

@Composable
private fun BrewChip(s: BrewAnalysisUi) {
    when (s.card()) {
        BrewCard.LoadingLocal -> Unit
        BrewCard.Running -> StatusChip(Status.Live, "ANALYSING")
        BrewCard.Error -> StatusChip(Status.Fault, "FAILED")
        BrewCard.Result -> {
            val v = s.stored?.brew?.let(::effectiveVerdict).orEmpty()
            StatusChip(verdictStatus(v), verdictChip(v))
        }
        BrewCard.Raw -> StatusChip(Status.Attention, "UNREAD")
        BrewCard.NoKey -> StatusChip(Status.Neutral, "NOT SET UP")
        BrewCard.Blocked -> StatusChip(Status.Neutral, "UNAVAILABLE")
        BrewCard.Ready -> StatusChip(Status.Neutral, "READY")
    }
}

// --------------------------------------------------------------------- bodies

/** A stored answer: the structured read when it decoded, else the reply as it came. */
@Composable
private fun StoredBody(s: BrewAnalysisUi, stored: StoredAnalysis, onRerun: () -> Unit) {
    val a = stored.brew
    if (stored.parsed && a != null) {
        BrewResultBody(s, stored, a, onRerun)
    } else {
        Column(Modifier.fillMaxWidth()) {
            RawReplyBody(stored.rawText)
            UsageFooter(
                stored = stored,
                stale = s.stale,
                staleText = brewStaleText(s),
                onRerun = onRerun,
                rerunEnabled = rerunEnabled(s),
            )
        }
    }
}

private const val BREW_EDITED = "Brew edited since · re-run for a fresh read"

private fun brewStaleText(s: BrewAnalysisUi): String = s.staleReason?.let { staleText(it, BREW_EDITED) } ?: BREW_EDITED

private fun rerunEnabled(s: BrewAnalysisUi): Boolean = !s.running && s.configured && s.blocker == null

@Composable
private fun BrewResultBody(s: BrewAnalysisUi, stored: StoredAnalysis, a: BrewAnalysis, onRerun: () -> Unit) {
    val c = CoffeeTheme.colors
    val variable = a.change.variable
    val move = stored.grindMove
    // A numbered or stepless dial: the same hero, in the dial's own numbers.
    val dialMove = stored.valueMove.takeIf { move == null }
    val value = when {
        move != null -> null
        dialMove?.from != null ->
            ValueMove(GrindValueMove.format(dialMove.from), GrindValueMove.format(dialMove.to), dialMove.unit)
        else -> ChangeParse.valueMove(a.change)
    }
    var why by rememberSaveable(stored.id) { mutableStateOf(false) }
    var clipped by remember(stored.id) { mutableStateOf(false) }

    // The chip says the verdict, so a headline that opens with it ("Dialled in: …") loses that
    // opening, and one that is only the verdict is not shown at all.
    val headline = remember(a.headline) { headlineWithoutVerdict(a.headline).arrows() }
    Column(Modifier.fillMaxWidth()) {
        if (headline.isNotBlank()) {
            Text(
                headline,
                style = VerdictHeadline,
                color = c.inkHigh,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
        }

        val keep = s.keep
        if (variable == "none" && keep != null) {
            // "Change nothing" is already the status line above the card. What is left to say is
            // which numbers to keep, as numbers rather than a sentence.
            KeepWell(keep)
        } else {
            ChangeHero(
                eyebrow = "THE ONE CHANGE",
                variableLabel = changeLabel(variable, s.grinderShort),
                move = move,
                value = value,
                instruction = if (variable == "none") {
                    "Change nothing"
                } else {
                    a.change.instruction.ifBlank { a.change.to }.ifBlank { "No single change suggested" }
                },
                // GrindMove.caption() already ends in "dial 7.7 → 6.7" on ten-click dials like the K-Ultra.
                summary = heroSummary(a, move, dialMove, value),
            )
        }

        if (a.diagnosis.isNotBlank() || a.evidence.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyebrowLabel("WHY")
                Text("  ·  ${a.confidence.uppercase()} CONFIDENCE", style = Micro, color = c.inkLow)
            }
            Spacer(Modifier.height(4.dp))
            if (a.diagnosis.isNotBlank()) {
                Text(
                    a.diagnosis.arrows(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.inkMid,
                    maxLines = if (why) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!why) clipped = it.hasVisualOverflow },
                )
            }
            // The panel's animateContentSize carries this expansion.
            if (why && a.evidence.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                a.evidence.forEachIndexed { i, line ->
                    if (i > 0) HairLine()
                    Text(
                        line.arrows(),
                        style = aiText(13, 18),
                        color = c.inkMid,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            if (why || clipped || a.evidence.isNotEmpty()) {
                // Nudged left so the label, not the touch padding, lines up with the text above.
                QuietButton(if (why) "Less" else "More", { why = !why }, Modifier.offset(x = (-8).dp))
            }
        }

        if (a.expectIfRight.isNotBlank() || a.ifWrong.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            OutcomeGrid(a.expectIfRight, a.ifWrong)
        }

        if (a.keep.isNotBlank() && variable != "none") {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                EyebrowLabel("KEEP", Modifier.padding(top = 3.dp))
                Spacer(Modifier.width(8.dp))
                Text(a.keep.arrows(), style = aiText(13, 18), color = c.inkMid)
            }
        }

        // A warning the log check below already shows is not repeated inside the card.
        val warnings = (a.dataWarnings + stored.localWarnings).notCoveredBy(s.flags).take(3)
        if (warnings.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            WarningRows(warnings)
        }

        UsageFooter(
            stored = stored,
            stale = s.stale,
            staleText = "Brew edited since · re-run for a fresh read",
            onRerun = onRerun,
            rerunEnabled = rerunEnabled(s),
        )
    }
}

/** Upper-cased here, from words that never hold a µ. */
private fun changeLabel(variable: String, grinderShort: String?): String? = when (variable) {
    "grind" -> listOfNotNull("GRIND", grinderShort).joinToString(" · ")
    "none" -> "NO CHANGE"
    "" -> null
    else -> variable.uppercase()
}

/** Phrases that tie a free-text warning to a checked field. Kept narrow on purpose. */
private val FIELD_WORDS: Map<String, List<String>> = mapOf(
    Fields.TASTE to listOf("slider"),
    Fields.BEVERAGE to listOf("cup weight", "stayed in the grounds"),
    Fields.EY to listOf("extraction yield"),
    Fields.ROAST to listOf("roast date"),
)

/** Drops warnings about a field the log check card already flags for this brew. */
private fun List<String>.notCoveredBy(flags: List<DataFlag>): List<String> {
    if (flags.isEmpty()) return this
    val words = flags.map { it.field }.distinct().flatMap { FIELD_WORDS[it].orEmpty() }
    if (words.isEmpty()) return this
    return filterNot { w ->
        val t = w.lowercase()
        words.any { it in t }
    }
}

/** KEEP: this brew's grind, temperature and ratio, the numbers "change nothing" refers to. */
@Composable
private fun KeepWell(keep: KeepNumbers) {
    val c = CoffeeTheme.colors
    val units = LocalUnits.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface2)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    ) {
        EyebrowLabel("KEEP")
        DataGrid(
            cells = listOf(
                Cell(label = "GRIND", value = keep.grind, unit = keep.grindUnit, textValue = keep.grindUnit == null),
                Cell(label = "TEMP", value = keep.tempC?.let(units::temp), unit = units.tempUnit),
                Cell(label = "RATIO", value = keep.ratio),
            ),
            columns = 3,
            compact = true,
        )
    }
}

/** The line under the hero, never a repeat of what the hero already says. */
private fun heroSummary(a: BrewAnalysis, move: GrindMove?, dialMove: GrindValueMove?, value: ValueMove?): String? = when {
    move != null -> move.caption().ifBlank { a.change.instruction }.ifBlank { null }
    // "finer by 1 · 14 -> 13 on the dial": the checked direction first, then the model's own words.
    dialMove != null -> listOf(dialMove.caption(), a.change.instruction).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
    a.change.variable == "none" -> a.keep.ifBlank { null }
    value != null -> a.change.instruction.ifBlank { null }
    else -> null
}

@Composable
private fun NoKeyBody(s: BrewAnalysisUi, onAddKey: () -> Unit) {
    val c = CoffeeTheme.colors
    // The price only holds for the default CommandCode model, so it's only claimed there.
    val price = if (s.host == ModelCatalog.COMMANDCODE_HOST) " About a tenth of a cent a read." else ""
    Column(Modifier.fillMaxWidth()) {
        EmptyStateRow(art = { SmallDripper() }) {
            Text("Get a verdict on this brew", style = aiText(17, 22, FontWeight.Medium), color = c.inkHigh)
            Spacer(Modifier.height(4.dp))
            Text(
                noKeyLead(s.host, s.keyHost, s.providerName) + " and a small model reads ${brewScope(s.contextBrews)}, " +
                    "then suggests one change.$price",
                style = aiText(14, 20),
                color = c.inkMid,
            )
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Add key", onAddKey, icon = Icons.Rounded.Key)
        if (s.quickRead.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            OfflineRead(s.quickRead, collapsible = false)
        }
    }
}

@Composable
private fun BlockedBody(blocker: AiError) {
    val c = CoffeeTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(blocker.title, style = aiText(17, 22, FontWeight.Medium), color = c.inkHigh)
        Spacer(Modifier.height(4.dp))
        Text(blocker.message, style = aiText(13, 18), color = c.inkMid)
    }
}

@Composable
private fun ReadyBody(s: BrewAnalysisUi, onAnalyse: () -> Unit, onOpenPhoto: ((PhotoEntity) -> Unit)?) {
    val c = CoffeeTheme.colors
    val sends = buildString {
        append("Sends ").append(brewScope(s.contextBrews))
        if (s.approxTokens > 0) append(" (~").append(oneDp(s.approxTokens / 1000.0)).append("k tokens)")
        if (s.photoCount > 0) append(" and ").append(plural(s.photoCount, "photo"))
        append(" to ").append(s.host)
        s.estimateLabel?.let { append(" · ").append(it) }
        append(" · ").append(s.modelLabel)
    }
    Column(Modifier.fillMaxWidth()) {
        EmptyStateRow(art = { SmallDripper() }) {
            Text("One change for next time", style = aiText(17, 22, FontWeight.Medium), color = c.inkHigh)
            Spacer(Modifier.height(4.dp))
            Text(sends, style = aiText(13, 18), color = c.inkMid)
        }
        s.photoLine?.let { line ->
            Spacer(Modifier.height(12.dp))
            PhotoLineRow(line, s.modelLabel, onOpenPhoto)
        }
        Spacer(Modifier.height(14.dp))
        // The one primary action on Brew detail.
        PrimaryButton(
            "Analyse brew",
            onAnalyse,
            Modifier.fillMaxWidth(),
            icon = Icons.Rounded.AutoAwesome,
            height = 52.dp,
        )
        if (s.quickRead.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            OfflineRead(s.quickRead, collapsible = true)
        }
    }
}

/** "Sends 2 photos", or why they stay. Kept apart from the layout so the words are tested. */
internal fun photoLineText(line: PhotoLine, modelLabel: String): String = when (line.hold) {
    null -> "Sends ${plural(line.sending.size, "photo")}. Open one to keep it out."
    PhotoHold.ModelBlind ->
        "Photos stay on the phone — ${modelLabel.ifBlank { "this model" }} can't see images"
    PhotoHold.SwitchedOff -> "Photos stay on the phone — sending photos is switched off in AI settings"
    PhotoHold.NoneTicked -> "Photos stay on the phone — none is ticked to send"
}

/** Above Analyse: exactly which photos would leave the phone with this read, as thumbnails. */
@Composable
private fun PhotoLineRow(line: PhotoLine, modelLabel: String, onOpen: ((PhotoEntity) -> Unit)?) {
    val c = CoffeeTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        line.sending.forEach { photo ->
            SmallPhotoTile(photo = photo, onClick = onOpen?.let { open -> { open(photo) } })
        }
        Text(
            photoLineText(line, modelLabel),
            style = aiText(13, 18),
            color = c.inkMid,
            modifier = Modifier.weight(1f),
        )
    }
}
