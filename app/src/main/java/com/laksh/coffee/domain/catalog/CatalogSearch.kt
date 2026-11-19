package com.laksh.coffee.domain.catalog

import com.laksh.coffee.domain.Brewer
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** One row a picker can show and search. [keys] are raw text (brand, model, aliases, city…). */
data class SearchDoc(
    val id: String,
    val title: String,
    val subtitle: String = "",
    /** Header the picker groups under when there is no query. */
    val group: String = "",
    val keys: List<String> = emptyList(),
    /** 1 = very common. Breaks ties. */
    val popularity: Int = 3,
)

data class SearchHit(val doc: SearchDoc, val score: Int)

enum class CatalogKind { Grinder, Roaster, Origin, Varietal, Brewer }

/**
 * Forgiving search over a catalog list. People type "commandante", "mahlkoenig" or "k ultra", and
 * each of those must still land on the right grinder. Pure and synchronous; the web app's
 * domain/search/search.ts is the same algorithm.
 *
 * Score per doc = best over its keys: exact 100; prefix 80; every query token a prefix of some key
 * token 60; substring 40; fuzzy 20 (every query token of 4+ letters within Damerau-Levenshtein 1
 * of a key token, 2 when the token has 8+ letters; shorter tokens must still be prefixes).
 * Ties: popularity ascending, then title.
 */
object CatalogSearch {

    private val COMBINING = Regex("\\p{M}+")
    private val NOT_ALNUM = Regex("[^a-z0-9]+")

    // Letters NFD leaves whole.
    private val FOLD = mapOf('ß' to "ss", 'æ' to "ae", 'ø' to "o", 'œ' to "oe", 'ł' to "l", 'đ' to "d", 'ı' to "i")

    /**
     * Lowercase, accents stripped, German digraphs collapsed (oe→o, ue→u, ae→a, so "Mahlkoenig"
     * meets "Mahlkönig"), and anything that is not a letter or digit becomes a token break.
     */
    fun normalise(s: String): String {
        val stripped = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(COMBINING, "")
        val folded = buildString(stripped.length) {
            for (ch in stripped) append(FOLD[ch] ?: ch.toString())
        }
        return folded.replace("oe", "o").replace("ue", "u").replace("ae", "a").replace(NOT_ALNUM, " ").trim()
    }

    fun tokens(s: String): List<String> = normalise(s).let { if (it.isEmpty()) emptyList() else it.split(' ') }

    /** Optimal string alignment distance (adjacent swaps count as one edit), capped at [max] + 1. */
    fun editDistance(a: String, b: String, max: Int = 2): Int {
        if (a == b) return 0
        if (abs(a.length - b.length) > max) return max + 1
        val n = b.length
        var prev2 = IntArray(n + 1)
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val spare = prev2
            prev2 = prev
            prev = cur
            cur = spare
        }
        return prev[n]
    }

    fun search(items: List<SearchDoc>, query: String, limit: Int = 60): List<SearchHit> =
        SearchIndex(items).search(query, limit)

    internal val ORDER: Comparator<SearchDoc> = compareBy<SearchDoc> { it.popularity }.thenBy { it.title }
}

/** A docs list with its keys normalised once, so typing never repeats that work. */
class SearchIndex(val docs: List<SearchDoc>) {

    private class Key(val joined: String, val tokens: List<String>)

    private val keys: List<List<Key>> = docs.map { doc ->
        doc.keys.map { CatalogSearch.tokens(it) }.filter { it.isNotEmpty() }.map { Key(it.joinToString(""), it) }
    }

    private val byId: Map<String, SearchDoc> = docs.associateBy { it.id }

    fun doc(id: String?): SearchDoc? = id?.let { byId[it] }

    /** Every doc in group order (groups as they first appear), popularity first inside a group. */
    val browse: List<SearchHit> by lazy {
        val groupIndex = LinkedHashMap<String, Int>()
        for (d in docs) groupIndex.getOrPut(d.group) { groupIndex.size }
        docs.sortedWith(compareBy<SearchDoc> { groupIndex.getValue(it.group) }.then(CatalogSearch.ORDER))
            .map { SearchHit(it, 0) }
    }

    fun search(query: String, limit: Int = 60): List<SearchHit> {
        val q = CatalogSearch.tokens(query)
        if (q.isEmpty()) return browse
        val joined = q.joinToString("")
        val hits = ArrayList<SearchHit>()
        for (i in docs.indices) {
            var best = 0
            for (k in keys[i]) {
                val s = score(joined, q, k)
                if (s > best) best = s
                if (best == 100) break
            }
            if (best > 0) hits += SearchHit(docs[i], best)
        }
        hits.sortWith(compareByDescending<SearchHit> { it.score }.thenComparing({ it.doc }, CatalogSearch.ORDER))
        return if (hits.size > limit) hits.subList(0, limit).toList() else hits
    }

    private fun score(joined: String, q: List<String>, k: Key): Int = when {
        k.joined == joined -> 100
        k.joined.startsWith(joined) -> 80
        q.all { t -> k.tokens.any { it.startsWith(t) } } -> 60
        k.joined.contains(joined) -> 40
        q.any { it.length >= 4 } && q.all { fuzzy(it, k.tokens) } -> 20
        else -> 0
    }

