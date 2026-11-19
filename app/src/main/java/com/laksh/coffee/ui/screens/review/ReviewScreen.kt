package com.laksh.coffee.ui.screens.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.grams
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.BrewMath
import com.laksh.coffee.domain.Channeling
import com.laksh.coffee.domain.Descriptors
import com.laksh.coffee.domain.FlowFeel
import com.laksh.coffee.domain.Sludge
import com.laksh.coffee.domain.TasteProfile
import com.laksh.coffee.domain.ai.Fields
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.ui.components.AxisSlider
import com.laksh.coffee.ui.components.BedShapeIcon
import com.laksh.coffee.ui.components.Cell
import com.laksh.coffee.ui.components.ChipFlow
import com.laksh.coffee.ui.components.DataGrid
import com.laksh.coffee.ui.components.DialReadingInput
import com.laksh.coffee.ui.components.DotRating
import com.laksh.coffee.ui.components.Dripper
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.KeyValueRow
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SecondaryButton
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.StickyCtaBar
import com.laksh.coffee.ui.components.TickingNumber
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.components.TumblingBeans
import com.laksh.coffee.ui.components.bedStatus
import com.laksh.coffee.ui.components.channelingStatus
import com.laksh.coffee.ui.components.color
import com.laksh.coffee.ui.components.container
import com.laksh.coffee.ui.components.copyText
import com.laksh.coffee.ui.components.cupLabel
import com.laksh.coffee.ui.components.flowStatus
import com.laksh.coffee.ui.components.pressSquash
import com.laksh.coffee.ui.components.scoreStatus
import com.laksh.coffee.ui.components.shareText
import com.laksh.coffee.ui.components.sludgeStatus
import com.laksh.coffee.ui.components.timeStatus
import com.laksh.coffee.ui.photos.ReviewPhotos
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Micro
import com.laksh.coffee.ui.theme.Motion
import com.laksh.coffee.ui.theme.Num
import com.laksh.coffee.ui.theme.WellShape
import kotlinx.coroutines.launch

@Composable
fun ReviewScreen(
    logId: Long,
    onDone: () -> Unit,
    onAsk: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    vm: ReviewViewModel = viewModel(factory = ReviewViewModel.factory(logId)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val aiReady by vm.aiReady.collectAsStateWithLifecycle()

    // Keyed on the log id so chip taps update the form in place; only null -> loaded animates.
    // The loading flag rides along so the fading-out spinner never flips to the 'missing' state.
    AnimatedContent(
        targetState = state.loading to state.log,
        contentKey = { (loading, log) -> log?.id ?: loading },
        transitionSpec = { fadeIn(Motion.decel(220)) togetherWith fadeOut(Motion.decel(160)) },
        label = "reviewLoad",
    ) { (loading, l) ->
        when {
            l != null -> ReviewForm(l, state, aiReady, vm, onDone, onAsk, onOpenDetail)
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TumblingBeans(color = CoffeeTheme.colors.inkLow)
            }
            else -> MissingBrew(onDone)
        }
    }
}

