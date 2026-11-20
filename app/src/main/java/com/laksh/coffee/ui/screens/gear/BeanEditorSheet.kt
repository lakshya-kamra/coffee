package com.laksh.coffee.ui.screens.gear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.laksh.coffee.core.unitWord
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.CatalogKind
import com.laksh.coffee.ui.catalog.CatalogPick
import com.laksh.coffee.ui.catalog.CatalogPickerSheet
import com.laksh.coffee.ui.catalog.ChipGroup
import com.laksh.coffee.ui.catalog.Expandable
import com.laksh.coffee.ui.catalog.PickerField
import com.laksh.coffee.ui.catalog.SheetNote
import com.laksh.coffee.ui.catalog.StepperRow
import com.laksh.coffee.ui.components.CoffeeSheet
import com.laksh.coffee.ui.components.EditorChrome
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.LocalToday
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SegmentedControl
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.SheetRole
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.components.focusRing
import com.laksh.coffee.ui.components.hoverTint
import com.laksh.coffee.ui.photos.BagPhotos
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.WellShape
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** A change to the bean being edited. One stable instance, so sections skip when their fields did not change. */
internal typealias BeanUpdate = ((BeanEntity) -> BeanEntity) -> Unit

private enum class BeanPicker { Roaster, Origin, Varietals }

/** "Ground for" choices (v4 §4.2), stored ids and the words on the chips. */
private val GROUND_FOR = listOf("filter" to "Filter", "press" to "Press", "espresso" to "Espresso", "moka" to "Moka")

/**
 * Add or edit a bag of coffee in a sheet (phones and medium windows; v4 §2.4 editor role, a side
 * sheet if it is ever opened wide). Only the name is required. The header carries Cancel and Save;
 * leaving with changes asks first.
 */
@Composable
fun BeanEditorSheet(
    initial: BeanEntity,
    onDismiss: () -> Unit,
    onSave: (BeanEntity) -> Unit,
    onDelete: (() -> Unit)?,
    start: BeanEntity = initial,
    onDraft: (BeanEntity) -> Unit = {},
) {
    val colors = CoffeeTheme.colors
    // [start] is a draft kept across a rotation; [initial] is what "unsaved" is measured against.
    var bean by remember { mutableStateOf(start) }
    val draftTo by rememberUpdatedState(onDraft)
    val update: BeanUpdate = remember {
        { change ->
            bean = change(bean)
            draftTo(bean)
        }
    }
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
            title = if (initial.id == 0L) "New coffee" else "Edit coffee",
            onSave = { if (InlineEdit.canSave(bean)) dismissThen { onSave(cleaned(bean)) } },
            canSave = InlineEdit.canSave(bean),
            dirty = InlineEdit.dirty(initial, bean),
        ),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BeanEditorForm(bean = bean, savedId = initial.id, update = update)
            if (onDelete != null) {
                QuietButton(
                    text = "Delete this coffee",
                    onClick = { dismissThen(onDelete) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = colors.fault,
                )
            }
        }
    }
}

/**
 * The bean editor's fields in the §4.7 order: name, roaster, whole or ground, roast date, roast
 * level, bag size, then "More details". Each section takes only the fields it shows, so a
 * keystroke recomposes one section rather than the form. Owns its pickers.
 */
