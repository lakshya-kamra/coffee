package com.laksh.coffee.domain

/** What [FirstBrew.pick] needs to know about the person. */
data class FirstBrewSettings(
    val ownedBrewerIds: Collection<String>,
    val hasScale: Boolean,
    val buysGround: Boolean,
    /** "" | "new" | "recipes" | "tinker" */
    val experience: String,
)

data class FirstBrewPick(
    /** Null when no owned brewer has a recipe (or there are no recipes). */
    val recipe: Recipe?,
    /** Owned brewer ids whose family has no recipe: "We don't have a tested recipe for your Cezve yet." */
    val noRecipeFor: List<String>,
)

/**
 * A guided first brew (v4 §4.6): the one recipe to suggest to someone with no brews yet. Web
 * twin: domain/core/firstBrew.ts, same picks (shared/fixtures/domain-v4-cases.json).
 */
object FirstBrew {

    /** The needs this person cannot meet today. */
    fun unmetNeeds(needs: List<String>, s: FirstBrewSettings, grinderCount: Int): List<String> = needs.filter { n ->
        when (n) {
            RecipeNeeds.SCALE -> !s.hasScale
            RecipeNeeds.GRINDER -> s.buysGround || grinderCount == 0
            // Not asked in onboarding (§9 item 10): assumed missing for someone new to this.
            RecipeNeeds.GOOSENECK, RecipeNeeds.THERMOMETER -> s.experience == "new"
            else -> false
        }
    }

    /**
     * Owned family first (never another family's recipe), then fewest unmet needs, then level
     * (easy, medium, advanced; none counts as medium), then catalog order. With no owned brewer
     * that has a family (none, or only a Melodrip) every recipe is a candidate. [grinderCount]
     * counts the person's grinders that are not archived.
     */
    fun pick(
        recipes: List<Recipe>,
        settings: FirstBrewSettings,
        grinderCount: Int,
        byId: (String) -> Brewer? = Brewers::byId,
    ): FirstBrewPick {
        val families = BrewerFamilies.owned(settings.ownedBrewerIds, byId)
        // Owning only an accessory (or nothing) is the same as not saying.
        val owns = families.isNotEmpty()
        val candidates = if (owns) recipes.filter { BrewerFamilies.of(it.brewer) in families } else recipes
        val noRecipeFor = if (!owns) emptyList() else settings.ownedBrewerIds.filter { id ->
            val b = byId(id) ?: return@filter false
            val f = BrewerFamilies.of(b)
            // An accessory or "other" never had recipes of its own to miss.
            f !in BrewerFamilies.NO_MATCH && recipes.none { BrewerFamilies.of(it.brewer) == f }
        }
        val best = candidates.withIndex().minWithOrNull(
            compareBy<IndexedValue<Recipe>>(
                { unmetNeeds(it.value.needs, settings, grinderCount).size },
                { RecipeLevels.rank(it.value.level) },
                { it.index },
            ),
        )
        return FirstBrewPick(best?.value, noRecipeFor)
    }
}
