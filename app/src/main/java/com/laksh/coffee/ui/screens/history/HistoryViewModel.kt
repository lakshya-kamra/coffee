package com.laksh.coffee.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.data.ai.AnalysisRepository
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.data.db.CoffeeDatabase
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.domain.BrewReport
import com.laksh.coffee.domain.LogFilter
import com.laksh.coffee.domain.LogQuery
import com.laksh.coffee.domain.LogSort
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.ShotTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

data class HistoryState(
    val logs: List<BrewLogEntity> = emptyList(),
    val beans: List<BeanEntity> = emptyList(),
    /** Brews with at least one stored analysis; rows get a small marker. */
    val analysedIds: Set<Long> = emptySet(),
    /** Planned total seconds per recipe id, so each row can show how far off target it ran. */
    val targetSecByRecipe: Map<String, Int> = emptyMap(),
    /** Espresso recipes' shot bands: a shot is judged against its band, never a minute either side. */
    val shotByRecipe: Map<String, ShotTarget> = emptyMap(),
    /** The search and filter chips (v4 §4.11). */
    val filter: LogFilter = LogFilter(),
    /** Brews with at least one photo, for "With photos". */
    val photoIds: Set<Long> = emptySet(),
) {
    // Built once per state (off the main thread for every update), never on a read in composition.

    /** What the list shows: the search, the chips and the sort applied. */
    val shown: List<BrewLogEntity> = if (filter == LogFilter()) logs else LogQuery.filter(logs, filter, photoIds)

    /** Shown logs bucketed by day, newest first, for the day eyebrows. Best rated is one block. */
    val shownByDay: List<Pair<LocalDate?, List<BrewLogEntity>>> = if (filter.sort == LogSort.Best) {
        if (shown.isEmpty()) emptyList() else listOf(null to shown)
    } else {
        shown.groupBy { localDate(it.startedAt) }.toList().sortedByDescending { it.first }
    }

    /** Logs bucketed by day, newest first, for the day eyebrows. */
    val byDay: List<Pair<LocalDate, List<BrewLogEntity>>> = logs
        .groupBy { localDate(it.startedAt) }
        .toList()
        .sortedByDescending { it.first }

    /** A brew with no recipe id still counts, by its name, so the header never undercounts. */
    val recipeCount: Int = logs.map { it.recipeId.ifBlank { it.recipeName } }.distinct().size

    val beanCount: Int = logs.mapNotNull { it.beanId ?: it.beanLabel.takeIf(String::isNotBlank) }.distinct().size

    /** Average of rated brews only; an unrated 0 would drag it down for no reason. */
    val avgScore: Double? = logs.filter { it.overall > 0 }.map { it.overall }.average().takeIf { !it.isNaN() }

    /** Weeks from the first brew to the latest, counting a partial week as one. */
    val spanWeeks: Int = if (byDay.isEmpty()) {
        0
    } else {
        val span = byDay.first().first.toEpochDay() - byDay.last().first.toEpochDay()
        ceil((span + 1) / 7.0).toInt().coerceAtLeast(1)
    }
}

private fun shotTargets(recipes: List<Recipe>): Map<String, ShotTarget> =
    recipes.filter { ShotTarget.applies(it) }.associate { it.id to ShotTarget.of(it) }

