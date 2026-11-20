package com.laksh.coffee.ui.screens.gear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.laksh.coffee.core.grams
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.CatalogGrinder
import com.laksh.coffee.domain.catalog.CatalogKind
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.domain.grind.GrindScale
import com.laksh.coffee.ui.catalog.CatalogPick
import com.laksh.coffee.ui.catalog.CatalogPickerSheet
import com.laksh.coffee.ui.catalog.ChipGroup
import com.laksh.coffee.ui.catalog.Expandable
import com.laksh.coffee.ui.catalog.PickerField
import com.laksh.coffee.ui.catalog.SheetNote
import com.laksh.coffee.ui.catalog.StepperRow
import com.laksh.coffee.ui.components.CoffeeSheet
import com.laksh.coffee.ui.components.EditorChrome
import com.laksh.coffee.ui.components.SheetRole
import com.laksh.coffee.ui.components.DialReadingInput
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.GrindRangeCard
import com.laksh.coffee.ui.components.GrindRanges
import com.laksh.coffee.ui.components.PillButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.theme.CoffeeTheme
import kotlinx.coroutines.launch

/**
 * Add or edit a grinder in a sheet (editor role, v4 §2.4). Picking one from the list fills its
 * identity and its scale from the catalog; the scale stays editable, and a row that no longer
 * matches the list says "Edited". [method] is what this grinder is brewed with most, so its
 * starting range sits on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrinderEditorSheet(
    initial: GrinderEntity,
    method: BrewMethod?,
    onDismiss: () -> Unit,
    onSave: (GrinderEntity) -> Unit,
    onDelete: (() -> Unit)?,
    start: GrinderEntity = initial,
    onDraft: (GrinderEntity) -> Unit = {},
) {
    val colors = CoffeeTheme.colors
    // [start] is a draft kept across a rotation; [initial] is what "unsaved" is measured against.
    var g by remember { mutableStateOf(start) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Slide the sheet away first; removing it from composition would cut it off in one frame.
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    CoffeeSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        role = SheetRole.Editor,
        editor = EditorChrome(
            title = if (initial.id == 0L) "New grinder" else "Edit grinder",
            onSave = { if (InlineEdit.canSave(g)) dismissThen { onSave(InlineEdit.tidy(g)) } },
            canSave = InlineEdit.canSave(g),
            dirty = InlineEdit.dirty(initial, g),
            discardBody = DISCARD_GRINDER,
        ),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GrinderEditorForm(
                g = g,
                savedId = initial.id,
                method = method,
                onChange = {
                    g = it
                    onDraft(it)
                },
            )
            if (onDelete != null) {
                QuietButton(
                    text = "Delete this grinder",
                    onClick = { dismissThen(onDelete) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = colors.fault,
                )
            }
        }
    }
}

internal const val DISCARD_GRINDER = "You've changed this grinder and haven't saved it."

/**
 * The grinder editor's fields, shared by the sheet and the inline pane. A blade grinder (setting
 * unit "s", v4 §4.2) is timed in seconds and has no dial to describe.
 */
