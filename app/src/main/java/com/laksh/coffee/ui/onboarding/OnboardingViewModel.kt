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
import com.laksh.coffee.domain.FirstBrew
import com.laksh.coffee.domain.FirstBrewPick
import com.laksh.coffee.domain.FirstBrewSettings
import com.laksh.coffee.domain.Temperatures
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.CatalogGrinder
import com.laksh.coffee.domain.catalog.CatalogWater
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

/** One of the plain water answers offered before the full list (v4 §4.1). */
data class PlainWater(val label: String, val waterId: String)

/** What an experience answer pre-sets (v4 §4.1, D8); null leaves a setting as it is. */
data class Presets(val reviewMode: String, val soundCues: Boolean? = null, val hasScale: Boolean? = null)

/** The settings writes onboarding makes, so they can be tested without DataStore. */
interface OnboardingPrefs {
    suspend fun setExperience(value: String)
    suspend fun setReviewMode(mode: String)
    suspend fun setSound(on: Boolean)
    suspend fun setHasScale(on: Boolean)
    suspend fun setBuysGround(on: Boolean)
    suspend fun setTempUnit(unit: String)
    suspend fun setWeightUnit(unit: String)
    suspend fun setHomeArea(area: String)
}

data class OnboardingState(
    /** False until settings and the catalog lists are read; the first step waits for it. */
    val loaded: Boolean = false,
    val step: Int = 0,
    /** "" until answered, then "new" | "recipes" | "tinker". */
    val experience: String = "",
    /** Null until the scale question has an answer (preselected "No" for someone new). */
    val hasScale: Boolean? = null,
    val tempUnit: String = "c",
    val weightUnit: String = "g",
    val homeArea: String = "",
    val areas: List<String> = emptyList(),
    /** "I buy ground coffee": no grinder is saved and grind settings are skipped. */
    val buysGround: Boolean = false,
    /** The catalog grinder chosen, or null. The blade grinder is the catalog's "blade-grinder". */
    val grinderId: String? = null,
    val custom: CustomGrinder? = null,
    val ownedBrewerIds: Set<String> = emptySet(),
    val brewerGroups: List<BrewerGroup> = emptyList(),
    val favouriteRoasterIds: Set<String> = emptySet(),
    /** Roaster id to name, for the chosen chips. */
    val roasterNames: Map<String, String> = emptyMap(),
    val waterId: String = "",
    val water: List<CatalogWater> = emptyList(),
    /** The last step's pick, worked out when it is reached. */
    val firstBrew: FirstBrewPick? = null,
    /** A step is being saved; Next waits. */
    val busy: Boolean = false,
) {
    val isLast: Boolean get() = step == Onboarding.STEPS - 1
}

/** What onboarding writes, kept pure so the no-duplicate rule can be tested. */
object Onboarding {

    const val STEPS = 6
    const val YOU = 0
    const val MEASURE = 1
    const val GRINDER = 2
    const val BREWERS = 3
    const val COFFEE = 4
    const val DONE = 5

    const val BLADE_GRINDER_ID = "blade-grinder"

    /** Step 1's three answers: id, title, body (v4 §4.1). */
    val EXPERIENCE_CHOICES: List<Triple<String, String, String>> = listOf(
        Triple("new", "I'm new to this", "Keep it simple. Explain the words. No scale needed."),
        Triple("recipes", "I follow recipes", "Show me good recipes and time each step."),
        Triple("tinker", "I tinker", "Give me every number: grind, flow, TDS."),
    )

    /**
     * What an answer pre-sets. Each stays editable on its own in Gear › Settings. hasScale is only
     * the measure step's preselected answer for someone new; that step saves it.
     */
    fun presetsFor(experience: String): Presets = when (experience) {
        "new" -> Presets(reviewMode = "quick", soundCues = true, hasScale = false)
        "tinker" -> Presets(reviewMode = "detailed")
        else -> Presets(reviewMode = "quick")
    }

    /** Step 1's writes: the answer and its presets, never the scale (asked next). */
    suspend fun saveYou(prefs: OnboardingPrefs, experience: String) {
        if (experience.isBlank()) return
        val p = presetsFor(experience)
        prefs.setExperience(experience)
        prefs.setReviewMode(p.reviewMode)
        p.soundCues?.let { prefs.setSound(it) }
    }

    /** Step 2's writes. A skipped scale question keeps the stored answer. */
    suspend fun saveMeasure(prefs: OnboardingPrefs, s: OnboardingState) {
        prefs.setTempUnit(s.tempUnit)
        prefs.setWeightUnit(s.weightUnit)
        prefs.setHomeArea(s.homeArea)
        s.hasScale?.let { prefs.setHasScale(it) }
    }

    /** Brewers step "Most common", in this order (v4 §4.1); the rest follow A-Z by category. */
    val COMMON_BREWER_IDS: List<String> = listOf(
        "drip-machine",
        "french-press",
        "hario-v60-02",
        "aeropress",
        "moka-pot",
        "espresso-machine",
        "drip-bag",
        "south-indian-filter",
        "phin",
        "cold-brew-jar",
    )

    /** Water: four plain answers first; "Not sure" stores no water. */
    val PLAIN_WATERS: List<PlainWater> = listOf(
        PlainWater("Tap water", "tap-unfiltered"),
        PlainWater("Filtered tap water", "tap-filtered"),
        PlainWater("Bottled spring water", "bottled-spring"),
        PlainWater("Not sure", ""),
    )

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

