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
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import com.laksh.coffee.ui.components.BrewerIcon
import com.laksh.coffee.ui.components.CoffeeMug
import com.laksh.coffee.ui.components.Dripper
import com.laksh.coffee.ui.components.asHeading
import com.laksh.coffee.ui.components.focusRing
import com.laksh.coffee.ui.components.hoverTint
import com.laksh.coffee.ui.screens.home.firstBrewChecklist
import com.laksh.coffee.ui.screens.home.firstBrewNeeds
import com.laksh.coffee.ui.theme.LocalWindowClass
import com.laksh.coffee.ui.theme.WidthClass
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
 * First-run setup (v4 §4.1, §3.11): who you are, how you measure, your grinder, the brewers you
 * own, your coffee and water, then your first brew. Every step can be skipped and every Next saves
 * its own step. Phones read one column; a medium window centres it at 560; expanded and up split
 * 40/60 with the why on the left (no art when short). Enter is Next, Esc is Back.
 *
 * [onFinished] leaves for Today; [onStartBrew] starts the first brew's recipe; [onOpenAiSettings]
 * is the optional "add a key".
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onStartBrew: (String) -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = CoffeeTheme.colors
    val wc = LocalWindowClass.current
    val split = wc.usesPanes
    val gutter = Dimens.gutter

    // System back walks the steps; on the first one it leaves, as back should.
    BackHandler(enabled = state.step > 0) { vm.back() }

    // Holds focus so Enter and Esc reach setup; a field that takes Enter keeps it (onKeyEvent
    // runs after the focused field).
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> (!state.isLast).also { if (it) vm.next() }
                    Key.Escape -> vm.back()
                    else -> false
                }
            }
            .focusRequester(keys)
            .focusable(),
    ) {
        // The same slow mesh as brew setup, turned well down behind a page of controls.
        MeshGradientBackground(mood = BrewMood.Idle, modifier = Modifier.fillMaxSize(), intensity = 0.3f)

        val topBar: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.CenterStart) {
                    if (state.step > 0) {
                        IconWell(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = { vm.back() }, shortcut = "Esc")
                    }
                }
                ProgressDots(step = state.step, count = Onboarding.STEPS, modifier = Modifier.weight(1f))
                Box(Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.CenterEnd) {
                    // Always there, so leaving is never a hunt (§3.11).
                    if (!state.isLast) QuietButton(text = "Skip for now", onClick = { vm.skip(onFinished) })
                }
            }
        }
        val stepBody: @Composable (Modifier) -> Unit = { m ->
            AnimatedContent(
                targetState = state.step,
                modifier = m,
                transitionSpec = {
                    // A short drift in the direction of travel; the fade does most of the work.
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(Motion.offset()) { w -> dir * w / 8 } + fadeIn(Motion.decel(220)))
                        .togetherWith(slideOutHorizontally(Motion.offset()) { w -> -dir * w / 8 } + fadeOut(Motion.decel(120)))
                },
                label = "onboardingStep",
            ) { step ->
                when (step) {
                    Onboarding.YOU -> YouStep(state, vm)
                    Onboarding.MEASURE -> MeasureStep(state, vm)
                    Onboarding.GRINDER -> GrinderStep(state, vm)
                    Onboarding.BREWERS -> BrewersStep(state, vm, twoColumns = wc.width.atLeast(WidthClass.Large))
                    Onboarding.COFFEE -> CoffeeStep(state, vm)
                    else -> FirstBrewStep(
                        state = state,
                        onStart = { id -> vm.finish { onStartBrew(id) } },
                        onToday = { vm.finish(onFinished) },
                        onOpenAiSettings = onOpenAiSettings,
                    )
                }
            }
        }
        val nextButton: @Composable (Modifier) -> Unit = { m ->
            if (!state.isLast) {
                PrimaryButton(
                    text = "Next",
                    onClick = { vm.next() },
                    modifier = m,
                    enabled = state.loaded && !state.busy,
                )
            }
        }

        if (split) {
            // Expanded and up: a centred 960 frame, the why on the left, the form on the right.
            Row(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = gutter),
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(Modifier.widthIn(max = 960.dp).fillMaxHeight()) {
                    WhyPane(state, art = !wc.isShort, modifier = Modifier.weight(0.4f).fillMaxHeight())
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(0.6f).fillMaxHeight()) {
                        topBar()
                        stepBody(Modifier.weight(1f).fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                            nextButton(Modifier.widthIn(min = 200.dp, max = 420.dp))
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Medium: the same column, centred at 560 (§3.11 M).
                val frame = if (wc.width == WidthClass.Compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 560.dp).fillMaxWidth()
                Column(frame.weight(1f)) {
                    topBar()
                    stepBody(Modifier.weight(1f).fillMaxWidth())
                    nextButton(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = gutter)
                            .padding(top = 8.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/** Why each step asks (§3.11 E+): two sentences, with the step's art above them unless short. */
@Composable
private fun WhyPane(state: OnboardingState, art: Boolean, modifier: Modifier = Modifier) {
    val c = CoffeeTheme.colors
    val (lead, more) = whyFor(state.step)
    Column(modifier.padding(top = 72.dp, bottom = 24.dp), verticalArrangement = Arrangement.Center) {
        if (art) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                val brewer = state.firstBrew?.recipe?.brewer
                when {
                    state.step == Onboarding.DONE && brewer != null -> BrewerIcon(brewer, size = 120.dp, tint = c.inkMid)
                    state.step == Onboarding.GRINDER || state.step == Onboarding.BREWERS || state.step == Onboarding.MEASURE ->
                        Dripper(waterLevel = 0.45f, dripping = true, shell = c.artShell, water = c.artWater, bed = c.artBed)
                    else -> CoffeeMug(fill = 0.6f, body = c.artShell, liquid = c.artBed, crema = c.artWater)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Text(lead, style = MaterialTheme.typography.headlineSmall, color = c.inkHigh)
        Spacer(Modifier.height(8.dp))
        Text(more, style = MaterialTheme.typography.bodyLarge, color = c.inkMid)
    }
}

private fun whyFor(step: Int): Pair<String, String> = when (step) {
    Onboarding.YOU -> "Everyone starts somewhere." to
        "Your answer sets how much the app explains and how many numbers it asks for. Change any of it later in Gear › Settings."
    Onboarding.MEASURE -> "Recipes are written in grams." to
        "We show them the way you measure. Spoons and a jug work fine without a scale."
    Onboarding.GRINDER -> "Grind is the setting you'll change most." to
        "If you buy ground coffee, we skip it. A blade grinder is timed in seconds."
    Onboarding.BREWERS -> "Recipes follow what you own." to
        "A French press never gets a pour-over recipe. Pick every brewer you have."
    Onboarding.COFFEE -> "Coffee and water both shape the cup." to
        "Both are optional. They help the app, and an AI if you add one, say more about your cups."
    else -> "One good cup first." to
        "We picked the easiest recipe for what you have. We'll time every step and tell you what to do."
}

/** A large radio card: a title and one line under it. Its group gives TalkBack "1 of 3". */
@Composable
private fun ChoiceCard(title: String, body: String?, selected: Boolean, onClick: () -> Unit) {
    val c = CoffeeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .focusRing(interaction, shape)
            .clip(shape)
            .background(if (selected) c.surface2 else c.surface1)
            .then(if (selected) Modifier.border(1.5.dp, c.inkHigh, shape) else Modifier)
            .hoverTint(interaction, c.inkHigh.copy(alpha = 0.04f))
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.inkHigh)
            if (body != null) {
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = c.inkMid)
            }
        }
        Spacer(Modifier.width(12.dp))
        val ring = c.outlineStrong
        val dot = c.inkHigh
        val centre = c.bg
        Spacer(
            Modifier
                .size(22.dp)
                .drawBehind {
                    val r = size.minDimension / 2f
                    if (selected) {
                        drawCircle(dot, radius = r)
                        drawCircle(centre, radius = 4.5.dp.toPx())
                    } else {
                        val sw = 1.5.dp.toPx()
                        drawCircle(ring, radius = r - sw / 2f, style = Stroke(sw))
                    }
                },
        )
    }
}

// ---------------------------------------------------------- 1. how do you make coffee now?

@Composable
private fun YouStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepPage("How do you make coffee now?", null, eyebrow = "STEP 1 OF ${Onboarding.STEPS}") {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Onboarding.EXPERIENCE_CHOICES.forEach { (id, title, body) ->
                ChoiceCard(title, body, state.experience == id) { vm.setExperience(id) }
            }
        }
        SheetNote("You can change this any time in Gear › Settings.")
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
private fun StepPage(
    title: String,
    lede: String?,
    eyebrow: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.gutter)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StepTitle(title, lede, eyebrow)
        content()
    }
}

