package com.laksh.coffee.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.core.clock
import com.laksh.coffee.core.counted
import com.laksh.coffee.data.ai.AiSettingsStore
import com.laksh.coffee.data.ai.AnalysisRepository
import com.laksh.coffee.data.ai.AnalysisTarget
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.domain.BedShape
import com.laksh.coffee.domain.BrewMath
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.BrewReport
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.Channeling
import com.laksh.coffee.domain.FlowFeel
import com.laksh.coffee.domain.MethodLabels
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.ReviewSections
import com.laksh.coffee.domain.Sludge
import com.laksh.coffee.domain.ai.DataQuality
import com.laksh.coffee.domain.ai.Fields
import com.laksh.coffee.domain.ai.FlagSeverity
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class ReviewUiState(
    val loading: Boolean = true,
    val log: BrewLogEntity? = null,
    val recipe: Recipe? = null,
    val bean: BeanEntity? = null,
    val grinder: GrinderEntity? = null,
    val saved: Boolean = false,
    /** Free text fields kept as strings so partial input does not fight the parser. */
    val drawdownText: String = "",
    val beverageText: String = "",
    val tdsText: String = "",
    /** Total clicks as typed, for click grinders. */
    val grindText: String = "",
    /** Total brew time as typed: "3:28", "3.28" and "328" all mean 3 min 28 s. */
    val timeText: String = "",
    /** The person's settings: quick or detailed review, scale, temperature unit. */
    val settings: Settings = Settings(),
) {
    /** The brewer this brew used, for the sections and the advice (§4.4). */
    val brewer: Brewer?
        get() = recipe?.brewer ?: log?.let { l -> Brewers.resolveOrNull(l.brewerId ?: l.brewer) }

    val sections: ReviewSections get() = ReviewSections.of(brewer)

    val labels: MethodLabels get() = MethodLabels.of(brewer?.method ?: BrewMethod.PourOver)

    val quick: Boolean get() = QuickReview.isQuick(settings.reviewMode)

    /** The first read, v2: by method, by whether the grind can change, in the person's units. */
    val quickRead: List<String>
        get() = log?.let {
            BrewReport.quickRead(
                it,
                recipe,
                BrewReport.QuickReadOptions(
                    brewer = brewer,
                    hasScale = settings.hasScale,
                    fahrenheit = settings.tempUnit == "f",
                    // Data quality already distrusts the time: no advice built on it.
                    timeIgnored = Fields.TIME in fieldWarnings,
                ),
            )
        } ?: emptyList()

    /**
     * Field name (a `Fields` constant, e.g. Fields.DRAWDOWN, Fields.BEVERAGE, Fields.TASTE,
     * Fields.GRIND, Fields.TIME) to the reason the analysis would refuse to trust it. Worked
     * out while typing, so an impossible number is caught before it is saved rather than
     * after it has been sent. The first flag per field wins; it is the most specific one.
     */
    val fieldWarnings: Map<String, String>
        get() {
            val l = log ?: return emptyMap()
            // A bug in the checks must never stop someone saving their brew.
            val flags = runCatching { DataQuality.check(l, recipe, grinder, bean) }.getOrDefault(emptyList())
            return buildMap {
                flags.filter { it.severity == FlagSeverity.Distrust }.forEach { f ->
                    if (f.field !in this) put(f.field, f.message)
                }
            }
        }

    /** Click grinders get a numeric clicks field; dials keep their free-text setting. */
    val grindInClicks: Boolean
        get() = grinder?.usesClicks ?: (log?.grindClicks != null)

    /** The time field read as seconds, or null while it holds something that is not m:ss. */
    val parsedTimeSec: Int?
        get() = parseClock(timeText)

    val timeTextValid: Boolean
        get() = timeText.isBlank() || parsedTimeSec != null
}

