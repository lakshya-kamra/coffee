package com.laksh.coffee.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.data.catalog.CatalogLinker
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.data.prefs.SettingsStore
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.Brewer
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.CatalogGrinder
import com.laksh.coffee.domain.catalog.CatalogWater
import com.laksh.coffee.domain.catalog.LegacyIds
import com.laksh.coffee.ui.screens.gear.GrinderMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A grinder typed by hand because it is not on the list. */
data class CustomGrinder(
    val brand: String = "",
    val model: String = "",
    val style: SettingStyle = SettingStyle.Clicks,
    /** Clicks per turn as typed; "" = not known. */
    val clicksPerTurn: String = "",
) {
    val isBlank: Boolean get() = brand.isBlank() && model.isBlank()
}

/** Brewer chips under one heading. */
data class BrewerGroup(val title: String, val brewers: List<Pair<String, String>>)

data class OnboardingState(
    /** False until settings and the catalog lists are read; the first step waits for it. */
    val loaded: Boolean = false,
    val step: Int = 0,
    val tempUnit: String = "c",
    val weightUnit: String = "g",
    val homeArea: String = "",
    val areas: List<String> = emptyList(),
    /** The catalog grinder chosen, or null. */
    val grinderId: String? = null,
    val custom: CustomGrinder? = null,
    val ownedBrewerIds: Set<String> = emptySet(),
    val brewerGroups: List<BrewerGroup> = emptyList(),
    val favouriteRoasterIds: Set<String> = emptySet(),
    /** Roaster id to name, for the chosen chips. */
    val roasterNames: Map<String, String> = emptyMap(),
    val waterId: String = "",
    val water: List<CatalogWater> = emptyList(),
    /** A step is being saved; Next waits. */
    val busy: Boolean = false,
) {
    val isLast: Boolean get() = step == Onboarding.STEPS - 1
}

/** What onboarding writes, kept pure so the no-duplicate rule can be tested. */
object Onboarding {

    const val STEPS = 5
    const val MEASURE = 0
    const val GRINDER = 1
    const val BREWERS = 2
    const val COFFEE = 3
    const val DONE = 4

    /**
     * The row to save for a grinder picked from the list. A grinder you already have (same
     * catalog entry, or the same brand and model) becomes the default instead of a second row.
     * It is linked to the list but keeps every number you gave it: when those differ from the
     * list's, the Grinders tab asks ([scaleQuestion]) instead of changing anything.
     */
    fun rowFor(entry: CatalogGrinder, existing: List<GrinderEntity>): GrinderEntity {
        val have = existing.firstOrNull { it.catalogId == entry.id }
            ?: existing.firstOrNull { sameName(it, entry.brand, entry.model) }
        return have?.copy(isDefault = true, catalogId = entry.id)
            ?: GrinderMapping.fill(GrinderEntity(brand = "", model = "", isDefault = true), entry)
    }

    /** True when a row you already had disagrees with a well-sourced list entry about its scale. */
    fun scaleQuestion(row: GrinderEntity, entry: CatalogGrinder): Boolean =
        row.id != 0L && CatalogLinker.scaleDiffers(row, entry)

    /** The row for a grinder typed by hand; null when nothing was typed. */
    fun rowFor(custom: CustomGrinder, existing: List<GrinderEntity>): GrinderEntity? {
        if (custom.isBlank) return null
        val brand = custom.brand.trim()
        val model = custom.model.trim()
        val have = existing.firstOrNull { sameName(it, brand, model) }
        return have?.copy(isDefault = true) ?: GrinderEntity(
            brand = brand,
            model = model,
            settingStyle = custom.style,
            clicksPerRotation = if (custom.style == SettingStyle.Clicks) custom.clicksPerTurn.toIntOrNull() ?: 0 else 0,
            isDefault = true,
        )
    }

    private fun sameName(row: GrinderEntity, brand: String, model: String): Boolean {
        val key = CatalogLinker.norm(brand + model)
        return key.isNotEmpty() && CatalogLinker.norm(row.brand + row.model) == key
    }

    /**
     * Brewer chips: the nine brewers 1.x shipped first (the ones most people own), then every
     * other brewer A to Z under its category.
     */
    fun brewerGroups(all: List<Brewer>): List<BrewerGroup> {
        val commonIds = LegacyIds.BREWER.values.distinct()
        val common = commonIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
        val rest = all.filter { it.id !in commonIds && it.id != "other" }
            .sortedBy { CatalogDocs.brewerTitle(it).lowercase() }
            .groupBy { it.category }
        val order = CatalogDocs.BREWER_CATEGORY_ORDER
        val categories = rest.keys.sortedBy { c -> order.indexOf(c).let { if (it < 0) order.size else it } }
        return buildList {
            if (common.isNotEmpty()) add(BrewerGroup("Most common", common.map { it.id to CatalogDocs.brewerTitle(it) }))
            categories.forEach { c ->
                add(BrewerGroup(CatalogDocs.brewerCategoryLabel(c), rest.getValue(c).map { it.id to CatalogDocs.brewerTitle(it) }))
            }
        }
    }
}

