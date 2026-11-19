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

    // timer
    val running: Boolean = false,
    val stepIndex: Int = 0,
    /** Wall-clock second at which the last pour step ended, for drawdown timing. */
    val lastPourEndSec: Int? = null,
    val bedExposedSec: Int? = null,
    val done: Boolean = false,
) {
    val step get() = recipe?.steps?.getOrNull(stepIndex)

    val totalSec: Int get() = recipe?.totalSec ?: 0

    val isPouring: Boolean
        get() = step?.kind == StepKind.Pour || step?.kind == StepKind.Bloom

    val selectedBean: BeanEntity? get() = beans.firstOrNull { it.id == selectedBeanId }
    val selectedGrinder: GrinderEntity? get() = grinders.firstOrNull { it.id == selectedGrinderId }

    val usesClicks: Boolean get() = selectedGrinder?.usesClicks == true

    /** The last brew of this recipe on the selected grinder, else the last brew of this recipe. */
    val lastLogForSelection: BrewLogEntity?
        get() = recipeLogs.firstOrNull { it.grinderId == selectedGrinderId } ?: recipeLogs.firstOrNull()

    /** What gets written to the log: click grinders describe themselves, dials use free text. */
    val grindLabel: String
        get() {
            val g = selectedGrinder
            return if (g != null && g.usesClicks) {
                grindClicks?.let { g.describeClicks(it) }.orEmpty()
            } else {
                grindSetting
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

class BrewViewModel(
    private val repo: CoffeeRepository,
    private val settings: SettingsStore,
    private val recipeId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(BrewUiState())
    val state: StateFlow<BrewUiState> = _state.asStateFlow()

    private val _anchor = MutableStateFlow(TimerAnchor(0L, 0L, false))
    val anchor: StateFlow<TimerAnchor> = _anchor.asStateFlow()

    /**
     * Emits a step index whenever the brew crosses into a new step, for haptics and sound.
     * No replay, so coming back to the screen does not buzz for a step already announced.
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
            val recipe = repo.recipeById(recipeId)
            val beans = repo.activeBeans.first()
            val grinders = repo.activeGrinders.first()
            val logs = repo.allLogs.first()
            val s = settings.settings.first()
            logSnapshot = logs

            _state.value = _state.value.copy(
                loading = false,
                recipe = recipe,
                settings = s,
                beans = beans,
                grinders = grinders,
                recipeLogs = logs.filter { it.recipeId == recipeId },
                doseG = recipe?.doseG ?: 15.0,
                tempC = recipe?.tempC ?: 96,
                selectedBeanId = s.defaultBeanId.takeIf { id -> beans.any { it.id == id } }
                    ?: beans.firstOrNull()?.id,
                selectedGrinderId = s.defaultGrinderId.takeIf { id -> grinders.any { it.id == id } }
                    ?: grinders.firstOrNull { it.isDefault }?.id
                    ?: grinders.firstOrNull()?.id,
            )
            prefillGrindSetting()
        }
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
        _state.value = _state.value.copy(doseG = dose, recipe = r.scaledToDose(dose))
    }

    fun setBean(id: Long?) {
        _state.value = _state.value.copy(selectedBeanId = id)
        viewModelScope.launch { id?.let { settings.setDefaultBean(it) } }
    }

    fun setGrinder(id: Long?) {
        _state.value = _state.value.copy(selectedGrinderId = id, grindSetting = "", grindClicks = null)
        prefillGrindSetting()
        viewModelScope.launch { id?.let { settings.setDefaultGrinder(it) } }
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
        _state.value = _state.value.copy(phase = BrewPhase.Running)
        resume()
        viewModelScope.launch { settings.setLastRecipe(recipeId) }
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
            lastPourEndSec = lastPourEnd,
            done = sec >= recipe.totalSec,
        )
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
        accumulatedMs = next.startSec * 1000L
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
            lastPourEndSec = null,
            bedExposedSec = null,
            done = false,
            phase = BrewPhase.Setup,
        )
    }

    // ------------------------------------------------------------------ finish

    /** Writes the brew and hands back the row id so the review screen can open on it. */
    fun finish(onSaved: (Long) -> Unit) {
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
                grinderId = grinder?.id,
                grinderLabel = grinder?.display.orEmpty(),
                grindSetting = st.grindLabel,
                grindClicks = if (grinder?.usesClicks == true) st.grindClicks else null,
                grindValue = if (grinder != null && !grinder.usesClicks) {
                    st.grindSetting.trim().replace(',', '.').toDoubleOrNull()
                } else {
                    null
                },
                waterId = st.settings.defaultWaterId.ifBlank { null },
                doseG = st.doseG,
                waterG = recipe.waterG,
                tempC = st.tempC,
                totalTimeSec = totalTimeSec,
                drawdownSec = drawdown,
                beverageG = null,
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
        fun factory(recipeId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val a = this.app
                BrewViewModel(a.repository, a.settings, recipeId)
            }
        }
    }
}
