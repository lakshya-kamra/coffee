package com.laksh.coffee.catalog

import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.CatalogKind
import com.laksh.coffee.domain.catalog.CatalogSearch
import com.laksh.coffee.domain.catalog.SearchDoc
import com.laksh.coffee.domain.catalog.SearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * People misspell grinders. These are the searches the spec says must land (android-spec §7.1),
 * run against the shipped catalog; a case is skipped only when its target is not in the catalog.
 */
class CatalogSearchTest {

    private val grinders by lazy { SearchIndex(CatalogDocs.build(CatalogKind.Grinder)) }
    private val roasters by lazy { SearchIndex(CatalogDocs.build(CatalogKind.Roaster)) }
    private val varietals by lazy { SearchIndex(CatalogDocs.build(CatalogKind.Varietal)) }
    private val origins by lazy { SearchIndex(CatalogDocs.build(CatalogKind.Origin)) }
    private val brewers by lazy { SearchIndex(CatalogDocs.build(CatalogKind.Brewer)) }

    private fun SearchIndex.ids(q: String): List<String> = search(q).map { it.doc.id }

    // ------------------------------------------------------------- the spec's cases

    @Test
    fun commandanteFindsTheComandanteFirst() {
        val target = Catalog.grinders.firstOrNull { it.brand == "Comandante" && it.model.contains("C40") }
        assumeTrue(target != null)
        val hits = grinders.search("commandante")
        assertTrue("no hits", hits.isNotEmpty())
        assertEquals("Comandante", Catalog.grinder(hits.first().doc.id)?.brand)
        assertTrue(target!!.id in hits.map { it.doc.id })
        // The spec asks for the C40 first among them.
        assertTrue(hits.first().doc.title.contains("C40"))
    }

    @Test
    fun mahlkonigHoweverItIsTyped() {
        val brandIds = Catalog.grinders.filter { it.brand.startsWith("Mahlk") }.map { it.id }.toSet()
        assumeTrue(brandIds.isNotEmpty())
        for (q in listOf("mahlkonigg", "mahlkoenig", "Mahlkönig", "mahlkonig")) {
            val ids = grinders.ids(q)
            assertTrue("\"$q\" found nothing", ids.isNotEmpty())
            assertTrue("\"$q\" put ${ids.first()} first", ids.first() in brandIds)
            assertTrue("\"$q\" missed some", ids.toSet().containsAll(brandIds))
        }
    }

    @Test
    fun kUltraWithOrWithoutTheSpace() {
        assumeTrue(Catalog.grinder("1zpresso-k-ultra") != null)
        assertEquals("1zpresso-k-ultra", grinders.ids("k ultra").first())
        assertEquals("1zpresso-k-ultra", grinders.ids("kultra").first())
        assertEquals("1zpresso-k-ultra", grinders.ids("K-Ultra").first())
    }

    @Test
    fun ek43FindsTheMahlkonig() {
        val ek = Catalog.grinders.filter { it.model.replace(" ", "").startsWith("EK43") }.map { it.id }
        assumeTrue(ek.isNotEmpty())
        assertTrue(grinders.ids("ek43").first() in ek)
    }

    @Test
    fun geishaIsGesha() {
        assumeTrue(Catalog.varietal("gesha") != null)
        assertEquals("gesha", varietals.ids("gesha").first())
        assertEquals("gesha", varietals.ids("geisha").first())
    }

    @Test
    fun squareMile() {
        val target = Catalog.roasters.firstOrNull { it.name.startsWith("Square Mile") }
        assumeTrue(target != null)
        assertEquals(target!!.id, roasters.ids("square mile").first())
        assertEquals(target.id, roasters.ids("squaremile").first())
    }

    // ------------------------------------------------------------------- the rules

    private val docs = listOf(
        SearchDoc("a", "Comandante C40 MK4", group = "Comandante", keys = listOf("Comandante", "C40 MK4", "Comandante C40 MK4"), popularity = 1),
        SearchDoc("b", "Commodore 64", group = "Other", keys = listOf("Commodore", "64"), popularity = 3),
        SearchDoc("c", "Timemore C3", group = "Timemore", keys = listOf("Timemore", "C3", "Timemore C3"), popularity = 2),
        SearchDoc("d", "Timemore C2", group = "Timemore", keys = listOf("Timemore", "C2", "Timemore C2"), popularity = 1),
    )

