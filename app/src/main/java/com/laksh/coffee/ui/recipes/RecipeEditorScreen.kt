package com.laksh.coffee.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.ratio
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.RecipeValidation
import com.laksh.coffee.domain.StepKind
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.CatalogKind
import com.laksh.coffee.ui.catalog.CatalogPick
import com.laksh.coffee.ui.catalog.CatalogPickerSheet
import com.laksh.coffee.ui.catalog.ChipGroup
import com.laksh.coffee.ui.catalog.PickerField
import com.laksh.coffee.ui.catalog.SheetNote
import com.laksh.coffee.ui.catalog.SheetTitle
import com.laksh.coffee.ui.catalog.StepperRow
import com.laksh.coffee.ui.components.CoffeeSheet
import com.laksh.coffee.ui.components.DetailTopBar
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.JourneyLine
import com.laksh.coffee.ui.components.JourneyStyle
import com.laksh.coffee.ui.components.PillButton
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SectionLabel
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.components.StatusLine
import com.laksh.coffee.ui.components.StickyCtaBar
import com.laksh.coffee.ui.components.journeyNodes
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.LocalUnits
import com.laksh.coffee.ui.theme.Motion

private typealias DraftEdit = ((RecipeDraft) -> RecipeDraft) -> Unit

private val NoProgress: () -> Float = { 0f }

/**
 * Write a recipe, copy one, or change one. [recipeId] is the recipe to edit or copy (null for a
 * new one). [onDone] gets the saved recipe's id, or null after a delete; [onBack] leaves without
 * saving.
 */
@Composable
fun RecipeEditorScreen(
    recipeId: String?,
    mode: EditorMode,
    onDone: (savedId: String?) -> Unit,
    onBack: () -> Unit,
    vm: RecipeEditorViewModel = viewModel(
        // Keyed: the store may hold another editor (a different recipe, or a different mode).
        key = "recipe-editor-${mode.name}-${recipeId.orEmpty()}",
        factory = RecipeEditorViewModel.factory(recipeId, mode),
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val draft = vm.draft
    val colors = CoffeeTheme.colors
    val edit: DraftEdit = remember(vm) { vm::edit }
    var pickingBrewer by remember { mutableStateOf(false) }
    var startingFrom by remember { mutableStateOf(false) }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(colors.bg).imePadding()) {
        if (draft == null) {
            if (state.missing) {
                Text(
                    "That recipe is no longer here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMid,
                    modifier = Modifier.align(Alignment.Center).padding(Dimens.Gutter),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dimens.Gutter,
                    end = Dimens.Gutter,
                    top = topInset + 56.dp,
                    bottom = 132.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            ) {
                item(key = "head", contentType = "head") {
                    EditorHead(
                        title = state.title,
                        note = state.builtIn?.let { RecipeDraft.overrideNote(it) } ?: draft.sourceNote,
                        offerStartFrom = state.mode == EditorMode.New,
                        onStartFrom = { startingFrom = true },
                    )
                }
                item(key = "preview", contentType = "preview") { Preview(draft) }
                item(key = "basics", contentType = "basics") {
                    Basics(
                        name = draft.name,
                        author = draft.author,
                        brewerTitle = CatalogDocs.brewerTitle(draft.brewer),
                        dose = draft.dose,
                        water = draft.water,
                        tempC = draft.tempC,
                        grind = draft.grind,
                        blurb = draft.blurb,
                        issues = state.issues,
                        edit = edit,
                        onPickBrewer = { pickingBrewer = true },
                    )
                }

                item(key = "steps-label", contentType = "label") {
                    SectionLabel("STEPS", trailing = clock(draft.totalSec), topGap = 12.dp)
                }
                itemsIndexed(draft.steps, key = { _, s -> s.key }, contentType = { _, _ -> "step" }) { i, step ->
                    StepCard(
                        index = i,
                        count = draft.steps.size,
                        step = step,
                        startSec = draft.steps.take(i).sumOf { it.durationSec.coerceAtLeast(0) },
                        delta = draft.pourDelta(i),
                        issue = state.issues.firstOrNull { it.stepIndex == i && it.fix == null }?.message,
                        edit = edit,
                        modifier = Modifier.animateItem(
                            fadeInSpec = Motion.settle(),
                            placementSpec = Motion.offset(),
                            fadeOutSpec = Motion.quick(),
                        ),
                    )
                }
                item(key = "add-step", contentType = "add-step") {
                    AddStep(
                        enabled = draft.steps.size < RecipeValidation.STEPS_MAX,
                        onAdd = vm::addStep,
                        modifier = Modifier.animateItem(placementSpec = Motion.offset()),
                    )
                }

                item(key = "prep", contentType = "list") {
                    TextList(
                        label = "BEFORE YOU START",
                        items = draft.prep,
                        addLabel = "Add a prep step",
                        onChange = { v -> edit { it.copy(prep = v) } },
                        modifier = Modifier.animateItem(placementSpec = Motion.offset()),
                    )
                }
                item(key = "tips", contentType = "list") {
                    TextList(
                        label = "TIPS",
                        items = draft.tips,
                        addLabel = "Add a tip",
                        onChange = { v -> edit { it.copy(tips = v) } },
                        modifier = Modifier.animateItem(placementSpec = Motion.offset()),
                    )
                }

                if (state.issues.isNotEmpty()) {
                    item(key = "issues", contentType = "issues") {
                        Issues(
                            issues = state.issues,
                            onFix = vm::applyFix,
                            modifier = Modifier.animateItem(placementSpec = Motion.offset()),
                        )
                    }
                }

                if (state.builtIn != null || state.canDelete) {
                    item(key = "danger", contentType = "danger") {
                        Danger(
                            canReset = state.builtIn != null && state.hasOverride,
                            isBuiltIn = state.builtIn != null,
                            canDelete = state.canDelete,
                            onReset = { vm.resetToOriginal(onDone) },
                            onDelete = { vm.delete(onDone) },
                            modifier = Modifier.animateItem(placementSpec = Motion.offset()),
                        )
                    }
                }
            }

            StickyCtaBar(Modifier.align(Alignment.BottomCenter)) {
                PrimaryButton(
                    text = if (state.saving) "Saving" else "Save recipe",
                    onClick = { vm.save(onDone) },
                    modifier = Modifier.weight(1f),
                    enabled = state.canSave,
                )
            }
        }

        // Opaque behind the bar, so fields scroll under it and not through it.
        Box(Modifier.fillMaxWidth().height(topInset + 56.dp).background(colors.bg))
        DetailTopBar(onBack = onBack)
    }

    if (pickingBrewer && draft != null) {
        CatalogPickerSheet(
            kind = CatalogKind.Brewer,
            title = "Which brewer?",
            selected = setOf(draft.brewer.id),
            allowCustom = false,
            onPick = { pick ->
                if (pick is CatalogPick.Entry) Brewers.byId(pick.id)?.let(vm::setBrewer)
            },
            onDismiss = { pickingBrewer = false },
        )
    }
    if (startingFrom) {
        StartFromSheet(
            vm = vm,
            onPick = { recipe ->
                vm.startFrom(recipe)
                startingFrom = false
            },
            onDismiss = { startingFrom = false },
        )
    }
}

@Composable
private fun EditorHead(title: String, note: String, offerStartFrom: Boolean, onStartFrom: () -> Unit) {
    val colors = CoffeeTheme.colors
    Column {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = colors.inkHigh)
        if (note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = colors.inkMid)
        }
        if (offerStartFrom) {
            Spacer(Modifier.height(10.dp))
            PillButton(text = "Start from a recipe…", onClick = onStartFrom, onCanvas = true)
        }
    }
}

