package com.laksh.coffee.data.backup

import com.laksh.coffee.data.catalog.CatalogLinker
import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.domain.catalog.CatalogGrinder
import com.laksh.coffee.domain.catalog.CatalogOrigin
import com.laksh.coffee.domain.catalog.CatalogRoaster

/**
 * Catalog linking for rows that came from a 1.x export, which was written before the catalog
 * existed. Same rules as the one-off linking at first start (StartupTasks): only ids are
 * written, never a number or a word the person entered, and a grinder whose scale disagrees
 * with a well-sourced catalog entry gets a notice instead of a change.
 *
 * A v2 backup is not linked: a row saved there without a catalog id was left that way on purpose.
 * It is still asked about ([scaleNotices]): the question is kept on the phone, not in the file,
 * so a grinder that left the old phone unanswered would otherwise never be asked about here.
 */
object LegacyLinking {

    class Linked(
        /** Only the rows that changed. */
        val grinders: List<GrinderEntity>,
        val beans: List<BeanEntity>,
        /** "grinder-scale:<uid>" for each linked grinder whose scale differs from the catalog's. */
        val notices: List<String>,
    )

    fun link(
        grinders: List<GrinderEntity>,
        beans: List<BeanEntity>,
        catalogGrinders: List<CatalogGrinder>,
        roasters: List<CatalogRoaster>,
        origins: List<CatalogOrigin>,
    ): Linked {
        val notices = ArrayList<String>()
        val linkedGrinders = grinders.filter { it.catalogId == null }.mapNotNull { row ->
            val match = CatalogLinker.matchGrinder(row, catalogGrinders) ?: return@mapNotNull null
            if (CatalogLinker.scaleDiffers(row, match)) notices += "grinder-scale:${row.uid}"
            row.copy(catalogId = match.id)
        }
        val linkedBeans = beans.filter { it.roasterId == null || it.originId == null }
            .mapNotNull { CatalogLinker.linkBean(it, roasters, origins) }
        return Linked(linkedGrinders, linkedBeans, notices)
    }

    /**
     * "grinder-scale:<uid>" for each grinder that is linked to a catalog entry and counts its
     * dial differently (a 1.x K-Ultra row says 10 clicks a turn, the catalog 100). The Grinders
     * tab then offers "Use these numbers" or "Keep mine"; nothing is changed until one is tapped.
     */
    fun scaleNotices(grinders: List<GrinderEntity>, catalog: (String?) -> CatalogGrinder?): List<String> =
        grinders.mapNotNull { row ->
            val entry = catalog(row.catalogId) ?: return@mapNotNull null
            if (CatalogLinker.scaleDiffers(row, entry)) "grinder-scale:${row.uid}" else null
        }
}
