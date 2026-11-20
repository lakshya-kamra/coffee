package com.laksh.coffee.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Temperatures by locale (v4 §4.15). Web twin: defaultTempUnit / renderTemps in units.ts; the
 * parity cases are in shared/fixtures/domain-v4-cases.json.
 */
object Temperatures {

    /** Regions that read temperatures in °F. */
    val FAHRENHEIT_REGIONS: Set<String> = setOf("US", "PR", "GU", "VI", "AS", "MP", "UM", "LR", "BZ", "BS", "KY", "PW", "FM", "MH")

    private val REGION = Regex("[-_]([A-Za-z]{2})\\b")

    /** The region of a language tag ("en-US" → "US"), parsed as the web's localCurrency does. */
    fun region(tag: String): String? = REGION.find(tag)?.groupValues?.get(1)?.uppercase(Locale.ROOT)

    /** "f" for a fresh install in a °F region, else "c". */
    fun defaultUnit(tag: String): String = if (region(tag) in FAHRENHEIT_REGIONS) "f" else "c"

    /** The same, for a Locale. */
    fun defaultUnit(locale: Locale = Locale.getDefault()): String = if (locale.country.uppercase(Locale.ROOT) in FAHRENHEIT_REGIONS) "f" else "c"

    /**
     * Prose temperatures are written "70 °C" or "90-95 °C" (shared/recipes.json). The same
     * pattern is in the parity fixture, so both apps convert the same text.
     */
    const val PROSE_TEMP = "(\\d+(?:\\.\\d+)?)(?:\\s*[-–]\\s*(\\d+(?:\\.\\d+)?))?\\s*°C"

    private val PROSE = Regex(PROSE_TEMP)

    fun cToF(c: Double): Int = (c * 9.0 / 5.0 + 32.0).roundToInt()

    /** Recipe prose in the user's unit: "70 °C" → "158 °F" when unit is "f"; unchanged for "c". */
    fun render(text: String, unit: String): String {
        if (unit != "f") return text
        return PROSE.replace(text) { m ->
            val a = cToF(m.groupValues[1].toDouble())
            val b = m.groupValues[2]
            if (b.isEmpty()) "$a °F" else "$a-${cToF(b.toDouble())} °F"
        }
    }
}