/** The brew as the timer will draw it, rebuilt as steps change. */
@Composable
private fun Preview(draft: RecipeDraft) {
    val steps = draft.steps
    // Only the steps shape the line; typing a name must not rebuild it.
    val recipe = remember(steps) { draft.toRecipe() }
    val nodes = remember(recipe) { recipe.journeyNodes() }
    SoftCard(Modifier.fillMaxWidth()) {
        EyebrowLabel("HOW IT WILL RUN")
        Spacer(Modifier.height(10.dp))
        JourneyLine(
            nodes = nodes,
            progress = NoProgress,
            modifier = Modifier.fillMaxWidth(),
            style = JourneyStyle.Historical,
            startLabel = "0:00",
            endLabel = clock(recipe.totalSec),
        )
    }
}

@Composable
private fun Basics(
    name: String,
    author: String,
    brewerTitle: String,
    dose: String,
    water: String,
    tempC: Int,
    grind: String,
    blurb: String,
    issues: List<RecipeValidation.Issue>,
    edit: DraftEdit,
    onPickBrewer: () -> Unit,
) {
    val units = LocalUnits.current
    val doseG = RecipeDraft.parseAmount(dose)
    val waterG = RecipeDraft.parseAmount(water)
    fun issue(field: RecipeValidation.Field): String? = issues.firstOrNull { it.field == field }?.message

    SoftCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldWell(
                label = "NAME",
                value = name,
                onChange = { v -> edit { it.copy(name = v.take(80)) } },
                placeholder = "What you call it",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                // An empty name on a new recipe is not a mistake yet; a too-long one is.
                warning = issue(RecipeValidation.Field.Name).takeIf { name.isNotBlank() },
            )
            FieldWell(
                label = "AUTHOR",
                value = author,
                onChange = { v -> edit { it.copy(author = v.take(60)) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            PickerField(label = "BREWER", value = brewerTitle, onClick = onPickBrewer)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldWell(
                    label = "COFFEE",
                    value = dose,
                    onChange = { v -> edit { it.copy(dose = v.take(6)) } },
                    modifier = Modifier.weight(1f),
                    suffix = "g",
                    numeric = true,
                    warning = issue(RecipeValidation.Field.Dose).takeIf { dose.isNotBlank() },
                )
                FieldWell(
                    label = "WATER",
                    value = water,
                    onChange = { v -> edit { it.copy(water = v.take(6)) } },
                    modifier = Modifier.weight(1f),
                    suffix = "g",
                    numeric = true,
                    warning = issue(RecipeValidation.Field.Water).takeIf { water.isNotBlank() },
                )
            }
            if (doseG != null && waterG != null && doseG > 0) {
                StatusLine(Status.Neutral, "Ratio ${ratio(doseG, waterG)}", detail = "water to coffee")
            }
            StepperRow(
                label = "WATER TEMPERATURE",
                value = units.temp(tempC),
                unit = units.tempUnit,
                // Always one degree Celsius: that is what is stored, whatever the display says.
                onMinus = { edit { it.copy(tempC = (it.tempC - 1).coerceAtLeast(RecipeValidation.TEMP_MIN)) } },
                onPlus = { edit { it.copy(tempC = (it.tempC + 1).coerceAtMost(RecipeValidation.TEMP_MAX)) } },
                onLongMinus = { edit { it.copy(tempC = (it.tempC - 5).coerceAtLeast(RecipeValidation.TEMP_MIN)) } },
                onLongPlus = { edit { it.copy(tempC = (it.tempC + 5).coerceAtMost(RecipeValidation.TEMP_MAX)) } },
                minusDescription = "One degree cooler",
                plusDescription = "One degree hotter",
            )
            FieldWell(
                label = "GRIND",
                value = grind,
                onChange = { v -> edit { it.copy(grind = v.take(60)) } },
                placeholder = "Medium-fine",
                helper = "Words, not a number: each grinder's number differs.",
            )
            FieldWell(
                label = "ABOUT THIS RECIPE",
                value = blurb,
                onChange = { v -> edit { it.copy(blurb = v.take(400)) } },
                singleLine = false,
                minLines = 2,
            )
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    count: Int,
    step: StepDraft,
    startSec: Int,
    delta: Int?,
    issue: String?,
    edit: DraftEdit,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    val pours = step.kind in RecipeDraft.POUR_KINDS || step.pourTo.isNotBlank()

    SoftCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                EyebrowLabel("STEP ${index + 1}")
                Text(
                    "${clock(startSec)} to ${clock(startSec + step.durationSec.coerceAtLeast(0))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMid,
                )
            }
            IconWell(
                icon = Icons.Rounded.KeyboardArrowUp,
                contentDescription = "Move step ${index + 1} earlier",
                onClick = { edit { it.moveStep(index, -1) } },
                size = 36.dp,
                onCanvas = false,
                enabled = index > 0,
            )
            Spacer(Modifier.width(6.dp))
            IconWell(
                icon = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Move step ${index + 1} later",
                onClick = { edit { it.moveStep(index, 1) } },
                size = 36.dp,
                onCanvas = false,
                enabled = index < count - 1,
            )
            Spacer(Modifier.width(6.dp))
            IconWell(
                icon = Icons.Rounded.Close,
                contentDescription = "Remove step ${index + 1}",
                onClick = { edit { it.removeStep(index) } },
                size = 36.dp,
                onCanvas = false,
                iconSize = 18.dp,
                enabled = count > 1,
            )
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChipGroup(label = null) {
                StepKind.entries.forEach { k ->
                    SelectableChip(k.label, step.kind == k, {
                        edit { d ->
                            d.updateStep(index) { s ->
                                // A title that was just the kind's name follows the kind.
                                val follows = s.title.isBlank() || s.title == s.kind.label
                                s.copy(kind = k, title = if (follows) k.label else s.title)
                            }
                        }
                    })
                }
            }
            FieldWell(
                label = "TITLE",
                value = step.title,
                onChange = { v -> edit { it.updateStep(index) { s -> s.copy(title = v.take(60)) } } },
                placeholder = step.kind.label,
            )
            FieldWell(
                label = "WHAT TO DO",
                value = step.detail,
                onChange = { v -> edit { it.updateStep(index) { s -> s.copy(detail = v.take(400)) } } },
                singleLine = false,
                minLines = 2,
            )
            StepperRow(
                label = "LASTS",
                value = clock(step.durationSec),
                onMinus = { edit { it.nudgeDuration(index, -5) } },
                onPlus = { edit { it.nudgeDuration(index, 5) } },
                onLongMinus = { edit { it.nudgeDuration(index, -1) } },
                onLongPlus = { edit { it.nudgeDuration(index, 1) } },
                minusDescription = "5 seconds shorter",
                plusDescription = "5 seconds longer",
            )
            SheetNote("Tap for 5 seconds, hold for 1.")
            if (pours) {
                FieldWell(
                    label = "POUR TO",
                    value = step.pourTo,
                    onChange = { v -> edit { it.updateStep(index) { s -> s.copy(pourTo = v.filter { c -> c.isDigit() }.take(4)) } } },
                    placeholder = "Total on the scale",
                    suffix = "g",
                    numeric = true,
                    helper = when {
                        delta == null -> "Leave empty if no water goes in."
                        delta >= 0 -> "+$delta g this pour"
                        else -> null
                    },
                )
            }
            if (issue != null) StatusLine(Status.Attention, "Needs a fix", detail = issue)
        }
    }
}