@Composable
internal fun BeanEditorForm(bean: BeanEntity, savedId: Long, update: BeanUpdate) {
    var picker by remember { mutableStateOf<BeanPicker?>(null) }
    val openPicker: (BeanPicker) -> Unit = remember { { picker = it } }
    // Anything already recorded below the fold starts unfolded, so it is never hidden from you.
    var more by remember { mutableStateOf(hasDetails(bean)) }
    val today = LocalToday.current.toEpochDay()

    CoffeeSection(bean.name, bean.roaster, update, openPicker)
    FormSection(bean.form, bean.groundFor, update)
    RoastedSection(bean.roastedOnEpochDay, bean.roastLevelId, today, update)
    BagSizeField(bean.bagSizeG, update)

    HairLine()
    Disclosure(
        title = "More details",
        subtitle = "Origin, farm, process, lot",
        open = more,
        onToggle = { more = !more },
    )

    Expandable(visible = more) {
        LotChips(bean.lotTypeId, update)
        OriginSection(
            origin = bean.origin,
            originId = bean.originId,
            region = bean.region,
            farm = bean.farm,
            producer = bean.producer,
            washingStation = bean.washingStation,
            altitudeMasl = bean.altitudeMasl,
            harvest = bean.harvest,
            update = update,
            openPicker = openPicker,
        )
        ProcessSection(bean.processId, bean.isDecaf, bean.decafMethodId, bean.originId, update)
        PlantSection(bean.speciesId, bean.varietalIds, bean.varietal, bean.originId, update, openPicker)
        RestSection(
            roastIntentId = bean.roastIntentId,
            roastedOn = bean.roastedOnEpochDay,
            restDays = bean.restDays,
            openedOn = bean.openedOnEpochDay,
            today = today,
            update = update,
        )
        PriceAndNotes(
            priceAmount = bean.priceAmount,
            currency = bean.currency,
            legacyPrice = bean.price,
            tastingNotes = bean.tastingNotes,
            notes = bean.notes,
            update = update,
        )
    }

    // Outside "More details", so a photo that exists is never folded away.
    if (savedId != 0L) {
        BagPhotos(beanId = savedId)
        ToggleRow(
            label = "Finished this bag",
            checked = bean.isArchived,
            onChange = { on -> update { it.copy(isArchived = on) } },
            subtitle = "Hidden from brew setup. Its brews stay in the log.",
        )
    } else {
        SheetNote("Once this coffee is saved, open it again to add a photo of the bag.")
    }

    when (picker) {
        BeanPicker.Roaster -> CatalogPickerSheet(
            kind = CatalogKind.Roaster,
            title = "Who roasted it?",
            selected = setOfNotNull(bean.roasterId),
            onPick = { pick ->
                when (pick) {
                    is CatalogPick.Entry -> Catalog.roaster(pick.id)?.let { r ->
                        update { it.copy(roasterId = r.id, roaster = r.name) }
                    }
                    is CatalogPick.Custom -> update { it.copy(roasterId = null, roaster = pick.text) }
                }
            },
            onDismiss = { picker = null },
        )
        BeanPicker.Origin -> CatalogPickerSheet(
            kind = CatalogKind.Origin,
            title = "Where is it from?",
            selected = setOfNotNull(bean.originId),
            onPick = { pick ->
                when (pick) {
                    is CatalogPick.Entry -> Catalog.origin(pick.id)?.let { o ->
                        // A region belongs to its country: a new country starts without one.
                        update { it.copy(originId = o.id, origin = o.country, region = if (it.originId == o.id) it.region else "") }
                    }
                    is CatalogPick.Custom -> update { it.copy(originId = null, origin = pick.text) }
                }
            },
            onDismiss = { picker = null },
        )
        BeanPicker.Varietals -> CatalogPickerSheet(
            kind = CatalogKind.Varietal,
            title = "Varietals",
            multiSelect = true,
            selected = bean.varietalIds.toSet(),
            customLabel = { "Add “$it” as my own" },
            // Multi-select hands back only a name typed by hand; the sheet stays open.
            onPick = { pick ->
                if (pick is CatalogPick.Custom) update { it.copy(varietal = BeanWords.appendVarietal(it.varietal, pick.text)) }
            },
            onDone = { ids ->
                // Catalog order, so the same choice always reads the same way.
                val ordered = Catalog.beans.varietals.map { it.id }.filter { it in ids }
                update { it.copy(varietalIds = ordered) }
            },
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}

/** A row that folds a section open and shut; TalkBack reads "expanded" or "collapsed". */
@Composable
internal fun Disclosure(title: String, subtitle: String?, open: Boolean, onToggle: () -> Unit) {
    val c = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(interaction, WellShape)
            .clip(WellShape)
            .hoverTint(interaction, c.inkHigh.copy(alpha = 0.04f), WellShape)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.inkHigh)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkLow)
        }
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.inkMid)
    }
}

