package com.laksh.coffee.domain.catalog

import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.Brewer

/**
 * What 1.x wrote, mapped to catalog ids. NEVER edit after release: MIGRATION_3_4 builds its SQL
 * from these maps, and old backups are read through them. LegacyIdsTest checks every value is a
 * real catalog id, so a catalog rename fails the build instead of orphaning old data.
 */
object LegacyIds {

    /** Brewer enum names AND labels written by 1.x → brewers.json ids. */
    val BREWER: Map<String, String> = linkedMapOf(
        "Hario V60" to "hario-v60-02", "V60" to "hario-v60-02",
        "Chemex" to "chemex",
        "Kalita Wave" to "kalita-wave-185", "Kalita" to "kalita-wave-185",
        "Hario Switch" to "hario-switch", "Switch" to "hario-switch",
        "AeroPress" to "aeropress",
        "French Press" to "french-press", "FrenchPress" to "french-press",
        "Moka Pot" to "moka-pot", "Moka" to "moka-pot",
        "Espresso" to "espresso-machine",
        "Cold Brew" to "cold-brew-jar", "ColdBrew" to "cold-brew-jar",
    )

    /** 1.x Process enum NAMES (Room) → beans.json process ids. "" = not recorded. */
    val PROCESS: Map<String, String> = linkedMapOf(
        "Washed" to "washed", "Natural" to "natural", "Honey" to "honey",
        "Anaerobic" to "anaerobic", "CarbonicMaceration" to "carbonic-maceration", "Wet" to "wet-hulled",
        "Other" to "unknown", "Unknown" to "",
    )

    /** 1.x Process LABELS (old JSON export) → ids. */
    val PROCESS_LABEL: Map<String, String> = linkedMapOf(
        "Washed" to "washed", "Natural" to "natural", "Honey" to "honey",
        "Anaerobic" to "anaerobic", "Carbonic maceration" to "carbonic-maceration", "Wet-hulled" to "wet-hulled",
        "Other" to "unknown", "Unknown" to "",
    )

    val ROAST_LEVEL: Map<String, String> = linkedMapOf(
        "Light" to "light", "MediumLight" to "medium-light",
        "Medium" to "medium", "MediumDark" to "medium-dark", "Dark" to "dark", "Unknown" to "",
    )

    val ROAST_LEVEL_LABEL: Map<String, String> = linkedMapOf(
        "Light" to "light", "Medium-light" to "medium-light",
        "Medium" to "medium", "Medium-dark" to "medium-dark", "Dark" to "dark", "Unknown" to "",
    )

    /**
     * The (name, author) pairs 1.x wrote on a brew of a built-in recipe → recipes.json ids. 1.x
     * stored no recipe id, so an old brew finds its recipe by these; they let a built-in be renamed
     * or re-attributed without orphaning brews logged under its 1.x name.
     */
    val RECIPE: Map<Pair<String, String>, String> = linkedMapOf(
        ("High-Extraction AeroPress" to "Lance Hedrick") to "hedrick-aeropress",
        ("Light Roast Espresso" to "Lance Hedrick") to "hedrick-espresso",
        ("Low-Agitation Pulse Pour" to "Jonathan Gagné") to "gagne-v60",
        ("Switch Hybrid: Steep and Release" to "Community standard") to "switch-hybrid",
    )

    /** Used only when brewers.json lacks a legacy id, so 1.x data always resolves. Labels are 1.x labels. */
    val FALLBACK_BREWERS: List<Brewer> = listOf(
        Brewer("hario-v60-02", "Hario V60", "Hario", "cone", BrewMethod.PourOver, "paper"),
        Brewer("chemex", "Chemex", "Chemex", "cone", BrewMethod.PourOver, "paper"),
        Brewer("kalita-wave-185", "Kalita Wave", "Kalita", "flat-bottom", BrewMethod.PourOver, "paper"),
        Brewer("hario-switch", "Hario Switch", "Hario", "hybrid", BrewMethod.PourOver, "paper"),
        Brewer("aeropress", "AeroPress", "AeroPress", "hybrid", BrewMethod.AeroPress, "paper"),
        Brewer("french-press", "French Press", "", "immersion", BrewMethod.FrenchPress, "metal"),
        Brewer("moka-pot", "Moka Pot", "", "pressure", BrewMethod.Moka, "metal"),
        Brewer("espresso-machine", "Espresso", "", "espresso", BrewMethod.Espresso, "metal"),
        Brewer("cold-brew-jar", "Cold Brew", "", "cold", BrewMethod.ColdBrew, "paper"),
    )

    /**
     * `CASE \`col\` WHEN 'k' THEN 'v' … ELSE <elseSql> END`, quotes doubled. Entries mapping to ""
     * are left to the ELSE branch. [elseSql] is raw SQL, e.g. "NULL" or "''".
     */
    fun caseSql(column: String, map: Map<String, String>, elseSql: String): String = buildString {
        append("CASE `").append(column).append('`')
        map.forEach { (k, v) ->
            if (v.isEmpty()) return@forEach
            append(" WHEN ").append(quote(k)).append(" THEN ").append(quote(v))
        }
        append(" ELSE ").append(elseSql).append(" END")
    }

    private fun quote(s: String): String = "'" + s.replace("'", "''") + "'"
}
