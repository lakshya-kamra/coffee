package com.laksh.coffee.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.CatalogKind
import com.laksh.coffee.domain.grind.DialFormat
import com.laksh.coffee.ui.catalog.CatalogPick
import com.laksh.coffee.ui.catalog.CatalogPickerContent
import com.laksh.coffee.ui.catalog.CatalogPickerSheet
import com.laksh.coffee.ui.catalog.ChipGroup
import com.laksh.coffee.ui.catalog.Expandable
import com.laksh.coffee.ui.catalog.SheetNote
import com.laksh.coffee.ui.components.DialReadingInput
import com.laksh.coffee.ui.components.EyebrowLabel
import com.laksh.coffee.ui.components.FieldWell
import com.laksh.coffee.ui.components.GrindRanges
import com.laksh.coffee.ui.components.GrindStart
import com.laksh.coffee.ui.components.IconWell
import com.laksh.coffee.ui.components.MeshGradientBackground
import com.laksh.coffee.ui.components.PillButton
import com.laksh.coffee.ui.components.PrimaryButton
import com.laksh.coffee.ui.components.QuietButton
import com.laksh.coffee.ui.components.SegmentedControl
import com.laksh.coffee.ui.components.SelectableChip
import com.laksh.coffee.ui.components.SoftCard
import com.laksh.coffee.ui.components.StatusChip
import com.laksh.coffee.ui.screens.gear.GrinderMapping
import com.laksh.coffee.ui.screens.gear.splitBrandModel
import com.laksh.coffee.ui.theme.BrewMood
import com.laksh.coffee.ui.theme.CoffeeTheme
import com.laksh.coffee.ui.theme.Dimens
import com.laksh.coffee.ui.theme.Motion

/**
 * First-run setup: how you measure, your grinder, the brewers you own, your coffee and water.
 * Every step can be skipped and every Next saves its own step. [onFinished] leaves the screen;
 * [onOpenAiSettings] is the optional "add a key" on the last step.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onOpenAiSettings: () -> Unit = {},
    vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = CoffeeTheme.colors

    // System back walks the steps; on the first one it leaves, as back should.
    BackHandler(enabled = state.step > 0) { vm.back() }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        // The same slow mesh as brew setup, turned well down behind a page of controls.
        MeshGradientBackground(mood = BrewMood.Idle, modifier = Modifier.fillMaxSize(), intensity = 0.3f)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Dimens.Gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterStart) {
                    if (state.step > 0) {
                        IconWell(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = { vm.back() })
                    }
                }
                ProgressDots(step = state.step, count = Onboarding.STEPS, modifier = Modifier.weight(1f))
                Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) {
                    if (!state.isLast) QuietButton(text = "Skip", onClick = { vm.skip(onFinished) })
                }
            }

            AnimatedContent(
                targetState = state.step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    // A short drift in the direction of travel; the fade does most of the work.
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(Motion.offset()) { w -> dir * w / 8 } + fadeIn(Motion.decel(220)))
                        .togetherWith(slideOutHorizontally(Motion.offset()) { w -> -dir * w / 8 } + fadeOut(Motion.decel(120)))
                },
                label = "onboardingStep",
            ) { step ->
                when (step) {
                    Onboarding.MEASURE -> MeasureStep(state, vm)
                    Onboarding.GRINDER -> GrinderStep(state, vm)
                    Onboarding.BREWERS -> BrewersStep(state, vm)
                    Onboarding.COFFEE -> CoffeeStep(state, vm)
                    else -> DoneStep(onOpenAiSettings)
                }
            }

            PrimaryButton(
                text = if (state.isLast) "Start brewing" else "Next",
                onClick = { vm.next(onFinished) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.Gutter)
                    .padding(top = 8.dp, bottom = 12.dp),
                enabled = state.loaded && !state.busy,
            )
        }
    }
}

/** One dot per step, with a wider mark that slides to the current one. Drawn, so nothing is laid out per frame. */
@Composable
private fun ProgressDots(step: Int, count: Int, modifier: Modifier = Modifier) {
    val colors = CoffeeTheme.colors
    val at = animateFloatAsState(step.toFloat(), Motion.SpatialDefault, label = "onboardingDot")
    val idle = colors.inkFaint
    val active = colors.inkHigh
    Box(
        modifier.semantics { contentDescription = "Step ${step + 1} of $count" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = (count * 14 + 10).dp, height = 6.dp)) {
            val gap = 14.dp.toPx()
            val r = 3.dp.toPx()
            val x0 = 5.dp.toPx() + r
            for (i in 0 until count) drawCircle(idle, r, Offset(x0 + i * gap, size.height / 2))
            val w = 16.dp.toPx()
            drawRoundRect(
                color = active,
                topLeft = Offset(x0 + at.value * gap - w / 2, 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(r, r),
            )
        }
    }
}

