package com.laksh.coffee.ui.screens.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.BrewerFamilies
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.grind.GrindScale
import com.laksh.coffee.domain.recipes.ExpertRecipes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Derived lists are built once per state, never on a read in composition.
 *
 * Filters stack: "Mine" (recipes you wrote, copied or edited), "My brewers" (recipes for a brewer
 * in the same family as one you own), one brewer, one author, and the search text.
 */
/** The list pane's sort control (v4 §3.2). */
enum class RecipeSort(val label: String) {
    Suggested("Suggested"), AZ("A-Z"), MostBrewed("Most brewed"), BestRated("Best rated");
}

/** Your brews of one recipe: how many, and the best score. */
data class RecipeStats(val count: Int, val best: Int)

data class RecipeListState(
    val all: List<Recipe> = emptyList(),
    val query: String = "",
    val brewer: Brewer? = null,
    val author: String? = null,
    val mine: Boolean = false,
    /** Null = not chosen yet: on when you own brewers, off when you own none. */
    val myBrewersChoice: Boolean? = null,
    val ownedBrewerIds: Set<String> = emptySet(),
    val sort: RecipeSort = RecipeSort.Suggested,
    /** Recipe id to your brews of it, for "Most brewed" and "Best rated". */
    val stats: Map<String, RecipeStats> = emptyMap(),
    /** false: the cards and rows read the coffee in level tablespoons and the water in ml (A1). */
    val hasScale: Boolean = true,
) {
    // CORE's families: a drip machine, phin or drip bag never borrows a V60 recipe, and an owned
    // Melodrip (an accessory) matches nothing rather than everything.
    private val ownedFamilies: Set<String> =
        if (ownedBrewerIds.isEmpty()) emptySet() else BrewerFamilies.owned(ownedBrewerIds)

    /** Something of yours is in the library. The "Mine" chip shows either way (§4.12). */
    val hasMine: Boolean = all.any(::isMine)

    /** "Mine" is on with nothing of yours: the list shows the guided empty state instead. */
    val mineEmpty: Boolean = mine && !hasMine

    /** The "My brewers" chip is only offered when you have said which brewers you own. */
    val hasOwned: Boolean = ownedFamilies.isNotEmpty()

    val myBrewers: Boolean = hasOwned && (myBrewersChoice ?: true)

    // What the two toggles leave; the brewer and author chips are cut from this, so no chip
    // ever leads to an empty list.
    private val scoped: List<Recipe> = all.filter { r ->
        (!mine || isMine(r)) && (!myBrewers || r.brewer.family in ownedFamilies)
    }

    val brewers: List<Brewer> = scoped.map { it.brewer }.distinct()

    /** A brewer chip chosen earlier only counts while it is still on offer. */
    val activeBrewer: Brewer? = brewer?.takeIf { it in brewers }

    val authors: List<String> = scoped
        .filter { activeBrewer == null || it.brewer == activeBrewer }
        .map { it.author }.filter { it.isNotBlank() }.distinct().sorted()

    val activeAuthor: String? = author?.takeIf { it in authors }

    /** Everyone with a recipe in the library, for the header count. */
    val people: Int = all.map { it.author }.distinct().size

    val filtered: List<Recipe> = query.trim().let { q ->
        val hits = scoped.filter { r ->
            (activeBrewer == null || r.brewer == activeBrewer) &&
                (activeAuthor == null || r.author == activeAuthor) &&
                (q.isEmpty() || r.name.contains(q, ignoreCase = true) ||
                    r.author.contains(q, ignoreCase = true) ||
                    r.blurb.contains(q, ignoreCase = true) ||
                    r.brewer.label.contains(q, ignoreCase = true))
        }
        // Stable sorts, so equal recipes keep the library's own order.
        when (sort) {
            RecipeSort.Suggested -> hits
            RecipeSort.AZ -> hits.sortedBy { it.name.lowercase() }
            RecipeSort.MostBrewed -> hits.sortedByDescending { stats[it.id]?.count ?: 0 }
            RecipeSort.BestRated -> hits.sortedByDescending { stats[it.id]?.best ?: 0 }
        }
    }

    /** Something other than the search text is narrowing the list. */
    val hasFilters: Boolean = mine || myBrewers || activeBrewer != null || activeAuthor != null

    companion object {
        /** Yours: written from scratch, copied, or a built-in you edited. */
        fun isMine(r: Recipe): Boolean = r.fidelity == "custom" || !r.isBuiltIn
    }
}

class RecipesViewModel(repo: CoffeeRepository, settings: StateFlow<Settings?>) : ViewModel() {

    private data class Filters(
        val query: String = "",
        val brewer: Brewer? = null,
        val author: String? = null,
        val mine: Boolean = false,
        val myBrewers: Boolean? = null,
        val sort: RecipeSort = RecipeSort.Suggested,
    )

    private val filters = MutableStateFlow(Filters())

    /** Brew counts and best scores per recipe, off the main thread. */
    private val stats = repo.allLogs.map { logs ->
        logs.groupBy { it.recipeId }.mapValues { (_, l) -> RecipeStats(l.size, l.maxOf { it.overall }) }
    }.flowOn(Dispatchers.Default).distinctUntilChanged()