/** Anything in the folded sections already recorded. */
internal fun hasDetails(b: BeanEntity): Boolean =
    b.lotTypeId != null || b.origin.isNotBlank() || b.region.isNotBlank() || b.farm.isNotBlank() ||
        b.producer.isNotBlank() || b.washingStation.isNotBlank() || b.altitudeMasl.isNotBlank() || b.harvest.isNotBlank() ||
        b.speciesId != null || b.varietalIds.isNotEmpty() || b.varietal.isNotBlank() ||
        b.processId.isNotBlank() || b.isDecaf || b.roastIntentId != null ||
        b.restDays != null || b.openedOnEpochDay != null ||
        b.priceAmount != null || b.price.isNotBlank() ||
        b.tastingNotes.isNotBlank() || b.notes.isNotBlank()

/** Trimmed text; a decaf method without decaf makes no sense and is dropped. */
internal fun cleaned(b: BeanEntity): BeanEntity = b.copy(
    name = b.name.trim(),
    roaster = b.roaster.trim(),
    origin = b.origin.trim(),
    region = b.region.trim(),
    farm = b.farm.trim(),
    producer = b.producer.trim(),
    washingStation = b.washingStation.trim(),
    harvest = b.harvest.trim(),
    varietal = b.varietal.trim(),
    decafMethodId = b.decafMethodId.takeIf { b.isDecaf },
    currency = b.currency.trim().uppercase(Locale.ROOT).takeIf { b.priceAmount != null }.orEmpty(),
    // "Ground for" only means something for ground coffee.
    groundFor = b.groundFor.takeIf { b.form == "ground" }.orEmpty(),
)

// ------------------------------------------------------------------ 1. the coffee

@Composable
private fun CoffeeSection(
    name: String,
    roaster: String,
    update: BeanUpdate,
    openPicker: (BeanPicker) -> Unit,
) {
    FieldWell(
        label = "COFFEE NAME",
        value = name,
        onChange = { v -> update { it.copy(name = v.take(80)) } },
        placeholder = "Kiamabara AA",
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
    )
    PickerField(
        label = "ROASTER",
        value = roaster,
        onClick = { openPicker(BeanPicker.Roaster) },
        placeholder = "Pick a roaster or brand",
        onClear = { update { it.copy(roasterId = null, roaster = "") } },
        helper = "Supermarket brands are on the list too.",
    )
}

/** Whole beans or ground, right under the roaster (v4 §4.2); ground coffee says what it is ground for. */
@Composable
private fun FormSection(form: String, groundFor: String, update: BeanUpdate) {
    val ground = form == "ground"
    Column {
        EyebrowLabel("WHOLE BEANS OR GROUND")
        Spacer(Modifier.height(6.dp))
        SegmentedControl(
            options = listOf("Whole beans", "Ground"),
            selectedIndex = if (ground) 1 else 0,
            onSelect = { i -> update { it.copy(form = if (i == 1) "ground" else "whole") } },
            onCard = true,
        )
    }
    Expandable(visible = ground) {
        ChipGroup("GROUND FOR", note = "As the bag says. Pre-ground coffee skips grind settings when you brew.") {
            GROUND_FOR.forEach { (id, label) ->
                SelectableChip(label, groundFor == id, { update { it.copy(groundFor = if (it.groundFor == id) "" else id) } })
            }
        }
    }
}

