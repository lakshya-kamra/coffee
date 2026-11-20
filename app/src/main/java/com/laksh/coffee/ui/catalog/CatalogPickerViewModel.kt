package com.laksh.coffee.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.data.catalog.CatalogRepository
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogKind
import com.laksh.coffee.domain.catalog.SearchDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One line of the picker's list: a group header or an entry. [key] is unique in the list. */
sealed interface PickerRow {
    val key: String

    data class Header(val title: String) : PickerRow {
        override val key: String get() = "h:$title"
    }

    data class Item(val doc: SearchDoc, val pinned: Boolean = false) : PickerRow {
        override val key: String get() = if (pinned) "p:${doc.id}" else doc.id
    }
}

data class PickerState(
    val loading: Boolean = true,
    /** The query these rows answer (trimmed), which can lag the text field by the debounce. */
    val query: String = "",
    val rows: List<PickerRow> = emptyList(),
    /** id → title for everything in this catalog, for chips and summaries. */
    val titles: Map<String, String> = emptyMap(),
) {
    val matches: Int = rows.count { it is PickerRow.Item }
}

/**
 * Searches one catalog list for the picker. The index is built once per process by
 * [CatalogRepository]; searching and grouping run on Dispatchers.Default, and the text field
 * never waits on them.
 */
class CatalogPickerViewModel(
    private val kind: CatalogKind,
    private val catalog: CatalogRepository,
    settings: StateFlow<Settings?>,
) : ViewModel() {

    private val query = MutableStateFlow("")

    private data class Prefs(val homeArea: String, val favourites: Set<String>, val ownedBrewers: Set<String>)

    @OptIn(FlowPreview::class)
    val state: StateFlow<PickerState> = combine(
        // Clearing the field answers at once; typing waits 80 ms for the next key.
        query.debounce { if (it.isBlank()) 0L else 80L },
        settings.map { Prefs(it?.homeArea.orEmpty(), it?.favouriteRoasterIds.orEmpty(), it?.ownedBrewerIds.orEmpty()) }
            .distinctUntilChanged(),
    ) { q, prefs -> build(q.trim(), prefs) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickerState())

    fun setQuery(text: String) {
        query.value = text
    }

    private suspend fun build(q: String, prefs: Prefs): PickerState {
        val index = catalog.index(kind)
        val titles = index.docs.associate { it.id to it.title }
        if (q.isNotEmpty()) {
            // A search is one ranked list: headers would break the ranking apart.
            return PickerState(false, q, index.search(q).map { PickerRow.Item(it.doc) }, titles)
        }
        val docs = index.browse.map { it.doc }
        val rows = ArrayList<PickerRow>(docs.size + 16)
        val ordered = docs
        if (kind == CatalogKind.Roaster) {
            // v4 §4.7: favourites and your region, then supermarket brands, then specialty A-Z.
            val byId = docs.associateBy { it.id }
            val roasters = Catalog.roasters.filter { it.id in byId }.map {
                RoasterLike(it.id, it.name, it.area, it.mainstream, it.regions)
            }
            RoasterOrder.groups(roasters, prefs.homeArea, prefs.favourites).forEach { g ->
                rows += PickerRow.Header(g.name)
                val pinned = g.name == RoasterOrder.FAVOURITES
                g.ids.forEach { id -> byId[id]?.let { rows += PickerRow.Item(it, pinned = pinned) } }
            }
            // Anything the catalog index has that the roaster list does not (never expected).
            val listed = roasters.map { it.id }.toSet()
            docs.filter { it.id !in listed }.forEach { rows += PickerRow.Item(it) }
            return PickerState(false, "", rows, titles)
        }
        if (kind == CatalogKind.Brewer) {
            // The brewers you own are the ones you are most likely to mean.
            val owned = docs.filter { it.id in prefs.ownedBrewers }
            if (owned.isNotEmpty()) {
                rows += PickerRow.Header("Your brewers")
                owned.forEach { rows += PickerRow.Item(it, pinned = true) }
            }
        }
        var group: String? = null
        for (d in ordered) {
            if (d.group != group) {
                group = d.group
                if (d.group.isNotBlank()) rows += PickerRow.Header(d.group)
            }
            rows += PickerRow.Item(d)
        }
        return PickerState(false, "", rows, titles)
    }

    companion object {
        fun factory(kind: CatalogKind): ViewModelProvider.Factory = viewModelFactory {
            initializer { CatalogPickerViewModel(kind, this.app.catalog, this.app.settingsState) }
        }
    }
}