    // Eager and seeded from the hot cache, so the tab's first frame already has its cards. The
    // filtering stays on the main dispatcher: 50 recipes is trivial, and the search field's
    // text must not wait on a thread hop.
    val state: StateFlow<RecipeListState> =
        combine(
            repo.recipes,
            filters,
            settings.map { (it?.ownedBrewerIds.orEmpty()) to (it?.hasScale ?: true) }.distinctUntilChanged(),
            stats,
        ) { recipes, f, (owned, hasScale), st ->
            RecipeListState(recipes, f.query, f.brewer, f.author, f.mine, f.myBrewers, owned, f.sort, st, hasScale)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            RecipeListState(
                repo.recipesNow.orEmpty(),
                ownedBrewerIds = settings.value?.ownedBrewerIds.orEmpty(),
                hasScale = settings.value?.hasScale ?: true,
            ),
        )

    fun setQuery(v: String) = filters.update { it.copy(query = v) }
    fun setBrewer(v: Brewer?) = filters.update { it.copy(brewer = if (it.brewer == v) null else v) }
    fun setAuthor(v: String?) = filters.update { it.copy(author = if (it.author == v) null else v) }
    fun toggleMine() = filters.update { it.copy(mine = !it.mine) }
    fun setSort(v: RecipeSort) = filters.update { it.copy(sort = v) }

    /** [shown] is what the chip shows now, so the first tap turns the default off. */
    fun toggleMyBrewers(shown: Boolean) = filters.update { it.copy(myBrewers = !shown) }

    /** The empty state's way back: every filter off, including the ones the query can't show. */
    fun clearFilters() {
        filters.value = Filters(myBrewers = false)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { RecipesViewModel(this.app.repository, this.app.settingsState) }
        }
    }
}

data class RecipeDetailState(
    val loading: Boolean = true,
    val recipe: Recipe? = null,
    /** This recipe's brews, newest first. */
    val logs: List<BrewLogEntity> = emptyList(),
    /** True when this is your edit of a built-in recipe, so the published one can be brought back. */
    val isOverride: Boolean = false,
    /** Your default grinder's name and scale, when the catalog has starting points for it. */
    val grinderName: String = "",
    val grindScale: GrindScale? = null,
    /** false: amounts in spoons and ml, and the no-scale prep lines (v4 §4.3). */
    val hasScale: Boolean = true,
    /** "c" | "f", for temperatures in the recipe's own words. */
    val tempUnit: String = "c",
    /** With [experience], which of the recipe's needs to flag in "What you'll need". */
    val buysGround: Boolean = false,
    val experience: String = "",
    /** Grinders that are not archived; a recipe that needs one is fine with any of them. */
    val grinderCount: Int = 0,
) {
    val brewedCount: Int get() = logs.size

    /** Highest rated; on a tie the newest wins, since its grind is the one to trust today. */
    val bestLog: BrewLogEntity? get() = logs.filter { it.overall > 0 }.maxByOrNull { it.overall }
}

class RecipeDetailViewModel(
    private val repo: CoffeeRepository,
    private val settings: StateFlow<Settings?>,
    private val recipeId: String,
) : ViewModel() {

    private val published: Recipe? = ExpertRecipes.byId(recipeId)

    // Seeded from the hot cache (a recipe of your own, or your edit of a built-in), else from the
    // built-in list, synchronously: the page, and the shared header that flies into it, exist on
    // the very first frame.
    private val _state = MutableStateFlow(
        (repo.recipesNow?.firstOrNull { it.id == recipeId } ?: published).let { r ->
            RecipeDetailState(loading = false, recipe = r, isOverride = isOverride(r)).withSettings(settings.value)
        },
    )
    val state: StateFlow<RecipeDetailState> = _state

    private fun isOverride(r: Recipe?): Boolean = published != null && r != null && !r.isBuiltIn

    private fun RecipeDetailState.withSettings(s: Settings?): RecipeDetailState =
        if (s == null) this else copy(hasScale = s.hasScale, tempUnit = s.tempUnit, buysGround = s.buysGround, experience = s.experience)

    init {
        viewModelScope.launch {
            settings.collect { s -> _state.update { it.withSettings(s) } }
        }
        viewModelScope.launch {
            // Observed, so an edit or a reset made in the builder shows when you come back. A
            // recipe that was deleted keeps its last version on screen while the page leaves.
            repo.recipes
                .map { all -> all.firstOrNull { it.id == recipeId } }
                .distinctUntilChanged()
                .collect { r ->
                    if (r != null) _state.update { it.copy(loading = false, recipe = r, isOverride = isOverride(r)) }
                }
        }
        viewModelScope.launch {
            // The grinder a brew would start on: the chosen default, else the one marked default.
            combine(repo.activeGrinders, settings.map { it?.defaultGrinderId }.distinctUntilChanged()) { all, id ->
                val g = all.firstOrNull { it.id == id } ?: all.firstOrNull { it.isDefault } ?: all.firstOrNull()
                // Only a scale the catalog has ranges for is worth a card.
                g?.let { it.display to it.scale() }?.takeIf { it.second.ranges.isNotEmpty() } to all.size
            }
                .flowOn(Dispatchers.Default)
                .collect { (found, count) ->
                    _state.update { it.copy(grinderName = found?.first.orEmpty(), grindScale = found?.second, grinderCount = count) }
                }
        }
        viewModelScope.launch {
            // Observed, not read once, so finishing a brew and coming back updates "Brewed n×".
            repo.allLogs
                .map { all -> all.filter { it.recipeId == recipeId } }
                .distinctUntilChanged()
                .collect { mine -> _state.update { it.copy(logs = mine) } }
        }
    }

    /** Built-ins only: drops your version so the published numbers come back. */
    fun resetToOriginal() {
        if (!_state.value.isOverride) return
        viewModelScope.launch { repo.resetRecipe(recipeId) }
    }

    companion object {
        fun factory(recipeId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { RecipeDetailViewModel(this.app.repository, this.app.settingsState, recipeId) }
        }
    }
}
