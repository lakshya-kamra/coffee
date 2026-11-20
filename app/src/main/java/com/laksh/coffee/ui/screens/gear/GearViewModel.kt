package com.laksh.coffee.ui.screens.gear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.data.catalog.CatalogLinker
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.data.prefs.SettingsStore
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.CatalogWater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GearState(
    val beans: List<BeanEntity> = emptyList(),
    val grinders: List<GrinderEntity> = emptyList(),
    val settings: Settings = Settings(),
    /** The whole log, only so each bean and grinder card can show its own counts. */
    val logs: List<BrewLogEntity> = emptyList(),
) {
    // Grouped once per emission, so each card's lookup is a map read rather than a scan
    // of the whole log on every recomposition.
    private val logsByBean: Map<Long?, List<BrewLogEntity>> = logs.groupBy { it.beanId }
    private val brewsByGrinder: Map<Long?, Int> = logs.groupingBy { it.grinderId }.eachCount()

    // The method each grinder is brewed with most, for the one range line on its card.
    private val methodByGrinder: Map<Long, BrewMethod?> = logs
        .filter { it.grinderId != null }
        .groupBy { it.grinderId!! }
        .mapValues { (_, l) ->
            GrinderMapping.mostBrewedMethod(
                l.mapNotNull { (Brewers.byId(it.brewerId) ?: Brewers.resolveOrNull(it.brewer))?.method },
            )
        }

    /** Finished bags stay listed but do not count as coffees you are drinking. */
    val activeBeanCount: Int = beans.count { !it.isArchived }

    /** Brews with this bean, and the average of the ones you actually rated (0 = unrated). */
    fun beanStats(id: Long): Pair<Int, Double?> {
        val l = logsByBean[id].orEmpty()
        return l.size to l.filter { it.overall > 0 }.map { it.overall }.average().takeIf { !it.isNaN() }
    }

    fun grinderBrews(id: Long): Int = brewsByGrinder[id] ?: 0

    /** Null when this grinder has no brews yet: the card then shows the pour-over range. */
    fun grinderMethod(id: Long): BrewMethod? = methodByGrinder[id]

    /** A "Check the scale" notice is waiting for this grinder. */
    fun scaleNotice(g: GrinderEntity): Boolean = noticeId(g) in settings.pendingNotices

    companion object {
        fun noticeId(g: GrinderEntity): String = "grinder-scale:${g.uid}"
    }
}

/** The catalog lists the App tab shows, read once off the main thread. */
data class GearLists(
    /** Where roasters are, A to Z: the "where do you buy coffee" chips. */
    val areas: List<String> = emptyList(),
    val water: List<CatalogWater> = emptyList(),
    /** Brewer id to the name a person would say ("Hario V60 02"). */
    val brewerTitles: Map<String, String> = emptyMap(),
)