class ReviewViewModel(
    private val repo: CoffeeRepository,
    private val analysis: AnalysisRepository,
    ai: AiSettingsStore,
    private val logId: Long,
    settings: StateFlow<Settings?> = kotlinx.coroutines.flow.MutableStateFlow(null),
) : ViewModel() {

    // Seeded from the hot caches when the brew is already there (Edit, Fix in log), so the form
    // rather than a loader is what rises in. A brew saved a moment ago may not be cached yet;
    // init then loads it as before.
    private val _state = MutableStateFlow(
        repo.allLogsNow?.firstOrNull { it.id == logId }?.let { log ->
            loaded(
                log = log,
                recipe = repo.recipesNow?.firstOrNull { it.id == log.recipeId },
                bean = log.beanId?.let { id -> repo.allBeansNow?.firstOrNull { it.id == id } },
                grinder = log.grinderId?.let { id -> repo.activeGrindersNow?.firstOrNull { it.id == id } },
            )
        } ?: ReviewUiState(),
    )
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    /** True when a key, model and base URL are saved, so 'Save & analyse' can run. */
    val aiReady: StateFlow<Boolean> = ai.settings
        .map { it.ready }
        // A settings read failure only hides the analyse button; it must not break saving.
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Settings ride along in the state, so quick/detailed and the units never lag a frame.
        settings.value?.let { s -> _state.update { it.copy(settings = s) } }
        viewModelScope.launch {
            settings.filterNotNull().collect { s -> _state.update { it.copy(settings = s) } }
        }
        viewModelScope.launch {
            val log = repo.log(logId)
            val recipe = log?.recipeId?.let { repo.recipeById(it) }
            val bean = log?.beanId?.let { repo.bean(it) }
            val grinder = log?.grinderId?.let { repo.grinder(it) }
            val cur = _state.value
            val fresh = loaded(log, recipe, bean, grinder).copy(settings = cur.settings)
            // Never overwrite edits made since the seed; only fill in what the cache lacked.
            _state.value = if (cur.log == null) fresh else cur.copy(recipe = recipe, bean = bean, grinder = grinder)
        }
    }

    private fun loaded(log: BrewLogEntity?, recipe: Recipe?, bean: BeanEntity?, grinder: GrinderEntity?) = ReviewUiState(
        loading = false,
        log = log,
        recipe = recipe,
        bean = bean,
        grinder = grinder,
        drawdownText = log?.drawdownSec?.toString().orEmpty(),
        beverageText = log?.beverageG?.let { BrewMath.round1(it).toString() }.orEmpty(),
        tdsText = log?.tdsPercent?.toString().orEmpty(),
        grindText = log?.grindClicks?.toString().orEmpty(),
        timeText = log?.totalTimeSec?.takeIf { it > 0 }?.let { clock(it) }.orEmpty(),
    )

    private fun edit(block: (BrewLogEntity) -> BrewLogEntity) {
        val log = _state.value.log ?: return
        _state.value = _state.value.copy(log = block(log), saved = false)
    }

    fun setBedShape(v: BedShape) = edit { it.copy(bedShape = v) }
    fun setSludge(v: Sludge) = edit { it.copy(sludge = v) }
    fun setChanneling(v: Channeling) = edit { it.copy(channeling = v) }
    fun setFlowFeel(v: FlowFeel) = edit { it.copy(flowFeel = v) }
    fun setBedNotes(v: String) = edit { it.copy(bedNotes = v) }
    fun setNotes(v: String) = edit { it.copy(notes = v) }
    fun setNextTime(v: String) = edit { it.copy(nextTime = v) }
    fun setOverall(v: Int) = edit { it.copy(overall = v) }
    fun setTaste(axis: String, value: Int) = edit { it.copy(taste = it.taste.with(axis, value)) }

    fun toggleDescriptor(v: String) = edit {
        it.copy(descriptors = it.descriptors.toggle(v))
    }

    fun toggleFault(v: String) = edit {
        it.copy(faults = it.faults.toggle(v))
    }

    /** One of the quick review's four "ANYTHING OFF?" toggles. */
    fun toggleQuick(toggle: String) = edit { it.copy(faults = QuickReview.toggle(it.faults, toggle)) }

    fun setDrawdownText(v: String) {
        val digits = v.filter { c -> c.isDigit() }.take(4)
        _state.value = _state.value.copy(drawdownText = digits)
        edit { it.copy(drawdownSec = digits.toIntOrNull()) }
    }

    fun setBeverageText(v: String) {
        _state.value = _state.value.copy(beverageText = v)
        edit { log ->
            val bev = v.toDoubleOrNull()
            log.copy(
                beverageG = bev,
                extractionYield = BrewMath.extractionYield(log.doseG, bev, log.tdsPercent),
            )
        }
    }

    fun setTdsText(v: String) {
        _state.value = _state.value.copy(tdsText = v)
        edit { log ->
            val tds = v.toDoubleOrNull()
            log.copy(
                tdsPercent = tds,
                extractionYield = BrewMath.extractionYield(log.doseG, log.beverageG, tds),
            )
        }
    }

    /**
     * Corrects the click count, for the log that says 0 or a dial reading. The written
     * setting follows it, so the report and the analysis never see the old number.
     */
    fun setGrindClicks(v: String) {
        val digits = v.filter { c -> c.isDigit() }.take(3)
        _state.value = _state.value.copy(grindText = digits)
        val clicks = digits.toIntOrNull()
        val grinder = _state.value.grinder
        edit { log ->
            log.copy(
                grindClicks = clicks,
                grindSetting = clicks?.let { n -> grinder?.describeClicks(n) ?: counted(n, "click") }.orEmpty(),
            )
        }
    }

    /** The same correction, entered the way the dial reads (turns, number, marks). */
    fun setGrindFromDial(v: Double?) = setGrindClicks(v?.roundToInt()?.coerceAtLeast(0)?.toString().orEmpty())

    /**
     * Free-text setting for dial grinders, which have no click count. The number the analysis
     * reads follows the text, by the same rule the brew screen saves it with.
     */
    fun setGrindSetting(v: String) {
        val numbered = _state.value.grinder?.let { !it.usesClicks } == true
        edit {
            it.copy(
                grindSetting = v,
                grindValue = if (numbered) v.trim().replace(',', '.').toDoubleOrNull() else it.grindValue,
            )
        }
    }

    /** Total time as m:ss. A half-typed value that cannot be read leaves the saved time alone. */
    fun setTotalTimeText(v: String) {
        val cleaned = v.filter { c -> c.isDigit() || c in CLOCK_SEPARATORS }.take(6)
        _state.value = _state.value.copy(timeText = cleaned)
        val sec = if (cleaned.isBlank()) 0 else parseClock(cleaned) ?: return
        edit { it.copy(totalTimeSec = sec) }
    }

    fun save(onSaved: () -> Unit = {}) {
        val log = _state.value.log ?: return
        viewModelScope.launch {
            repo.updateLog(log)
            _state.value = _state.value.copy(saved = true)
            onSaved()
        }
    }

    /**
     * Saves, then starts the analysis in the app-scoped repository so it survives leaving
     * this screen. start() runs only after updateLog completes, so the prompt reads the
     * saved row and not the one before these edits.
     */
    fun saveAndAnalyse(onSaved: (Long) -> Unit) {
        val log = _state.value.log ?: return
        viewModelScope.launch {
            repo.updateLog(log)
            _state.value = _state.value.copy(saved = true)
            analysis.start(AnalysisTarget.Brew(log.id))
            onSaved(log.id)
        }
    }

    /** Markdown for this brew, with recent history attached for context. */
    suspend fun buildReport(): String {
        val st = _state.value
        val log = st.log ?: return ""
        val history = repo.recentLogs(9).first().filter { it.id != log.id }
        return BrewReport.markdown(log, st.recipe, st.bean, st.grinder, history)
    }

    companion object {
        fun factory(logId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val a = this.app
                ReviewViewModel(a.repository, a.analysis, a.aiSettings, logId, a.settingsState)
            }
        }
    }
}

