package com.laksh.coffee.ui.screens.history

import com.laksh.coffee.data.db.entity.BrewLogEntity
import com.laksh.coffee.domain.Brewers
import com.laksh.coffee.domain.LogFilter
import com.laksh.coffee.domain.LogQuery
import com.laksh.coffee.domain.LogSort
import com.laksh.coffee.ui.components.beanShortName

/** One choice in a filter chip's menu: the value the filter stores and the words it shows. */
data class FilterOption(val key: String, val label: String)

/** The toggle chips (v4 §4.11), in the order they read. */
enum class LogChip(val label: String) { Favourites("Favourites"), WithPhotos("With photos"), Rated8("Rated 8+") }

/** The chips that open a menu, in the order they read after the toggles. */
enum class LogMenu(val label: String) { Coffee("Coffee"), Recipe("Recipe"), Brewer("Brewer") }

/**
 * What the Log's search and filter row shows and does (v4 §4.11). Pure, so the rules are tested
 * without a screen; matching itself is CORE's [LogQuery].
 */
object LogFilterUi {
    const val PLACEHOLDER = "Search coffee, recipe, notes"

    /** How long J/K must rest before the pane follows the cursor (§2.6). */
    const val PANE_SETTLE_MS = 120L
    const val NO_RESULTS = "No brews match."
    const val CLEAR = "Clear filters"
    const val SORT_NEWEST = "Newest"
    const val SORT_BEST = "Best rated"

    fun isOn(f: LogFilter, chip: LogChip): Boolean = when (chip) {
        LogChip.Favourites -> f.favourites
        LogChip.WithPhotos -> f.withPhotos
        LogChip.Rated8 -> f.rated8
    }

    fun toggle(f: LogFilter, chip: LogChip): LogFilter = when (chip) {
        LogChip.Favourites -> f.copy(favourites = !f.favourites)
        LogChip.WithPhotos -> f.copy(withPhotos = !f.withPhotos)
        LogChip.Rated8 -> f.copy(rated8 = !f.rated8)
    }

    fun selected(f: LogFilter, menu: LogMenu): String? = when (menu) {
        LogMenu.Coffee -> f.coffee
        LogMenu.Recipe -> f.recipe
        LogMenu.Brewer -> f.brewer
    }

    /** Picks [key] for [menu]; picking the chosen one again (or null, "Any") clears it. */
    fun pick(f: LogFilter, menu: LogMenu, key: String?): LogFilter {
        val next = key?.takeIf { it != selected(f, menu) }
        return when (menu) {
            LogMenu.Coffee -> f.copy(coffee = next)
            LogMenu.Recipe -> f.copy(recipe = next)
            LogMenu.Brewer -> f.copy(brewer = next)
        }
    }

    /** Every filter off and the search empty; the sort is a view choice and stays. */
    fun cleared(f: LogFilter): LogFilter = LogFilter(sort = f.sort)

    /** The choices a menu offers: only what the log holds, most brewed first, then A to Z. */
    fun options(logs: List<BrewLogEntity>, menu: LogMenu): List<FilterOption> {
        val keyed: List<Pair<String, String>> = logs.mapNotNull { l ->
            when (menu) {
                LogMenu.Coffee -> l.beanLabel.takeIf { it.isNotBlank() }?.let { it to beanShortName(it).ifBlank { it } }
                LogMenu.Recipe -> LogQuery.recipeKey(l).takeIf { it.isNotBlank() }?.let { it to l.recipeName.ifBlank { it } }
                LogMenu.Brewer -> LogQuery.brewerKey(l).takeIf { it.isNotBlank() }?.let { key ->
                    key to (Brewers.byId(l.brewerId)?.shortName ?: Brewers.resolveOrNull(l.brewer)?.shortName ?: l.brewer.ifBlank { key })
                }
            }
        }
        val counts = keyed.groupingBy { it.first }.eachCount()
        val labels = keyed.toMap()
        return counts.keys
            .sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { labels.getValue(it).lowercase() })
            .map { FilterOption(it, labels.getValue(it)) }
    }

    /** "Coffee ▾" until one is chosen, then the choice's own name. */
    fun menuLabel(menu: LogMenu, chosen: String?, options: List<FilterOption>): String {
        if (chosen == null) return "${menu.label} ▾"
        return (options.firstOrNull { it.key == chosen }?.label ?: chosen) + " ▾"
    }

    fun sortLabel(sort: LogSort): String = if (sort == LogSort.Best) SORT_BEST else SORT_NEWEST

    /** "12 brews" while anything narrows the list; nothing when the whole log shows. */
    fun resultLine(shown: Int, f: LogFilter): String? = if (f.isActive) LogQuery.countLabel(shown) else null

    /** The no-results state shows only when a filter hid every brew there is. */
    fun noResults(total: Int, shown: Int, f: LogFilter): Boolean = total > 0 && shown == 0 && f.isActive

    /**
     * The brew a J/K or arrow press moves to, from [current] (the selected or newest brew).
     * Clamped at both ends; null when the list is empty.
     */
    fun <T> step(ids: List<T>, current: T?, by: Int): T? {
        if (ids.isEmpty()) return null
        val at = ids.indexOf(current)
        if (at < 0) return ids.first()
        return ids[(at + by).coerceIn(0, ids.lastIndex)]
    }
}