@Composable
internal fun GrinderEditorForm(
    g: GrinderEntity,
    savedId: Long,
    method: BrewMethod?,
    onChange: (GrinderEntity) -> Unit,
) {
    val colors = CoffeeTheme.colors
    var picking by remember { mutableStateOf(false) }
    // A grinder typed by hand opens with its fields showing; a listed one keeps them folded.
    var custom by remember { mutableStateOf(g.catalogId == null && savedId != 0L) }
    var scaleOpen by remember { mutableStateOf(false) }
    // Bumped whenever the row is refilled from the list, so the text fields re-read their numbers.
    var fill by remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(g)
    val set: (GrinderEntity) -> Unit = onChange

    val entry: CatalogGrinder? = remember(g.catalogId) { Catalog.grinder(g.catalogId) }
    val scale: GrindScale = remember(g) { g.scale() }
    val blade = g.settingUnit == "s"

    PickerField(
        label = "WHICH GRINDER?",
        value = entry?.display.orEmpty(),
        onClick = { picking = true },
        placeholder = "Pick it from the list",
        helper = when {
            blade -> "Timed in seconds: brew setup asks how long you ground for."
            entry != null -> CatalogDocs.grinderSubtitle(entry)
            custom -> "Not on the list, so you describe it below."
            else -> "The list knows how each dial reads, so your numbers mean something."
        },
    )

    if (entry == null && !custom) {
        QuietButton(
            text = "Not listed? Add it yourself",
            onClick = { custom = true },
            modifier = Modifier.offset(x = (-8).dp),
        )
    }

    if (blade) {
        // A blade grinder: one number, how long it runs (v4 §4.2).
        StepperRow(
            label = "USUAL GRIND TIME",
            value = (g.defaultFilterSetting?.toInt() ?: 0).takeIf { it > 0 }?.toString() ?: "—",
            unit = "s",
            onMinus = { set(latest.copy(defaultFilterSetting = ((latest.defaultFilterSetting ?: 12.0) - 1).coerceAtLeast(1.0))) },
            onPlus = { set(latest.copy(defaultFilterSetting = ((latest.defaultFilterSetting ?: 11.0) + 1).coerceAtMost(60.0))) },
            minusDescription = "One second less",
            plusDescription = "One second more",
        )
        SheetNote("Pulse in short bursts and shake the grinder between them.")
        ToggleRow(
            label = "Use this one by default",
            checked = g.isDefault,
            onChange = { set(latest.copy(isDefault = it)) },
        )
    }

    Expandable(visible = !blade && entry == null && custom) {
        key(fill) { IdentityFields(g = g, onChange = set) }
    }

    // ------------------------------------------------------- the scale
    Expandable(visible = !blade && (entry != null || custom)) {
        SoftCard(Modifier.fillMaxWidth(), color = colors.surface2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EyebrowLabel("THE SCALE", Modifier.weight(1f))
                val confidence = GrindRanges.confidence(entry?.confidence)
                when {
                    entry != null && GrinderMapping.edited(g, entry) -> StatusChip(Status.Attention, "Edited")
                    confidence != null -> StatusChip(confidence.first, confidence.second)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                GrinderMapping.scaleWords(g),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkHigh,
            )
            Row(Modifier.offset(x = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
                QuietButton(
                    text = if (scaleOpen) "Done with the scale" else "Edit the scale",
                    onClick = { scaleOpen = !scaleOpen },
                )
                if (entry != null && GrinderMapping.edited(g, entry)) {
                    QuietButton(
                        text = "Use the list's numbers",
                        onClick = {
                            set(GrinderMapping.withScale(latest, entry))
                            fill++
                        },
                    )
                }
            }
        }
        Expandable(visible = scaleOpen) {
            key(fill) { ScaleFields(g = g, onChange = set) }
        }

        if (entry != null && entry.setting.dialReading.isNotBlank()) {
            Column {
                EyebrowLabel("HOW TO READ YOUR DIAL")
                Spacer(Modifier.height(4.dp))
                SheetNote(entry.setting.dialReading)
            }
        }
        if (entry != null && entry.setting.entryHint.isNotBlank()) {
            Column {
                EyebrowLabel("WHAT TO TYPE")
                Spacer(Modifier.height(4.dp))
                SheetNote(entry.setting.entryHint)
            }
        }

        GrindRangeCard(scale = scale, method = method, inSheet = true)

        key(fill) {
            UsualSetting(
                label = "USUAL FILTER",
                scale = scale,
                value = g.defaultFilterSetting,
                onValue = { set(latest.copy(defaultFilterSetting = it)) },
            )
            UsualSetting(
                label = "USUAL ESPRESSO",
                scale = scale,
                value = g.defaultEspressoSetting,
                onValue = { set(latest.copy(defaultEspressoSetting = it)) },
            )
        }
        SheetNote("Both optional. Brew setup starts from them; the list never fills them in for you.")

        ToggleRow(
            label = "Use this one by default",
            checked = g.isDefault,
            onChange = { set(latest.copy(isDefault = it)) },
        )
    }

    if (picking) {
        CatalogPickerSheet(
            kind = CatalogKind.Grinder,
            title = "Which grinder?",
            selected = setOfNotNull(g.catalogId),
            customLabel = { "Add “$it” myself" },
            onPick = { pick ->
                when (pick) {
                    is CatalogPick.Entry -> Catalog.grinder(pick.id)?.let { c ->
                        set(GrinderMapping.fill(latest, c))
                        custom = false
                    }
                    is CatalogPick.Custom -> {
                        val (brand, model) = splitBrandModel(pick.text)
                        // Their own grinder: the link goes, the numbers they already had stay.
                        set(latest.copy(brand = brand, model = model, catalogId = null, settingUnit = ""))
                        custom = true
                    }
                }
                fill++
            },
            onDismiss = { picking = false },
        )
    }
}

/** "Baratza Encore ESP" → ("Baratza", "Encore ESP"); one word is the model's brand only. */
internal fun splitBrandModel(text: String): Pair<String, String> {
    val t = text.trim()
    val space = t.indexOf(' ')
    return if (space < 0) t to "" else t.substring(0, space) to t.substring(space + 1).trim()
}

@Composable
private fun IdentityFields(g: GrinderEntity, onChange: (GrinderEntity) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldWell(
            label = "BRAND",
            value = g.brand,
            onChange = { onChange(g.copy(brand = it)) },
            modifier = Modifier.weight(1f),
        )
        FieldWell(
            label = "MODEL",
            value = g.model,
            onChange = { onChange(g.copy(model = it)) },
            modifier = Modifier.weight(1f),
        )
    }
    FieldWell(
        label = "BURR SIZE",
        value = g.burrSizeMm,
        onChange = { onChange(g.copy(burrSizeMm = it.take(6))) },
        suffix = "mm",
        numeric = true,
    )
    ChipGroup("BURRS") {
        BurrType.entries.forEach { b ->
            SelectableChip(b.label, g.burrType == b, { onChange(g.copy(burrType = b)) })
        }
    }
}

