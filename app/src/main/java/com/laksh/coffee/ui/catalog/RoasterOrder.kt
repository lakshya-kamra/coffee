package com.laksh.coffee.ui.catalog

/** What the roaster order needs from a catalog roaster. */
data class RoasterLike(
    val id: String,
    val name: String,
    val area: String,
    val mainstream: Boolean = false,
    val regions: List<String> = emptyList(),
)

data class RoasterGroup(val name: String, val ids: List<String>)

/**
 * The roaster picker's order with an empty query (v4 §4.7): your favourites and your region first,
 * then supermarket brands, then specialty roasters A-Z. Pure; the web twin is
 * features/catalog-picker/roasterOrder.ts and gives the same groups.
 */
object RoasterOrder {
    const val FAVOURITES = "Your roasters"
    const val MAINSTREAM = "Supermarket brands"
    const val SPECIALTY = "Specialty roasters"

    /** Sold where you buy coffee: based there, or a brand stocked there. */
    fun inRegion(r: RoasterLike, homeArea: String): Boolean =
        homeArea.isNotEmpty() && (r.area == homeArea || homeArea in r.regions)

    private val byName = compareBy<RoasterLike, String>(String.CASE_INSENSITIVE_ORDER) { it.name }

    /**
     * Favourites stay listed in their own group too (as owned brewers do), so the list reads the
     * same whether or not you starred anything. Every other roaster appears exactly once. Empty
     * groups are left out.
     */
    fun groups(roasters: List<RoasterLike>, homeArea: String, favourites: Set<String>): List<RoasterGroup> {
        val favs = roasters.filter { it.id in favourites }.sortedWith(byName)
        val region = roasters.filter { inRegion(it, homeArea) }.sortedWith(byName)
        val inReg = region.map { it.id }.toSet()
        val mainstream = roasters.filter { it.id !in inReg && it.mainstream }.sortedWith(byName)
        val specialty = roasters.filter { it.id !in inReg && !it.mainstream }.sortedWith(byName)
        return listOf(
            RoasterGroup(FAVOURITES, favs.map { it.id }),
            RoasterGroup(homeArea, region.map { it.id }),
            RoasterGroup(MAINSTREAM, mainstream.map { it.id }),
            RoasterGroup(SPECIALTY, specialty.map { it.id }),
        ).filter { it.ids.isNotEmpty() }
    }
}