@Composable
private fun StepTitle(title: String, lede: String?, eyebrow: String? = null) {
    val colors = CoffeeTheme.colors
    Column(Modifier.padding(top = 8.dp)) {
        if (eyebrow != null) {
            EyebrowLabel(eyebrow)
            Spacer(Modifier.height(6.dp))
        }
        Text(title, style = MaterialTheme.typography.displaySmall, color = colors.inkHigh, modifier = Modifier.asHeading())
        if (lede != null) {
            Spacer(Modifier.height(6.dp))
            Text(lede, style = MaterialTheme.typography.bodyMedium, color = colors.inkMid)
        }
    }
}

// ---------------------------------------------------------------- 2. how you measure

@Composable
private fun MeasureStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepPage("How you measure", null, eyebrow = "STEP 2 OF ${Onboarding.STEPS}") {
        Column {
            EyebrowLabel("DO YOU HAVE A KITCHEN SCALE?", Modifier.asHeading())
            Spacer(Modifier.height(8.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceCard("Yes", null, state.hasScale == true) { vm.setHasScale(true) }
                ChoiceCard("No, I'll use spoons and a jug", null, state.hasScale == false) { vm.setHasScale(false) }
            }
            Spacer(Modifier.height(8.dp))
            SheetNote("You can change this any time in Gear › Settings.")
        }
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

// ------------------------------------------------------------------- 3. your grinder

@Composable
private fun GrinderStep(state: OnboardingState, vm: OnboardingViewModel) {
    val grinderId = state.grinderId
    val custom = state.custom
    when {
        state.buysGround -> StepPage("Your grinder", null, eyebrow = "STEP 3 OF ${Onboarding.STEPS}") {
            SoftCard(Modifier.fillMaxWidth()) {
                Text("You're set: brews will use the coffee as bought.", style = MaterialTheme.typography.bodyLarge, color = CoffeeTheme.colors.inkHigh)
                Spacer(Modifier.height(6.dp))
                SheetNote("We'll skip grind settings. If you get a grinder later, add it in Gear › Grinders.")
            }
            QuietButton(text = "I have a grinder after all", onClick = vm::clearGrinder, modifier = Modifier.offset(x = (-8).dp))
        }
        grinderId != null -> StepPage("Your grinder", GRINDER_LEDE, eyebrow = "STEP 3 OF ${Onboarding.STEPS}") {
            GrinderSummary(grinderId, onChange = vm::clearGrinder)
        }
        custom != null -> StepPage("Your grinder", GRINDER_LEDE, eyebrow = "STEP 3 OF ${Onboarding.STEPS}") {
            CustomGrinderCard(custom, onChange = vm::setCustom)
            QuietButton(
                text = "Pick from the list instead",
                onClick = vm::clearGrinder,
                modifier = Modifier.offset(x = (-8).dp),
            )
        }
        // The list scrolls on its own, so this page does not.
        else -> Column(Modifier.fillMaxSize().padding(horizontal = Dimens.gutter)) {
            StepTitle("Your grinder", null, eyebrow = "STEP 3 OF ${Onboarding.STEPS}")
            Spacer(Modifier.height(12.dp))
            // The two answers most people have, above the burr grinders (v4 §4.2).
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceCard("I buy ground coffee", "We'll skip grind settings.", false, vm::setBuysGround)
                ChoiceCard("Blade grinder", "The kind with a spinning blade. We'll time it in seconds.", false) {
                    vm.pickGrinder(Onboarding.BLADE_GRINDER_ID)
                }
            }
            Spacer(Modifier.height(16.dp))
            EyebrowLabel("BURR GRINDER (HAND OR ELECTRIC)", Modifier.asHeading())
            Spacer(Modifier.height(8.dp))
            CatalogPickerContent(
                kind = CatalogKind.Grinder,
                modifier = Modifier.weight(1f).heightIn(min = 160.dp),
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

// ---------------------------------------------------------------- 4. brewers you own

@Composable
private fun BrewersStep(state: OnboardingState, vm: OnboardingViewModel, twoColumns: Boolean) {
    StepPage("Brewers you own", "Recipes for these come first. Pick as many as you like.", eyebrow = "STEP 4 OF ${Onboarding.STEPS}") {
        state.brewerGroups.forEach { group ->
            ChipGroup(group.title.uppercase()) {
                group.brewers.forEach { (id, title) ->
                    SelectableChip(
                        title,
                        id in state.ownedBrewerIds,
                        { vm.toggleBrewer(id) },
                        onCanvas = true,
                        // Wide windows give each chip a column's width, so the names line up.
                        modifier = if (twoColumns) Modifier.widthIn(min = 200.dp) else Modifier,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------- 5. your coffee

@Composable
private fun CoffeeStep(state: OnboardingState, vm: OnboardingViewModel) {
    var picking by remember { mutableStateOf(false) }
    // Opens by itself when the saved water is one of the longer list's.
    var moreWaters by rememberSaveable {
        mutableStateOf(state.waterId.isNotBlank() && Onboarding.PLAIN_WATERS.none { it.waterId == state.waterId })
    }
    StepPage(
        "Your coffee",
        "Optional. Both help the app, and an AI if you add one, say more about your cups.",
        eyebrow = "STEP 5 OF ${Onboarding.STEPS}",
    ) {
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
        // Four plain answers first (v4 §4.1); "Not sure" stores no water.
        ChipGroup(
            label = "WHAT WATER DO YOU BREW WITH?",
            note = state.water.firstOrNull { it.id == state.waterId }?.description,
        ) {
            Onboarding.PLAIN_WATERS.forEach { w ->
                val on = if (w.waterId.isEmpty()) state.waterId.isEmpty() else state.waterId == w.waterId
                SelectableChip(w.label, on, { vm.setWater(w.waterId) }, onCanvas = true)
            }
        }
        if (state.water.isNotEmpty()) {
            QuietButton(
                text = if (moreWaters) "Fewer waters" else "More waters",
                onClick = { moreWaters = !moreWaters },
                modifier = Modifier.offset(x = (-8).dp),
            )
            Expandable(visible = moreWaters) {
                ChipGroup(label = null) {
                    state.water.filter { w -> Onboarding.PLAIN_WATERS.none { it.waterId == w.id } }.forEach { w ->
                        SelectableChip(w.label, state.waterId == w.id, { vm.setWater(w.id) }, onCanvas = true)
                    }
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

// ----------------------------------------------------------------- 6. your first brew

/**
 * v4 §4.1, §4.6: the recipe that suits what was just answered, what to get out, three lines on
 * how it goes, "Start my first brew" and "Go to Today". Never a recipe for a brewer you lack.
 */
@Composable
private fun FirstBrewStep(
    state: OnboardingState,
    onStart: (String) -> Unit,
    onToday: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val c = CoffeeTheme.colors
    val pick = state.firstBrew
    val recipe = pick?.recipe
    StepPage(
        "Your first brew",
        "Everything you chose is saved on this device. Change any of it later in Gear.",
        eyebrow = "STEP 6 OF ${Onboarding.STEPS}",
    ) {
        if (recipe != null) {
            val hasScale = state.hasScale ?: true
            val needs = remember(recipe, hasScale) { firstBrewNeeds(recipe, hasScale) }
            val steps = remember(recipe) { firstBrewChecklist(recipe) }
            SoftCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EyebrowLabel("YOUR FIRST BREW", Modifier.weight(1f).asHeading())
                    BrewerIcon(recipe.brewer, size = 16.dp, tint = c.inkLow)
                    Spacer(Modifier.width(6.dp))
                    Text(recipe.brewer.shortName.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.inkLow, maxLines = 1)
                }
                Spacer(Modifier.height(10.dp))
                Text(recipe.name, style = MaterialTheme.typography.headlineMedium, color = c.inkHigh)
                Text(recipe.author, style = MaterialTheme.typography.bodySmall, color = c.inkMid)
                Spacer(Modifier.height(16.dp))
                EyebrowLabel("YOU'LL NEED")
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    needs.forEach { NeedChip(it) }
                }
                Spacer(Modifier.height(16.dp))
                steps.forEachIndexed { i, line ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = c.inkLow, modifier = Modifier.width(24.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = c.inkHigh)
                    }
                }
            }
        } else {
            val missing = pick?.noRecipeFor?.firstOrNull()?.let { Catalog.brewer(it) }
            QuietPanelText(
                if (missing != null) {
                    "We don't have a tested recipe for your ${missing.shortName} yet. Today has every recipe to look through."
                } else {
                    "Pick a recipe on Today or in Recipes when you're ready."
                },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recipe != null) {
                // Inverse, not crema: crema is kept for things that are live, and nothing is yet.
                PrimaryButton(
                    text = "Start my first brew",
                    onClick = { onStart(recipe.id) },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
                    icon = Icons.Rounded.PlayArrow,
                )
            }
            QuietButton(text = "Go to Today", onClick = onToday)
        }
        PillButton(text = "Add an AI key (optional)", onClick = onOpenAiSettings, onCanvas = true)
    }
}

@Composable
private fun NeedChip(text: String) {
    val c = CoffeeTheme.colors
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface2)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.inkHigh)
    }
}

@Composable
private fun QuietPanelText(text: String) {
    SoftCard(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = CoffeeTheme.colors.inkHigh)
    }
}
