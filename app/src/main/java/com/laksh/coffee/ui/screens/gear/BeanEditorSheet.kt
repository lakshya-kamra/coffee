package com.laksh.coffee.ui.screens.gear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.laksh.coffee.ui.catalog.SheetTitle
import com.laksh.coffee.ui.catalog.StepperRow
import com.laksh.coffee.ui.components.CoffeeSheet
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.HairLine
import com.laksh.coffee.ui.components.LocalToday
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.ToggleRow
import com.laksh.coffee.ui.photos.BagPhotos
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.LocalUnits
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

private val BEAN_DATE = DateTimeFormatter.ofPattern("d MMM yyyy")

/** A change to the bean being edited. One stable instance, so sections skip when their fields did not change. */
private typealias BeanUpdate = ((BeanEntity) -> BeanEntity) -> Unit

private enum class BeanPicker { Roaster, Origin, Varietals }

/**
 * Add or edit a bag of coffee. Only the name is required. Each section is its own composable and
 * takes only the fields it shows, so a keystroke recomposes one section rather than the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeanEditorSheet(
    initial: BeanEntity,
    onDismiss: () -> Unit,
    onSave: (BeanEntity) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = CoffeeTheme.colors
    var bean by remember { mutableStateOf(initial) }
    val update: BeanUpdate = remember { { change -> bean = change(bean) } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<BeanPicker?>(null) }
    val openPicker: (BeanPicker) -> Unit = remember { { picker = it } }
    // Anything already recorded below the fold starts unfolded, so it is never hidden from you.
    var more by remember { mutableStateOf(hasDetails(initial)) }
    val today = LocalToday.current.toEpochDay()

    // Slide the sheet away first; removing it from composition would cut it off in one frame.
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    CoffeeSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(if (initial.id == 0L) "New coffee" else "Edit coffee")

            CoffeeSection(bean.name, bean.roaster, bean.lotTypeId, update, openPicker)
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

            HairLine()
            ToggleRow(
                label = "More details",
                checked = more,
                onChange = { more = it },
                subtitle = "Plant, process, roast date and the bag.",
            )

            Expandable(visible = more) {
                PlantSection(bean.speciesId, bean.varietalIds, bean.varietal, bean.originId, update, openPicker)
                ProcessSection(bean.processId, bean.isDecaf, bean.decafMethodId, bean.originId, update)
                RoastSection(
                    roastLevelId = bean.roastLevelId,
                    roastIntentId = bean.roastIntentId,
                    roastedOn = bean.roastedOnEpochDay,
                    restDays = bean.restDays,
                    openedOn = bean.openedOnEpochDay,
                    today = today,
                    update = update,
                )
                BagSection(
                    bagSizeG = bean.bagSizeG,
                    priceAmount = bean.priceAmount,
                    currency = bean.currency,
                    legacyPrice = bean.price,
                    tastingNotes = bean.tastingNotes,
                    notes = bean.notes,
                    update = update,
                )
            }

            // Outside "More details", so a photo that exists is never folded away.
            if (initial.id != 0L) {
                BagPhotos(beanId = initial.id)
            } else {
                SheetNote("Once this coffee is saved, open it again to add a photo of the bag.")
            }

            if (initial.id != 0L) {
                ToggleRow(
                    label = "Finished this bag",
                    checked = bean.isArchived,
                    onChange = { on -> update { it.copy(isArchived = on) } },
                    subtitle = "Hidden from brew setup. Its brews stay in the log.",
                )
            }

            Column(Modifier.padding(top = 8.dp)) {
                PrimaryButton(
                    text = "Save",
                    onClick = { dismissThen { onSave(cleaned(bean)) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = bean.name.isNotBlank(),
                )
                if (onDelete != null) {
                    Spacer(Modifier.height(4.dp))
                    QuietButton(
                        text = "Delete",
                        onClick = { dismissThen(onDelete) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = colors.fault,
                    )
                }
            }
        }
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

/** Anything in the folded sections already recorded. */
internal fun hasDetails(b: BeanEntity): Boolean =
    b.speciesId != null || b.varietalIds.isNotEmpty() || b.varietal.isNotBlank() ||
        b.processId.isNotBlank() || b.isDecaf || b.roastLevelId.isNotBlank() || b.roastIntentId != null ||
        b.roastedOnEpochDay != null || b.restDays != null || b.openedOnEpochDay != null ||
        b.bagSizeG != null || b.priceAmount != null || b.price.isNotBlank() ||
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
)

// ------------------------------------------------------------------ 1. the coffee