/**
 * Onboarding's state and the writes behind each step. Every Next saves the step it leaves, so
 * leaving half way keeps what was chosen; Skip and "Start brewing" mark setup as done.
 */
class OnboardingViewModel(
    private val repo: CoffeeRepository,
    private val prefs: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Prefilled from what is saved, so "Run setup again" starts from your last answers.
            val s = prefs.settings.first()
            val grinders = repo.allGrinders()
            val lists = withContext(Dispatchers.IO) {
                Triple(
                    Catalog.roasters.map { it.area }.filter { it.isNotBlank() }.distinct().sorted(),
                    Onboarding.brewerGroups(Catalog.brewers),
                    Catalog.roasters.associate { it.id to it.name },
                )
            }
            val water = withContext(Dispatchers.IO) { Catalog.water }
            _state.update {
                it.copy(
                    loaded = true,
                    tempUnit = s.tempUnit,
                    weightUnit = s.weightUnit,
                    homeArea = s.homeArea,
                    areas = lists.first,
                    grinderId = grinders.firstOrNull { g -> g.isDefault && !g.isArchived }?.catalogId,
                    ownedBrewerIds = s.ownedBrewerIds,
                    brewerGroups = lists.second,
                    favouriteRoasterIds = s.favouriteRoasterIds,
                    roasterNames = lists.third,
                    waterId = s.defaultWaterId,
                    water = water,
                )
            }
        }
    }

    fun setTempUnit(unit: String) = _state.update { it.copy(tempUnit = unit) }
    fun setWeightUnit(unit: String) = _state.update { it.copy(weightUnit = unit) }
    fun setHomeArea(area: String) = _state.update { it.copy(homeArea = if (it.homeArea == area) "" else area) }

    fun pickGrinder(catalogId: String) = _state.update { it.copy(grinderId = catalogId, custom = null) }
    fun clearGrinder() = _state.update { it.copy(grinderId = null, custom = null) }
    fun setCustom(custom: CustomGrinder) = _state.update { it.copy(grinderId = null, custom = custom) }

    fun toggleBrewer(id: String) = _state.update {
        it.copy(ownedBrewerIds = if (id in it.ownedBrewerIds) it.ownedBrewerIds - id else it.ownedBrewerIds + id)
    }

    fun setRoasters(ids: Set<String>) = _state.update { it.copy(favouriteRoasterIds = ids) }
    fun removeRoaster(id: String) = _state.update { it.copy(favouriteRoasterIds = it.favouriteRoasterIds - id) }
    fun setWater(id: String) = _state.update { it.copy(waterId = if (it.waterId == id) "" else id) }

    /** Saves this step, then moves on; on the last step it finishes instead. */
    fun next(onFinished: () -> Unit) {
        val now = _state.value
        if (now.busy || !now.loaded) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            // A failed write must not trap anyone on a step: the next visit can save it again.
            runCatching { persist(now) }
            if (now.isLast) {
                finish()
                _state.update { it.copy(busy = false) }
                onFinished()
            } else {
                _state.update { it.copy(busy = false, step = now.step + 1) }
            }
        }
    }

    /** One step back; false on the first step, where Back leaves onboarding. */
    fun back(): Boolean {
        val now = _state.value
        if (now.step == 0) return false
        _state.update { it.copy(step = now.step - 1) }
        return true
    }

    /** Skip: nothing more is saved, and onboarding does not come back on its own. */
    fun skip(onFinished: () -> Unit) {
        viewModelScope.launch {
            finish()
            onFinished()
        }
    }

    private suspend fun persist(s: OnboardingState) {
        when (s.step) {
            Onboarding.MEASURE -> {
                prefs.setTempUnit(s.tempUnit)
                prefs.setWeightUnit(s.weightUnit)
                prefs.setHomeArea(s.homeArea)
            }
            Onboarding.GRINDER -> {
                val existing = repo.allGrinders()
                val entry = s.grinderId?.let { Catalog.grinder(it) }
                val row = when {
                    entry != null -> Onboarding.rowFor(entry, existing)
                    s.custom != null -> Onboarding.rowFor(s.custom, existing)
                    else -> null
                }
                if (row != null) {
                    // Un-archive: picking a grinder you had put away brings it back.
                    val id = repo.upsertGrinder(row.copy(isArchived = false))
                    // Brew setup reads this first, so the choice takes effect on the next brew.
                    prefs.setDefaultGrinder(id)
                    // Your numbers are never overwritten: the Grinders tab asks which to keep.
                    if (entry != null && Onboarding.scaleQuestion(row, entry)) {
                        prefs.addNotice("grinder-scale:${row.uid}")
                    }
                }
            }
            Onboarding.BREWERS -> prefs.setOwnedBrewerIds(s.ownedBrewerIds)
            Onboarding.COFFEE -> {
                prefs.setFavouriteRoasterIds(s.favouriteRoasterIds)
                prefs.setDefaultWaterId(s.waterId)
            }
        }
    }

    private suspend fun finish() {
        prefs.setOnboardingDone(true)
        // Setup has now been seen, so Home's "Make it yours" card has nothing left to offer.
        prefs.setOnboardingAuto(false)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { OnboardingViewModel(this.app.repository, this.app.settings) }
        }
    }
}