private fun List<String>.toggle(v: String): List<String> =
    if (v in this) this - v else this + v

/** '.' and ',' count as ':' because number keyboards rarely have a colon. */
private val CLOCK_SEPARATORS = setOf(':', '.', ',')

/**
 * Reads a typed brew time. "3:28", "3.28" and "328" are all 3 min 28 s; one or two bare
 * digits are seconds. Returns null for anything else, including 60 or more seconds after
 * a separator.
 */
private fun parseClock(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val sep = t.indexOfFirst { it in CLOCK_SEPARATORS }
    val minutes: Int
    val seconds: Int
    when {
        sep >= 0 -> {
            val secText = t.substring(sep + 1)
            if (secText.length > 2 || secText.any { it in CLOCK_SEPARATORS }) return null
            minutes = t.substring(0, sep).ifEmpty { "0" }.toIntOrNull() ?: return null
            // "3:" and "3:4" are half-typed "3:45"; read them as they stand.
            seconds = secText.ifEmpty { "0" }.toIntOrNull() ?: return null
            if (seconds >= 60) return null
        }
        t.length <= 2 -> {
            minutes = 0
            seconds = t.toIntOrNull() ?: return null
        }
        else -> {
            minutes = t.dropLast(2).toIntOrNull() ?: return null
            seconds = t.takeLast(2).toIntOrNull() ?: return null
            if (seconds >= 60) return null
        }
    }
    return minutes * 60 + seconds
}