    /** Brewer chips: the most common ten in the §4.1 order, then every other brewer A to Z under its category. */
    fun brewerGroups(all: List<Brewer>): List<BrewerGroup> {
        val common = COMMON_BREWER_IDS.mapNotNull { id -> all.firstOrNull { it.id == id } }
        val commonIds = common.map { it.id }.toSet()
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

/** The real writes, through DataStore. */
private class StorePrefs(private val s: SettingsStore) : OnboardingPrefs {
    override suspend fun setExperience(value: String) { s.setExperience(value) }
    override suspend fun setReviewMode(mode: String) { s.setReviewMode(mode) }
    override suspend fun setSound(on: Boolean) { s.setSound(on) }
    override suspend fun setHasScale(on: Boolean) { s.setHasScale(on) }
    override suspend fun setBuysGround(on: Boolean) { s.setBuysGround(on) }
    override suspend fun setTempUnit(unit: String) { s.setTempUnit(unit) }
    override suspend fun setWeightUnit(unit: String) { s.setWeightUnit(unit) }
    override suspend fun setHomeArea(area: String) { s.setHomeArea(area) }
}

/**
 * Onboarding's state and the writes behind each step. Every Next saves the step it leaves, so
 * leaving half way keeps what was chosen; Skip and the last step's buttons mark setup as done.
 * The step lives here, so a rotation or a fold keeps it (§2.10).
 */
class OnboardingViewModel(
    private val repo: CoffeeRepository,
    private val prefs: SettingsStore,
) : ViewModel() {

    private val writes: OnboardingPrefs = StorePrefs(prefs)
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
            // A first setup takes the unit the region uses (v4 §4.15); a rerun keeps the stored one.
            val temp = if (s.onboardingDone) s.tempUnit else Temperatures.defaultUnit()
            val defaultGrinder = grinders.firstOrNull { g -> g.isDefault && !g.isArchived }
            _state.update {
                it.copy(
                    loaded = true,
                    experience = s.experience,
                    hasScale = if (s.onboardingDone || s.experience.isNotBlank()) s.hasScale else null,
                    tempUnit = temp,
                    weightUnit = s.weightUnit,
                    homeArea = s.homeArea,
                    areas = lists.first,
                    buysGround = s.buysGround,
                    grinderId = defaultGrinder?.catalogId,
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

    fun setExperience(e: String) = _state.update {
        // "I'm new" preselects "No" on the scale question; the others leave it for you.
        val preset = Onboarding.presetsFor(e).hasScale
        it.copy(experience = e, hasScale = if (it.hasScale == null || it.experience != e) preset ?: it.hasScale else it.hasScale)
    }

    fun setHasScale(on: Boolean) = _state.update { it.copy(hasScale = on) }
    fun setTempUnit(unit: String) = _state.update { it.copy(tempUnit = unit) }
    fun setWeightUnit(unit: String) = _state.update { it.copy(weightUnit = unit) }
    fun setHomeArea(area: String) = _state.update { it.copy(homeArea = if (it.homeArea == area) "" else area) }

    fun setBuysGround() = _state.update { it.copy(buysGround = true, grinderId = null, custom = null) }
    fun pickGrinder(catalogId: String) = _state.update { it.copy(grinderId = catalogId, custom = null, buysGround = false) }
    fun clearGrinder() = _state.update { it.copy(grinderId = null, custom = null, buysGround = false) }
    fun setCustom(custom: CustomGrinder) = _state.update { it.copy(grinderId = null, custom = custom, buysGround = false) }

    fun toggleBrewer(id: String) = _state.update {
        it.copy(ownedBrewerIds = if (id in it.ownedBrewerIds) it.ownedBrewerIds - id else it.ownedBrewerIds + id)
    }

    fun setRoasters(ids: Set<String>) = _state.update { it.copy(favouriteRoasterIds = ids) }
    fun removeRoaster(id: String) = _state.update { it.copy(favouriteRoasterIds = it.favouriteRoasterIds - id) }
    fun setWater(id: String) = _state.update { it.copy(waterId = if (it.waterId == id) "" else id) }

    /** Saves this step, then moves on. The last step's buttons call [finish] instead. */
    fun next() {
        val now = _state.value
        if (now.busy || !now.loaded || now.isLast) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            // A failed write must not trap anyone on a step: the next visit can save it again.
            runCatching { persist(now) }
            val pick = if (now.step + 1 == Onboarding.DONE) runCatching { firstBrew(_state.value) }.getOrNull() else null
            _state.update { it.copy(busy = false, step = now.step + 1, firstBrew = pick ?: it.firstBrew) }
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
            markDone()
            onFinished()
        }
    }

    /** "Start my first brew" and "Go to Today": setup is done either way. */
    fun finish(onFinished: () -> Unit) {
        viewModelScope.launch {
            markDone()
            onFinished()
        }
    }

    /** The first brew for what was just answered (v4 §4.6), read from the saved answers. */
    private suspend fun firstBrew(s: OnboardingState): FirstBrewPick = withContext(Dispatchers.Default) {
        val recipes = repo.recipes.first()
        val grinders = repo.allGrinders().count { !it.isArchived }
        FirstBrew.pick(
            recipes,
            FirstBrewSettings(
                ownedBrewerIds = s.ownedBrewerIds,
                hasScale = s.hasScale ?: true,
                buysGround = s.buysGround,
                experience = s.experience,
            ),
            grinderCount = if (s.buysGround) 0 else grinders,
        )
    }

    private suspend fun persist(s: OnboardingState) {
        when (s.step) {
            Onboarding.YOU -> Onboarding.saveYou(writes, s.experience)
            Onboarding.MEASURE -> Onboarding.saveMeasure(writes, s)
            Onboarding.GRINDER -> {
                if (s.buysGround) {
                    writes.setBuysGround(true)
                    return
                }
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
                    writes.setBuysGround(false)
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

    private suspend fun markDone() {
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