/** The roast date (a date, or "about n days ago"), then the roast level. */
@Composable
private fun RoastedSection(roastedOn: Long?, roastLevelId: String, today: Long, update: BeanUpdate) {
    val levels = Catalog.beans.roastLevels
    val daysAgo = roastedOn?.let { (today - it).coerceAtLeast(0L) }
    DateField(
        label = "ROASTED ON",
        day = roastedOn,
        today = today,
        onDay = { d -> update { it.copy(roastedOnEpochDay = d) } },
    )
    // For a bag without a printed date: step the days instead (v4 §4.7).
    StepperRow(
        label = "OR DAYS AGO",
        value = daysAgo?.toString() ?: "—",
        unit = daysAgo?.let { unitWord(it.toInt(), "day") },
        onMinus = { update { it.copy(roastedOnEpochDay = it.roastedOnEpochDay?.let { d -> (d + 1).coerceAtMost(today) }) } },
        onPlus = { update { it.copy(roastedOnEpochDay = (it.roastedOnEpochDay ?: (today + 1)) - 1) } },
        onLongMinus = { update { it.copy(roastedOnEpochDay = it.roastedOnEpochDay?.let { d -> (d + 7).coerceAtMost(today) }) } },
        onLongPlus = { update { it.copy(roastedOnEpochDay = (it.roastedOnEpochDay ?: today) - 7) } },
        minusDescription = "One day more recent",
        plusDescription = "One day longer ago",
    )
    if (daysAgo != null) {
        Text(
            "Roasted about $daysAgo ${if (daysAgo == 1L) "day" else "days"} ago",
            style = MaterialTheme.typography.bodyMedium,
            color = CoffeeTheme.colors.inkHigh,
        )
    } else {
        SheetNote("Roast date is the one field that pays for itself. You will not remember it later.")
    }
    if (levels.isNotEmpty()) {
        ChipGroup(label = "ROAST LEVEL", note = levels.firstOrNull { it.id == roastLevelId }?.description) {
            levels.forEach { r ->
                SelectableChip(r.label, roastLevelId == r.id, {
                    update { it.copy(roastLevelId = if (it.roastLevelId == r.id) "" else r.id) }
                })
            }
        }
    }
}

@Composable
private fun BagSizeField(bagSizeG: Double?, update: BeanUpdate) {
    val units = LocalUnits.current
    var size by remember(units) { mutableStateOf(bagSizeG?.let { units.bigWeight(it).first }.orEmpty()) }
    FieldWell(
        label = "BAG SIZE",
        value = size,
        onChange = { v ->
            size = v.take(7)
            val n = size.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
            update { it.copy(bagSizeG = n?.let(units::bigWeightToGrams)) }
        },
        suffix = units.bigWeightUnit,
        numeric = true,
    )
}

@Composable
private fun LotChips(lotTypeId: String?, update: BeanUpdate) {
    val lots = Catalog.beans.lotTypes
    if (lots.isEmpty()) return
    ChipGroup(label = "LOT", note = lots.firstOrNull { it.id == lotTypeId }?.description) {
        lots.forEach { t ->
            SelectableChip(t.label, lotTypeId == t.id, {
                update { it.copy(lotTypeId = if (it.lotTypeId == t.id) null else t.id) }
            })
        }
    }
}

// ---------------------------------------------------------------------- 2. origin

