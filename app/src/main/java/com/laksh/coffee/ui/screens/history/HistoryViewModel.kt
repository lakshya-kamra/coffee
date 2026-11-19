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
import com.laksh.coffee.domain.BrewReport
import com.laksh.coffee.domain.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
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
) {
    // Built once per state (off the main thread for every update), never on a read in composition.

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

private fun localDate(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

class HistoryViewModel(
    private val repo: CoffeeRepository,
    analysis: AnalysisRepository,
) : ViewModel() {

    val state: StateFlow<HistoryState> = combine(
        repo.allLogs,
        repo.activeBeans,
        analysis.analysedBrewIds,
        repo.recipes,
    ) { logs, beans, ids, recipes ->
        HistoryState(
            logs = logs,
            beans = beans,
            analysedIds = ids,
            targetSecByRecipe = recipes.associate { it.id to it.totalSec },
        )
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        // Eager and seeded from the hot caches: the Log tab has its rows before it is shown.
        SharingStarted.Eagerly,
        HistoryState(
            logs = repo.allLogsNow.orEmpty(),
            beans = repo.activeBeansNow.orEmpty(),
            targetSecByRecipe = repo.recipesNow.orEmpty().associate { it.id to it.totalSec },
        ),
    )

    fun delete(log: BrewLogEntity) {
        viewModelScope.launch { repo.deleteLog(log) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { HistoryViewModel(this.app.repository, this.app.analysis) }
        }
    }
}

data class BrewDetailState(
    val loading: Boolean = true,
    val log: BrewLogEntity? = null,
    val recipe: Recipe? = null,
    val bean: BeanEntity? = null,
    val grinder: GrinderEntity? = null,
) {
    val quickRead: List<String>
        get() = log?.let { BrewReport.quickRead(it, recipe) } ?: emptyList()
}

class BrewDetailViewModel(
    private val repo: CoffeeRepository,
    private val logId: Long,
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
                )
            }
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
            initializer { BrewDetailViewModel(this.app.repository, logId) }
        }
    }
}