@Composable
private fun ReviewForm(
    log: BrewLogEntity,
    state: ReviewUiState,
    aiReady: Boolean,
    vm: ReviewViewModel,
    onDone: () -> Unit,
    onAsk: () -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val warnings = state.fieldWarnings
    // Both exits navigate; a double tap must not pop or push twice.
    var leaving by remember { mutableStateOf(false) }

    // imePadding shrinks the scroll viewport so a focused field is scrolled above the keyboard.
    Box(Modifier.fillMaxSize().imePadding()) {
        // The status bar inset sits outside the scroll, so content never slides under the clock.
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
        ) {
            NumbersHeader(log, state, warnings, vm)
            OverallSection(log, vm)
            TasteSection(log, warnings, vm)
            DrawdownSection(log, state, warnings, vm)
            BedSection(log, vm)
            ReviewPhotos(log.id)
            NotesSection(log, state, warnings, vm)
            FirstReadSection(state.quickRead, aiReady)

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconWell(
                    icon = Icons.Rounded.Share,
                    contentDescription = "Share report",
                    onClick = {
                        scope.launch {
                            vm.save()
                            shareText(context, vm.buildReport(), "Brew log — ${log.recipeName}")
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                IconWell(
                    icon = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy report",
                    onClick = {
                        scope.launch {
                            vm.save()
                            copyText(context, vm.buildReport())
                        }
                    },
                )
                Spacer(Modifier.weight(1f))
                QuietButton(text = "Open Insights", onClick = onAsk, icon = Icons.Rounded.Insights)
            }

            // Clears the sticky bar (40 dp fade + 56 dp button + 12 dp) so the last row scrolls fully into view.
            Spacer(Modifier.height(120.dp))
            Spacer(Modifier.navigationBarsPadding())
        }

        // Hidden while typing: the keyboard already takes half the screen.
        AnimatedVisibility(
            visible = !WindowInsets.isImeVisible,
            enter = fadeIn(Motion.decel(200)),
            exit = fadeOut(Motion.decel(120)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            StickyCtaBar {
                if (aiReady) {
                    SecondaryButton(
                        text = "Save",
                        onClick = {
                            if (!leaving) {
                                leaving = true
                                vm.save(onDone)
                            }
                        },
                        modifier = Modifier.weight(0.36f),
                        height = 56.dp,
                        onCanvas = true,
                    )
                    PrimaryButton(
                        text = "Save & analyse",
                        onClick = {
                            if (!leaving) {
                                leaving = true
                                vm.saveAndAnalyse(onOpenDetail)
                            }
                        },
                        modifier = Modifier.weight(0.64f),
                        icon = Icons.Rounded.AutoAwesome,
                    )
                } else {
                    PrimaryButton(
                        text = "Save brew",
                        onClick = {
                            if (!leaving) {
                                leaving = true
                                vm.save(onDone)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ header stub

@Composable
private fun NumbersHeader(
    log: BrewLogEntity,
    state: ReviewUiState,
    warnings: Map<String, String>,
    vm: ReviewViewModel,
) {
    val c = CoffeeTheme.colors
    val ts = timeStatus(log.totalTimeSec, state.recipe?.totalSec ?: 0)
    val grindFlag = warnings[Fields.GRIND]
    val timeFlag = warnings[Fields.TIME]
    // Opens by itself when the brew arrived with a grind or time the analysis would ignore,
    // which is the only place in the app those two can be corrected.
    var editing by rememberSaveable { mutableStateOf(grindFlag != null || timeFlag != null) }
    // Only a click grinder whose dial has words of its own (turns, a numbered ring) gets the
    // choice; on a plain dial the number typed already is the reading.
    val dialScale = remember(state.grinder) {
        state.grinder?.takeIf { it.usesClicks }?.scale()?.takeIf { it.dial != DialFormat.Plain }
            // A typed correction may be any count up to 999, and the steppers get the same room:
            // a brew logged past the grinder's saved range (75 clicks on a row that says 40 is
            // the top) must not be pulled back into that range by the first tap.
            ?.let { it.copy(max = maxOf(it.max, REVIEW_MAX_CLICKS)) }
    }
    var asDial by rememberSaveable { mutableStateOf(false) }

    Spacer(Modifier.height(12.dp))
    EyebrowLabel("HOW WAS IT?")
    Spacer(Modifier.height(4.dp))
    Text(
        log.recipeName.ifBlank { "That brew" },
        style = MaterialTheme.typography.headlineLarge,
        color = c.inkHigh,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(12.dp))

    SoftCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
        DataGrid(
            cells = listOf(
                Cell(label = "DOSE", value = grams(log.doseG), unit = "g"),
                Cell(label = "WATER", value = grams(log.waterG), unit = "g"),
                Cell(
                    label = "GRIND",
                    value = log.grindClicks?.toString() ?: log.grindSetting.trim().ifEmpty { null },
                    unit = log.grindClicks?.let { "clk" },
                    textValue = log.grindClicks == null,
                    status = grindFlag?.let { Status.Attention },
                    sub = grindFlag?.let { "CHECK" },
                    subStatus = grindFlag?.let { Status.Attention },
                ),
                Cell(
                    label = "TIME",
                    value = log.totalTimeSec.takeIf { it > 0 }?.let { clock(it) },
                    status = if (timeFlag != null) Status.Attention else ts.first,
                    sub = timeFlag?.let { "CHECK" },
                    subStatus = timeFlag?.let { Status.Attention },
                ),
            ),
            columns = 4,
            compact = true,
        )

        AnimatedVisibility(
            visible = editing,
            enter = fadeIn(Motion.decel()) + expandVertically(Motion.size()),
            exit = fadeOut(Motion.decel(140)) + shrinkVertically(Motion.size()),
        ) {
            Column {
                HairLine()
                Spacer(Modifier.height(12.dp))
                // The steppers need the full width, so the time field drops below them.
                if (dialScale != null && asDial) {
                    EyebrowLabel("GRIND")
                    Spacer(Modifier.height(6.dp))
                    DialReadingInput(
                        scale = dialScale,
                        value = log.grindClicks?.toDouble(),
                        onValue = vm::setGrindFromDial,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (dialScale != null && asDial) {
                        // Entered above, the way the dial reads.
                    } else if (state.grindInClicks) {
                        FieldWell(
                            label = "GRIND",
                            value = state.grindText,
                            onChange = vm::setGrindClicks,
                            modifier = Modifier.weight(1f),
                            suffix = unitWord(log.grindClicks ?: 0, "click"),
                            numeric = true,
                            warning = grindFlag?.let { "" },
                            helper = log.grindClicks?.let { dialReading(it, state.grinder) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    } else {
                        FieldWell(
                            label = "GRIND",
                            value = log.grindSetting,
                            onChange = vm::setGrindSetting,
                            modifier = Modifier.weight(1f),
                            placeholder = "setting",
                            warning = grindFlag?.let { "" },
                        )
                    }
                    val parsed = state.parsedTimeSec
                    FieldWell(
                        label = "TOTAL TIME",
                        value = state.timeText,
                        onChange = vm::setTotalTimeText,
                        modifier = Modifier.weight(1f),
                        placeholder = "m:ss",
                        numeric = true,
                        error = if (state.timeTextValid) null else "Use m:ss, like 3:28",
                        warning = timeFlag?.let { "" },
                        // Echo the reading when it was typed without a colon, e.g. 328 -> 3:28.
                        helper = parsed?.let { clock(it) }?.takeIf { it != state.timeText }?.let { "reads as $it" },
                    )
                }
                if (dialScale != null) {
                    ToggleRow(
                        label = "Type it as the dial reads",
                        checked = asDial,
                        onChange = { asDial = it },
                        subtitle = "Turns and marks, as you read them off the grinder, instead of one click count.",
                    )
                }
                grindFlag?.let { WarningNote(it) }
                timeFlag?.let { WarningNote(it) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    val target = state.recipe?.totalSec?.takeIf { it > 0 }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusLine(
            status = ts.first,
            lead = ts.second,
            modifier = Modifier.weight(1f),
            detail = if (target != null && log.totalTimeSec > 0) {
                "${clock(log.totalTimeSec)} against ${clock(target)}"
            } else {
                null
            },
        )
        QuietButton(
            text = if (editing) "Done" else "Edit",
            onClick = { editing = !editing },
            icon = if (editing) null else Icons.Rounded.Edit,
        )
    }
}

/** The most clicks Review's grind field takes (three digits), shared by the dial steppers. */
private const val REVIEW_MAX_CLICKS = 999.0

/** The grinder's own reading of a click count: "dial 7.5", or "7 turns + 5 · dial 7.5" on a 1.x row. */
private fun dialReading(clicks: Int, grinder: GrinderEntity?): String? =
    // No burr travel here: the line sits under a half-width field and only the dial words help.
    grinder?.scale()?.describe(clicks.toDouble(), travel = false)?.takeIf { it.isNotBlank() && it != "clicks from zero" }

// ---------------------------------------------------------------------- overall

@Composable
private fun OverallSection(log: BrewLogEntity, vm: ReviewViewModel) {
    val c = CoffeeTheme.colors
    val rated = log.overall > 0
    SectionLabel("OVERALL")
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TickingNumber(
                value = if (rated) "${log.overall}" else "—",
                style = Num.Display,
                // inkLow, not inkFaint: the dash is text and must stay legible in light mode.
                color = if (rated) scoreStatus(log.overall).color() else c.inkLow,
                unit = "/10",
            )
            Spacer(Modifier.weight(1f))
            if (rated) {
                StatusChip(status = scoreStatus(log.overall), text = cupLabel(log.overall))
            } else {
                Text(
                    "Tap a dot",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkLow,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        DotRating(value = log.overall, onChange = vm::setOverall)
    }
}

// ------------------------------------------------------------------------ taste

@Composable
private fun TasteSection(log: BrewLogEntity, warnings: Map<String, String>, vm: ReviewViewModel) {
    val c = CoffeeTheme.colors
    SectionLabel("TASTE")
    SoftCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TasteProfile.AXES.forEach { (name, hint, get) ->
                AxisSlider(
                    label = name,
                    hint = hint,
                    value = get(log.taste),
                    onChange = { vm.setTaste(name, it) },
                )
            }
        }

        // Only once a score is in: before that, untouched sliders are just a form not yet filled.
        AnimatedVisibility(
            visible = warnings[Fields.TASTE] != null && log.overall > 0,
            enter = fadeIn(Motion.decel()) + expandVertically(Motion.size()),
            exit = fadeOut(Motion.decel(140)) + shrinkVertically(Motion.size()),
        ) {
            WarningNote(
                "Move at least one slider, or the analysis will treat taste as missing.",
                Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("TASTED OF", style = Micro, color = c.inkLow)
        Spacer(Modifier.height(8.dp))
        ChipFlow(
            options = Descriptors.POSITIVE,
            selected = log.descriptors.toSet(),
            onToggle = vm::toggleDescriptor,
        )

        Spacer(Modifier.height(16.dp))
        Text("FAULTS", style = Micro, color = c.inkLow)
        Spacer(Modifier.height(8.dp))
        ChipFlow(
            options = Descriptors.FAULTS,
            selected = log.faults.toSet(),
            onToggle = vm::toggleFault,
            status = Status.Fault,
        )
    }
}

// --------------------------------------------------------------------- drawdown

@Composable
private fun DrawdownSection(
    log: BrewLogEntity,
    state: ReviewUiState,
    warnings: Map<String, String>,
    vm: ReviewViewModel,
) {
    val c = CoffeeTheme.colors
    val drawdownFlag = warnings[Fields.DRAWDOWN]
    val beverageFlag = warnings[Fields.BEVERAGE]
    SectionLabel("DRAWDOWN")
    SoftCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // A blank warning outlines the well; the full reason sits full-width underneath.
            FieldWell(
                label = "DRAWDOWN",
                value = state.drawdownText,
                onChange = vm::setDrawdownText,
                modifier = Modifier.weight(1f),
                suffix = "s",
                numeric = true,
                warning = drawdownFlag?.let { "" },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            FieldWell(
                label = "IN CUP",
                value = state.beverageText,
                onChange = vm::setBeverageText,
                modifier = Modifier.weight(1f),
                suffix = "g",
                numeric = true,
                warning = beverageFlag?.let { "" },
            )
        }
        drawdownFlag?.let { WarningNote(it) }
        beverageFlag?.let { WarningNote(it) }

        Spacer(Modifier.height(12.dp))
        Text("FLOW", style = Micro, color = c.inkLow)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowFeel.entries.filter { it != FlowFeel.Unset }.forEach { f ->
                SelectableChip(
                    text = f.label,
                    selected = log.flowFeel == f,
                    onClick = { vm.setFlowFeel(if (log.flowFeel == f) FlowFeel.Unset else f) },
                    status = flowStatus(f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        val expected = BrewMath.expectedBeverageG(log.doseG, log.waterG)
        KeyValueRow("Expected in cup", "~${grams(expected)} g")
        log.beverageG?.let { bev ->
            KeyValueRow("Retained by the bed", "${grams(log.waterG - bev)} g")
        }
    }
}

// ---------------------------------------------------------------------- the bed

@Composable
private fun BedSection(log: BrewLogEntity, vm: ReviewViewModel) {
    val c = CoffeeTheme.colors
    // The last real shape, so the reading does not blank out while its well collapses.
    val lastShape = remember { arrayOf(log.bedShape) }
    if (log.bedShape != BedShape.Unset) lastShape[0] = log.bedShape

    SectionLabel("THE BED")
    SoftCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BedShape.entries.filter { it != BedShape.Unset }.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { shape ->
                        BedShapeTile(
                            shape = shape,
                            selected = log.bedShape == shape,
                            onClick = {
                                vm.setBedShape(if (log.bedShape == shape) BedShape.Unset else shape)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Seven shapes: the short last row keeps the same tile width as the first.
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        AnimatedVisibility(
            visible = log.bedShape != BedShape.Unset,
            enter = fadeIn(Motion.decel()) + expandVertically(Motion.settle()),
            exit = fadeOut(Motion.decel(140)) + shrinkVertically(Motion.settle()),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(WellShape)
                        .drawBehind { drawRect(c.surface2) }
                        .padding(12.dp),
                ) {
                    Text(
                        lastShape[0].reading,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = c.inkHigh,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("SURFACE", style = Micro, color = c.inkLow)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sludge.entries.filter { it != Sludge.Unset }.forEach { s ->
                SelectableChip(
                    text = s.label,
                    selected = log.sludge == s,
                    onClick = { vm.setSludge(if (log.sludge == s) Sludge.Unset else s) },
                    status = sludgeStatus(s),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("CHANNELLING", style = Micro, color = c.inkLow)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Channeling.entries.filter { it != Channeling.Unset }.forEach { ch ->
                SelectableChip(
                    text = ch.label,
                    selected = log.channeling == ch,
                    onClick = { vm.setChanneling(if (log.channeling == ch) Channeling.Unset else ch) },
                    status = channelingStatus(ch),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        FieldWell(
            label = "NOTES ON THE BED",
            value = log.bedNotes,
            onChange = vm::setBedNotes,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Anything else you saw",
            singleLine = false,
            minLines = 3,
        )
    }
}

@Composable
private fun BedShapeTile(
    shape: BedShape,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CoffeeTheme.colors
    val status = bedStatus(shape)
    val interaction = remember { MutableInteractionSource() }
    // Fill, outline and glyph are held as State and read only in draw, so the spring runs
    // without recomposing the tile. All four glide on the same spring so nothing lands early.
    val bg = animateColorAsState(
        if (selected) status?.container() ?: c.surface3 else c.surface2,
        Motion.settle(), label = "bedBg",
    )
    val outline = animateColorAsState(
        if (selected) status?.color() ?: c.inkHigh else Color.Transparent,
        Motion.settle(), label = "bedOutline",
    )
    val glyph = animateColorAsState(
        if (selected) status?.color() ?: c.inkHigh else c.inkMid,
        Motion.settle(), label = "bedGlyph",
    )
    val labelC by animateColorAsState(
        if (selected) c.inkHigh else c.inkMid,
        Motion.settle(), label = "bedLabel",
    )
    val lift = animateFloatAsState(if (selected) 1.03f else 1f, Motion.quick(), label = "bedLift")

    Column(
        modifier
            .height(76.dp)
            .graphicsLayer {
                scaleX = lift.value
                scaleY = lift.value
            }
            .pressSquash(interaction, 0.96f)
            .clip(WellShape)
            .drawBehind {
                drawRect(bg.value)
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    color = outline.value,
                    topLeft = Offset(w / 2f, w / 2f),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(14.dp.toPx() - w / 2f),
                    style = Stroke(width = w),
                )
            }
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BedShapeIcon(shape = shape, size = 28.dp, tint = { glyph.value }, cut = { bg.value })
        Spacer(Modifier.height(4.dp))
        Text(
            shape.label.uppercase(),
            style = Micro.copy(letterSpacing = 0.5.sp),
            color = labelC,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------------ notes

@Composable
private fun NotesSection(
    log: BrewLogEntity,
    state: ReviewUiState,
    warnings: Map<String, String>,
    vm: ReviewViewModel,
) {
    val eyFlag = warnings[Fields.EY]
    SectionLabel("NOTES")
    SoftCard {
        FieldWell(
            label = "NOTES",
            value = log.notes,
            onChange = vm::setNotes,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "What stood out",
            singleLine = false,
            minLines = 3,
        )
        Spacer(Modifier.height(12.dp))
        FieldWell(
            label = "NEXT TIME",
            value = log.nextTime,
            onChange = vm::setNextTime,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "The one thing to change",
        )
        Spacer(Modifier.height(12.dp))
        FieldWell(
            label = "TDS",
            value = state.tdsText,
            onChange = vm::setTdsText,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "optional",
            suffix = "%",
            numeric = true,
            warning = eyFlag?.let { "" },
            helper = "Only if you have a refractometer.",
        )
        val ey = log.extractionYield
        AnimatedVisibility(
            visible = ey != null,
            enter = fadeIn(Motion.decel()) + expandVertically(Motion.size()),
            exit = fadeOut(Motion.decel(140)) + shrinkVertically(Motion.size()),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Extraction yield", ey?.let { "${oneDp(it)} %" } ?: "")
                eyFlag?.let { WarningNote(it) }
            }
        }
    }
}

// ------------------------------------------------------------------- first read

@Composable
private fun FirstReadSection(read: List<String>, aiReady: Boolean) {
    val c = CoffeeTheme.colors
    AnimatedVisibility(
        visible = read.isNotEmpty(),
        enter = fadeIn(Motion.decel()) + expandVertically(Motion.size()),
        exit = fadeOut(Motion.decel(140)) + shrinkVertically(Motion.size()),
    ) {
        Column {
            SectionLabel("FIRST READ · OFFLINE")
            SoftCard(
                modifier = Modifier.animateContentSize(Motion.size()),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) {
                read.forEachIndexed { i, line ->
                    if (i > 0) HairLine()
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = c.inkHigh,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (aiReady) {
                        "Offline pattern matching, not a verdict. Save & analyse for a real read."
                    } else {
                        "Offline pattern matching, not a verdict. Share it, or set up analysis in Gear › App."
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = c.inkLow,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- helpers

/** One impossible-value reason, in attention colour, under the field it belongs to. */
@Composable
private fun WarningNote(text: String, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    Row(modifier.fillMaxWidth().padding(top = 6.dp)) {
        Icon(
            Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = c.attention,
            modifier = Modifier.padding(top = 1.dp).size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            color = c.attention,
        )
    }
}

/** The log row was deleted while this screen was open, or the id never existed. */
@Composable
private fun MissingBrew(onDone: () -> Unit) {
    val c = CoffeeTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Dripper sizes itself to 140 dp; the box caps it so the art stays small.
        Box(Modifier.size(88.dp)) {
            Dripper(
                waterLevel = 0f,
                dripping = false,
                shell = c.artShell,
                water = c.artWater,
                bed = c.artBed,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "This brew is no longer in the log.",
            style = MaterialTheme.typography.titleMedium,
            color = c.inkHigh,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        QuietButton(text = "Back to Today", onClick = onDone)
    }
}