class GearViewModel(
    private val repo: CoffeeRepository,
    private val prefs: SettingsStore,
    settingsNow: StateFlow<Settings?>,
) : ViewModel() {

    val state: StateFlow<GearState> = combine(
        repo.allBeans,
        repo.activeGrinders,
        settingsNow.filterNotNull(),
        repo.allLogs,
    ) { beans, grinders, settings, logs ->
        GearState(beans, grinders, settings, logs)
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        // Eager and seeded from the hot caches, so the tab has its cards before it is shown.
        SharingStarted.Eagerly,
        GearState(
            beans = repo.allBeansNow.orEmpty(),
            grinders = repo.activeGrindersNow.orEmpty(),
            settings = settingsNow.value ?: Settings(),
            logs = repo.allLogsNow.orEmpty(),
        ),
    )

    val lists: StateFlow<GearLists> = flow {
        emit(
            GearLists(
                areas = Catalog.roasters.map { it.area }.filter { it.isNotBlank() }.distinct().sorted(),
                water = Catalog.water,
                brewerTitles = Catalog.brewers.associate { it.id to CatalogDocs.brewerTitle(it) },
            ),
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, GearLists())

    fun saveBean(bean: BeanEntity) = viewModelScope.launch { repo.upsertBean(bean) }
    fun deleteBean(bean: BeanEntity) = viewModelScope.launch { repo.deleteBean(bean) }
    fun archiveBean(bean: BeanEntity) =
        viewModelScope.launch { repo.upsertBean(bean.copy(isArchived = !bean.isArchived)) }

    fun saveGrinder(g: GrinderEntity) = viewModelScope.launch {
        repo.upsertGrinder(g)
        // A row saved from the editor is what the person just looked at. If it now agrees with
        // the catalog (or is no longer linked), there is nothing left to ask about.
        val c = Catalog.grinder(g.catalogId)
        if (c == null || !CatalogLinker.scaleDiffers(g, c)) prefs.removeNotice(GearState.noticeId(g))
    }

    fun deleteGrinder(g: GrinderEntity) = viewModelScope.launch {
        repo.deleteGrinder(g)
        prefs.removeNotice(GearState.noticeId(g))
    }

    /** "Use these numbers": only the scale is copied; logged settings keep their numbers. */
    fun useCatalogScale(g: GrinderEntity) = viewModelScope.launch {
        val c = Catalog.grinder(g.catalogId)
        if (c != null) repo.upsertGrinder(GrinderMapping.withScale(g, c))
        prefs.removeNotice(GearState.noticeId(g))
    }

    /** "Keep mine": the row stays as typed and the question is not asked again. */
    fun keepOwnScale(g: GrinderEntity) = viewModelScope.launch { prefs.removeNotice(GearState.noticeId(g)) }

    fun setDarkMode(mode: String) = viewModelScope.launch { prefs.setDarkMode(mode) }
    fun setHaptics(on: Boolean) = viewModelScope.launch { prefs.setHaptics(on) }
    fun setKeepScreenOn(on: Boolean) = viewModelScope.launch { prefs.setKeepScreenOn(on) }
    fun setName(name: String) = viewModelScope.launch { prefs.setName(name) }

    fun setTempUnit(unit: String) = viewModelScope.launch { prefs.setTempUnit(unit) }
    fun setWeightUnit(unit: String) = viewModelScope.launch { prefs.setWeightUnit(unit) }
    fun setHomeArea(area: String) = viewModelScope.launch { prefs.setHomeArea(area) }
    fun setOwnedBrewers(ids: Set<String>) = viewModelScope.launch { prefs.setOwnedBrewerIds(ids) }
    fun setWater(id: String) = viewModelScope.launch { prefs.setDefaultWaterId(id) }

    // v4 §6.1: each of these stands alone, whatever onboarding pre-set.
    fun setExperience(value: String) = viewModelScope.launch { prefs.setExperience(value) }
    fun setHasScale(on: Boolean) = viewModelScope.launch { prefs.setHasScale(on) }
    fun setReviewMode(mode: String) = viewModelScope.launch { prefs.setReviewMode(mode) }
    fun setSound(on: Boolean) = viewModelScope.launch { prefs.setSound(on) }
    fun setBuysGround(on: Boolean) = viewModelScope.launch { prefs.setBuysGround(on) }

    /**
     * Unsaved inline edits by row key ("bean:12", "grinder:new"), kept here so a rotation or a
     * resize across 840 never loses typed text (v4 §2.10).
     */
    val drafts = mutableMapOf<String, Any>()

    fun saveBeanThen(bean: BeanEntity, onSaved: (Long) -> Unit) = viewModelScope.launch {
        val id = repo.upsertBean(bean)
        onSaved(if (bean.id != 0L) bean.id else id)
    }

    fun saveGrinderThen(g: GrinderEntity, onSaved: (Long) -> Unit) = viewModelScope.launch {
        val id = repo.upsertGrinder(g)
        val c = Catalog.grinder(g.catalogId)
        if (c == null || !CatalogLinker.scaleDiffers(g, c)) prefs.removeNotice(GearState.noticeId(g))
        onSaved(if (g.id != 0L) g.id else id)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { GearViewModel(this.app.repository, this.app.settings, this.app.settingsState) }
        }
    }
}
