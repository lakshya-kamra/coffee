package com.laksh.coffee.domain.recipes

import com.laksh.coffee.domain.Recipe
import com.laksh.coffee.domain.catalog.Catalog

/**
 * Curated recipes from well-known coffee people, loaded from shared/recipes.json (one file for
 * the phone and the web app).
 *
 * Every entry carries a [Recipe.sourceNote] saying how faithful the numbers are, a fidelity
 * (published, adapted, community) and its sources. The merge rules for adding recipes are in
 * docs/plan/android-spec.md §2.7: existing ids never change numbers unless an audit proved a
 * published recipe misquoted its author.
 */
object ExpertRecipes {

    val all: List<Recipe> get() = Catalog.recipes

    /** Synchronous: RecipeDetailViewModel seeds its first frame from it. */
    fun byId(id: String): Recipe? = Catalog.recipe(id)

    val authors: List<String> get() = all.map { it.author }.distinct().sorted()

    /** The 14 ids shipped in 1.x. Old logs point at them; they must resolve forever. */
    val LEGACY_IDS: List<String> = listOf(
        "hoffmann-v60", "kasuya-46", "hoffmann-french-press", "hoffmann-aeropress",
        "hedrick-aeropress", "rao-v60", "hedrick-espresso", "perger-v60", "gagne-v60", "wendelboe-chemex",
        "onyx-kalita", "eckroth-v60", "switch-hybrid", "hoffmann-moka",
    )
}
