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
import com.laksh.coffee.domain.Brewers
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
data class RecipeListState(
    val all: List<Recipe> = emptyList(),
    val query: String = "",
    val brewer: Brewer? = null,
    val author: String? = null,
    val mine: Boolean = false,
    /** Null = not chosen yet: on when you own brewers, off when you own none. */
    val myBrewersChoice: Boolean? = null,
    val ownedBrewerIds: Set<String> = emptySet(),
) {
    private val ownedFamilies: Set<String> =
        if (ownedBrewerIds.isEmpty()) emptySet() else ownedBrewerIds.mapNotNull { Brewers.byId(it)?.family }.toSet()

    /** The "Mine" chip is only offered once there is something of yours to show. */
    val hasMine: Boolean = all.any(::isMine)

    /** The "My brewers" chip is only offered when you have said which brewers you own. */
    val hasOwned: Boolean = ownedFamilies.isNotEmpty()

    val myBrewers: Boolean = hasOwned && (myBrewersChoice ?: true)

    // What the two toggles leave; the brewer and author chips are cut from this, so no chip
    // ever leads to an empty list.
    private val scoped: List<Recipe> = all.filter { r ->
        (!mine || !hasMine || isMine(r)) && (!myBrewers || r.brewer.family in ownedFamilies)
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
        scoped.filter { r ->
            (activeBrewer == null || r.brewer == activeBrewer) &&
                (activeAuthor == null || r.author == activeAuthor) &&
                (q.isEmpty() || r.name.contains(q, ignoreCase = true) ||
                    r.author.contains(q, ignoreCase = true) ||
                    r.blurb.contains(q, ignoreCase = true) ||
                    r.brewer.label.contains(q, ignoreCase = true))
        }
    }

    /** Something other than the search text is narrowing the list. */
    val hasFilters: Boolean = (mine && hasMine) || myBrewers || activeBrewer != null || activeAuthor != null

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
    )

    private val filters = MutableStateFlow(Filters())

    // Eager and seeded from the hot cache, so the tab's first frame already has its cards. The
    // filtering stays on the main dispatcher: 50 recipes is trivial, and the search field's
    // text must not wait on a thread hop.
    val state: StateFlow<RecipeListState> =
        combine(
            repo.recipes,
            filters,
            settings.map { it?.ownedBrewerIds.orEmpty() }.distinctUntilChanged(),
        ) { recipes, f, owned ->
            RecipeListState(recipes, f.query, f.brewer, f.author, f.mine, f.myBrewers, owned)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            RecipeListState(repo.recipesNow.orEmpty(), ownedBrewerIds = settings.value?.ownedBrewerIds.orEmpty()),
        )

    fun setQuery(v: String) = filters.update { it.copy(query = v) }
    fun setBrewer(v: Brewer?) = filters.update { it.copy(brewer = if (it.brewer == v) null else v) }
    fun setAuthor(v: String?) = filters.update { it.copy(author = if (it.author == v) null else v) }
    fun toggleMine() = filters.update { it.copy(mine = !it.mine) }

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
            RecipeDetailState(loading = false, recipe = r, isOverride = isOverride(r))
        },
    )
    val state: StateFlow<RecipeDetailState> = _state

    private fun isOverride(r: Recipe?): Boolean = published != null && r != null && !r.isBuiltIn

    init {
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
                g?.let { it.display to it.scale() }?.takeIf { it.second.ranges.isNotEmpty() }
            }
                .flowOn(Dispatchers.Default)
                .collect { found ->
                    _state.update { it.copy(grinderName = found?.first.orEmpty(), grindScale = found?.second) }
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