@Composable
private fun OriginSection(
    origin: String,
    originId: String?,
    region: String,
    farm: String,
    producer: String,
    washingStation: String,
    altitudeMasl: String,
    harvest: String,
    update: BeanUpdate,
    openPicker: (BeanPicker) -> Unit,
) {
    val regions = remember(originId) { Catalog.origin(originId)?.growingRegions.orEmpty() }
    // "Other…" is open when the region typed is not one of the list's.
    var otherRegion by remember(originId) { mutableStateOf(region.isNotBlank() && region !in regions) }
    // Metres are stored; feet are only how the fields read (v4 §4.7).
    var feet by remember { mutableStateOf(false) }
    val parts = if (feet) BeanWords.altitudeInFeet(altitudeMasl) else BeanWords.altitudeParts(altitudeMasl)
    var low by remember(feet) { mutableStateOf(parts.first) }
    var high by remember(feet) { mutableStateOf(parts.second) }
    val store: () -> Unit = {
        val text = if (feet) BeanWords.altitudeFromFeet(low, high) else BeanWords.altitudeText(low, high)
        update { it.copy(altitudeMasl = text) }
    }

    SectionLabel("ORIGIN", topGap = 8.dp)
    PickerField(
        label = "COUNTRY",
        value = origin,
        onClick = { openPicker(BeanPicker.Origin) },
        placeholder = "Pick a country",
        onClear = { update { it.copy(originId = null, origin = "", region = "") } },
    )
    if (regions.isNotEmpty()) {
        ChipGroup("GROWING REGION") {
            regions.forEach { r ->
                SelectableChip(r, region == r && !otherRegion, {
                    otherRegion = false
                    update { it.copy(region = if (it.region == r) "" else r) }
                })
            }
            SelectableChip("Other…", otherRegion, {
                otherRegion = !otherRegion
                update { it.copy(region = "") }
            })
        }
    }
    Expandable(visible = regions.isEmpty() || otherRegion) {
        FieldWell(
            label = if (regions.isEmpty()) "GROWING REGION" else "OTHER REGION",
            value = region,
            onChange = { v -> update { it.copy(region = v.take(60)) } },
        )
    }
    FieldWell(
        label = "FARM / ESTATE",
        value = farm,
        onChange = { v -> update { it.copy(farm = v.take(80)) } },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldWell(
            label = "PRODUCER",
            value = producer,
            onChange = { v -> update { it.copy(producer = v.take(80)) } },
            modifier = Modifier.weight(1f),
        )
        FieldWell(
            label = "WASHING STATION",
            value = washingStation,
            onChange = { v -> update { it.copy(washingStation = v.take(80)) } },
            modifier = Modifier.weight(1f),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        EyebrowLabel("ALTITUDE", Modifier.weight(1f))
        SegmentedControl(
            options = listOf("m", "ft"),
            selectedIndex = if (feet) 1 else 0,
            onSelect = { feet = it == 1 },
            modifier = Modifier.width(120.dp),
            onCard = true,
        )
    }
    val unit = if (feet) "ft" else "m"
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldWell(
            label = "FROM",
            value = low,
            onChange = { v ->
                low = v.filter { c -> c.isDigit() }.take(5)
                store()
            },
            modifier = Modifier.weight(1f),
            suffix = unit,
            numeric = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        FieldWell(
            label = "TO",
            value = high,
            onChange = { v ->
                high = v.filter { c -> c.isDigit() }.take(5)
                store()
            },
            modifier = Modifier.weight(1f),
            suffix = unit,
            numeric = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    FieldWell(
        label = "HARVEST",
        value = harvest,
        onChange = { v -> update { it.copy(harvest = v.take(30)) } },
        placeholder = "As the bag says it",
    )
}

// ----------------------------------------------------------------------- 3. plant

@Composable
private fun PlantSection(
    speciesId: String?,
    varietalIds: List<String>,
    varietal: String,
    originId: String?,
    update: BeanUpdate,
    openPicker: (BeanPicker) -> Unit,
) {
    val species = Catalog.beans.species
    // What usually grows there, as one-tap chips; anything else comes from the full list.
    val common = remember(originId) {
        Catalog.origin(originId)?.commonVarietals.orEmpty().mapNotNull { Catalog.varietal(it) }
    }
    val chosen = remember(varietalIds) { varietalIds.mapNotNull { Catalog.varietal(it) } }

    SectionLabel("VARIETAL", topGap = 8.dp)
    if (species.isNotEmpty()) {
        ChipGroup("SPECIES") {
            species.forEach { s ->
                SelectableChip(s.label, speciesId == s.id, {
                    update { it.copy(speciesId = if (it.speciesId == s.id) null else s.id) }
                })
            }
        }
    }
    if (common.isNotEmpty()) {
        ChipGroup("VARIETALS OFTEN GROWN THERE") {
            common.forEach { v ->
                SelectableChip(v.label, v.id in varietalIds, {
                    update { b -> b.copy(varietalIds = if (v.id in b.varietalIds) b.varietalIds - v.id else b.varietalIds + v.id) }
                })
            }
        }
    }
    PickerField(
        label = "VARIETALS",
        value = chosen.joinToString(", ") { it.label },
        onClick = { openPicker(BeanPicker.Varietals) },
        placeholder = "Pick from the list",
        onClear = { update { it.copy(varietalIds = emptyList()) } },
    )
    FieldWell(
        label = "OTHER VARIETAL NAMES",
        value = varietal,
        onChange = { v -> update { it.copy(varietal = v.take(120)) } },
        helper = "For anything not on the list, comma-separated.",
    )
}

// --------------------------------------------------------------------- 4. process

@Composable
private fun ProcessSection(
    processId: String,
    isDecaf: Boolean,
    decafMethodId: String?,
    originId: String?,
    update: BeanUpdate,
) {
    val all = Catalog.beans.processes
    val common = remember(originId) {
        Catalog.origin(originId)?.commonProcesses.orEmpty().mapNotNull { Catalog.process(it) }
    }
    val groups = remember(originId) {
        val commonIds = common.map { it.id }.toSet()
        all.filter { it.id !in commonIds }.groupBy { it.group }.toList()
    }
    val toggle: (String) -> Unit = { id -> update { it.copy(processId = if (it.processId == id) "" else id) } }

    SectionLabel("PROCESS", topGap = 8.dp)
    if (common.isNotEmpty()) {
        ChipGroup("COMMON THERE") {
            common.forEach { p -> SelectableChip(p.label, processId == p.id, { toggle(p.id) }) }
        }
    }
    groups.forEach { (group, list) ->
        ChipGroup(CatalogDocs.processGroupLabel(group).uppercase(Locale.ROOT)) {
            list.forEach { p -> SelectableChip(p.label, processId == p.id, { toggle(p.id) }) }
        }
    }
    Catalog.process(processId)?.description?.takeIf { it.isNotBlank() }?.let { SheetNote(it) }

    ToggleRow(
        label = "Decaf",
        checked = isDecaf,
        onChange = { on -> update { it.copy(isDecaf = on) } },
    )
    Expandable(visible = isDecaf && Catalog.beans.decafMethods.isNotEmpty()) {
        ChipGroup("DECAF METHOD") {
            Catalog.beans.decafMethods.forEach { d ->
                SelectableChip(d.label, decafMethodId == d.id, {
                    update { it.copy(decafMethodId = if (it.decafMethodId == d.id) null else d.id) }
                })
            }
        }
    }
}

// ------------------------------------------------------------- 5. rest and opening

@Composable
private fun RestSection(
    roastIntentId: String?,
    roastedOn: Long?,
    restDays: Int?,
    openedOn: Long?,
    today: Long,
    update: BeanUpdate,
) {
    val intents = Catalog.beans.roastIntents
    SectionLabel("ROAST AND REST", topGap = 8.dp)
    if (intents.isNotEmpty()) {
        ChipGroup("ROASTED FOR") {
            intents.forEach { r ->
                SelectableChip(r.label, roastIntentId == r.id, {
                    update { it.copy(roastIntentId = if (it.roastIntentId == r.id) null else r.id) }
                })
            }
        }
    }
    StepperRow(
        label = "REST BEFORE OPENING",
        value = restDays?.toString() ?: "—",
        unit = restDays?.let { unitWord(it, "day") },
        onMinus = { update { it.copy(restDays = it.restDays?.let { d -> if (d <= 0) null else d - 1 }) } },
        onPlus = { update { it.copy(restDays = ((it.restDays ?: -1) + 1).coerceAtMost(MAX_REST_DAYS)) } },
        onLongMinus = { update { it.copy(restDays = it.restDays?.let { d -> if (d < 7) null else d - 7 }) } },
        onLongPlus = { update { it.copy(restDays = ((it.restDays ?: 0) + 7).coerceAtMost(MAX_REST_DAYS)) } },
        minusDescription = "One day less rest",
        plusDescription = "One day more rest",
    )
    SheetNote("The rest your roaster suggests before opening, if any. Hold a button to move a week.")
    if (roastedOn != null && restDays != null) {
        val left = roastedOn + restDays - today
        if (left > 0) {
            StatusLine(Status.Attention, "Resting", detail = BeanWords.restDetail(left))
        } else {
            StatusLine(Status.Good, "Rested", detail = "past the rest your roaster suggests")
        }
    }

    DateField(
        label = "OPENED ON",
        day = openedOn,
        today = today,
        onDay = { d -> update { it.copy(openedOnEpochDay = d) } },
    )
}

private const val MAX_REST_DAYS = 90

// ------------------------------------------------------------ 6. price and notes

@Composable
private fun PriceAndNotes(
    priceAmount: Double?,
    currency: String,
    legacyPrice: String,
    tastingNotes: String,
    notes: String,
    update: BeanUpdate,
) {
    var price by remember { mutableStateOf(priceAmount?.let(::priceText).orEmpty()) }
    // The phone's own currency until you say otherwise.
    val localCurrency = remember { runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("") }

    SectionLabel("PRICE AND NOTES", topGap = 8.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldWell(
            label = "PRICE",
            value = price,
            onChange = { v ->
                price = v.take(9)
                val n = price.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
                update { it.copy(priceAmount = n, currency = if (n != null && it.currency.isBlank()) localCurrency else it.currency) }
            },
            modifier = Modifier.weight(1.4f),
            numeric = true,
            // What 1.x stored as free text stays readable until a number replaces it.
            helper = legacyPrice.takeIf { it.isNotBlank() && priceAmount == null }?.let { "You wrote “$it” before." },
        )
        FieldWell(
            label = "CURRENCY",
            value = currency,
            onChange = { v -> update { it.copy(currency = v.filter { c -> c.isLetter() }.take(3).uppercase(Locale.ROOT)) } },
            modifier = Modifier.weight(1f),
            placeholder = localCurrency.ifBlank { "GBP" },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
    }
    FieldWell(
        label = "ROASTER'S TASTING NOTES",
        value = tastingNotes,
        onChange = { v -> update { it.copy(tastingNotes = v.take(300)) } },
        singleLine = false,
        minLines = 2,
    )
    FieldWell(
        label = "YOUR NOTES",
        value = notes,
        onChange = { v -> update { it.copy(notes = v.take(1000)) } },
        singleLine = false,
        minLines = 2,
    )
}

// ------------------------------------------------------------------------- dates

/** A date that opens the Material picker. Days are epoch days; nothing after [today] can be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    day: Long?,
    today: Long,
    onDay: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(onDay)
    // The phone's own date style (v4 §4.15).
    val format = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    PickerField(
        label = label,
        value = day?.let { LocalDate.ofEpochDay(it).format(format) }.orEmpty(),
        onClick = { open = true },
        modifier = modifier,
        placeholder = "Pick a date",
        onClear = { latest(null) },
    )
    if (open) {
        val limit = today * MILLIS_PER_DAY
        val state = rememberDatePickerState(
            // The picker works in UTC midnights, which is exactly an epoch day.
            initialSelectedDateMillis = (day ?: today) * MILLIS_PER_DAY,
            selectableDates = remember(limit) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= limit
                }
            },
        )
        val c = CoffeeTheme.colors
        val pickerColors = DatePickerDefaults.colors(
            containerColor = c.surface1,
            titleContentColor = c.inkMid,
            headlineContentColor = c.inkHigh,
            weekdayContentColor = c.inkMid,
            subheadContentColor = c.inkMid,
            navigationContentColor = c.inkMid,
            yearContentColor = c.inkHigh,
            currentYearContentColor = c.inkHigh,
            selectedYearContainerColor = c.inverse,
            selectedYearContentColor = c.onInverse,
            dayContentColor = c.inkHigh,
            disabledDayContentColor = c.inkFaint,
            selectedDayContainerColor = c.inverse,
            selectedDayContentColor = c.onInverse,
            todayContentColor = c.inkHigh,
            todayDateBorderColor = c.inkMid,
            dividerColor = c.hairline,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            colors = pickerColors,
            confirmButton = {
                QuietButton(
                    text = "Use this date",
                    onClick = {
                        state.selectedDateMillis?.let { latest(Math.floorDiv(it, MILLIS_PER_DAY)) }
                        open = false
                    },
                    color = CoffeeTheme.colors.inkHigh,
                )
            },
            dismissButton = { QuietButton(text = "Cancel", onClick = { open = false }) },
        ) {
            DatePicker(state = state, showModeToggle = false, colors = pickerColors)
        }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * A stored price as the field shows it: every digit that was typed, no trailing zeros
 * (12.99 stays "12.99", 13.0 reads "13"). Not the weight formatter, which rounds to one decimal.
 */
internal fun priceText(amount: Double): String =
    if (amount.isFinite()) java.math.BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString() else ""