@Composable
private fun CoffeeSection(
    name: String,
    roaster: String,
    lotTypeId: String?,
    update: BeanUpdate,
    openPicker: (BeanPicker) -> Unit,
) {
    val lots = Catalog.beans.lotTypes
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
        placeholder = "Pick a roaster",
        onClear = { update { it.copy(roasterId = null, roaster = "") } },
    )
    if (lots.isNotEmpty()) {
        ChipGroup(label = "LOT", note = lots.firstOrNull { it.id == lotTypeId }?.description) {
            lots.forEach { t ->
                SelectableChip(t.label, lotTypeId == t.id, {
                    update { it.copy(lotTypeId = if (it.lotTypeId == t.id) null else t.id) }
                })
            }
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
    val parts = BeanWords.altitudeParts(altitudeMasl)
    var low by remember { mutableStateOf(parts.first) }
    var high by remember { mutableStateOf(parts.second) }

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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldWell(
            label = "ALTITUDE FROM",
            value = low,
            onChange = { v ->
                low = v.filter { c -> c.isDigit() }.take(4)
                update { it.copy(altitudeMasl = BeanWords.altitudeText(low, high)) }
            },
            modifier = Modifier.weight(1f),
            suffix = "m",
            numeric = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        FieldWell(
            label = "TO",
            value = high,
            onChange = { v ->
                high = v.filter { c -> c.isDigit() }.take(4)
                update { it.copy(altitudeMasl = BeanWords.altitudeText(low, high)) }
            },
            modifier = Modifier.weight(1f),
            suffix = "m",
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

    SectionLabel("PLANT", topGap = 8.dp)
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

// ----------------------------------------------------------------------- 5. roast

@Composable
private fun RoastSection(
    roastLevelId: String,
    roastIntentId: String?,
    roastedOn: Long?,
    restDays: Int?,
    openedOn: Long?,
    today: Long,
    update: BeanUpdate,
) {
    val levels = Catalog.beans.roastLevels
    val intents = Catalog.beans.roastIntents
    var daysAgo by remember { mutableStateOf(roastedOn?.let { (today - it).coerceAtLeast(0).toString() }.orEmpty()) }

    SectionLabel("ROAST", topGap = 8.dp)
    if (levels.isNotEmpty()) {
        ChipGroup(label = "ROAST LEVEL", note = levels.firstOrNull { it.id == roastLevelId }?.description) {
            levels.forEach { r ->
                SelectableChip(r.label, roastLevelId == r.id, {
                    update { it.copy(roastLevelId = if (it.roastLevelId == r.id) "" else r.id) }
                })
            }
        }
    }
    if (intents.isNotEmpty()) {
        ChipGroup("ROASTED FOR") {
            intents.forEach { r ->
                SelectableChip(r.label, roastIntentId == r.id, {
                    update { it.copy(roastIntentId = if (it.roastIntentId == r.id) null else r.id) }
                })
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DateField(
            label = "ROASTED ON",
            day = roastedOn,
            today = today,
            onDay = { d ->
                daysAgo = d?.let { (today - it).coerceAtLeast(0).toString() }.orEmpty()
                update { it.copy(roastedOnEpochDay = d) }
            },
            modifier = Modifier.weight(1.3f),
        )
        FieldWell(
            label = "OR DAYS AGO",
            value = daysAgo,
            onChange = { v ->
                val digits = v.filter { c -> c.isDigit() }.take(3)
                daysAgo = digits
                update { it.copy(roastedOnEpochDay = digits.toLongOrNull()?.let { n -> today - n }) }
            },
            modifier = Modifier.weight(1f),
            placeholder = "12",
            suffix = unitWord(daysAgo.toIntOrNull() ?: 0, "day"),
            numeric = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    if (roastedOn == null) SheetNote("The one field that pays for itself. You will not remember it later.")

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

// --------------------------------------------------------------------- 6. the bag

@Composable
private fun BagSection(
    bagSizeG: Double?,
    priceAmount: Double?,
    currency: String,
    legacyPrice: String,
    tastingNotes: String,
    notes: String,
    update: BeanUpdate,
) {
    val units = LocalUnits.current
    var size by remember(units) { mutableStateOf(bagSizeG?.let { units.bigWeight(it).first }.orEmpty()) }
    var price by remember { mutableStateOf(priceAmount?.let(::priceText).orEmpty()) }
    // The phone's own currency until you say otherwise.
    val localCurrency = remember { runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("") }

    SectionLabel("THE BAG", topGap = 8.dp)
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
    PickerField(
        label = label,
        value = day?.let { LocalDate.ofEpochDay(it).format(BEAN_DATE) }.orEmpty(),
        onClick = { open = true },
        modifier = modifier,
        placeholder = "Pick a date",
        onClear = { onDay(null) },
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
                        state.selectedDateMillis?.let { onDay(Math.floorDiv(it, MILLIS_PER_DAY)) }
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