private fun localDate(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

class HistoryViewModel(
    private val repo: CoffeeRepository,
    analysis: AnalysisRepository,
    /** Ids of brews that have a photo; read again whenever the log changes. */
    private val photoBrewIds: suspend () -> Set<Long> = { emptySet() },
    /** Where a delete still runs after this screen's scope is gone (the app's scope). */
    private val commitScope: CoroutineScope? = null,
) : ViewModel() {

    private val filter = MutableStateFlow(LogFilter())

    /** Brews deleted but still undoable: hidden at once, really deleted when the undo runs out. */
    private val pending = MutableStateFlow<Map<Long, BrewLogEntity>>(emptyMap())
    private val pendingJobs = mutableMapOf<Long, Job>()

    /** The brew just deleted, for the undo bar; null when there is nothing to undo. */
    private val _undo = MutableStateFlow<BrewLogEntity?>(null)
    val undo: StateFlow<BrewLogEntity?> = _undo

    private val photos = repo.allLogs.map { runCatching { photoBrewIds() }.getOrDefault(emptySet()) }

    private data class Base(
        val logs: List<BrewLogEntity>,
        val beans: List<BeanEntity>,
        val ids: Set<Long>,
        val targets: Map<String, Int>,
        val shots: Map<String, ShotTarget>,
    )

    val state: StateFlow<HistoryState> = combine(
        combine(repo.allLogs, repo.activeBeans, analysis.analysedBrewIds, repo.recipes) { logs, beans, ids, recipes ->
            Base(logs, beans, ids, recipes.associate { it.id to ShotTarget.targetSec(it) }, shotTargets(recipes))
        },
        filter,
        pending,
        photos,
    ) { base, f, hidden, photoIds ->
        HistoryState(
            logs = if (hidden.isEmpty()) base.logs else base.logs.filter { it.id !in hidden },
            beans = base.beans,
            analysedIds = base.ids,
            targetSecByRecipe = base.targets,
            shotByRecipe = base.shots,
            filter = f,
            photoIds = photoIds,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        // Eager and seeded from the hot caches: the Log tab has its rows before it is shown.
        SharingStarted.Eagerly,
        HistoryState(
            logs = repo.allLogsNow.orEmpty(),
            beans = repo.activeBeansNow.orEmpty(),
            targetSecByRecipe = repo.recipesNow.orEmpty().associate { it.id to ShotTarget.targetSec(it) },
            shotByRecipe = shotTargets(repo.recipesNow.orEmpty()),
        ),
    )

    fun delete(log: BrewLogEntity) {
        viewModelScope.launch { repo.deleteLog(log) }
    }

    fun setFilter(f: LogFilter) {
        filter.value = f
    }

    fun updateFilter(change: (LogFilter) -> LogFilter) {
        filter.value = change(filter.value)
    }

    /**
     * Delete with undo (v4 §2.7): the row goes at once, the brew (and its photos) only after
     * [UNDO_MS], so "Undo" brings back everything. A later delete commits the earlier one.
     */
    fun deleteWithUndo(log: BrewLogEntity) {
        _undo.value?.let { commit(it.id) }
        pending.value = pending.value + (log.id to log)
        _undo.value = log
        pendingJobs[log.id] = viewModelScope.launch {
            delay(UNDO_MS)
            commit(log.id)
        }
    }

    fun undoDelete() {
        val log = _undo.value ?: return
        pendingJobs.remove(log.id)?.cancel()
        pending.value = pending.value - log.id
        _undo.value = null
    }

    private fun commit(id: Long) {
        pendingJobs.remove(id)?.cancel()
        val log = pending.value[id] ?: return
        if (_undo.value?.id == id) _undo.value = null
        // The app's scope, so a delete started just before the screen goes still happens.
        (commitScope ?: viewModelScope).launch {
            repo.deleteLog(log)
            pending.value = pending.value - id
        }
    }

    override fun onCleared() {
        pending.value.keys.toList().forEach(::commit)
        super.onCleared()
    }

    companion object {
        const val UNDO_MS = 8_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this.app
                HistoryViewModel(
                    app.repository,
                    app.analysis,
                    photoBrewIds = {
                        CoffeeDatabase.get(app).photoDao().all().mapNotNullTo(HashSet()) { it.brewLogId }
                    },
                    commitScope = app.appScope,
                )
            }
        }
    }
}

data class BrewDetailState(
    val loading: Boolean = true,
    val log: BrewLogEntity? = null,
    val recipe: Recipe? = null,
    val bean: BeanEntity? = null,
    val grinder: GrinderEntity? = null,
    /** Settings' "I have a scale": without one the pass's water and the planned steps read in ml. */
    val hasScale: Boolean = true,
) {
    val quickRead: List<String>
        get() = log?.let { BrewReport.quickRead(it, recipe) } ?: emptyList()
}

class BrewDetailViewModel(
    private val repo: CoffeeRepository,
    private val logId: Long,
    private val settings: StateFlow<Settings?> = MutableStateFlow(null),
) : ViewModel() {

    // Seeded from the hot caches when the brew is already there, so the page (not a loader) is
    // what slides in, and the observer below only confirms it.
    private val _state = MutableStateFlow(seed())
    val state: StateFlow<BrewDetailState> = _state

    private fun seed(): BrewDetailState {
        val log = repo.allLogsNow?.firstOrNull { it.id == logId } ?: return BrewDetailState()
        return BrewDetailState(
            loading = false,
            log = log,
            recipe = log.recipeId.takeIf { it.isNotBlank() }?.let { id -> repo.recipesNow?.firstOrNull { it.id == id } },
            bean = log.beanId?.let { id -> repo.allBeansNow?.firstOrNull { it.id == id } },
            grinder = log.grinderId?.let { id -> repo.activeGrindersNow?.firstOrNull { it.id == id } },
            hasScale = settings.value?.hasScale ?: true,
        )
    }

    /** Guards against a double tap on Delete popping the back stack twice. */
    private var deleting = false

    init {
        // Observed rather than loaded once, so coming back from an edit in Review refreshes the pass.
        viewModelScope.launch {
            repo.observeLog(logId).collect { log ->
                if (log == null) {
                    // A null after a real log means a delete is in flight; keep the page instead of flashing the loader.
                    if (_state.value.log == null) _state.value = BrewDetailState(loading = false)
                    return@collect
                }
                _state.value = BrewDetailState(
                    loading = false,
                    log = log,
                    recipe = log.recipeId.takeIf { it.isNotBlank() }?.let { repo.recipeById(it) },
                    bean = log.beanId?.let { repo.bean(it) },
                    grinder = log.grinderId?.let { repo.grinder(it) },
                    hasScale = _state.value.hasScale,
                )
            }
        }
        viewModelScope.launch {
            settings.collect { s -> if (s != null) _state.update { it.copy(hasScale = s.hasScale) } }
        }
    }

    fun report(): String {
        val st = _state.value
        val log = st.log ?: return ""
        return BrewReport.markdown(log, st.recipe, st.bean, st.grinder)
    }

    fun delete(onDeleted: () -> Unit) {
        val log = _state.value.log ?: return
        if (deleting) return
        deleting = true
        viewModelScope.launch {
            repo.deleteLog(log)
            onDeleted()
        }
    }

    companion object {
        fun factory(logId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { BrewDetailViewModel(this.app.repository, logId, this.app.settingsState) }
        }
    }
}
