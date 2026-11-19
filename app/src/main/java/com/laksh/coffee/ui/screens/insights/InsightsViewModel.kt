package com.laksh.coffee.ui.screens.insights

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.data.ai.AiSettingsStore
import com.laksh.coffee.data.backup.BackupExporter
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.BrewReport
import com.laksh.coffee.domain.Recipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Everything Insights draws, derived from four plain lists. The derived values are lazy so a
 * recomposition never recomputes them, and a new emission builds a fresh state with fresh
 * caches.
 */
data class InsightsState(
    val logs: List<BrewLogEntity> = emptyList(),
    val beans: List<BeanEntity> = emptyList(),
    val grinders: List<GrinderEntity> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    /** Streaks and "days since" count from here; part of the state so a new day rebuilds them. */
    val today: LocalDate = LocalDate.now(),
) {
    val hasData: Boolean get() = logs.isNotEmpty()

    val passport: Passport by lazy { PassportStats.passport(logs, today) }
    val bests: PersonalBests by lazy { PassportStats.personalBests(logs, beans, today) }
    val scores: List<ScorePoint> by lazy { PassportStats.scoreSeries(logs) }
    val lastFive: Double? by lazy { PassportStats.lastFiveAvg(logs) }
    val prevFive: Double? by lazy { PassportStats.prevFiveAvg(logs) }
    val rankedRecipes: List<Ranked> by lazy { PassportStats.rankedRecipes(logs, recipes) }
    val rankedBeans: List<Ranked> by lazy { PassportStats.rankedBeans(logs, beans) }
    val grind: GrindSeries? by lazy { PassportStats.grindSeries(logs, grinders) }

    /** Computes every lazy value now, on the caller's thread. */
    fun warm() {
        passport; bests; scores; lastFive; prevFive; rankedRecipes; rankedBeans; grind
    }

    companion object {
        /**
         * The state kept hot in [scope] (the process): every statistic is computed on
         * Dispatchers.Default when a list or the day changes, so composition only reads. Null
         * until Room's first answers have been turned into numbers.
         */
        fun hot(
            repo: CoffeeRepository,
            today: StateFlow<LocalDate>,
            scope: CoroutineScope,
        ): StateFlow<InsightsState?> = combine(
            repo.allLogs,
            repo.allBeans,
            repo.activeGrinders,
            repo.recipes,
            today,
        ) { logs, beans, grinders, recipes, day ->
            InsightsState(logs, beans, grinders, recipes, day).also { it.warm() }
        }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)
    }
}

/** How the last "Save backup (JSON)" went; null before one was asked for. */
enum class BackupSave { Saving, Saved, Failed }

class InsightsViewModel(
    private val repo: CoffeeRepository,
    ai: AiSettingsStore,
    hot: StateFlow<InsightsState?>,
    private val exporter: BackupExporter,
    /** The app's scope: a backup being written finishes even if the tab is left. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    // Seeded from the app's hot state, whose numbers were computed off the main thread, so the
    // tab has them before it is shown. Only a tab opened before Room's first answer starts empty.
    val state: StateFlow<InsightsState> = hot.filterNotNull()
        .stateIn(viewModelScope, SharingStarted.Eagerly, hot.value ?: InsightsState())

    /**
     * The provider and host analysis would talk to, or null when no key is saved. The privacy
     * copy names them, so what the screen promises always matches what the app would actually
     * do. A settings read failure falls back to the not-configured copy instead of crashing.
     */
    val aiTarget: StateFlow<AiTarget?> = ai.settings
        .map { if (it.hasKey) AiTarget(it.providerName, it.host) else null }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Markdown for the most recent brew, with the ones before it as context. */
    suspend fun lastBrewReport(): String {
        val logs = repo.allLogs.first()
        val log = logs.firstOrNull() ?: return ""
        val recipe = repo.recipeById(log.recipeId)
        val bean = log.beanId?.let { repo.bean(it) }
        val grinder = log.grinderId?.let { repo.grinder(it) }
        return BrewReport.markdown(log, recipe, bean, grinder, logs.drop(1))
    }

    /** Markdown for the whole run, for asking about patterns. */
    fun trendReport(limit: Int): String {
        val st = state.value
        return BrewReport.trendMarkdown(st.logs.take(limit), st.beans)
    }

    private val backupSaveState = MutableStateFlow<BackupSave?>(null)
    val backupSave: StateFlow<BackupSave?> = backupSaveState.asStateFlow()

    /**
     * "Save backup (JSON)": the same file Gear › App › Back up everything writes, with saved
     * analyses and without photos. Like every backup it never contains an API key.
     */
    fun saveBackup(uri: Uri) {
        if (backupSaveState.value == BackupSave.Saving) return
        backupSaveState.value = BackupSave.Saving
        appScope.launch {
            backupSaveState.value = try {
                exporter.writeTo(uri, includePhotos = false, includeAnalyses = true)
                BackupSave.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                BackupSave.Failed
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                InsightsViewModel(
                    this.app.repository, this.app.aiSettings, this.app.insightsState,
                    this.app.backupExporter, this.app.appScope,
                )
            }
        }
    }
}