@Composable
private fun AddStep(enabled: Boolean, onAdd: (StepKind) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (enabled) {
            ChipGroup(label = "ADD A STEP") {
                StepKind.entries.forEach { k ->
                    SelectableChip("+ ${k.label}", false, { onAdd(k) }, onCanvas = true)
                }
            }
        } else {
            SheetNote("That is the most steps a recipe can hold.")
        }
    }
}

/** A short list of one-line notes (prep, tips): edit in place, remove, add. */
@Composable
private fun TextList(
    label: String,
    items: List<String>,
    addLabel: String,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionLabel(label, topGap = 12.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { i, text ->
                FieldWell(
                    label = null,
                    value = text,
                    onChange = { v -> onChange(items.toMutableList().also { it[i] = v.take(200) }) },
                    onCanvas = true,
                    singleLine = false,
                    trailing = {
                        IconWell(
                            icon = Icons.Rounded.Close,
                            contentDescription = "Remove",
                            onClick = { onChange(items.filterIndexed { j, _ -> j != i }) },
                            size = 32.dp,
                            onCanvas = false,
                            iconSize = 16.dp,
                        )
                    },
                )
            }
            if (items.size < MAX_LIST_ITEMS) {
                QuietButton(
                    text = addLabel,
                    onClick = { onChange(items + "") },
                    modifier = Modifier.offset(x = (-8).dp),
                )
            }
        }
    }
}