    private fun fuzzy(t: String, keyTokens: List<String>): Boolean {
        if (t.length < 4) return keyTokens.any { it.startsWith(t) }
        val max = if (t.length >= 8) 2 else 1
        return keyTokens.any { CatalogSearch.editDistance(t, it, max) <= max }
    }
}

/**
 * The search documents for each catalog list. Group names are the picker's sticky headers
 * (grinders: brand; roasters and origins: area; varietals: species; brewers: category).
 */
object CatalogDocs {

    fun build(kind: CatalogKind): List<SearchDoc> = when (kind) {
        // Brands A–Z so the headers read like an index; a search ranks across them anyway.
        CatalogKind.Grinder -> Catalog.grinders.sortedBy { it.brand.lowercase(Locale.ROOT) }.map { g ->
            SearchDoc(
                id = g.id,
                title = g.display,
                subtitle = grinderSubtitle(g),
                group = g.brand,
                keys = listOf(g.brand, g.model, "${g.brand} ${g.model}"),
                popularity = g.popularity,
            )
        }
        CatalogKind.Roaster -> Catalog.roasters.sortedBy { it.area }.map { r ->
            SearchDoc(
                id = r.id,
                title = r.name,
                subtitle = listOf(listOf(r.city, r.country).filter { it.isNotBlank() }.joinToString(", "), r.notable)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                group = r.area.ifBlank { ELSEWHERE },
                keys = listOf(r.name, r.city, r.country),
            )
        }
        CatalogKind.Origin -> Catalog.origins.sortedBy { it.area }.map { o ->
            SearchDoc(
                id = o.id,
                title = o.country,
                subtitle = o.growingRegions.take(4).joinToString(", "),
                group = o.area.ifBlank { ELSEWHERE },
                keys = listOf(o.country, o.iso2),
            )
        }
        CatalogKind.Varietal -> {
            val species = Catalog.beans.species
            val order = species.withIndex().associate { it.value.id to it.index }
            Catalog.beans.varietals.sortedBy { order[it.species] ?: 99 }.map { v ->
                SearchDoc(
                    id = v.id,
                    title = v.label,
                    subtitle = v.aliases.joinToString(", "),
                    group = species.firstOrNull { it.id == v.species }?.label ?: "Other",
                    keys = listOf(v.label) + v.aliases,
                )
            }
        }
        CatalogKind.Brewer -> Catalog.brewers
            .sortedWith(compareBy<Brewer> { categoryRank(it.category) }.thenBy { it.category })
            .map { b ->
                SearchDoc(
                    id = b.id,
                    title = brewerTitle(b),
                    subtitle = if (b.notes.length > 90) b.notes.take(89).trimEnd() + "…" else b.notes,
                    group = brewerCategoryLabel(b.category),
                    keys = listOf(b.label, b.brand, "${b.brand} ${b.label}"),
                    // The nine brewers 1.x shipped are the ones most people own.
                    popularity = if (b.id in LegacyIds.BREWER.values) 1 else 3,
                )
            }
    }

    const val ELSEWHERE = "Elsewhere"

    /** "Hand · Conical 48 mm". */
    fun grinderSubtitle(g: CatalogGrinder): String {
        val kind = g.kind.replaceFirstChar { it.uppercase(Locale.ROOT) }
        val burr = listOfNotNull(
            g.burr.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase(Locale.ROOT) },
            g.burrSizeMm?.let { "${trimNumber(it)} mm" },
        ).joinToString(" ")
        return listOf(kind, burr).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** "Hario V60 02", "Chemex", "Moka pot": the brand only where it adds something. */
    fun brewerTitle(b: Brewer): String {
        val brand = b.brand.trim()
        val generic = GENERIC_BRAND.containsMatchIn(brand)
        return if (brand.isEmpty() || generic || b.label.startsWith(brand, ignoreCase = true)) b.label else "$brand ${b.label}"
    }

    private val GENERIC_BRAND = Regex("\\b(others|various)\\b", RegexOption.IGNORE_CASE)

    /** Brewer categories in the order a person thinks of them, then anything new A–Z. */
    val BREWER_CATEGORY_ORDER: List<String> =
        listOf("cone", "flat-bottom", "hybrid", "immersion", "pressure", "espresso", "siphon", "cold", "other")

    private fun categoryRank(c: String): Int = BREWER_CATEGORY_ORDER.indexOf(c).let { if (it < 0) BREWER_CATEGORY_ORDER.size else it }

    fun brewerCategoryLabel(c: String): String = when (c) {
        "cone" -> "Cones"
        "flat-bottom" -> "Flat-bottom"
        "hybrid" -> "Hybrid"
        "immersion" -> "Immersion"
        "pressure" -> "Stovetop"
        "espresso" -> "Espresso"
        "siphon" -> "Siphon"
        "cold" -> "Cold brew"
        "other" -> "Other"
        else -> c.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }

    fun processGroupLabel(g: String): String = when (g) {
        "classic" -> "Classic"
        "honey" -> "Honey"
        "fermentation" -> "Fermentation"
        "other" -> "Other"
        else -> g.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }

    /** 48.0 → "48", 38.5 → "38.5". */
    fun trimNumber(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