    @Test
    fun scoresFollowTheLadder() {
        fun score(q: String, id: String) = CatalogSearch.search(docs, q).firstOrNull { it.doc.id == id }?.score
        assertEquals(100, score("comandante", "a")) // exact key
        assertEquals(100, score("timemore c3", "c")) // exact joined key
        assertEquals(80, score("coman", "a")) // prefix
        assertEquals(60, score("c40 comandante", "a")) // every token a prefix, any order
        assertEquals(40, score("mandante", "a")) // substring
        assertEquals(20, score("commandante", "a")) // one letter out
        assertEquals(null, score("zzzz", "a"))
    }

    @Test
    fun tiesBreakByPopularityThenTitle() {
        // Both Timemores score the same for "timemore"; the more common one leads.
        assertEquals(listOf("d", "c"), CatalogSearch.search(docs, "timemore").map { it.doc.id })
    }

    @Test
    fun shortTokensAreNeverFuzzy() {
        // "c4" is two letters: it may only match as a prefix, never as a near miss of "c3".
        assertEquals(listOf("a"), CatalogSearch.search(docs, "c4").map { it.doc.id })
    }

    @Test
    fun emptyQueryIsEveryDocInGroupOrderPopularityFirst() {
        val all = CatalogSearch.search(docs, "  ")
        assertEquals(listOf("a", "b", "d", "c"), all.map { it.doc.id })
    }

    @Test
    fun limitCapsASearchButNotBrowsing() {
        val many = (1..100).map { SearchDoc("id$it", "Grinder $it", keys = listOf("Grinder $it")) }
        assertEquals(60, CatalogSearch.search(many, "grinder").size)
        assertEquals(5, CatalogSearch.search(many, "grinder", limit = 5).size)
        assertEquals(100, CatalogSearch.search(many, "").size)
    }

    @Test
    fun normaliseFoldsAccentsAndDigraphs() {
        assertEquals("mahlkonig", CatalogSearch.normalise("Mahlkönig"))
        assertEquals("mahlkonig", CatalogSearch.normalise("Mahlkoenig"))
        assertEquals("k ultra", CatalogSearch.normalise("K-Ultra"))
        assertEquals("sao paulo", CatalogSearch.normalise("São Paulo"))
        assertEquals("", CatalogSearch.normalise(" · "))
    }

    @Test
    fun editDistanceCountsASwapAsOne() {
        assertEquals(0, CatalogSearch.editDistance("gesha", "gesha"))
        assertEquals(1, CatalogSearch.editDistance("gesha", "geisha"))
        assertEquals(1, CatalogSearch.editDistance("ehtiopia", "ethiopia"))
        assertEquals(2, CatalogSearch.editDistance("mahlkonigg", "mahlkoni"))
        // Capped: anything past max is max + 1.
        assertEquals(3, CatalogSearch.editDistance("abcdefgh", "zzzzzzzz"))
    }

    // ------------------------------------------------------------ the shipped docs

    @Test
    fun everyCatalogKindBuildsDocsWithUniqueIds() {
        for (index in listOf(grinders, roasters, varietals, origins, brewers)) {
            assertTrue(index.docs.isNotEmpty())
            assertEquals(index.docs.size, index.docs.map { it.id }.toSet().size)
            index.docs.forEach { assertFalse("${it.id} has no title", it.title.isBlank()) }
        }
    }

    @Test
    fun browsingKeepsAGroupTogether() {
        // The picker draws one header per run of equal groups, so a group must never come back.
        for (index in listOf(grinders, roasters, varietals, origins, brewers)) {
            val seen = HashSet<String>()
            var last: String? = null
            for (hit in index.browse) {
                val g = hit.doc.group
                if (g != last) {
                    assertTrue("group \"$g\" appears twice", seen.add(g))
                    last = g
                }
            }
        }
    }

    @Test
    fun grinderSubtitleReadsLikeTheSpec() {
        val k = Catalog.grinder("1zpresso-k-ultra")
        assumeTrue(k != null)
        assertEquals("Hand · Conical 48 mm", CatalogDocs.grinderSubtitle(k!!))
    }

    @Test
    fun countriesAndCitiesAreSearchable() {
        assumeTrue(Catalog.origin("ethiopia") != null)
        assertEquals("ethiopia", origins.ids("ethopia").first())
        val london = Catalog.roasters.filter { it.city == "London" }.map { it.id }.toSet()
        assumeTrue(london.isNotEmpty())
        assertTrue(roasters.ids("london").take(london.size.coerceAtMost(5)).all { it in london })
    }
}
