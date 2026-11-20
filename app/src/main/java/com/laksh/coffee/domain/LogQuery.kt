package com.laksh.coffee.domain

import com.laksh.coffee.core.counted
import com.laksh.coffee.data.db.entity.BrewLogEntity
import java.text.Normalizer
import java.util.Locale

/** Log sort order (v4 §4.11). */
enum class LogSort { Newest, Best }

data class LogFilter(
    val query: String = "",
    val favourites: Boolean = false,
    val withPhotos: Boolean = false,
    /** Overall 8 or more. */
    val rated8: Boolean = false,
    /** A beanLabel, or null for any coffee. */
    val coffee: String? = null,
    /** A [LogQuery.recipeKey], or null for any recipe. */
    val recipe: String? = null,
    /** A [LogQuery.brewerKey], or null for any brewer. */
    val brewer: String? = null,
    val sort: LogSort = LogSort.Newest,
) {
    val isActive: Boolean
        get() = query.isNotBlank() || favourites || withPhotos || rated8 || coffee != null || recipe != null || brewer != null
}

/**
 * Log search and filters (v4 §4.11). Pure; web twin: domain/core/logQuery.ts, same matches
 * (shared/fixtures/domain-v4-cases.json).
 */
object LogQuery {

    private val MARKS = Regex("\\p{M}+")
    private val SPACE = Regex("\\s+")

    /** Lower case with accents removed: "Café" and "cafe" are the same word. */
    fun fold(text: String): String = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /**
     * Every word of the query appears somewhere in the brew's recipe, coffee, grinder, brewer,
     * notes, next time or descriptors. An empty query matches everything.
     */
    fun matches(
        query: String,
        recipeName: String,
        recipeAuthor: String,
        beanLabel: String,
        grinderLabel: String,
        brewer: String,
        notes: String,
        nextTime: String,
        descriptors: List<String>,
    ): Boolean {
        val words = fold(query).split(SPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = fold((listOf(recipeName, recipeAuthor, beanLabel, grinderLabel, brewer, notes, nextTime) + descriptors).joinToString("\n"))
        return words.all { it in hay }
    }

    fun matches(log: BrewLogEntity, query: String): Boolean = matches(
        query, log.recipeName, log.recipeAuthor, log.beanLabel, log.grinderLabel, log.brewer, log.notes, log.nextTime, log.descriptors,
    )

    /** The recipe id, or its name for a brew that has none (a deleted one-off). */
    fun recipeKey(log: BrewLogEntity): String = log.recipeId.trim().ifEmpty { log.recipeName }

    /** The brewer id, or its label for a brew from before brewer ids. */
    fun brewerKey(log: BrewLogEntity): String = log.brewerId ?: log.brewer

    /** The brews that pass, sorted. [photoBrewIds] holds the ids of brews with at least one photo. */
    fun filter(brews: List<BrewLogEntity>, f: LogFilter, photoBrewIds: Set<Long> = emptySet()): List<BrewLogEntity> =
        brews.filter { b ->
            (!f.favourites || b.isFavourite) &&
                (!f.withPhotos || b.id in photoBrewIds) &&
                (!f.rated8 || b.overall >= 8) &&
                (f.coffee == null || b.beanLabel == f.coffee) &&
                (f.recipe == null || recipeKey(b) == f.recipe) &&
                (f.brewer == null || brewerKey(b) == f.brewer) &&
                matches(b, f.query)
        }.sortedWith(
            if (f.sort == LogSort.Best) compareByDescending<BrewLogEntity> { it.overall }.thenByDescending { it.startedAt }
            else compareByDescending { it.startedAt },
        )

    /** "12 brews", "1 brew". */
    fun countLabel(n: Int): String = counted(n, "brew")
}
