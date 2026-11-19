package com.laksh.coffee.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.laksh.coffee.core.grams
import com.laksh.coffee.core.oneDp
import com.laksh.coffee.data.prefs.Settings
import kotlin.math.roundToInt

/**
 * How the app shows temperatures and big weights. Everything is stored in °C and grams.
 *
 * Temperatures convert fully. Brew targets (dose, water, pours) stay in grams even in ounce
 * mode, because that is what a coffee scale reads; only bag sizes and totals use [bigWeight].
 */
@Immutable
data class Units(val fahrenheit: Boolean = false, val ounces: Boolean = false) {

    val tempUnit: String get() = if (fahrenheit) "°F" else "°C"

    /** The number only: "93" or "199". Pair it with [tempUnit]. */
    fun temp(c: Int): String = (if (fahrenheit) toFahrenheit(c) else c).toString()

    /** "93 °C" or "199 °F". */
    fun tempWithUnit(c: Int): String = "${temp(c)} $tempUnit"

    val bigWeightUnit: String get() = if (ounces) "oz" else "g"

    /** Bags and totals: value and unit, ounces to one decimal place. */
    fun bigWeight(g: Double): Pair<String, String> =
        if (ounces) oneDp(g / GRAMS_PER_OUNCE) to "oz" else grams(g) to "g"

    /**
     * A lifetime total: grams then kilograms, or ounces then pounds, each to one decimal place
     * once it has left the small unit.
     */
    fun totalWeight(g: Double): Pair<String, String> = when {
        ounces && g >= GRAMS_PER_OUNCE * OUNCES_PER_POUND -> oneDp(g / (GRAMS_PER_OUNCE * OUNCES_PER_POUND)) to "lb"
        ounces -> oneDp(g / GRAMS_PER_OUNCE) to "oz"
        g >= 1000 -> oneDp(g / 1000) to "kg"
        else -> grams(g) to "g"
    }

    /** What a person typed in the current unit, back to grams. */
    fun bigWeightToGrams(v: Double): Double = if (ounces) v * GRAMS_PER_OUNCE else v

    companion object {
        const val GRAMS_PER_OUNCE = 28.349523125
        const val OUNCES_PER_POUND = 16

        fun toFahrenheit(c: Int): Int = (c * 9.0 / 5.0 + 32.0).roundToInt()

        fun of(s: Settings): Units = Units(fahrenheit = s.tempUnit == "f", ounces = s.weightUnit == "oz")
    }
}

/** Static: units change in Settings about once in an app's life, and then everything redraws. */
val LocalUnits = staticCompositionLocalOf { Units() }
