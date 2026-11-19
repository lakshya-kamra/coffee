package com.laksh.coffee.data.catalog

import com.laksh.coffee.data.db.entity.BeanEntity
import com.laksh.coffee.data.db.entity.GrinderEntity
import com.laksh.coffee.data.db.entity.SettingStyle
import com.laksh.coffee.domain.catalog.CatalogGrinder
import com.laksh.coffee.domain.catalog.CatalogOrigin
import com.laksh.coffee.domain.catalog.CatalogRoaster
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Links rows typed before the catalog existed to catalog ids. Only ids are written: no number
 * or text a person entered is ever changed. When a linked grinder's scale disagrees with a
 * well-sourced catalog entry, a notice asks the user instead (android-spec §6, §7.4).
 */
object CatalogLinker {

    /** Lowercase, accents stripped (ö → o), only [a-z0-9] kept. */
    fun norm(s: String): String {
        val decomposed = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return decomposed.replace(COMBINING, "").replace("ø", "o").replace(NOT_ALNUM, "")
    }

    /** The catalog entry whose brand + model matches exactly one entry, else null. */
    fun matchGrinder(row: GrinderEntity, catalog: List<CatalogGrinder>): CatalogGrinder? {
        val key = norm(row.brand + row.model)
        if (key.isEmpty()) return null
        return catalog.filter { norm(it.brand + it.model) == key }.singleOrNull()
    }

    /** True when the row's scale differs from the catalog's and the catalog is sourced well enough to say so. */
    fun scaleDiffers(row: GrinderEntity, c: CatalogGrinder): Boolean {
        if (c.confidence != "high" && c.confidence != "medium") return false
        val s = c.setting
        val style = when (s.style) {
            "clicks" -> SettingStyle.Clicks
            "numbers" -> SettingStyle.Numbers
            "stepless" -> SettingStyle.Stepless
            else -> return false
        }
        val styleDiffers = row.settingStyle != style &&
            !(row.settingStyle == SettingStyle.Microns && style != SettingStyle.Clicks)
        val cprDiffers = s.clicksPerRotation != null && row.clicksPerRotation != s.clicksPerRotation
        val micronsDiffer = s.micronsPerStep != null && row.micronsPerClick != null &&
            abs(row.micronsPerClick - s.micronsPerStep) > 0.001
        return styleDiffers || cprDiffers || micronsDiffer || row.lowerIsFiner != s.lowerIsFiner
    }

    fun matchRoaster(name: String, catalog: List<CatalogRoaster>): CatalogRoaster? {
        if (name.isBlank()) return null
        val key = norm(name)
        val bare = stripSuffix(name)
        return catalog.firstOrNull { norm(it.name) == key }
            ?: catalog.firstOrNull { stripSuffix(it.name).isNotEmpty() && stripSuffix(it.name) == bare }
    }

    fun matchOrigin(origin: String, catalog: List<CatalogOrigin>): CatalogOrigin? {
        val t = origin.trim()
        if (t.isEmpty()) return null
        return catalog.firstOrNull { it.country.equals(t, ignoreCase = true) || it.iso2.equals(t, ignoreCase = true) }
    }

    /** The bean with roaster/origin ids filled where a match is certain; null when nothing changes. */
    fun linkBean(b: BeanEntity, roasters: List<CatalogRoaster>, origins: List<CatalogOrigin>): BeanEntity? {
        val roasterId = b.roasterId ?: matchRoaster(b.roaster, roasters)?.id
        val originId = b.originId ?: matchOrigin(b.origin, origins)?.id
        if (roasterId == b.roasterId && originId == b.originId) return null
        return b.copy(roasterId = roasterId, originId = originId)
    }

    // "Square Mile Coffee Roasters" and "Square Mile" are the same roaster.
    private val SUFFIX_WORDS = setOf("coffee", "roasters", "roastery", "roasting", "co", "company")

    private fun stripSuffix(name: String): String {
        val words = name.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.last() in SUFFIX_WORDS) words.removeAt(words.lastIndex)
        return norm(words.joinToString(""))
    }

    private val COMBINING = Regex("\\p{M}+")
    private val NOT_ALNUM = Regex("[^a-z0-9]")
}
