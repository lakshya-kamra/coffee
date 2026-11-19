package com.laksh.coffee.ui.recipes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.RecipeValidation
import com.laksh.coffee.domain.StepKind
import com.laksh.coffee.domain.recipes.ExpertRecipes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecipeEditorState(
    val loading: Boolean = true,
    val mode: EditorMode = EditorMode.New,
    /** The recipe to edit or copy no longer exists. */
    val missing: Boolean = false,
    /** The published recipe when editing a built-in: saving writes an override under its id. */
    val builtIn: Recipe? = null,
    /** An override of [builtIn] is already saved, so "Reset to original" has something to undo. */
    val hasOverride: Boolean = false,
    val issues: List<RecipeValidation.Issue> = emptyList(),
    /** Brewers you own, so the brewer picker can put them first. */
    val ownedBrewerIds: Set<String> = emptySet(),
    val saving: Boolean = false,
) {
    val canSave: Boolean get() = !loading && !missing && !saving && !RecipeValidation.hasBlocking(issues)

    /** A recipe that is only yours: it can be deleted outright. */
    val canDelete: Boolean get() = mode == EditorMode.Edit && builtIn == null && !loading && !missing

    val title: String
        get() = when (mode) {
            EditorMode.New -> "New recipe"
            EditorMode.Duplicate -> "Your copy"
            EditorMode.Edit -> "Edit recipe"
        }
}

/**
 * The recipe builder's state. The draft holds text as typed and is Compose state, so a text field
 * sees its own keystroke in the same frame; every change re-runs [RecipeValidation] on a
 * background thread, and Save stays off while a rule is broken.
 */
class RecipeEditorViewModel(
    private val repo: CoffeeRepository,
    private val settings: StateFlow<Settings?>,
    private val recipeId: String?,
    private val mode: EditorMode,
) : ViewModel() {

    private val _state = MutableStateFlow(RecipeEditorState(mode = mode))
    val state: StateFlow<RecipeEditorState> = _state.asStateFlow()

    /** Null while loading, or when the recipe to edit no longer exists. */
    var draft: RecipeDraft? by mutableStateOf(null)
        private set

    /** Every recipe, for "Start from…". */
    val recipes: StateFlow<List<Recipe>> get() = recipesState
    private val recipesState = MutableStateFlow(repo.recipesNow.orEmpty())

    init {
        viewModelScope.launch {
            val s = settings.value ?: Settings()
            val author = s.name
            val source = recipeId?.let { repo.recipeById(it) }
            val published = recipeId?.let { ExpertRecipes.byId(it) }
            val first = when (mode) {
                EditorMode.New -> RecipeDraft.blank(author)
                EditorMode.Duplicate -> source?.let { RecipeDraft.duplicate(it, author) }
                EditorMode.Edit -> source?.let { RecipeDraft.from(it) }
            }
            val builtIn = published.takeIf { mode == EditorMode.Edit }
            draft = first
            _state.value = RecipeEditorState(
                loading = false,
                mode = mode,
                missing = first == null,
                builtIn = builtIn,
                hasOverride = builtIn != null && repo.allSavedRecipes().any { it.id == builtIn.id },
                issues = first?.let { check(it) }.orEmpty(),
                ownedBrewerIds = s.ownedBrewerIds,
            )
            recipesState.value = repo.recipes.first()
        }
    }

    private suspend fun check(draft: RecipeDraft): List<RecipeValidation.Issue> =
        withContext(Dispatchers.Default) { RecipeValidation.check(draft.toRecipe()) }

    /** One change to the draft. Validation follows on a background thread; typing never waits on it. */
    fun edit(change: (RecipeDraft) -> RecipeDraft) {
        val before = draft ?: return
        val after = change(before)
        if (after == before) return
        draft = after
        viewModelScope.launch {
            val issues = check(after)
            // Only the newest draft's answer counts.
            if (draft == after) _state.update { it.copy(issues = issues) }
        }
    }

    fun setBrewer(brewer: Brewer) = edit { it.copy(brewer = brewer) }
    fun addStep(kind: StepKind) = edit { it.addStep(kind) }
    fun applyFix(fix: RecipeValidation.Fix) = edit { it.setLastPour(fix.waterG) }

    /** "Start from…": the draft becomes your copy of [recipe]. Only offered on a new recipe. */
    fun startFrom(recipe: Recipe) {
        val author = settings.value?.name.orEmpty()
        edit { RecipeDraft.duplicate(recipe, author) }
        _state.update { it.copy(mode = EditorMode.Duplicate) }
    }

    fun save(onDone: (savedId: String?) -> Unit) {
        val s = _state.value
        val saved = draft ?: return
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            // Checked again on what is actually saved, not on what the screen last showed.
            val issues = check(saved)
            if (RecipeValidation.hasBlocking(issues)) {
                _state.update { it.copy(saving = false, issues = issues) }
                return@launch
            }
            var recipe = saved.toRecipe()
            s.builtIn?.let { published ->
                // The numbers are now yours, so the override stops claiming to be the published ones.
                recipe = recipe.copy(fidelity = "custom", sourceNote = RecipeDraft.overrideNote(published), basedOn = null)
            }
            repo.saveRecipe(recipe)
            _state.update { it.copy(saving = false) }
            onDone(recipe.id)
        }
    }

    /** Built-ins only: drops your override so the published recipe comes back. */
    fun resetToOriginal(onDone: (savedId: String?) -> Unit) {
        val id = _state.value.builtIn?.id ?: return
        viewModelScope.launch {
            repo.resetRecipe(id)
            onDone(id)
        }
    }

    /** Custom recipes only. Logs keep the recipe's name, so history is untouched. */
    fun delete(onDone: (savedId: String?) -> Unit) {
        val id = draft?.id ?: return
        if (!_state.value.canDelete) return
        viewModelScope.launch {
            repo.resetRecipe(id)
            onDone(null)
        }
    }

    companion object {
        fun factory(recipeId: String?, mode: EditorMode): ViewModelProvider.Factory = viewModelFactory {
            initializer { RecipeEditorViewModel(this.app.repository, this.app.settingsState, recipeId, mode) }
        }
    }
}