private const val MAX_LIST_ITEMS = 12

/** What still stands between the draft and Save, each in a sentence, with a one-tap fix where there is one. */
@Composable
private fun Issues(
    issues: List<RecipeValidation.Issue>,
    onFix: (RecipeValidation.Fix) -> Unit,
    modifier: Modifier = Modifier,
) {
    SoftCard(modifier.fillMaxWidth()) {
        EyebrowLabel("BEFORE YOU SAVE")
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            issues.forEach { issue ->
                StatusLine(Status.Attention, "To fix", detail = issue.message)
                issue.fix?.let { fix -> PillButton(text = fix.label, onClick = { onFix(fix) }) }
            }
        }
    }
}

@Composable
private fun Danger(
    canReset: Boolean,
    isBuiltIn: Boolean,
    canDelete: Boolean,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoffeeTheme.colors
    // Two taps, so a slip of the thumb never loses a recipe.
    var armed by remember { mutableStateOf(false) }
    Column(modifier.padding(top = 8.dp)) {
        if (isBuiltIn) {
            SheetNote(
                if (canReset) {
                    "This is your version of a built-in recipe. Resetting brings the published numbers back."
                } else {
                    "Saving keeps your version under the same name. You can reset to the original at any time."
                },
            )
            if (canReset) {
                QuietButton(
                    text = if (armed) "Tap again to reset" else "Reset to original",
                    onClick = { if (armed) onReset() else armed = true },
                    modifier = Modifier.offset(x = (-8).dp),
                    color = colors.fault,
                )
            }
        }
        if (canDelete) {
            SheetNote("Brews you logged with it keep its name.")
            QuietButton(
                text = if (armed) "Tap again to delete" else "Delete recipe",
                onClick = { if (armed) onDelete() else armed = true },
                modifier = Modifier.offset(x = (-8).dp),
                color = colors.fault,
            )
        }
    }
}

@Composable
private fun StartFromSheet(vm: RecipeEditorViewModel, onPick: (Recipe) -> Unit, onDismiss: () -> Unit) {
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val colors = CoffeeTheme.colors
    CoffeeSheet(onDismissRequest = onDismiss) {
        SheetTitle("Start from a recipe")
        SheetNote("You get a copy that is yours. The original stays as it is.")
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            items(recipes, key = { it.id }, contentType = { "recipe" }) { r ->
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onPick(r) }
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        r.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.inkHigh,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${r.author.trim()} · ${r.brewer.shortName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
