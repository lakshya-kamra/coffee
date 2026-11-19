package com.laksh.coffee.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.data.prefs.SettingsStore
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.ui.components.Status
import com.laksh.coffee.ui.screens.gear.BeanWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Home's one-line answer under the title: a status dot, a coloured lead and a quiet detail. */
data class HomeHeadline(val status: Status, val lead: String, val detail: String?)

/**
 * Logs are newest first. "Scored" means overall > 0; unrated brews never move an average.
 *
 * Everything derived is computed once, when the state is built (off the main thread for every
 * update), so composition only reads fields. Body properties are not part of equals.
 */
data class HomeUiState(
    val recentLogs: List<BrewLogEntity> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val beans: List<BeanEntity> = emptyList(),
    val settings: Settings = Settings(),
    /** Part of the state, so a new day rebuilds "today", the week and the streak. */
    val today: LocalDate = LocalDate.now(),
) {
    val lastRecipe: Recipe? = recipes.firstOrNull { it.id == settings.lastRecipeId }
        ?: recentLogs.firstOrNull()?.let { l -> recipes.firstOrNull { it.id == l.recipeId } }

    /**
     * The recipe the NEXT BREW pass offers. With nothing brewed yet it is the first recipe for a
     * brewer you own (V60 01, 02 and 03 count as one family), else the first expert recipe.
     */
    val nextRecipe: Recipe? = lastRecipe ?: firstForOwnedBrewer(recipes, settings.ownedBrewerIds) ?: recipes.firstOrNull()

    /** Existing users skipped onboarding automatically; Home offers it once, until dismissed. */
    val showPersonalise: Boolean = settings.onboardingAuto && !settings.personaliseDismissed

    /**
     * The coffee brew setup will start with, when the rest its roaster suggested is not over:
     * its name and the days left. Null when no rest was recorded: the app never guesses one.
     */
    val resting: Pair<String, Long>? = beans.firstOrNull { it.id == settings.defaultBeanId }?.let { bean ->
        // Trimmed for the sentence only: a name saved with a stray space stays as it was saved.
        BeanWords.restDaysLeft(bean, today.toEpochDay())?.let { bean.name.trim() to it }
    }

    private val days: List<LocalDate> = recentLogs.map { localDate(it.startedAt) }

    val brewsToday: Int = days.count { it == today }

    /** Brews in the rolling seven days ending today, so Monday never resets it to zero. */
    val brewsThisWeek: Int = today.minusDays(6).let { from ->
        days.count { day -> !day.isBefore(from) && !day.isAfter(today) }
    }

    val lastBrew: BrewLogEntity? get() = recentLogs.firstOrNull()

    /** Consecutive days ending today (or yesterday) with at least one brew. */
    val streakDays: Int = run {
        if (days.isEmpty()) return@run 0
        val set = days.toSet()
        var cursor = today
        if (!set.contains(cursor)) {
            cursor = cursor.minusDays(1)
            if (!set.contains(cursor)) return@run 0
        }
        var count = 0
        while (set.contains(cursor)) {
            count++
            cursor = cursor.minusDays(1)
        }
        count
    }

    private val scoredLogs: List<BrewLogEntity> = recentLogs.filter { it.overall > 0 }

    /** Rolling average of the last ten scored brews. */
    val averageScore: Double? = scoredLogs.take(10).averageOrNull()

    /** Average of the five newest scored brews. */
    val lastFiveAvg: Double? = scoredLogs.take(5).averageOrNull()

    /** Average of scored brews six to ten, the window LAST 5 is compared against. */
    val prevFiveAvg: Double? = scoredLogs.drop(5).take(5).averageOrNull()

    fun lastLogFor(recipeId: String): BrewLogEntity? =
        recentLogs.firstOrNull { it.recipeId == recipeId }

    /** Skips 0 and null so a corrupted "0 clicks" log is never offered as the grind to use. */
    fun lastGrindFor(recipeId: String): Int? =
        recentLogs.firstOrNull { it.recipeId == recipeId && (it.grindClicks ?: 0) > 0 }?.grindClicks

    /** First match wins, so a dialled-in run outranks one weak cup. */
    val headline: HomeHeadline = run {
        if (recentLogs.isEmpty()) {
            return@run HomeHeadline(Status.Neutral, "No brews yet", "start with a recipe below")
        }
        val lastThree = scoredLogs.take(3)
        if (lastThree.size == 3) {
            val avg = lastThree.averageOrNull() ?: 0.0
            if (avg >= 7.5) {
                val first = lastThree.first().overall
                val detail = if (lastThree.all { it.overall == first }) {
                    "last 3 brews scored $first"
                } else {
                    "last 3 avg ${oneDp(avg)}"
                }
                return@run HomeHeadline(Status.Good, "Dialled in", detail)
            }
        }
        val newest = scoredLogs.firstOrNull()
        if (newest != null && newest.overall in 1..5) {
            return@run HomeHeadline(Status.Attention, "Worth a tweak", "last cup ${newest.overall}/10")
        }
        HomeHeadline(Status.Neutral, "Brewing steady", averageScore?.let { "avg ${oneDp(it)}" })
    }
}

/** The first recipe whose brewer is in the same family as one you own; null when you own none. */
internal fun firstForOwnedBrewer(recipes: List<Recipe>, ownedBrewerIds: Set<String>): Recipe? {
    if (ownedBrewerIds.isEmpty()) return null
    val families = ownedBrewerIds.mapNotNull { Brewers.byId(it)?.family }.toSet()
    return recipes.firstOrNull { it.brewer.family in families }
}

private fun localDate(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

private fun List<BrewLogEntity>.averageOrNull(): Double? =
    if (isEmpty()) null else sumOf { it.overall }.toDouble() / size

/**
 * Activity-scoped (the tabs live outside the NavHost) and eager: the state is built from the
 * repository's hot caches when the VM is created, then kept current off the main thread, so
 * the tab always has its data before it is shown.
 */
class HomeViewModel(
    repo: CoffeeRepository,
    settings: StateFlow<Settings?>,
    today: StateFlow<LocalDate>,
    private val prefs: SettingsStore,
) : ViewModel() {

    /** "Not now" on the personalise card: it does not come back. */
    fun dismissPersonalise() {
        viewModelScope.launch { prefs.setPersonaliseDismissed(true) }
    }

    val state: StateFlow<HomeUiState> = combine(
        repo.recentLogs(RECENT),
        repo.recipes,
        repo.activeBeans,
        settings.filterNotNull(),
        today,
    ) { logs, recipes, beans, s, day ->
        HomeUiState(logs, recipes, beans, s, day)
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        HomeUiState(
            recentLogs = repo.allLogsNow?.take(RECENT).orEmpty(),
            recipes = repo.recipesNow.orEmpty(),
            beans = repo.activeBeansNow.orEmpty(),
            settings = settings.value ?: Settings(),
            today = today.value,
        ),
    )

    companion object {
        private const val RECENT = 40

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(this.app.repository, this.app.settingsState, this.app.today, this.app.settings) }
        }
    }
}