/** The numbers behind the scale. Text of their own, so "0." survives while a decimal is typed. */
@Composable
private fun ScaleFields(g: GrinderEntity, onChange: (GrinderEntity) -> Unit) {
    var perTurn by remember { mutableStateOf(g.clicksPerRotation.takeIf { it > 0 }?.toString().orEmpty()) }
    var microns by remember { mutableStateOf(g.micronsPerClick?.let(::grams).orEmpty()) }
    var min by remember { mutableStateOf(grams(g.minSetting)) }
    var max by remember { mutableStateOf(grams(g.maxSetting)) }
    var step by remember { mutableStateOf(g.step.toString().removeSuffix(".0")) }
    val clicks = g.settingStyle == SettingStyle.Clicks

    ChipGroup("HOW IT ADJUSTS") {
        SettingStyle.entries.forEach { st ->
            SelectableChip(st.label, g.settingStyle == st, { onChange(g.copy(settingStyle = st)) })
        }
    }

    // Only click grinders need the click maths.
    Expandable(visible = clicks) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldWell(
                label = "CLICKS PER TURN",
                value = perTurn,
                onChange = { input ->
                    val digits = input.filter { c -> c.isDigit() }.take(3)
                    perTurn = digits
                    onChange(g.copy(clicksPerRotation = digits.toIntOrNull() ?: 0))
                },
                modifier = Modifier.weight(1f),
                numeric = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            FieldWell(
                label = "MICRONS / CLICK",
                value = microns,
                onChange = { input ->
                    microns = input.take(6)
                    onChange(g.copy(micronsPerClick = microns.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }))
                },
                modifier = Modifier.weight(1f),
                suffix = "µm",
                numeric = true,
            )
        }
        SheetNote("Both from your manual, both optional. They make grind advice specific to this grinder.")
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldWell(
            label = "LOWEST",
            value = min,
            onChange = { input ->
                min = input.take(6)
                min.replace(',', '.').toDoubleOrNull()?.let { onChange(g.copy(minSetting = it)) }
            },
            modifier = Modifier.weight(1f),
            numeric = true,
        )
        FieldWell(
            label = "HIGHEST",
            value = max,
            onChange = { input ->
                max = input.take(6)
                max.replace(',', '.').toDoubleOrNull()?.let { onChange(g.copy(maxSetting = it)) }
            },
            modifier = Modifier.weight(1f),
            numeric = true,
            warning = if (g.maxSetting <= g.minSetting) "Needs to be above the lowest." else null,
        )
        FieldWell(
            label = "STEP",
            value = step,
            onChange = { input ->
                step = input.take(5)
                step.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { onChange(g.copy(step = it)) }
            },
            modifier = Modifier.weight(1f),
            numeric = true,
        )
    }

    ToggleRow(
        label = if (clicks) "Fewer clicks is finer" else "Lower is finer",
        checked = g.lowerIsFiner,
        onChange = { onChange(g.copy(lowerIsFiner = it)) },
        subtitle = "True for anything you zero at burr contact and dial outward.",
    )

    Expandable(visible = clicks) {
        ChipGroup(
            label = "HOW YOUR DIAL READS",
            note = "Only changes the words. The app always stores total clicks from zero.",
        ) {
            GrinderMapping.DIAL_CHOICES.forEach { (code, label) ->
                SelectableChip(label, g.dialFormat == code, { onChange(g.copy(dialFormat = code)) })
            }
        }
    }
}

/**
 * A usual setting, typed as the stored number or (on dials that read differently) stepped in the
 * dial's own terms.
 */
@Composable
private fun UsualSetting(
    label: String,
    scale: GrindScale,
    value: Double?,
    onValue: (Double?) -> Unit,
) {
    var text by remember { mutableStateOf(value?.let(scale::format).orEmpty()) }
    var asDial by remember { mutableStateOf(false) }
    val hasDial = scale.dial !is DialFormat.Plain

    Column {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldWell(
                label = label,
                value = text,
                onChange = { input ->
                    text = input.take(7)
                    onValue(text.replace(',', '.').toDoubleOrNull())
                },
                modifier = Modifier.weight(1f),
                suffix = scale.unit.ifBlank { null },
                numeric = true,
            )
            if (hasDial) {
                PillButton(
                    text = if (asDial) "Hide the dial" else "Set by the dial",
                    onClick = { asDial = !asDial },
                    height = 52.dp,
                )
            }
        }
        // Read back the way the dial is read, so 66 is visibly "6.6" on a K-Ultra.
        val words = value?.let { scale.describe(it) }.orEmpty()
        if (words.isNotBlank() && !asDial) {
            Spacer(Modifier.height(6.dp))
            SheetNote(words)
        }
        Expandable(visible = asDial && hasDial, modifier = Modifier.padding(top = 10.dp)) {
            DialReadingInput(
                scale = scale,
                value = value,
                onValue = { v ->
                    text = v?.let(scale::format).orEmpty()
                    onValue(v)
                },
            )
        }
    }
}