@Composable
private fun StepPage(title: String, lede: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.Gutter)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StepTitle(title, lede)
        content()
    }
}

@Composable
private fun StepTitle(title: String, lede: String?) {
    val colors = CoffeeTheme.colors
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = colors.inkHigh)
        if (lede != null) {
            Spacer(Modifier.height(6.dp))
            Text(lede, style = MaterialTheme.typography.bodyMedium, color = colors.inkMid)
        }
    }
}

// ---------------------------------------------------------------- 1. how you measure

@Composable
private fun MeasureStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepPage("How you measure", null) {
        Column {
            EyebrowLabel("TEMPERATURE")
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                options = listOf("°C", "°F"),
                selectedIndex = if (state.tempUnit == "f") 1 else 0,
                onSelect = { vm.setTempUnit(if (it == 1) "f" else "c") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column {
            EyebrowLabel("WEIGHT")
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                options = listOf("Grams", "Ounces"),
                selectedIndex = if (state.weightUnit == "oz") 1 else 0,
                onSelect = { vm.setWeightUnit(if (it == 1) "oz" else "g") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            SheetNote(
                "Brew targets stay in grams — that's what coffee scales read. Bag sizes and totals follow your choice.",
            )
        }
        if (state.areas.isNotEmpty()) {
            ChipGroup(
                label = "WHERE DO YOU BUY COFFEE?",
                note = "Roasters near you come first when you pick one.",
            ) {
                state.areas.forEach { a ->
                    SelectableChip(a, state.homeArea == a, { vm.setHomeArea(a) }, onCanvas = true)
                }
            }
        }
    }
}

// ------------------------------------------------------------------- 2. your grinder

@Composable
private fun GrinderStep(state: OnboardingState, vm: OnboardingViewModel) {
    val grinderId = state.grinderId
    val custom = state.custom
    when {
        grinderId != null -> StepPage("Your grinder", GRINDER_LEDE) {
            GrinderSummary(grinderId, onChange = vm::clearGrinder)
        }
        custom != null -> StepPage("Your grinder", GRINDER_LEDE) {
            CustomGrinderCard(custom, onChange = vm::setCustom)
            QuietButton(
                text = "Pick from the list instead",
                onClick = vm::clearGrinder,
                modifier = Modifier.offset(x = (-8).dp),
            )
        }
        // The list scrolls on its own, so this page does not.
        else -> Column(Modifier.fillMaxSize().padding(horizontal = Dimens.Gutter)) {
            StepTitle("Your grinder", GRINDER_LEDE)
            Spacer(Modifier.height(12.dp))
            CatalogPickerContent(
                kind = CatalogKind.Grinder,
                modifier = Modifier.weight(1f),
                onCanvas = true,
                customLabel = { "Add “$it” myself" },
                onPick = { pick ->
                    when (pick) {
                        is CatalogPick.Entry -> vm.pickGrinder(pick.id)
                        is CatalogPick.Custom -> {
                            val (brand, model) = splitBrandModel(pick.text)
                            vm.setCustom(CustomGrinder(brand = brand, model = model))
                        }
                    }
                },
            )
            QuietButton(
                text = "Not listed? Add it yourself",
                onClick = { vm.setCustom(CustomGrinder()) },
                modifier = Modifier.offset(x = (-8).dp),
            )
        }
    }
}

private const val GRINDER_LEDE = "Pick it from the list so the app reads your numbers the way your dial does."

@Composable
private fun GrinderSummary(catalogId: String, onChange: () -> Unit) {
    val colors = CoffeeTheme.colors
    val entry = remember(catalogId) { Catalog.grinder(catalogId) }
    if (entry == null) {
        QuietButton(text = "Pick a different grinder", onClick = onChange)
        return
    }
    // The row this pick would save, so the summary and the demo read exactly as the app will.
    val row = remember(entry) { GrinderMapping.fill(GrinderEntity(brand = "", model = ""), entry) }
    val scale = remember(row) { row.scale() }
    val chip = GrindRanges.confidence(entry.confidence)
    // The demo starts on the pour-over starting point, so the reading means something at once.
    var demo by remember(entry) { mutableStateOf(GrindStart.of(scale, BrewMethod.PourOver)?.value) }

    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyebrowLabel(entry.brand, Modifier.weight(1f))
            if (chip != null) StatusChip(chip.first, chip.second)
        }
        Spacer(Modifier.height(4.dp))
        Text(entry.model, style = MaterialTheme.typography.headlineSmall, color = colors.inkHigh)
        Spacer(Modifier.height(2.dp))
        Text(CatalogDocs.grinderSubtitle(entry), style = MaterialTheme.typography.bodySmall, color = colors.inkMid)
        Spacer(Modifier.height(10.dp))
        Text(GrinderMapping.scaleWords(row), style = MaterialTheme.typography.bodyMedium, color = colors.inkHigh)
        if (entry.setting.dialReading.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            EyebrowLabel("HOW TO READ YOUR DIAL")
            Spacer(Modifier.height(4.dp))
            SheetNote(entry.setting.dialReading)
        }
        if (entry.setting.entryHint.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            EyebrowLabel("WHAT TO TYPE")
            Spacer(Modifier.height(4.dp))
            SheetNote(entry.setting.entryHint)
        }
        if (scale.dial !is DialFormat.Plain) {
            Spacer(Modifier.height(14.dp))
            EyebrowLabel("TRY IT: SET A READING")
            Spacer(Modifier.height(8.dp))
            DialReadingInput(scale = scale, value = demo, onValue = { demo = it })
        }
        Spacer(Modifier.height(6.dp))
        QuietButton(
            text = "Pick a different grinder",
            onClick = onChange,
            modifier = Modifier.offset(x = (-8).dp),
        )
    }
}

