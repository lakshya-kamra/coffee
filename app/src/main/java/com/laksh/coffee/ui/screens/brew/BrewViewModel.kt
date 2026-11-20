package com.laksh.coffee.ui.screens.brew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.core.grams
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.data.prefs.SettingsStore
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.NoScale
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.StepKind
import com.laksh.coffee.domain.ai.DataQuality
import com.laksh.coffee.domain.catalog.CatalogDocs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

enum class BrewPhase { Setup, Running, Finishing }

/**
 * The exact clock anchors. The UI integrates these once per frame instead of being fed a
 * ticked elapsed value, so the state below never has to change while the timer runs.
 */
data class TimerAnchor(val accumulatedMs: Long, val startedAtNanos: Long, val running: Boolean) {
    fun elapsedAt(nowNanos: Long): Long =
        if (running) accumulatedMs + ((nowNanos - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        else accumulatedMs
}

data class BrewUiState(
    val loading: Boolean = true,
    val recipe: Recipe? = null,
    val phase: BrewPhase = BrewPhase.Setup,
    val settings: Settings = Settings(),

    // setup
    val beans: List<BeanEntity> = emptyList(),
    val grinders: List<GrinderEntity> = emptyList(),
    val selectedBeanId: Long? = null,
    val selectedGrinderId: Long? = null,
    val grindSetting: String = "",
    val grindClicks: Int? = null,
    val doseG: Double = 0.0,
    val tempC: Int = 96,
    /** This recipe's logs, newest first. Read once when the screen opens. */
    val recipeLogs: List<BrewLogEntity> = emptyList(),
    /** The recipe as published (or as the brew-again log had it), before any servings preset. */
    val baseRecipe: Recipe? = null,
    /** "Pre-ground (as bought)": no grind to set, and the log says so. */
    val preground: Boolean = false,
    /** A blade grinder's seconds (GRIND FOR). */
    val bladeSec: Int = DEFAULT_BLADE_SEC,
    /** The servings preset chosen (1, 2 or 3 mugs); null once the dose is stepped by hand. */
    val mugs: Int? = null,
    /** No brew has been logged yet: the steps explain their words once. */
    val firstEver: Boolean = false,
    /** Opened from "Brew again". */
    val again: Boolean = false,
    /** Espresso: the shot was stopped and waits for its yield. */
    val shotStopped: Boolean = false,

    // timer
    val running: Boolean = false,
    val stepIndex: Int = 0,
    /**
     * The step the clock is in. Equal to [stepIndex] unless auto-advance is off, where the step
     * card waits for a tap (Next) after the clock passes its end.
     */
    val clockStepIndex: Int = 0,
    /** Wall-clock second at which the last pour step ended, for drawdown timing. */
    val lastPourEndSec: Int? = null,
    val bedExposedSec: Int? = null,
    val done: Boolean = false,
) {
    val step get() = recipe?.steps?.getOrNull(stepIndex)

    /** The step card follows the clock (the setting); a shot always does, it has no card to tap. */
    val autoAdvance: Boolean get() = settings.autoAdvanceSteps || isEspresso

    /** Auto-advance is off and the clock has passed this step: the card waits for Next (A4). */
    val waitingForTap: Boolean
        get() = phase == BrewPhase.Running && !autoAdvance && clockStepIndex > stepIndex && !done

    val totalSec: Int get() = recipe?.totalSec ?: 0

    val isPouring: Boolean
        get() = step?.kind == StepKind.Pour || step?.kind == StepKind.Bloom

    val selectedBean: BeanEntity? get() = beans.firstOrNull { it.id == selectedBeanId }
    val selectedGrinder: GrinderEntity? get() = grinders.firstOrNull { it.id == selectedGrinderId }

    val usesClicks: Boolean get() = !preground && selectedGrinder?.usesClicks == true

    val grindChoice: GrindChoice get() = GrindChoice.of(preground, selectedGrinder)

    val isEspresso: Boolean get() = recipe?.brewer?.method == BrewMethod.Espresso

    /** The last brew of this recipe on the selected grinder, else the last brew of this recipe. */
    val lastLogForSelection: BrewLogEntity?
        get() = recipeLogs.firstOrNull { it.grinderId == selectedGrinderId } ?: recipeLogs.firstOrNull()

    /**
     * What gets written to the log: "As bought" for pre-ground, "12 s" for a blade grinder; click
     * grinders describe themselves and dials use free text.
     */
    val grindLabel: String
        get() {
            val g = selectedGrinder
            return when {
                preground -> "As bought"
                g?.settingUnit == "s" -> bladeLabel(bladeSec)
                g != null && g.usesClicks -> grindClicks?.let { g.describeClicks(it) }.orEmpty()
                else -> grindSetting
            }
        }

    val readyToBrew: Boolean get() = recipe != null && doseG > 0
}

/** Cumulative water target reached at [elapsedMs], interpolated inside the current pour. */
fun Recipe.waterAt(elapsedMs: Long): Int = waterAtF(elapsedMs).roundToInt()

/** Unrounded [waterAt], so the ring's liquid rises smoothly instead of a gram at a time. */
fun Recipe.waterAtF(elapsedMs: Long): Float {
    val e = elapsedMs / 1000f
    var previousTarget = 0
    steps.forEach { s ->
        val target = s.targetWaterG
        if (target != null) {
            when {
                e >= s.endSec -> previousTarget = target
                e > s.startSec -> {
                    val f = ((e - s.startSec) / (s.endSec - s.startSec).coerceAtLeast(1)).coerceIn(0f, 1f)
                    return previousTarget + (target - previousTarget) * f
                }
                else -> return previousTarget.toFloat()
            }
        }
    }
    return previousTarget.toFloat()
}

/** Fractional positions of every step boundary, for the ring ticks. */
fun Recipe.stepBoundaries(): List<Float> =
    steps.dropLast(1).map { it.endSec.toFloat() / totalSec.coerceAtLeast(1) }

/** Where a blade grinder's stepper starts: a short burst, then taste. */
const val DEFAULT_BLADE_SEC = 10

class BrewViewModel(
    private val repo: CoffeeRepository,
    private val settings: SettingsStore,
    private val recipeId: String,
    /** A logged brew to repeat (v4 §4.10); 0 for a plain brew. */
    private val againLogId: Long = 0L,
) : ViewModel() {

    private val _state = MutableStateFlow(BrewUiState())
    val state: StateFlow<BrewUiState> = _state.asStateFlow()

    private val _anchor = MutableStateFlow(TimerAnchor(0L, 0L, false))
    val anchor: StateFlow<TimerAnchor> = _anchor.asStateFlow()

    /**
     * Emits a step index whenever the brew crosses into a new step, for haptics and sound. With
     * auto-advance off it fires when the clock reaches the next step (the index of that step, for
     * the banner) and again when Next moves the card. No replay, so coming back to the screen does
     * not buzz for a step already announced.
     */
    private val _stepCue = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val stepCue: SharedFlow<Int> = _stepCue.asSharedFlow()

    private var ticker: Job? = null
    private var startedAtRealtime = 0L
    private var accumulatedMs = 0L

    /** Every log, newest first, read once in init. Only the grind prefill looks at it. */
    private var logSnapshot: List<BrewLogEntity> = emptyList()

    init {
        viewModelScope.launch {
            val beans = repo.activeBeans.first()
            val grinders = repo.activeGrinders.first()
            val logs = repo.allLogs.first()
            val s = settings.settings.first()
            logSnapshot = logs
            val againLog = againLogId.takeIf { it > 0 }?.let { id -> logs.firstOrNull { it.id == id } ?: repo.log(id) }
            // A recipe deleted since that brew still brews: a one-off with the brew's own numbers.
            val recipe = repo.recipeById(recipeId) ?: againLog?.let { AgainPrefill.fallbackRecipe(it) }

            val beanId = s.defaultBeanId.takeIf { id -> beans.any { it.id == id } } ?: beans.firstOrNull()?.id
            val grinderId = s.defaultGrinderId.takeIf { id -> grinders.any { it.id == id } }
                ?: grinders.firstOrNull { it.isDefault }?.id
                ?: grinders.firstOrNull()?.id
            var next = _state.value.copy(
                loading = false,
                recipe = recipe,
                baseRecipe = recipe,
                settings = s,
                beans = beans,
                grinders = grinders,
                recipeLogs = logs.filter { it.recipeId == recipeId },
                doseG = recipe?.doseG ?: 15.0,
                tempC = recipe?.tempC ?: 96,
                selectedBeanId = beanId,
                selectedGrinderId = grinderId,
                preground = GrindChoice.startsPreground(beans.firstOrNull { it.id == beanId }, s.buysGround, grinders.size),
                firstEver = logs.isEmpty(),
            )
            if (againLog != null && recipe != null) next = withAgain(next, recipe, AgainPrefill.of(againLog, beans, grinders))
            _state.value = next
            if (againLog == null) prefillGrindSetting()
        }
    }

    /** The setup a logged brew had: its coffee, grinder, grind, dose and water. */
    private fun withAgain(st: BrewUiState, recipe: Recipe, p: AgainPrefill): BrewUiState {
        val dose = p.doseG.takeIf { it > 0 } ?: recipe.doseG
        var r = recipe.scaledToDose(dose)
        if (p.waterG > 0 && abs(r.waterG - p.waterG) > 1.0) r = r.scaledToWater(p.waterG).copy(doseG = r.doseG)
        return st.copy(
            again = true,
            recipe = r,
            doseG = r.doseG,
            tempC = p.tempC ?: st.tempC,
            selectedBeanId = p.beanId ?: st.selectedBeanId,
            selectedGrinderId = if (p.preground) st.selectedGrinderId else p.grinderId ?: st.selectedGrinderId,
            preground = p.preground,
            grindClicks = p.grindClicks,
            grindSetting = p.grindSetting,
            bladeSec = p.bladeSec ?: st.bladeSec,
        )
    }

    /** The grinder's own go-to setting for this kind of brew, if the user set one. */
    private fun grindPreference(st: BrewUiState, g: GrinderEntity): Double? =
        if (st.recipe?.brewer?.method == BrewMethod.Espresso) g.defaultEspressoSetting else g.defaultFilterSetting

    private fun prefillGrindSetting() {
        val st = _state.value
        val g = st.selectedGrinder ?: return
        val pref = grindPreference(st, g)
        when {
            // Stays null when nothing is known. Defaulting to 0 here is how impossible
            // "0 clicks" brews used to reach the log.
            g.usesClicks && st.grindClicks == null ->
                _state.value = st.copy(
                    grindClicks = lastTrustedClicks(g, st.recipe?.brewer)
                        ?: pref?.roundToInt()?.takeIf { it > 0 },
                )
            !g.usesClicks && st.grindSetting.isBlank() && pref != null ->
                _state.value = st.copy(grindSetting = grams(pref))
        }
    }

    /**
     * The newest believable click count on [g]: this recipe first, then another recipe on the
     * same brewer. Never a different brewer's grind (an AeroPress 55 is wrong for a V60), never
     * 0, which is burr contact, and never a filter reading the data-quality rules distrust as a
     * dial number typed in place of total clicks.
     */
    private fun lastTrustedClicks(g: GrinderEntity, brewer: Brewer?): Int? {
        val scale = g.scale()
        val method = brewer?.method
        val trusted = logSnapshot.filter { log ->
            val c = log.grindClicks ?: 0
            log.grinderId == g.id && c > 0 && !DataQuality.grindLooksTyped(c, scale, method)
        }
        val sameRecipe = trusted.firstOrNull { it.recipeId == recipeId }
        val sameBrewer = brewer?.let { b -> trusted.firstOrNull { it.brewerId == b.id || it.brewer == b.label } }
        return (sameRecipe ?: sameBrewer)?.grindClicks
    }

    /** The lowest click count a stepper may reach. 0 is burr contact, which never brews. */
    private fun minClicks(st: BrewUiState): Int =
        maxOf(1, st.selectedGrinder?.minSetting?.roundToInt() ?: 1)

    // ------------------------------------------------------------------- setup

    fun setDose(dose: Double) {
        val r = _state.value.recipe ?: return
        _state.value = _state.value.copy(doseG = dose, recipe = r.scaledToDose(dose), mugs = null)
    }

    /** "1 mug", "2 mugs", "3 mugs": 250 ml of water each, the dose from the recipe's ratio. */
    fun setServings(mugs: Int) {
        val st = _state.value
        val base = st.baseRecipe ?: return
        val r = NoScale.servings(base, mugs)
        _state.value = st.copy(recipe = r, doseG = r.doseG, mugs = mugs)
    }

    fun setBean(id: Long?) {
        val st = _state.value
        val bean = st.beans.firstOrNull { it.id == id }
        // A ground coffee selects pre-ground; leaving one undoes only what it selected.
        val wasAuto = st.selectedBean?.form == "ground"
        val preground = GrindChoice.startsPreground(bean, st.settings.buysGround, st.grinders.size) ||
            (st.preground && !wasAuto)
        _state.value = st.copy(selectedBeanId = id, preground = preground)
        viewModelScope.launch { id?.let { settings.setDefaultBean(it) } }
    }

    fun setGrinder(id: Long?) {
        _state.value = _state.value.copy(selectedGrinderId = id, grindSetting = "", grindClicks = null, preground = false)
        prefillGrindSetting()
        viewModelScope.launch { id?.let { settings.setDefaultGrinder(it) } }
    }

    /** "Pre-ground (as bought)": the first row of the grinder choice. */
    fun setPreground() {
        _state.value = _state.value.copy(preground = true)
    }

    fun adjustBlade(delta: Int) {
        val st = _state.value
        _state.value = st.copy(bladeSec = (st.bladeSec + delta).coerceIn(1, 60))
    }

    /** The chime card's answer: sets the cue and never asks again on this device. */
    fun answerChime(chime: Boolean) {
        val st = _state.value
        _state.value = st.copy(settings = st.settings.copy(soundCues = chime, soundCuesAsked = true))
        viewModelScope.launch {
            settings.setSound(chime)
            settings.setSoundCuesAsked(true)
        }
    }

    /** M while brewing. */
    fun toggleSound() {
        val st = _state.value
        val on = !st.settings.soundCues
        _state.value = st.copy(settings = st.settings.copy(soundCues = on))
        viewModelScope.launch { settings.setSound(on) }
    }

    fun setGrindSetting(v: String) {
        _state.value = _state.value.copy(grindSetting = v)
    }

    /**
     * Nudge the grind by [delta] clicks. An unset grind starts from the grinder's own go-to
     * setting. Never goes below one click, so 0 cannot be logged by stepping down.
     */
    fun adjustClicks(delta: Int) {
        val st = _state.value
        val current = st.grindClicks
            ?: st.selectedGrinder?.let { g -> grindPreference(st, g)?.roundToInt() }
            ?: 0
        _state.value = st.copy(grindClicks = (current + delta).coerceAtLeast(minClicks(st)))
    }

    fun setGrindClicks(v: Int?) {
        val st = _state.value
        val floor = minClicks(st)
        _state.value = st.copy(grindClicks = v?.coerceIn(floor, maxOf(floor, 999)))
    }

    fun setTemp(c: Int) {
        _state.value = _state.value.copy(tempC = c.coerceIn(60, 100))
    }

    // ------------------------------------------------------------------- timer

    private fun publishAnchor() {
        _anchor.value = TimerAnchor(accumulatedMs, startedAtRealtime, _state.value.running)
    }

    private fun currentElapsedMs(): Long = _anchor.value.elapsedAt(System.nanoTime())

    fun start() {
        _state.value = _state.value.copy(phase = BrewPhase.Running, shotStopped = false)
        // A shot starts on its own big button, once the portafilter is in.
        if (!_state.value.isEspresso) resume()
        viewModelScope.launch { settings.setLastRecipe(recipeId) }
    }

    // ------------------------------------------------------------------ shot mode

    /** The big button: start the shot, then stop it. */
    fun shotPrimary() {
        val st = _state.value
        when (ShotPhase.of(st.running, _anchor.value.accumulatedMs, st.shotStopped).onPrimary()) {
            ShotPhase.Pulling -> resume()
            ShotPhase.Stopped -> {
                pause()
                _state.value = _state.value.copy(shotStopped = true)
            }
            ShotPhase.Ready -> Unit
        }
    }

    /** "Pull another": a ready shot again, with the same setup. */
    fun restartShot() {
        pause()
        accumulatedMs = 0L
        publishAnchor()
        _state.value = _state.value.copy(shotStopped = false, stepIndex = 0, clockStepIndex = 0, done = false)
    }

    fun resume() {
        if (_state.value.running) return
        startedAtRealtime = System.nanoTime()
        _state.value = _state.value.copy(running = true)
        publishAnchor()
        ticker?.cancel()
        // 50 ms keeps the step haptic within a frame or two of the arc head. Cheap now,
        // because the state only changes at step boundaries.
        ticker = viewModelScope.launch {
            while (isActive) {
                onTick(currentElapsedMs())
                delay(50)
            }
        }
    }

    fun pause() {
        if (!_state.value.running) return
        accumulatedMs += (System.nanoTime() - startedAtRealtime) / 1_000_000L
        ticker?.cancel()
        ticker = null
        _state.value = _state.value.copy(running = false)
        publishAnchor()
    }

    fun toggleRunning() = if (_state.value.running) pause() else resume()

    private fun onTick(ms: Long) {
        val st = _state.value
        val recipe = st.recipe ?: return
        val sec = (ms / 1000L).toInt()

        // Which step are we in?
        var index = recipe.steps.indexOfLast { sec >= it.startSec }
        if (index < 0) index = 0

        if (!st.autoAdvance) {
            // The card holds its step until Next; the clock's step is tracked for the prompt.
            val next = st.copy(clockStepIndex = index, done = sec >= recipe.totalSec)
            if (next.waitingForTap && !st.waitingForTap && st.running) _stepCue.tryEmit(st.stepIndex + 1)
            _state.value = next
            return
        }

        val crossed = index != st.stepIndex
        var lastPourEnd = st.lastPourEndSec
        if (crossed) {
            val prev = recipe.steps.getOrNull(st.stepIndex)
            if (prev?.targetWaterG != null) lastPourEnd = prev.endSec
            _stepCue.tryEmit(index)
        }

        // On most ticks this equals the current value and the StateFlow drops it.
        _state.value = st.copy(
            stepIndex = index,
            clockStepIndex = index,
            lastPourEndSec = lastPourEnd,
            done = sec >= recipe.totalSec,
        )
    }

    /** Auto-advance off: the card moves on to the next step. The clock is not moved. */
    private fun advanceStep() {
        val st = _state.value
        val steps = st.recipe?.steps ?: return
        val index = st.stepIndex + 1
        if (index >= steps.size) return
        val prev = steps.getOrNull(st.stepIndex)
        _state.value = st.copy(
            stepIndex = index,
            lastPourEndSec = if (prev?.targetWaterG != null) prev.endSec else st.lastPourEndSec,
        )
        if (st.running) _stepCue.tryEmit(index)
    }

    /** Tapped when the bed is first exposed. This is the honest drawdown number. */
    fun markBedExposed() {
        val st = _state.value
        if (st.bedExposedSec != null) return
        _state.value = st.copy(bedExposedSec = (currentElapsedMs() / 1000L).toInt())
    }

    fun addSeconds(delta: Int) {
        // Start from the true elapsed time, not the stored offset, or a running brew loses
        // everything since it was last anchored.
        accumulatedMs = (currentElapsedMs() + delta * 1000L).coerceAtLeast(0L)
        if (_state.value.running) startedAtRealtime = System.nanoTime()
        publishAnchor()
        onTick(currentElapsedMs())
    }

    fun skipToNextStep() {
        val st = _state.value
        val next = st.recipe?.steps?.getOrNull(st.stepIndex + 1) ?: return
        if (!st.autoAdvance) {
            advanceStep()
            // Waiting: the clock is already past the step, so Next only moves the card.
            if (st.waitingForTap) return
        }
        // With auto-advance off the card can lag the clock, so the jump never goes back in time.
        accumulatedMs = if (st.autoAdvance) next.startSec * 1000L else maxOf(currentElapsedMs(), next.startSec * 1000L)
        if (st.running) startedAtRealtime = System.nanoTime()
        publishAnchor()
        onTick(accumulatedMs)
    }

    fun reset() {
        pause()
        accumulatedMs = 0L
        publishAnchor()
        _state.value = _state.value.copy(
            stepIndex = 0,
            clockStepIndex = 0,
            lastPourEndSec = null,
            bedExposedSec = null,
            done = false,
            phase = BrewPhase.Setup,
        )
    }

    // ------------------------------------------------------------------ finish

    /**
     * Writes the brew and hands back the row id so the review screen can open on it. [yieldG] is
     * what a shot put in the cup (shot mode's "How much came out?").
     */
    fun finish(onSaved: (Long) -> Unit, yieldG: Double? = null) {
        // A second tap while the first save is in flight would insert the brew twice.
        if (_state.value.phase == BrewPhase.Finishing) return
        val totalTimeSec = (currentElapsedMs() / 1000L).toInt()
        pause()
        val st = _state.value
        val recipe = st.recipe ?: return
        _state.value = st.copy(phase = BrewPhase.Finishing)

        viewModelScope.launch {
            val bean = st.selectedBean
            val grinder = st.selectedGrinder
            val days = bean?.roastedOnEpochDay?.let { LocalDate.now().toEpochDay() - it }

            val drawdown = st.bedExposedSec?.let { exposed ->
                st.lastPourEndSec?.let { pourEnd -> (exposed - pourEnd).coerceAtLeast(0) }
            }

            val choice = st.grindChoice
            val log = BrewLogEntity(
                startedAt = System.currentTimeMillis(),
                recipeId = recipe.id,
                recipeName = recipe.name,
                recipeAuthor = recipe.author,
                // With its maker ("Hario V60 02"): this is what Brew detail and a backup show.
                brewer = CatalogDocs.brewerTitle(recipe.brewer),
                brewerId = recipe.brewer.id,
                beanId = bean?.id,
                beanLabel = bean?.display.orEmpty(),
                daysOffRoast = days,
                // Pre-ground coffee went through no grinder of yours.
                grinderId = if (choice == GrindChoice.Preground) null else grinder?.id,
                grinderLabel = if (choice == GrindChoice.Preground) "" else grinder?.display.orEmpty(),
                grindSetting = st.grindLabel,
                grindClicks = if (choice == GrindChoice.Dial && grinder?.usesClicks == true) st.grindClicks else null,
                grindValue = when {
                    choice == GrindChoice.Blade -> st.bladeSec.toDouble()
                    choice == GrindChoice.Dial && grinder != null && !grinder.usesClicks ->
                        st.grindSetting.trim().replace(',', '.').toDoubleOrNull()
                    else -> null
                },
                grindKind = GrindChoice.recordKind(st.preground, grinder),
                waterId = st.settings.defaultWaterId.ifBlank { null },
                doseG = st.doseG,
                waterG = recipe.waterG,
                tempC = st.tempC,
                totalTimeSec = totalTimeSec,
                drawdownSec = drawdown,
                beverageG = yieldG?.takeIf { it > 0 },
            )
            val id = repo.insertLog(log)
            onSaved(id)
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(recipeId: String, againLogId: Long = 0L): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val a = this.app
                BrewViewModel(a.repository, a.settings, recipeId, againLogId)
            }
        }
    }
}
