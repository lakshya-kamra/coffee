package com.laksh.coffee.ui.screens.gear

import com.laksh.coffee.core.grams
import com.laksh.coffee.data.db.entity.BurrType
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.BrewMethod
import com.laksh.coffee.domain.catalog.CatalogDocs
import com.laksh.coffee.domain.catalog.CatalogGrinder
import com.laksh.coffee.domain.grind.DialFormat
import kotlin.math.abs

/**
 * How a catalog grinder becomes (or updates) the user's own row. Pure. The row is always what the
 * person saw and accepted: nothing here runs without a tap.
 */
object GrinderMapping {

    fun style(catalog: String): SettingStyle = when (catalog) {
        "numbers" -> SettingStyle.Numbers
        "stepless" -> SettingStyle.Stepless
        else -> SettingStyle.Clicks
    }

    fun burr(catalog: String): BurrType = when (catalog) {
        "conical" -> BurrType.Conical
        "flat" -> BurrType.Flat
        "ghost" -> BurrType.Ghost
        "blade" -> BurrType.Blade
        else -> BurrType.Unknown
    }

    /**
     * Picking a grinder from the list: identity and scale come from the catalog. The usual
     * filter and espresso settings are left alone: those are yours, never the catalog's.
     */
    fun fill(row: GrinderEntity, c: CatalogGrinder): GrinderEntity = withScale(
        row.copy(
            brand = c.brand,
            model = c.model,
            burrType = burr(c.burr),
            burrSizeMm = c.burrSizeMm?.let(CatalogDocs::trimNumber).orEmpty(),
            catalogId = c.id,
        ),
        c,
    )

    /** "Use these numbers": only the scale changes. Logged settings keep their numbers. */
    fun withScale(row: GrinderEntity, c: CatalogGrinder): GrinderEntity {
        val s = c.setting
        return row.copy(
            settingStyle = style(s.style),
            clicksPerRotation = s.clicksPerRotation ?: 0,
            micronsPerClick = s.micronsPerStep,
            lowerIsFiner = s.lowerIsFiner,
            minSetting = s.min,
            maxSetting = s.max,
            step = s.step.takeIf { it > 0 } ?: 1.0,
            // Auto: the catalog's own dial format applies once the turn size agrees.
            dialFormat = "",
        )
    }

    /** The row's scale no longer matches its catalog entry: the card says "Edited". */
    fun edited(row: GrinderEntity, c: CatalogGrinder): Boolean {
        val s = c.setting
        return row.settingStyle != style(s.style) ||
            row.clicksPerRotation != (s.clicksPerRotation ?: 0) ||
            !same(row.micronsPerClick, s.micronsPerStep) ||
            row.lowerIsFiner != s.lowerIsFiner ||
            !same(row.minSetting, s.min) || !same(row.maxSetting, s.max) ||
            !same(row.step, s.step.takeIf { it > 0 } ?: 1.0) ||
            row.dialFormat.isNotBlank()
    }

    private fun same(a: Double?, b: Double?): Boolean =
        if (a == null || b == null) a == b else abs(a - b) < 0.001

    /** "clicks · fewer is finer · 100 a turn · 20 µm a click". */
    fun scaleWords(g: GrinderEntity): String = listOfNotNull(
        when (g.settingStyle) {
            SettingStyle.Clicks -> "clicks"
            SettingStyle.Numbers -> "numbered dial"
            SettingStyle.Microns -> "microns"
            SettingStyle.Stepless -> "stepless"
        },
        when {
            g.usesClicks && g.lowerIsFiner -> "fewer is finer"
            g.usesClicks -> "more is finer"
            g.lowerIsFiner -> "lower is finer"
            else -> "higher is finer"
        },
        g.clicksPerRotation.takeIf { it > 0 && g.usesClicks }?.let { "$it a turn" },
        g.micronsPerClick?.takeIf { it > 0 }?.let { "${grams(it)} µm a ${if (g.usesClicks) "click" else "step"}" },
        "${grams(g.minSetting)} to ${grams(g.maxSetting)}".takeIf { g.maxSetting > g.minSetting && !g.usesClicks },
    ).joinToString(" · ")

    /**
     * The detail under "Check the scale": the first thing that differs, as the catalog's claim
     * against the row's, e.g. "1Zpresso says 100 clicks a turn; yours says 10".
     */
    fun noticeWords(row: GrinderEntity, c: CatalogGrinder): String {
        val who = c.rangeSources.firstOrNull { it.who.isNotBlank() }?.who ?: c.brand
        val s = c.setting
        val cpr = s.clicksPerRotation
        return when {
            row.settingStyle != style(s.style) ->
                "$who says it adjusts by ${styleWords(style(s.style))}; yours says ${styleWords(row.settingStyle)}"
            cpr != null && cpr != row.clicksPerRotation ->
                "$who says $cpr clicks a turn; yours says ${if (row.clicksPerRotation > 0) row.clicksPerRotation.toString() else "nothing yet"}"
            s.micronsPerStep != null && !same(row.micronsPerClick, s.micronsPerStep) ->
                "$who says ${grams(s.micronsPerStep)} µm a click; yours says ${row.micronsPerClick?.let { grams(it) } ?: "nothing yet"}"
            row.lowerIsFiner != s.lowerIsFiner ->
                "$who says ${if (s.lowerIsFiner) "lower" else "higher"} is finer; yours says the opposite"
            else -> "$who lists different numbers for this grinder"
        }
    }

    private fun styleWords(s: SettingStyle): String = when (s) {
        SettingStyle.Clicks -> "clicks"
        SettingStyle.Numbers -> "a numbered dial"
        SettingStyle.Microns -> "microns"
        SettingStyle.Stepless -> "a stepless dial"
    }

    /** The dial-reading choices the editor offers. "" = let the app work it out. */
    val DIAL_CHOICES: List<Pair<String, String>> = listOf(
        "" to "Auto",
        DialFormat.PLAIN to "Plain number",
        DialFormat.TURNS to "Turns + clicks",
        DialFormat.NUMBER_TICK + "10" to "Numbered dial (10 marks a number)",
    )

    /** The method this grinder is brewed with most, for the one range line on its card. */
    fun mostBrewedMethod(methods: List<BrewMethod>): BrewMethod? =
        methods.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}