@Composable
private fun CustomGrinderCard(value: CustomGrinder, onChange: (CustomGrinder) -> Unit) {
    SoftCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EyebrowLabel("YOUR GRINDER")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldWell(
                    label = "BRAND",
                    value = value.brand,
                    onChange = { onChange(value.copy(brand = it.take(40))) },
                    modifier = Modifier.weight(1f),
                )
                FieldWell(
                    label = "MODEL",
                    value = value.model,
                    onChange = { onChange(value.copy(model = it.take(40))) },
                    modifier = Modifier.weight(1f),
                )
            }
            ChipGroup("HOW IT ADJUSTS") {
                listOf(SettingStyle.Clicks, SettingStyle.Numbers, SettingStyle.Stepless).forEach { st ->
                    SelectableChip(st.label, value.style == st, { onChange(value.copy(style = st)) })
                }
            }
            Expandable(visible = value.style == SettingStyle.Clicks) {
                FieldWell(
                    label = "CLICKS PER TURN",
                    value = value.clicksPerTurn,
                    onChange = { v -> onChange(value.copy(clicksPerTurn = v.filter { it.isDigit() }.take(3))) },
                    numeric = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    helper = "Optional. Your manual says it; the app uses it to read clicks back as turns.",
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 3. brewers you own

@Composable
private fun BrewersStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepPage("Brewers you own", "Recipes for these come first. Pick as many as you like.") {
        state.brewerGroups.forEach { group ->
            ChipGroup(group.title.uppercase()) {
                group.brewers.forEach { (id, title) ->
                    SelectableChip(title, id in state.ownedBrewerIds, { vm.toggleBrewer(id) }, onCanvas = true)
                }
            }
        }
    }
}

// -------------------------------------------------------------------- 4. your coffee

@Composable
private fun CoffeeStep(state: OnboardingState, vm: OnboardingViewModel) {
    var picking by remember { mutableStateOf(false) }
    StepPage("Your coffee", "Optional. Both help the app, and an AI if you add one, say more about your cups.") {
        ChipGroup("FAVOURITE ROASTERS") {
            // Catalog order is not needed here: the chips read in the order you picked them.
            state.favouriteRoasterIds.forEach { id ->
                SelectableChip(state.roasterNames[id] ?: id, true, { vm.removeRoaster(id) }, onCanvas = true)
            }
            PillButton(
                text = if (state.favouriteRoasterIds.isEmpty()) "Pick roasters…" else "Add more…",
                onClick = { picking = true },
                height = Dimens.Chip,
                onCanvas = true,
            )
        }
        if (state.water.isNotEmpty()) {
            ChipGroup(
                label = "WHAT WATER DO YOU BREW WITH?",
                note = state.water.firstOrNull { it.id == state.waterId }?.description,
            ) {
                state.water.forEach { w ->
                    SelectableChip(w.label, state.waterId == w.id, { vm.setWater(w.id) }, onCanvas = true)
                }
            }
        }
    }
    if (picking) {
        CatalogPickerSheet(
            kind = CatalogKind.Roaster,
            title = "Favourite roasters",
            multiSelect = true,
            selected = state.favouriteRoasterIds,
            allowCustom = false,
            onPick = {},
            onDone = vm::setRoasters,
            onDismiss = { picking = false },
        )
    }
}

// --------------------------------------------------------------------------- 5. done

@Composable
private fun DoneStep(onOpenAiSettings: () -> Unit) {
    StepPage(
        "You're set",
        "Everything you chose is saved on this phone. Change any of it later in Gear. An AI key is " +
            "optional: the app reads every brew offline, and a model of your choice can say more.",
    ) {
        PillButton(text = "Add an AI key (optional)", onClick = onOpenAiSettings, onCanvas = true)
    }
}
